package cn.privatereader

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CoreTest {
    private fun store(): Store = Store(Files.createTempDirectory("reader-test").toFile().apply { deleteOnExit() })
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> entries.forEach { (name, value) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry()
        } }
        return output.toByteArray()
    }
    private fun text(value: String) = value.toByteArray(Charsets.UTF_8)
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected rejection") } catch (expected: IllegalArgumentException) { }
    }
    @Test fun utf8OriginalRemainsImmutableThroughEditAndRollback() {
        val store = store(); val original = text("原稿内容\n第二行")
        val book = store.importBook("我的小说.txt", original)
        val edited = book.copy(chapters = listOf(Chapter("第一章", "修订后的文字")))
        store.save(edited)
        assertEquals(edited, store.load(book.id))
        assertArrayEquals(original, store.originalBytes(book.id))
        assertEquals(1, store.revisions(book.id).size)
        store.save(edited)
        assertEquals(1, store.revisions(book.id).size)
        assertEquals(book, store.rollback(book.id, store.revisions(book.id).first()))
        assertArrayEquals(original, store.originalBytes(book.id))
    }
    @Test fun gb18030AndSurrogateSafeChunks() {
        val store = store()
        val book = store.importBook("中文.txt", "你好世界".toByteArray(Charset.forName("GB18030")))
        assertEquals("你好世界", book.chapters.first().text)
        val longText = "甲".repeat(11999) + "😀" + "乙".repeat(12001)
        val chunks = store.importBook("长篇.txt", text(longText)).chapters
        assertTrue(chunks.all { it.text.length <= Store.CHAPTER_SIZE })
        assertEquals(longText, chunks.joinToString("") { it.text })
        assertFalse(chunks.any { Character.isHighSurrogate(it.text.last()) })
    }
    @Test fun backupRestoresWorkingStateAndOriginalWithoutOverwriting() {
        val store = store()
        val original = store.importBook("原文.txt", text("原文"))
        val edited = original.copy(style = "简洁", facts = "主角姓名", buttons = listOf("检查"), protectedText = listOf("保留台词"), chapters = listOf(Chapter("新标题", "修订")))
        store.save(edited)
        val restored = store.importBackup(store.exportBackup(original.id))
        assertNotEquals(original.id, restored.id)
        assertEquals(edited.copy(id = restored.id), restored)
        assertEquals(edited, store.load(original.id))
        assertArrayEquals(text("原文"), store.originalBytes(restored.id))
        assertEquals(1, store.revisions(restored.id).size)
        assertEquals("原文", store.rollback(restored.id, store.revisions(restored.id).first()).chapters.first().text)
    }
    @Test fun epubRespectsSpineInsteadOfZipOrManifestOrder() {
        val store = store()
        val bytes = zip(
            "META-INF/container.xml" to text("<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>"),
            "OPS/book.opf" to text("<package xmlns='http://www.idpf.org/2007/opf'><manifest><item id='a' href='a.xhtml' media-type='application/xhtml+xml'/><item id='b' href='b.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='b'/><itemref idref='a'/></spine></package>"),
            "OPS/a.xhtml" to text("<html><head><title>隐藏</title></head><body><h1>甲</h1><p>A&amp;B</p></body></html>"),
            "OPS/b.xhtml" to text("<html><body><h1>乙</h1><script>evil()</script><p>正文</p></body></html>")
        )
        val book = store.importBook("顺序.epub", bytes)
        assertEquals(listOf("乙", "甲"), book.chapters.map { it.title })
        assertFalse(book.chapters.first().text.contains("evil"))
        assertFalse(book.chapters.last().text.contains("隐藏"))
        assertTrue(book.chapters.last().text.contains("A&B"))
    }
    @Test fun refusesZipTraversalAndDtd() {
        val store = store()
        rejected { store.importBook("bad.epub", zip("../escape" to text("x"))) }
        rejected { store.importBook("bad.epub", zip("/escape" to text("x"))) }
        rejected { store.importBook("bad.epub", zip("META-INF/container.xml" to text("<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><root>&x;</root>"))) }
        rejected { store.load("../bad") }
    }
    @Test fun rejectsExpandedZipBombAndOversizedFile() {
        val store = store()
        rejected { store.importBook("huge.txt", ByteArray(Store.MAX_FILE + 1)) }
        rejected { store.importBook("bomb.epub", zip("huge" to ByteArray(Store.MAX_EXPANDED + 1))) }
    }
    @Test fun backupTamperingRejected() {
        val store = store()
        rejected { store.importBackup(zip("manifest.json" to text("{\"format\":\"private-reader-backup\",\"version\":1}"), "source.bin" to text("modified"))) }
    }
    @Test fun htmlBecomesInertText() {
        val book = store().importBook("saved.html", text("<h1>标题</h1><script>fetch('remote')</script><p>正文 &lt;b&gt;</p>"))
        assertFalse(book.chapters.first().text.contains("fetch"))
        assertTrue(book.chapters.first().text.contains("正文 <b>"))
    }
    @Test fun corruptWorkingCopyRecoversRevisionAndPreservesEvidence() {
        val root = Files.createTempDirectory("reader-corruption").toFile()
        val store = Store(root)
        val original = store.importBook("原稿.txt", text("原稿"))
        store.save(original.copy(style = "修订"))
        java.io.File(root, "${original.id}/book.json").writeText("corrupt")
        assertEquals(original, store.load(original.id))
        assertTrue(java.io.File(root, original.id).listFiles()!!.any { it.name.startsWith("book.corrupt-") && it.readText() == "corrupt" })
        assertTrue(store.recoveryNotice(original.id)!!.contains("历史版本"))
        assertArrayEquals(text("原稿"), store.originalBytes(original.id))
    }
    @Test fun corruptWorkingCopyWithoutHistoryRecoversOriginal() {
        val root = Files.createTempDirectory("reader-original-recovery").toFile()
        val store = Store(root)
        val original = store.importBook("原稿.txt", text("原稿"))
        java.io.File(root, "${original.id}/book.json").writeText("broken")
        assertEquals(original, store.list().single())
        assertTrue(store.recoveryNotice(original.id)!!.contains("从原稿恢复"))
    }
    @Test fun completelyCorruptBookIsNotSilentlyHidden() {
        val root = Files.createTempDirectory("reader-unrecoverable").toFile()
        val store = Store(root)
        val original = store.importBook("原稿.txt", text("原稿"))
        java.io.File(root, "${original.id}/book.json").writeText("broken")
        java.io.File(root, "${original.id}/source.bin").writeText("modified")
        try { store.list(); fail("Unrecoverable corruption should be visible") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("损坏")) }
    }

    @Test fun txtHeadingsPreservePrefaceOrderAndAllText() {
        val source = "作者的话\n这是序言。\n\n第一章 初见\n甲的故事。\n\n第2章 重逢\n乙的故事。\nChapter III: Ending\nThe end.\n"
        val book = store().importBook("小说.txt", text(source))
        assertEquals(listOf("序言", "第一章 初见", "第2章 重逢", "Chapter III: Ending"), book.chapters.map { it.title })
        assertEquals(source, book.chapters.joinToString("") { it.text })
    }
    @Test fun blankPrefixDoesNotCreateEmptyChapterAndIsPreserved() {
        val source = "\n \t\n第一章 开始\n正文\n第二章 继续\n后文\n"
        val chapters = store().parseText("小说", source)
        assertEquals(2, chapters.size)
        assertEquals("第一章 开始", chapters.first().title)
        assertEquals(source, chapters.joinToString("") { it.text })
    }
    @Test fun headingSectionsSplitOversizedTextWithoutLosingWhitespace() {
        val source = "第一章 长章\n" + "甲".repeat(11990) + "😀" + " \n".repeat(100) + "第二章 结尾\n终。"
        val chapters = store().parseText("小说", source)
        assertTrue(chapters.size >= 3)
        assertEquals("第二章 结尾", chapters.last().title)
        assertTrue(chapters.all { it.text.length <= Store.CHAPTER_SIZE })
        assertEquals(source, chapters.joinToString("") { it.text })
    }

    @Test fun rejectsDirectoryPayloadInsteadOfDrainingUnchecked() {
        rejected { store().importBook("bad.epub", zip("directory/" to text("unexpected compressed payload"))) }
        rejected { store().importBook("bomb.epub", zip("directory/" to ByteArray(Store.MAX_EXPANDED + 1))) }
    }
    private fun backupEntries(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                result[entry.name] = input.readBytes()
                input.closeEntry()
            }
        }
        return result
    }
    @Test fun rejectsBackupWithMissingDeclaredRevision() {
        val store = store()
        val book = store.importBook("原稿.txt", text("原稿"))
        store.save(book.copy(style = "修改"))
        val entries = backupEntries(store.exportBackup(book.id))
        entries.remove(entries.keys.first { it.startsWith("revisions/") })
        rejected { store.importBackup(zip(*entries.entries.map { it.key to it.value }.toTypedArray())) }
    }
    @Test fun rejectsBackupWithUnknownMemberEvenIfChecksummed() {
        val store = store()
        val book = store.importBook("原稿.txt", text("原稿"))
        val entries = backupEntries(store.exportBackup(book.id))
        val added = text("unexpected")
        val manifest = org.json.JSONObject(String(entries.getValue("manifest.json"), Charsets.UTF_8))
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(added).joinToString("") { "%02x".format(it) }
        manifest.put("unknown.bin", digest)
        entries["unknown.bin"] = added
        entries["manifest.json"] = text(manifest.toString())
        rejected { store.importBackup(zip(*entries.entries.map { it.key to it.value }.toTypedArray())) }
    }

    @Test fun deeplyNestedManifestIsRejectedBeforeRecursiveParser() {
        val malicious = "{\"nested\":" + "[".repeat(10000) + "0" + "]".repeat(10000) + "}"
        rejected { store().importBackup(zip("manifest.json" to text(malicious))) }
        rejected { store().importBackup(zip("manifest.json" to text("{\"a\":[}"))) }
    }
    @Test fun jsonDepthScannerIgnoresBracketsInsideEscapedStrings() {
        val store = store()
        val original = store.importBook("字符串.txt", text("正文"))
        val edited = original.copy(style = "带引号 \" 与反斜线 \\" + "[".repeat(200) + "}".repeat(200))
        store.save(edited)
        assertEquals(edited, store.load(original.id))
        val restored = store.importBackup(store.exportBackup(original.id))
        assertEquals(edited.style, restored.style)
    }

    @Test fun shelfSummariesContainOnlyBoundedPreview() {
        val store = store()
        val book = store.importBook("书架.txt", text("甲".repeat(79) + "😀" + "乙".repeat(15000)))
        val summary = store.summaries().single()
        assertEquals(book.id, summary.id)
        assertEquals(book.title, summary.title)
        assertEquals(book.chapters.size, summary.chapterCount)
        assertTrue(summary.preview.length <= 80)
        assertFalse(Character.isHighSurrogate(summary.preview.last()))
        assertEquals("甲".repeat(79), summary.preview)
        assertEquals(book, store.load(summary.id))
    }

    @Test fun backupExportRejectsCorruptOriginalWithoutMutatingFiles() {
        val root = Files.createTempDirectory("reader-export-integrity").toFile()
        val store = Store(root)
        val book = store.importBook("原稿.txt", text("原稿"))
        val dir = java.io.File(root, book.id)
        val workingBefore = java.io.File(dir, "book.json").readBytes()
        val metadataBefore = java.io.File(dir, "source.json").readBytes()
        val corrupted = text("已损坏的原稿")
        java.io.File(dir, "source.bin").writeBytes(corrupted)
        rejected { store.exportBackup(book.id) }
        assertArrayEquals(corrupted, java.io.File(dir, "source.bin").readBytes())
        assertArrayEquals(workingBefore, java.io.File(dir, "book.json").readBytes())
        assertArrayEquals(metadataBefore, java.io.File(dir, "source.json").readBytes())
        assertEquals(book, store.load(book.id))
    }

}
