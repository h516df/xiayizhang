package cn.privatereader

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

data class Chapter(val title: String, val text: String, val id:String=newId(), val revision:Long=0, val scene:Scene=Scene(), val epoch:String=newId())
data class BookSummary(val id: String, val title: String, val chapterCount: Int, val preview: String)
data class Book(
    val id: String,
    val title: String,
    val chapters: List<Chapter>,
    val style: String = "",
    val facts: String = "",
    val buttons: List<String> = listOf("润色", "续写", "总结"),
    val protectedText: List<String> = emptyList(),
    val cards:List<StoryCard> = emptyList(),
    val relations:List<StoryRelation> = emptyList(),
    val workspaceId:String=newId(),
    val resources:List<ImportedResource> = emptyList(),
    val bindings:List<ReferenceBinding> = emptyList()
)

/** Offline-only storage. Each source is immutable; revisions contain working text only. */
class Store(private val root: File) {
    companion object {
        const val MAX_FILE = 20 * 1024 * 1024
        const val MAX_EXPANDED = 40 * 1024 * 1024
        const val CHAPTER_SIZE = 12000
        private const val MAX_ENTRIES = 2000
        private val safeId = Regex("[A-Za-z0-9_-]{1,100}")
    }
    init { require(root.isDirectory || root.mkdirs()) { "无法创建本地书库" } }
    private fun folder(id: String): File {
        require(safeId.matches(id)) { "无效书籍标识" }
        return File(root, id)
    }
    @Synchronized fun list(): List<Book> = root.listFiles().orEmpty().filter { it.isDirectory && safeId.matches(it.name) }
        .mapNotNull { load(it.name) }.sortedBy { it.title }
    /** Retain only compact shelf metadata, never all loaded novels at once. */
    @Synchronized fun summaries(): List<BookSummary> {
        val summaries = mutableListOf<BookSummary>()
        for (dir in root.listFiles().orEmpty()) {
            if (!dir.isDirectory || !safeId.matches(dir.name)) continue
            val book = load(dir.name) ?: continue
            val text = book.chapters.firstOrNull()?.text.orEmpty()
            var end = minOf(80, text.length)
            if (end < text.length && end > 0 && Character.isHighSurrogate(text[end - 1])) end--
            summaries += BookSummary(book.id, book.title, book.chapters.size, text.substring(0, end))
        }
        return summaries.sortedBy { it.title }
    }
    @Synchronized fun recoveryNotice(id: String): String? = File(folder(id), "recovery.txt").takeIf { it.isFile }
        ?.let { String(readBounded(it, 4096), Charsets.UTF_8) }
    @Synchronized fun load(id: String): Book? {
        val dir = folder(id)
        if (!dir.exists()) return null
        val file = File(dir, "book.json")
        val current = runCatching { decode(readBounded(file, MAX_FILE)).also { require(it.id == id) } }
        current.getOrNull()?.let { return it }
        var notice = "工作稿损坏，已从最近有效历史版本恢复。原损坏文件已保留。"
        var recovered = revisions(id).firstNotNullOfOrNull { revision ->
            runCatching { decode(readBounded(File(dir, "revisions/$revision"), MAX_FILE)).also { require(it.id == id) } }.getOrNull()
        }
        if (recovered == null) {
            recovered = runCatching {
                val source = originalBytes(id)
                val metadata = parseJson(readBounded(File(dir, "source.json"), MAX_FILE))
                require(metadata.getString("sha256") == sha(source))
                val name = metadata.getString("name")
                Book(id, name.substringBeforeLast('.').take(200).ifBlank { "恢复的书籍" }, importChapters(name, source)).also(::validate)
            }.getOrNull()
            notice = "工作稿和历史版本无法读取，已从原稿恢复；修改内容及偏好未能恢复。原损坏文件已保留。"
        }
        val recoveredBook = recovered ?: throw IllegalStateException("书籍 $id 损坏且无可用恢复副本，请从本地备份恢复。原文件未删除。", current.exceptionOrNull())
        // Recovery is a new version epoch: old candidate/source anchors never become valid again.
        val restored = recoveredBook.copy(chapters=recoveredBook.chapters.map{it.copy(epoch=newId())})
        // Preserve forensic evidence before replacing a damaged working copy. Never modify source.bin.
        if (file.exists()) {
            val corrupt = File(dir, "book.corrupt-${System.currentTimeMillis()}-${UUID.randomUUID()}.json")
            require(file.renameTo(corrupt)) { "无法保留损坏工作稿，已停止自动恢复" }
        }
        atomicWrite(file, encode(restored))
        atomicWrite(File(dir, "recovery.txt"), notice.toByteArray(Charsets.UTF_8))
        return restored
    }
    @Synchronized fun save(input: Book, freshEpoch:Boolean=false): Book {
        val previous = load(input.id)
        val previousChapters=previous?.chapters?.associateBy{it.id}.orEmpty()
        val book=input.copy(chapters=input.chapters.map { c ->
            val old=previousChapters[c.id]
            if(old!=null) {
                require(old.revision<Long.MAX_VALUE || old.text==c.text){"版本号已到上限"}
                c.copy(revision=maxOf(c.revision,old.revision+if(old.text!=c.text)1 else 0),epoch=if(freshEpoch)newId()else old.epoch)
            } else if(freshEpoch)c.copy(epoch=newId())else c
        })
        validate(book)
        val dir = folder(book.id)
        require(File(dir, "source.bin").isFile) { "原稿不存在，请先导入书籍" }
        val target = File(dir, "book.json")
        val bytes = encode(book)
        require(bytes.size <= MAX_FILE) { "工作稿过大" }
        if (target.exists()) {
            val old = readBounded(target, MAX_FILE)
            if (old.contentEquals(bytes)) return book
            val revisions = File(dir, "revisions").apply { mkdirs() }
            val newest = revisions.listFiles().orEmpty().mapNotNull { it.name.substringBefore('_').toLongOrNull() }.maxOrNull() ?: 0L
            val timestamp = maxOf(System.currentTimeMillis(), newest + 1L)
            atomicWrite(File(revisions, "${timestamp}_${UUID.randomUUID()}.json"), old)
            // Keep a bounded local history; backup exports the latest 50 revisions too.
            var keptBytes = 0L
            revisions.listFiles().orEmpty().sortedByDescending { it.name }.forEachIndexed { index, file ->
                keptBytes += file.length()
                if (index >= 50 || keptBytes > MAX_EXPANDED) file.delete()
            }
        }
        atomicWrite(target, bytes)
        return book
    }
    @Synchronized fun importReference(bookId:String,workspaceId:String,sceneId:String,resource:ImportedResource):Book {
        val current=load(bookId)?:error("书籍不存在")
        require(current.workspaceId==workspaceId){"工作区已变化，请重新导入"}
        require(current.chapters.any{it.scene.id==sceneId}){"场景已变化，请重新选择"}
        require(current.resources.none{it.id==resource.id}){"这次导入已经保存，请勿重复确认"}
        return save(current.copy(resources=current.resources+resource,bindings=current.bindings+ReferenceBinding(resourceId=resource.id,sceneIds=listOf(sceneId))))
    }
    @Synchronized fun editSelection(id:String, anchor:SourceAnchor, candidate:String):Book {
        val current=load(id)?:error("书籍不存在")
        val chapter=current.chapters.find{it.id==anchor.chapterId}?:error("章节已不存在")
        val edited=applyAnchoredEdit(chapter,anchor,candidate,current.protectedText)
        return save(current.copy(chapters=current.chapters.map{if(it.id==edited.id)edited else it}))
    }
    @Synchronized fun undoSelection(id:String,before:Chapter,after:Chapter):Book {
        val current=load(id)?:error("书籍不存在")
        require(current.chapters.find{it.id==after.id}==after){"正文已变化，请使用历史恢复"}
        return save(current.copy(chapters=current.chapters.map{if(it.id==after.id)it.copy(text=before.text,revision=after.revision+1)else it}))
    }
    @Synchronized fun revisions(id: String): List<String> = File(folder(id), "revisions").listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(".json") }.map { it.name }.sortedDescending()
    @Synchronized fun rollback(id: String, revision: String): Book {
        require(Regex("[A-Za-z0-9_-]+\\.json").matches(revision)) { "无效版本" }
        val book = decode(readBounded(File(folder(id), "revisions/$revision"), MAX_FILE))
        require(book.id == id)
        return save(book,freshEpoch=true)
    }
    @Synchronized fun importBook(name: String, bytes: ByteArray): Book {
        require(bytes.isNotEmpty() && bytes.size <= MAX_FILE) { "文件不能为空，且不能超过 20 MB" }
        val chapters = importChapters(name, bytes)
        require(chapters.any { it.text.isNotBlank() }) { "未找到可阅读文字" }
        val book = Book(UUID.randomUUID().toString(), name.substringBeforeLast('.').take(200).ifBlank { "未命名书籍" }, chapters)
        validate(book)
        install(book, bytes, name, emptyMap())
        return book
    }
    /** Install cleaned working text atomically, with exact original bytes and a rollback revision. */
    @Synchronized fun importCleanedText(name:String, original:String, cleaned:String):Book {
        require(name.endsWith(".txt",ignoreCase=true)) { "清理导入仅支持纯文本" }
        require(original.isNotBlank() && cleaned.isNotBlank()) { "清理后不能没有正文" }
        require(original.length<=120000 && cleaned.length<=120000) { "一次清理最多 12 万字符" }
        val id=newId()
        val title=name.substringBeforeLast('.').take(200).ifBlank { "未命名文字" }
        val before=Book(id,title,parseText(title,original))
        val after=before.copy(chapters=parseText(title,cleaned))
        validate(before); validate(after)
        val history=if(original==cleaned)emptyMap() else mapOf("revisions/${System.currentTimeMillis()}_${newId()}.json" to encode(before))
        install(after,original.toByteArray(Charsets.UTF_8),name.dropLast(4).take(496)+".txt",history)
        return after
    }
    /** Exact originally imported bytes, suitable for an explicit original export. */
    @Synchronized fun originalBytes(id: String): ByteArray = readBounded(File(folder(id), "source.bin"), MAX_FILE)
    @Synchronized fun exportBackup(id: String): ByteArray {
        val dir = folder(id)
        val original = originalBytes(id)
        val sourceMetadata = readBounded(File(dir, "source.json"), MAX_FILE)
        require(parseJson(sourceMetadata).getString("sha256") == sha(original)) {
            "原稿校验失败，可能已损坏；已停止导出备份，原文件未修改。"
        }
        val book = load(id) ?: error("书籍不存在")
        val items = linkedMapOf("book.json" to encode(book), "source.bin" to original,
            "source.json" to sourceMetadata)
        var size = items.values.sumOf { it.size.toLong() }
        require(size <= MAX_EXPANDED - 65536) { "备份解压内容超过 40 MB，请减少工作稿大小" }
        for (revision in revisions(id)) {
            val data = readBounded(File(dir, "revisions/$revision"), MAX_FILE)
            // Older history may be omitted to keep portable backups bounded.
            if (size + data.size > MAX_EXPANDED - 65536) break
            items["revisions/$revision"] = data
            size += data.size
        }
        val manifest = JSONObject().put("format", "private-reader-backup").put("version", 1)
        items.forEach { (name, bytes) -> manifest.put(name, sha(bytes)) }
        items["manifest.json"] = manifest.toString().toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> items.forEach { (name, data) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
        } }
        return output.toByteArray().also { require(it.size <= MAX_FILE) { "备份超过 20 MB，请减少工作稿大小" } }
    }
    @Synchronized fun importBackup(bytes: ByteArray): Book {
        val entries = unzip(bytes, allowDirectories = false)
        val manifest = parseJson(entries["manifest.json"] ?: error("备份缺少清单"))
        require(manifest.optString("format") == "private-reader-backup" && manifest.optInt("version") == 1) { "未知备份格式" }
        val requiredMembers = setOf("book.json", "source.bin", "source.json")
        val actualMembers = entries.keys - "manifest.json"
        require(actualMembers.containsAll(requiredMembers)) { "备份缺少必要文件" }
        require(actualMembers.all { it in requiredMembers || Regex("revisions/[A-Za-z0-9_-]+\\.json").matches(it) }) { "备份包含未知文件" }
        val declaredMembers = manifest.keys().asSequence().toSet() - setOf("format", "version")
        require(declaredMembers == actualMembers) { "备份清单与文件不一致，可能已损坏" }
        for (name in actualMembers) {
            val checksum = manifest.getString(name)
            require(Regex("[0-9a-f]{64}").matches(checksum) && checksum == sha(entries.getValue(name))) { "备份校验失败" }
        }
        val original = entries["source.bin"] ?: error("备份缺少原稿")
        require(original.size <= MAX_FILE)
        val metadata = parseJson(entries["source.json"] ?: error("备份缺少原稿信息"))
        require(metadata.getString("sha256") == sha(original)) { "原稿校验失败" }
        val old = decode(entries["book.json"] ?: error("备份缺少工作稿"))
        // Restore as a new independent book: never overwrite an existing original.
        val book = old.copy(id = UUID.randomUUID().toString(),workspaceId=newId())
        val history = entries.filterKeys { it.startsWith("revisions/") }.mapValues { (_, data) ->
            val rev = decode(data); require(rev.id == old.id); encode(rev.copy(id = book.id,workspaceId=book.workspaceId))
        }
        require(history.size <= 50)
        history.keys.forEach { require(Regex("revisions/[A-Za-z0-9_-]+\\.json").matches(it)) }
        install(book, original, metadata.optString("name", "原稿"), history)
        return book
    }
    private fun importChapters(name: String, bytes: ByteArray): List<Chapter> = when (name.substringAfterLast('.', "").lowercase()) {
        "txt" -> splitChapters(name.substringBeforeLast('.'), decodeText(bytes))
        "epub" -> parseEpub(bytes)
        "html", "htm" -> splitChapters(name.substringBeforeLast('.'), plainHtml(decodeText(bytes)))
        else -> error("只支持 TXT、HTML 和无 DRM 的 EPUB")
    }
    private fun install(book: Book, original: ByteArray, name: String, history: Map<String, ByteArray>) {
        val working = encode(book)
        require(working.size <= MAX_FILE) { "工作稿超过 20 MB" }
        val dir = folder(book.id)
        require(!dir.exists())
        val staging = File(root, ".import-${UUID.randomUUID()}")
        require(staging.mkdirs())
        try {
            atomicWrite(File(staging, "source.bin"), original)
            atomicWrite(File(staging, "source.json"), JSONObject().put("name", name.take(500)).put("sha256", sha(original)).toString().toByteArray(Charsets.UTF_8))
            atomicWrite(File(staging, "book.json"), working)
            history.forEach { (path, data) -> atomicWrite(File(staging, path), data) }
            require(staging.renameTo(dir)) { "无法保存导入书籍" }
        } finally { if (staging.exists()) staging.deleteRecursively() }
    }
    private fun validate(book: Book) {
        StoryJson.validate(book)
        validateReferences(book)
        require(safeId.matches(book.id) && book.title.length <= 500 && book.chapters.isNotEmpty() && book.chapters.size <= 5000)
        require(book.chapters.all { it.title.length <= 500 && it.text.length <= CHAPTER_SIZE }) { "单章最多 12000 字符" }
        require(book.protectedText.size <= 1000 && book.protectedText.all { it.length <= CHAPTER_SIZE })
        require(book.style.length <= 50000 && book.facts.length <= 100000 && book.buttons.size <= 20 && book.buttons.all { it.length <= 2000 })
    }
    private fun encode(book: Book): ByteArray {
        validate(book)
        return JSONObject().put("version", 1).put("id", book.id).put("title", book.title)
            .put("style", book.style).put("facts", book.facts).put("buttons", JSONArray(book.buttons))
            .put("protectedText", JSONArray(book.protectedText))
            .put("cards",StoryJson.cards(book.cards)).put("relations",StoryJson.relations(book.relations))
            .put("workspaceId",book.workspaceId).put("resources",ResourceJson.encode(book.resources)).put("bindings",BindingJson.encode(book.bindings))
            .put("chapters", JSONArray(book.chapters.map { JSONObject().put("title", it.title).put("text", it.text).put("id",it.id).put("revision",it.revision).put("sceneId",it.scene.id).put("sceneTitle",it.scene.title).put("epoch",it.epoch) }))
            .toString().toByteArray(Charsets.UTF_8)
    }
    private fun decode(bytes: ByteArray): Book {
        require(bytes.size <= MAX_FILE)
        val json = parseJson(bytes)
        require(json.getInt("version") == 1) { "不支持的书籍版本" }
        val chapters = json.getJSONArray("chapters")
        require(chapters.length() in 1..5000)
        val buttons = json.optJSONArray("buttons") ?: JSONArray()
        val protectedText = json.optJSONArray("protectedText") ?: JSONArray()
        return Book(json.getString("id"), json.getString("title"), (0 until chapters.length()).map {
            chapters.getJSONObject(it).let { c -> Chapter(c.getString("title"), c.getString("text"), c.optString("id").ifBlank { "chapter-$it" }, c.optLong("revision",0), Scene(c.optString("sceneId").ifBlank { "scene-$it" }, c.optString("sceneTitle","本章场景")),c.optString("epoch").ifBlank{"legacy"}) }
        }, json.optString("style"), json.optString("facts"), (0 until buttons.length()).map { buttons.getString(it) },
            (0 until protectedText.length()).map { protectedText.getString(it) }, StoryJson.cards(json.optJSONArray("cards")), StoryJson.relations(json.optJSONArray("relations")),
            if(json.has("workspaceId")) (json.get("workspaceId") as? String ?: error("工作区标识类型无效")) else UUID.nameUUIDFromBytes(("workspace:"+json.getString("id")).toByteArray()).toString(),
            ResourceJson.decode(strictOptionalArray(json,"resources")),BindingJson.decode(strictOptionalArray(json,"bindings"))).also(::validate)
    }
    /** Bound recursive JSON parsing before org.json sees any untrusted bytes. */
    private fun parseJson(bytes: ByteArray): JSONObject {
        require(bytes.size <= MAX_FILE) { "JSON 文件过大" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val stack = mutableListOf<Char>()
        var quoted = false
        var escaped = false
        for (character in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { require(stack.size < 64) { "JSON 嵌套过深" }; stack.add(character) }
                '}', ']' -> {
                    require(stack.isNotEmpty() && stack.last() == if (character == '}') '{' else '[') { "JSON 括号不匹配" }
                    stack.removeAt(stack.lastIndex)
                }
                else -> require(character in ",: \t\r\n0123456789.-+eEtruefalsn") { "JSON 包含非标准语法" }
            }
        }
        require(!quoted && !escaped && stack.isEmpty()) { "JSON 不完整" }
        return JSONObject(text)
    }
    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile!!.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            require(temporary.renameTo(target)) { "无法原子保存文件" }
        } finally { temporary.delete() }
    }
    private fun readBounded(file: File, max: Int): ByteArray {
        require(file.isFile && file.length() <= max) { "文件不存在或过大" }
        return file.inputStream().use { input ->
            val result = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break
                require(result.size().toLong() + n <= max); result.write(buffer, 0, n)
            }; result.toByteArray()
        }
    }
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun decodeText(bytes: ByteArray): String {
        fun strict(charset: Charset) = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return runCatching { strict(Charsets.UTF_8) }.getOrElse { strict(Charset.forName("GB18030")) }
            .removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "")
    }
    fun parseText(title: String, text: String): List<Chapter> {
        require(text.length <= MAX_FILE) { "文字过大" }
        return splitChapters(title, text)
    }
    fun previewHtml(html: String): String {
        require(html.length <= MAX_FILE) { "HTML 过大" }
        return plainHtml(html)
    }
    private fun splitChapters(title: String, text: String): List<Chapter> {
        if (text.isBlank()) return emptyList()
        val headings = Regex("(?m)^[ \\t]*(第[一二三四五六七八九十百千万零〇两0-9]+[章节回卷部篇][^\\r\\n]{0,70}|Chapter[ \\t]+(?:[0-9]+|[IVXLCDM]+)(?:[ \\t:：.-][^\\r\\n]{0,70})?)[ \\t]*$", RegexOption.IGNORE_CASE)
            .findAll(text).toList()
        val result = mutableListOf<Chapter>()
        fun appendParts(sectionTitle: String, sectionText: String) {
            if (sectionText.isBlank()) return
            var position = 0
            var part = 0
            while (position < sectionText.length) {
                var end = minOf(position + CHAPTER_SIZE, sectionText.length)
                if (end < sectionText.length && Character.isHighSurrogate(sectionText[end - 1])) end--
                // Keep all source whitespace and the heading itself in the working text.
                val piece = sectionText.substring(position, end)
                result += Chapter(sectionTitle.take(450) + if (sectionText.length > CHAPTER_SIZE) " · ${++part}" else "", piece)
                position = end
            }
        }
        if (headings.isEmpty()) {
            appendParts(title, text)
        } else {
            val prefix = text.substring(0, headings.first().range.first)
            val blankPrefix = prefix.takeIf { it.isBlank() }.orEmpty()
            if (prefix.isNotBlank()) appendParts("序言", prefix)
            headings.forEachIndexed { index, heading ->
                val end = headings.getOrNull(index + 1)?.range?.first ?: text.length
                val sectionText = (if (index == 0) blankPrefix else "") + text.substring(heading.range.first, end)
                appendParts(heading.groupValues[1].trim(), sectionText)
            }
        }
        return result
    }
    private fun unzip(bytes: ByteArray, allowDirectories: Boolean = true): Map<String, ByteArray> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_FILE) { "压缩文件不能超过 20 MB" }
        val result = linkedMapOf<String, ByteArray>(); val seen = mutableSetOf<String>(); var expanded = 0L; var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= MAX_ENTRIES) { "压缩文件条目过多" }
                val name = entry.name
                require(name.isNotBlank() && name.length <= 1000 && !name.startsWith('/') && !name.contains('\\') && !name.contains(':') && !name.contains('\u0000') && name.split('/').none { it == ".." || it == "." }) { "压缩包路径不安全" }
                require(seen.add(name)) { "压缩包包含重复路径" }
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                // Directory entries may contain compressed payloads too. Never let closeEntry
                // drain unchecked data: read every member through the same expansion counter.
                while (true) {
                    val n = zip.read(buffer); if (n < 0) break
                    expanded += n
                    require(expanded <= MAX_EXPANDED) { "解压内容不能超过 40 MB" }
                    require(!entry.isDirectory || n == 0) { "压缩包目录包含非法内容" }
                    output.write(buffer, 0, n)
                }
                if (entry.isDirectory) require(allowDirectories) { "备份包含非标准目录条目" }
                else result[name] = output.toByteArray()
                zip.closeEntry()
            }
        }
        require(result.isNotEmpty()) { "空或无效压缩包" }
        return result
    }
    private fun xml(bytes: ByteArray): org.w3c.dom.Document {
        require(bytes.size <= MAX_FILE)
        // Reject declarations before parsing, in addition to disabling external resolution.
        val probe = String(bytes, Charsets.ISO_8859_1).replace("\u0000", "")
        require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(probe)) { "不支持包含 DTD 或实体声明的 EPUB" }
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching { factory.isExpandEntityReferences = false }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw IllegalArgumentException("禁止访问外部实体") }
        return builder.parse(ByteArrayInputStream(bytes))
    }
    private fun resolve(base: String, href: String): String {
        val raw = java.net.URLDecoder.decode(href.substringBefore('#'), "UTF-8")
        require(!raw.startsWith('/') && !raw.contains(':') && !raw.contains('\\')) { "EPUB 路径不安全" }
        val parts = mutableListOf<String>()
        (base.substringBeforeLast('/', "") .let { if (it.isEmpty()) raw else "$it/$raw" }).split('/').forEach {
            when (it) { "", "." -> Unit; ".." -> { require(parts.isNotEmpty()); parts.removeAt(parts.lastIndex) }; else -> parts.add(it) }
        }
        return parts.joinToString("/")
    }
    private fun parseEpub(bytes: ByteArray): List<Chapter> {
        val files = unzip(bytes)
        require(!files.containsKey("META-INF/encryption.xml")) { "不支持加密或 DRM EPUB" }
        val container = xml(files["META-INF/container.xml"] ?: error("EPUB 缺少 container.xml"))
        val roots = container.getElementsByTagNameNS("*", "rootfile")
        require(roots.length > 0) { "EPUB 缺少 OPF" }
        val opfPath = resolve("", (roots.item(0) as Element).getAttribute("full-path"))
        val opf = xml(files[opfPath] ?: error("未找到 OPF"))
        val manifest = mutableMapOf<String, String>()
        val items = opf.getElementsByTagNameNS("*", "item")
        for (i in 0 until items.length) {
            val item = items.item(i) as Element
            if (item.getAttribute("media-type") in listOf("application/xhtml+xml", "text/html")) manifest[item.getAttribute("id")] = resolve(opfPath, item.getAttribute("href"))
        }
        val spine = opf.getElementsByTagNameNS("*", "itemref")
        require(spine.length in 1..MAX_ENTRIES) { "EPUB 阅读顺序无效" }
        val result = mutableListOf<Chapter>()
        var characters = 0L
        for (i in 0 until spine.length) {
            val ref = spine.item(i) as Element
            val path = manifest[ref.getAttribute("idref")] ?: continue
            val source = decodeText(files[path] ?: error("EPUB 缺少章节：$path"))
            val title = Regex("<h[12]\\b[^>]*>([\\s\\S]*?)</h[12]>", RegexOption.IGNORE_CASE).find(source)?.groupValues?.get(1)?.let(::plainHtml)?.take(450)?.ifBlank { null } ?: "第 ${i + 1} 章"
            val text = plainHtml(source)
            characters += text.length
            require(characters <= MAX_EXPANDED && result.size <= 5000) { "EPUB 文字过多" }
            result.addAll(splitChapters(title, text))
        }
        return result
    }
    /** Produces inert text only: scripts, styles, tags and remote resources are never rendered. */
    private fun plainHtml(source: String): String {
        return source.replace(Regex("<(script|style|head|svg)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<(script|style|head|svg)\\b[^>]*>[\\s\\S]*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<!--[\\s\\S]*?-->"), "")
            .replace(Regex("</?(p|div|br|h[1-6]|li|section|article)\\b[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]*>"), "")
            .replace(Regex("&#(x[0-9a-fA-F]+|[0-9]+);")) { match ->
                runCatching { val raw = match.groupValues[1]; val point = if (raw.startsWith("x")) raw.drop(1).toInt(16) else raw.toInt(); require(Character.isValidCodePoint(point) && point !in 0xD800..0xDFFF); String(Character.toChars(point)) }.getOrDefault("")
            }
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
            .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n[ \\t]*\\n(?:[ \\t]*\\n)+"), "\n\n").trim()
    }
}
