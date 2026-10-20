package cn.privatereader
import org.junit.Test
import org.junit.Assert.*
class EditingTest {
 @Test fun acceptOnlyUniqueSource(){assertEquals("前文新文后文",acceptRewrite("前文旧文后文","旧文","新文",emptyList()));assertThrows(IllegalArgumentException::class.java){acceptRewrite("重重","重","新",emptyList())};assertThrows(IllegalArgumentException::class.java){acceptRewrite("原文","自编","新",emptyList())}}
 @Test fun protectedOccurrencesCannotDisappear(){assertThrows(IllegalArgumentException::class.java){acceptRewrite("名字与名字","名字与名字","名字",listOf("名字"))};assertEquals("名字来了",acceptRewrite("名字走了","走了","来了",listOf("名字")))}
 @Test fun diffShowsOnlyChangedSpan(){assertEquals(TextChange("他","走","跑","了"),textChange("他走了","他跑了"));assertEquals(TextChange("相同","","",""),textChange("相同","相同"))}
 @Test fun lateOlderDraftCannotOverwriteFinalSave(){
  val root=java.nio.file.Files.createTempDirectory("ordered-drafts").toFile()
  val store=Store(root);val original=store.importBook("sample.txt","原文".toByteArray());val saver=OrderedDraftSaver(store)
  val old=saver.nextTicket();val fresh=saver.nextTicket()
  val latest=original.copy(chapters=listOf(Chapter("sample","最新")))
  assertEquals(latest,saver.save(latest,fresh))
  assertNull(saver.save(original,old));assertEquals(latest,store.load(original.id));root.deleteRecursively()
 }
}
