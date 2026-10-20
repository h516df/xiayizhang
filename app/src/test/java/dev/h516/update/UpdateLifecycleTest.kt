package dev.h516.update

import android.content.ContextWrapper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UpdateLifecycleTest {
    private val now = 1_700_000_000_000L

    private fun authorization(marker: Char = 'A', expired: Boolean = false) = UpdateAuthorization(
        "ghu_" + marker.toString().repeat(30), "ghr_" + marker.toString().repeat(30),
        if (expired) now - 1000 else now + 3_600_000, now + 7_200_000,
    )

    private fun approval(marker: Char) = JSONObject()
        .put("access_token", "ghu_" + marker.toString().repeat(30))
        .put("refresh_token", "ghr_" + marker.toString().repeat(30))
        .put("token_type", "bearer").put("scope", "")
        .put("expires_in", 3600).put("refresh_token_expires_in", 7200)

    private fun metadata() = JSONObject().put("id", UpdateConfig.REPO_ID)
        .put("full_name", UpdateConfig.REPO).put("name", UpdateConfig.REPO_NAME)
        .put("owner", JSONObject().put("login", "h516df"))

    private fun notes() = JSONObject().put("versionName", "0.4").put("versionCode", 4)
        .put("新增", JSONArray().put("合成验证说明")).toString()

    private fun releases() = JSONArray().put(JSONObject().put("tag_name", "v0.4")
        .put("draft", false).put("prerelease", false).put("assets", JSONArray()
            .put(JSONObject().put("id", 1).put("name", UpdateConfig.notesName("0.4")).put("size", 200))
            .put(JSONObject().put("id", 2).put("name", "xiayizhang-v0.4-universal.apk").put("size", 10))
            .put(JSONObject().put("id", 3).put("name", UpdateConfig.shaName("0.4")).put("size", 100)))).toString()

    private inner class MemoryTokens(var stored: UpdateAuthorization? = authorization()) : UpdateTokens {
        val saved = mutableListOf<UpdateAuthorization>()
        override fun load() = stored
        override fun save(token: UpdateAuthorization) { saved += token; stored = token }
        override fun clear() { stored = null }
    }

    private inner class FakeTransport : UpdateTransport {
        val calls = mutableListOf<String>()
        var cancelled = false
        var repository = metadata()
        var afterRepository: () -> Unit = {}
        var onApproval: () -> Unit = {}
        var approvalMarker = 'B'
        override fun cancel() { cancelled = true }
        override fun json(path: String, token: String): String {
            check(!cancelled)
            return if (path.isEmpty()) {
                calls += "repo"
                val result = repository.toString()
                afterRepository()
                result
            } else {
                calls += "releases"
                releases()
            }
        }
        override fun oauth(path: String, values: Map<String, String>): JSONObject {
            check(!cancelled)
            calls += path
            if (path == "device/code") return JSONObject().put("user_code", "AAAA-BBBB")
                .put("device_code", "a".repeat(40)).put("verification_uri", UpdateConfig.VERIFY_URI)
                .put("expires_in", 600).put("interval", 5)
            // Deliberately return a late response even if onApproval cancels this operation.
            onApproval()
            return approval(approvalMarker)
        }
        override fun assetText(id: Long, token: String): String {
            check(!cancelled); calls += "asset:" + id
            check(id == 1L)
            return notes()
        }
        override fun downloadApk(id: Long, token: String, expectedSize: Long, dest: File, onProgress: (Int) -> Unit) {
            error("No test may perform a real or fake APK download")
        }
    }

    private inner class Harness(val tokens: MemoryTokens = MemoryTokens()) {
        val work = mutableListOf<() -> Unit>()
        val ui = mutableListOf<() -> Unit>()
        val available = mutableListOf<FakeTransport>()
        var pauseHook: () -> Unit = {}
        val updater = Updater(object : ContextWrapper(null) {}, UpdateRuntime(
            transport = { available.removeAt(0) }, tokens = tokens,
            execute = { work.add(it) }, post = { ui.add(it) }, installedCode = { 3 },
            deviceAbis = { listOf("arm64-v8a") }, verifyArchive = { _, _ -> error("not used") },
            now = { now }, pause = { pauseHook() },
        ))
        fun runWork() { work.removeAt(0).invoke() }
        fun drainUi() { while (ui.isNotEmpty()) ui.removeAt(0).invoke() }
    }

    @Test fun cancelThenRetryUsesFreshTransportAndIgnoresQueuedOldWork() {
        val h = Harness(); val old = FakeTransport(); val replacement = FakeTransport()
        h.available += listOf(old, replacement)
        h.updater.check(); h.updater.cancel(); h.updater.check()
        h.runWork(); h.runWork(); h.drainUi()
        assertTrue(old.cancelled); assertTrue(old.calls.isEmpty())
        assertEquals(listOf("repo", "releases", "asset:1"), replacement.calls)
        assertTrue(h.updater.state is UpdateState.Available)
    }

    @Test fun lateOldReplyAndUiCallbacksCannotReplaceTheNewOperation() {
        val h = Harness(); val old = FakeTransport(); val replacement = FakeTransport()
        h.available += listOf(old, replacement)
        old.afterRepository = { h.updater.cancel(); h.updater.check() }
        h.updater.check(); h.runWork(); h.runWork(); h.drainUi()
        assertEquals(listOf("repo"), old.calls)
        assertEquals(listOf("repo", "releases", "asset:1"), replacement.calls)
        assertTrue(h.updater.state is UpdateState.Available)
    }

    @Test fun lateCancelledDeviceApprovalCannotPersistItsToken() {
        val tokens = MemoryTokens(null); val h = Harness(tokens)
        val old = FakeTransport().apply { approvalMarker = 'O' }
        val replacement = FakeTransport().apply { approvalMarker = 'N' }
        h.available += listOf(old, replacement)
        old.onApproval = { h.updater.cancel(); h.updater.check() }
        h.updater.check(); h.runWork(); h.runWork(); h.drainUi()
        assertEquals(listOf(authorization('N').access), tokens.saved.map { it.access })
        assertFalse(old.calls.contains("repo"))
        assertTrue(h.updater.state is UpdateState.Available)
    }

    @Test fun lateCancelledRefreshCannotPersistOrClearTheNewToken() {
        val tokens = MemoryTokens(authorization('A', expired = true)); val h = Harness(tokens)
        val old = FakeTransport().apply { approvalMarker = 'O' }
        val replacement = FakeTransport().apply { approvalMarker = 'N' }
        h.available += listOf(old, replacement)
        old.onApproval = { h.updater.cancel(); h.updater.check() }
        h.updater.check(); h.runWork(); h.runWork(); h.drainUi()
        assertEquals(listOf(authorization('N').access), tokens.saved.map { it.access })
        assertEquals(authorization('N').access, tokens.stored?.access)
        assertTrue(h.updater.state is UpdateState.Available)
    }

    @Test fun cancelDuringDevicePollDelayDoesNotSendAnotherPoll() {
        val h = Harness(MemoryTokens(null)); val old = FakeTransport()
        h.available += old
        h.pauseHook = { h.updater.cancel() }
        h.updater.check(); h.runWork(); h.drainUi()
        assertEquals(listOf("device/code"), old.calls)
        assertTrue(h.tokens.saved.isEmpty())
        assertTrue(h.updater.state is UpdateState.Idle)
    }

    @Test fun repositoryIdAndAllCanonicalNamesMustMatchBeforeReleaseRequests() {
        val invalid = listOf(
            metadata().put("id", UpdateConfig.REPO_ID + 1),
            metadata().put("id", UpdateConfig.REPO_ID.toString()),
            metadata().put("full_name", "h516df/another-app"),
            metadata().put("name", "another-app"),
            metadata().put("owner", JSONObject().put("login", "another-owner")),
        )
        invalid.forEach { repository ->
            val h = Harness(); val fake = FakeTransport().apply { this.repository = repository }
            h.available += fake; h.updater.check(); h.runWork(); h.drainUi()
            assertEquals(listOf("repo"), fake.calls)
            assertTrue(h.updater.state is UpdateState.Failed)
        }
    }

    @Test fun downloadRechecksRepositoryIdentityBeforeAnyAssetRequest() {
        val h = Harness(); val checking = FakeTransport()
        val downloading = FakeTransport().apply { repository.put("id", UpdateConfig.REPO_ID + 1) }
        h.available += listOf(checking, downloading)
        h.updater.check(); h.runWork(); h.drainUi()
        assertTrue(h.updater.state is UpdateState.Available)
        h.updater.download(); h.runWork(); h.drainUi()
        assertEquals(listOf("repo"), downloading.calls)
        assertTrue(h.updater.state is UpdateState.Failed)
    }
}
