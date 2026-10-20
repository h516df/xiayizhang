package cn.privatereader

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TextCleanupTest {
 private fun all(p:CleanupPlan)=p.apply(p.edits.map{it.key}.toSet())
 private fun root()=File(System.getProperty("java.io.tmpdir"),"cleanup-${System.nanoTime()}")
 @Test fun noDeletionIsAutomaticallyRecommended(){val p=planCleanup("正文\n上一章 | 目录 | 下一章\n请收藏本站 https://example.test\n后文");assertEquals(2,p.edits.size);assertTrue(p.edits.none{it.recommended});assertEquals(p.source,p.apply(emptySet()));assertEquals("正文\n后文",all(p))}
 @Test fun dialogueUrlsAndNumbersRemain(){val raw="他说：下一章会发生什么？\n她说请收藏本站，不然就找不到我。\n剧情地址 https://example.test/a。\n12345\n甲乙丙丁。";assertEquals(raw,all(planCleanup(raw)))}
 @Test fun standaloneUrlIsNotAutomaticallyDeleted(){val raw="https://example.test/story\n故事的线索";assertTrue(planCleanup(raw).edits.isEmpty())}
 @Test fun exactNavigationIsOnlyUnselectedCandidate(){val p=planCleanup("下一章");assertEquals(1,p.edits.size);assertEquals("下一章",p.apply(emptySet()));assertFalse(p.edits[0].recommended)}
 @Test fun duplicateHeadingOnlyAdjacentNonblank(){val p=planCleanup("第一章 雨夜\n\n第一章 雨夜\n正文\n第一章 雨夜\n正文");assertEquals(1,p.edits.size);assertEquals("相邻重复章名（保留首次）",p.edits[0].reason);assertEquals(2,Regex("第一章 雨夜").findAll(all(p)).count())}
 @Test fun whitespaceKeepsIndentAndParagraphs(){val raw="　　正文  \r\n\r\n下一段\r\n";assertEquals("　　正文\n\n下一段\n",all(planCleanup(raw)))}
 @Test fun poetryAndListsNeverJoinByDefault(){val raw="床前明月光\n疑是地上霜\n1. 起点\n2. 终点\n“对话第一行\n继续对话”";assertEquals(raw,all(planCleanup(raw)))}
 @Test fun longChineseWrapIsOptInAndUnchecked(){val line="这是一段足够长的中文小说段落没有结束标点用于测试";val raw="$line\n$line";assertTrue(planCleanup(raw).edits.isEmpty());val p=planCleanup(raw,CleanupOptions(joinWrappedLines=true));assertEquals(1,p.edits.size);assertFalse(p.edits[0].recommended);assertEquals(line+line,all(p))}
 @Test fun quoteAndHeadingsAreNotWrapJoined(){val line="这是一段足够长的中文小说段落没有结束标点用于测试";val raw="$line\n“$line”\n第一章 $line";assertTrue(planCleanup(raw,CleanupOptions(joinWrappedLines=true)).edits.isEmpty())}
 @Test fun literalIsNotRegexAndDoesNotRunOtherRules(){val p=planCleanup("正文 .* 下一章\n下一章",CleanupOptions(find=".*",replacement=""));assertEquals(1,p.edits.size);assertEquals("正文  下一章\n下一章",all(p))}
 @Test fun literalNeverRecursivelyReplaces(){assertEquals("哈哈哈哈",all(planCleanup("哈哈",CleanupOptions(find="哈",replacement="哈哈"))))}
 @Test fun literalCanChooseOneOccurrence(){val p=planCleanup("同词同词",CleanupOptions(find="同词",replacement="改"));assertEquals("同词改",p.apply(setOf(p.edits.last().key)))}
 @Test fun staleKeysAreRejected(){val p=planCleanup("下一章");assertThrows(IllegalArgumentException::class.java){p.apply(setOf("old"))}}
 @Test fun tooLargeAndTooManyCandidatesFailWithoutPartialPlan(){assertThrows(IllegalArgumentException::class.java){planCleanup("中".repeat(120001))};assertThrows(IllegalArgumentException::class.java){planCleanup("下一章\n".repeat(1001))}}
 @Test fun oversizedReplacementResultRejected(){val p=planCleanup("中".repeat(500),CleanupOptions(find="中",replacement="长".repeat(500)));assertThrows(IllegalArgumentException::class.java){all(p)}}
 @Test fun unicodePairsRemainIntact(){val p=planCleanup("😀甲😀乙",CleanupOptions(find="😀",replacement="🙂"));assertEquals("🙂甲🙂乙",all(p));assertThrows(IllegalArgumentException::class.java){planCleanup("😀",CleanupOptions(find="\uDE00"))}}
 @Test fun cleanedImportPreservesExactBytesAndRestorableHistory(){val dir=root();try{val s=Store(dir);val raw="第一章 雨\n原文\r\n下一章\n后文";val p=planCleanup(raw);val b=s.importCleanedText("测试.txt",raw,all(p));assertArrayEquals(raw.toByteArray(),s.originalBytes(b.id));assertFalse(b.chapters.joinToString(""){it.text}.contains("下一章"));val restored=s.rollback(b.id,s.revisions(b.id).single());assertEquals(raw,restored.chapters.joinToString(""){it.text});assertArrayEquals(raw.toByteArray(),s.originalBytes(b.id));val copy=Store(File(dir,"other")).importBackup(s.exportBackup(b.id));assertEquals(raw,copy.chapters.joinToString(""){it.text})}finally{dir.deleteRecursively()}}
 @Test fun cleanupImportRejectsMislabeledSource(){val dir=root();try{val s=Store(dir);assertThrows(IllegalArgumentException::class.java){s.importCleanedText("a.epub","原文","正文")};assertTrue(s.summaries().isEmpty())}finally{dir.deleteRecursively()}}
 @Test fun longNameRetainsTxtExtensionForSourceRecovery(){val dir=root();try{val s=Store(dir);val b=s.importCleanedText("a".repeat(495)+".epub.txt","原文","原文");File(dir,"${b.id}/book.json").writeText("broken");val recovered=s.load(b.id)!!;assertEquals("原文",recovered.chapters.joinToString(""){it.text});assertArrayEquals("原文".toByteArray(),s.originalBytes(b.id))}finally{dir.deleteRecursively()}}
 @Test fun invalidCleanedImportCreatesNoBook(){val dir=root();try{val s=Store(dir);assertThrows(IllegalArgumentException::class.java){s.importCleanedText("a.txt","原文","")};assertTrue(s.summaries().isEmpty())}finally{dir.deleteRecursively()}}
 @Test fun selectionCleanupKeepsOriginalAndUndoAndRejectsStale(){val dir=root();try{val s=Store(dir);val b=s.importBook("测试.txt","前文\n下一章\n后文".toByteArray());val c=b.chapters[0];val a=selectionAnchor(c,0,c.text.length);val edited=s.editSelection(b.id,a,all(planCleanup(c.text)));assertThrows(IllegalArgumentException::class.java){s.editSelection(b.id,a,"错误覆盖")};val undone=s.undoSelection(b.id,c,edited.chapters[0]);assertEquals(c.text,undone.chapters[0].text);assertEquals("前文\n下一章\n后文",String(s.originalBytes(b.id)))}finally{dir.deleteRecursively()}}
 @Test fun protectedTextStillBlocksCleanup(){val dir=root();try{val s=Store(dir);val b=s.importBook("测试.txt","正文\n下一章\n后文".toByteArray());val guarded=s.save(b.copy(protectedText=listOf("下一章")));val c=guarded.chapters[0];assertThrows(IllegalArgumentException::class.java){s.editSelection(b.id,selectionAnchor(c,0,c.text.length),all(planCleanup(c.text)))};assertEquals(c,s.load(b.id)!!.chapters[0])}finally{dir.deleteRecursively()}}
}
