package dev.h516.update

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    class NeedAuth(val userCode: String, val verifyUri: String) : UpdateState()
    class Latest(val notes: ReleaseNotes?) : UpdateState()
    class Available(val candidate: UpdateCandidate) : UpdateState()
    class Downloading(val percent: Int) : UpdateState()
    class ReadyToInstall(val file: File) : UpdateState()
    class Failed(val message: String) : UpdateState()
}

/** Narrow seams for deterministic tests; production still uses Android's protected token store. */
internal interface UpdateTokens {
    fun load(): UpdateAuthorization?
    fun save(token: UpdateAuthorization)
    fun clear()
}

internal class UpdateRuntime(
    val transport: () -> UpdateTransport,
    val tokens: UpdateTokens,
    val execute: (() -> Unit) -> Unit,
    val post: (() -> Unit) -> Unit,
    val installedCode: () -> Int,
    val deviceAbis: () -> List<String>,
    val verifyArchive: (File, Int) -> Unit,
    val now: () -> Long = { System.currentTimeMillis() },
    val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    companion object {
        // Shared bounded workers avoid retaining one executor for every visit to the update page.
        private val io = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "app-update").apply { isDaemon = true }
        }

        fun production(context: Context): UpdateRuntime {
            val main = Handler(Looper.getMainLooper())
            val protectedStore = UpdateTokenStore(context)
            return UpdateRuntime(
                transport = { UpdateHttp() },
                tokens = object : UpdateTokens {
                    override fun load() = protectedStore.load()
                    override fun save(token: UpdateAuthorization) = protectedStore.save(token)
                    override fun clear() = protectedStore.clear()
                },
                execute = { action -> io.execute { action() } },
                post = { action -> main.post { action() }; Unit },
                installedCode = { UpdateVerify.installedVersionCode(context) },
                deviceAbis = { UpdateVerify.deviceAbis() },
                verifyArchive = { file, code -> UpdateVerify.verifyArchive(context, file, code) },
            )
        }
    }
}

class Updater internal constructor(private val context: Context, private val runtime: UpdateRuntime) {
    constructor(context: Context) : this(context, UpdateRuntime.production(context))

    private class Operation(val generation: Long, val http: UpdateTransport, var running: Boolean = true)
    private val gate = Any()
    private var generation = 0L
    private var active: Operation? = null
    private var candidate: UpdateCandidate? = null

    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    fun currentNotes(): ReleaseNotes? = runCatching {
        context.assets.open("release-notes.json").use {
            ReleaseNotes.decode(String(it.readBytes(), Charsets.UTF_8))
        }
    }.getOrNull()

    fun installedVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(UpdateConfig.PACKAGE, 0).versionName.orEmpty()
    }.getOrDefault("")

    fun installedVersionCode(): Int = runCatching { runtime.installedCode() }.getOrDefault(0)

    /** One operation owns one transport. A cancelled transport is never reused. */
    private fun begin(download: Boolean): Pair<Operation, UpdateCandidate?>? = synchronized(gate) {
        if (active?.running == true) return null
        val target = if (download) candidate ?: return null else null
        active?.http?.cancel()
        generation += 1
        val operation = Operation(generation, runtime.transport())
        active = operation
        if (!download) candidate = null
        operation to target
    }

    /** This same lock orders cancellation against both token persistence and UI state changes. */
    private fun <T> current(operation: Operation, action: () -> T): T = synchronized(gate) {
        if (active !== operation || generation != operation.generation || Thread.currentThread().isInterrupted) {
            throw InterruptedException()
        }
        action()
    }

    private fun alive(operation: Operation) = current(operation) { Unit }

    private fun publish(operation: Operation, value: UpdateState) {
        runtime.post {
            synchronized(gate) {
                if (active === operation && generation == operation.generation) state = value
            }
        }
    }

    private fun run(operation: Operation, failure: String, action: () -> Unit) {
        runtime.execute {
            try {
                alive(operation)
                action()
            } catch (_: InterruptedException) {
                publish(operation, UpdateState.Idle)
            } catch (e: UpdateFailure) {
                publish(operation, UpdateState.Failed(e.userMessage))
            } catch (_: Exception) {
                publish(operation, UpdateState.Failed(failure))
            } finally {
                synchronized(gate) { if (active === operation) operation.running = false }
            }
        }
    }

    fun check() {
        val operation = begin(download = false)?.first ?: return
        publish(operation, UpdateState.Checking)
        run(operation, "检查更新失败，请稍后重试") {
            val token = tokenOrAuthorize(operation)
            verifyRepository(operation, token)
            val releases = operation.http.json("/releases?per_page=20", token)
            alive(operation)
            val newest = UpdateCatalog.newest(UpdateCatalog.parse(releases))
                ?: throw UpdateFailure("还没发布过带更新说明的版本，请稍后再试")
            val notesText = operation.http.assetText(newest.notesAsset.id, token)
            alive(operation)
            val notes = ReleaseNotes.decode(notesText)
            when (val result = UpdateCatalog.decide(notes, newest.assets, runtime.installedCode(), runtime.deviceAbis())) {
                is UpdateCatalog.Result.Latest -> publish(operation, UpdateState.Latest(result.notes))
                is UpdateCatalog.Result.Available -> {
                    current(operation) { candidate = result.candidate }
                    publish(operation, UpdateState.Available(result.candidate))
                }
            }
        }
    }

    private fun verifyRepository(operation: Operation, token: String) {
        alive(operation)
        val json = JSONObject(operation.http.json("", token))
        alive(operation)
        val id = json.opt("id")
        requireUpdate(
            id is Number && id.toLong() == UpdateConfig.REPO_ID && id.toDouble() == UpdateConfig.REPO_ID.toDouble() &&
                json.optString("full_name") == UpdateConfig.REPO &&
                json.optString("name") == UpdateConfig.REPO_NAME &&
                json.optJSONObject("owner")?.optString("login") == UpdateConfig.REPO.substringBefore('/'),
            "更新来源身份不符，已停止",
        )
    }

    fun download() {
        val started = begin(download = true) ?: return
        val operation = started.first
        val target = started.second ?: return
        publish(operation, UpdateState.Downloading(0))
        run(operation, "下载或校验失败，请稍后重试") {
            val token = current(operation) { runtime.tokens.load()?.access }
                ?: throw UpdateFailure("授权失效了，请重新检查更新")
            verifyRepository(operation, token)
            // Cancelled and replacement downloads must never share/delete the same destination.
            val dir = File(context.cacheDir, "updates/" + UUID.randomUUID()).apply { mkdirs() }
            val dest = File(dir, target.apk.name)
            var keep = false
            try {
                operation.http.downloadApk(target.apk.id, token, target.apk.size, dest) { percent ->
                    publish(operation, UpdateState.Downloading(percent))
                }
                alive(operation)
                val expectedSha = target.shaFile?.let {
                    UpdateVerify.parseShaFile(operation.http.assetText(it.id, token))
                } ?: target.apk.apiDigest ?: throw UpdateFailure("发布资料里缺校验值，请稍后再试")
                alive(operation)
                UpdateVerify.verifyHash(dest, target.apk.size, expectedSha, target.apk.apiDigest)
                runtime.verifyArchive(dest, target.notes.code)
                current(operation) { keep = true }
                publish(operation, UpdateState.ReadyToInstall(dest))
            } finally {
                if (!keep) { dest.delete(); dir.delete() }
            }
        }
    }

    fun install() {
        val ready = synchronized(gate) {
            if (active == null) null else state as? UpdateState.ReadyToInstall
        } ?: return
        try {
            val uri = FileProvider.getUriForFile(context, UpdateConfig.PACKAGE + ".updates", ready.file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            synchronized(gate) { active }?.let { publish(it, UpdateState.Failed("打不开系统安装界面，请稍后重试")) }
        }
    }

    fun hasAuthorization(): Boolean = synchronized(gate) { runtime.tokens.load() != null }

    fun forgetAuthorization() = invalidate(clearAuthorization = true)
    fun cancel() = invalidate(clearAuthorization = false)

    private fun invalidate(clearAuthorization: Boolean) {
        val (previous, stamp) = synchronized(gate) {
            generation += 1
            val previous = active
            active = null
            candidate = null
            if (clearAuthorization) runtime.tokens.clear()
            previous to generation
        }
        previous?.http?.cancel()
        runtime.post {
            synchronized(gate) { if (generation == stamp && active == null) state = UpdateState.Idle }
        }
    }

    private fun tokenOrAuthorize(operation: Operation): String {
        val now = runtime.now()
        val stored = current(operation) { runtime.tokens.load() }
        if (stored != null) {
            if (!stored.expired(now)) return current(operation) { stored.access }
            if (!stored.refreshExpired(now)) {
                try {
                    return refresh(operation, stored.refresh)
                } catch (e: InterruptedException) {
                    throw e
                } catch (_: Exception) {
                    alive(operation)
                }
            }
            current(operation) { runtime.tokens.clear() }
        }
        return authorize(operation)
    }

    private fun refresh(operation: Operation, refreshToken: String): String {
        alive(operation)
        val json = operation.http.oauth("oauth/access_token", mapOf(
            "client_id" to UpdateConfig.CLIENT_ID,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
        ))
        alive(operation)
        val authorization = UpdateAuthorization.fromResponse(json, runtime.now())
        return current(operation) { runtime.tokens.save(authorization); authorization.access }
    }

    private fun authorize(operation: Operation): String {
        alive(operation)
        val grantJson = operation.http.oauth("device/code", mapOf("client_id" to UpdateConfig.CLIENT_ID))
        alive(operation)
        val grant = UpdateGrant.decode(grantJson, runtime.now())
        publish(operation, UpdateState.NeedAuth(grant.userCode, UpdateConfig.VERIFY_URI))
        var interval = grant.intervalSeconds
        while (runtime.now() < grant.expiresAt) {
            alive(operation)
            runtime.pause(interval * 1000L)
            alive(operation)
            val json = operation.http.oauth("oauth/access_token", mapOf(
                "client_id" to UpdateConfig.CLIENT_ID,
                "device_code" to grant.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ))
            alive(operation)
            when (val poll = UpdatePoll.decode(json, interval, runtime.now())) {
                is UpdatePoll.Pending -> interval = poll.nextIntervalSeconds
                is UpdatePoll.Approved -> return current(operation) {
                    runtime.tokens.save(poll.authorization)
                    poll.authorization.access
                }
            }
        }
        throw UpdateFailure("授权超时了，请重新点检查更新")
    }
}
