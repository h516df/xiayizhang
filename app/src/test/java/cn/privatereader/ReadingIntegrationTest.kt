package cn.privatereader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Model/storage contract only. Does not execute Android WebView JavaScript. */
class ReadingIntegrationTest {
 @Test fun repeatedChineseEmojiWhitespaceRangesUseExactUtf16Offsets(){
  val text="重复句😀。\n\n  重复句😀。\t尾段\r\n跨段文字"
  val chapter=Chapter("合成",text)
  val start=text.indexOf("  重复")+2;val end=text.length-2
  val anchor=ReadingProjection.selection(chapter,JSONObject().put("start",start).put("end",end).put("text",text.substring(start,end)).toString())
  val changed=applyAnchoredEdit(chapter,anchor,"新片段",emptyList())
  assertEquals(text.substring(0,start)+"新片段"+text.substring(end),changed.text)
  assertEquals("重复句😀。\n\n  ",changed.text.substring(0,start))
  assertThrows(IllegalArgumentException::class.java){ReadingProjection.selection(chapter,JSONObject().put("start",0).put("end",4).put("text","错误片段").toString())}
 }
 @Test fun projectionIsEscapedAndCannotFetchOriginalMarkup(){
  val text="<script>alert('x')</script><img src='https://evil.invalid/x'><iframe src='file:///secret'> & 😀\n\t尾段\r"
  val html=ReadingProjection.html(Chapter("安全",text),text.indexOf("😀")+1)
  assertFalse(html.contains("<script>"));assertFalse(html.contains("<img "));assertFalse(html.contains("<iframe "))
  assertTrue(html.contains("connect-src 'none'"));assertTrue(html.contains("form-action 'none'"));assertTrue(html.contains("&lt;script&gt;"))
  assertEquals(text.indexOf("😀"),ReadingProjection.safeOffset(text,text.indexOf("😀")+1))
 }
 @Test fun sourceOffsetMovesWithEditsAndUndoWithoutSearchingText(){
  assertEquals(50,ReadingProjection.remapOffset(70,10,40,10))
  assertEquals(10,ReadingProjection.remapOffset(20,10,40,10))
  assertEquals(5,ReadingProjection.remapOffset(5,10,40,10))
  assertEquals(70,ReadingProjection.remapOffset(50,10,20,30))
 }
 @Test fun millionCharacterBookEditUndoBackupAndRecoveryPreserveOriginalAndReferences(){
  val dir=File(System.getProperty("java.io.tmpdir"),"readium-contract-${System.nanoTime()}")
  try{
   val store=Store(dir);val raw=(1..150).joinToString("\n"){"第${it}章 合成长书\n"+"重复对白😀。她停在窗前。\n".repeat(600)}.toByteArray()
   var book=store.importBook("合成长书.txt",raw)
   assertTrue(book.chapters.size>100);assertTrue(book.chapters.all{it.text.length<=Store.CHAPTER_SIZE})
   val target=book.chapters[50];val card=StoryCard(kind=CardKind.CHARACTER,name="合成人物",notes="资料保留",sceneIds=listOf(target.scene.id))
   book=store.save(book.copy(cards=listOf(card)))
   val at=target.text.indexOf("重复对白",10);val a=selectionAnchor(target,at,at+"重复对白😀。".length);val updated=store.editSelection(book.id,a,"接受的候选")
   assertEquals(listOf(card),updated.cards);assertArrayEquals(raw,store.originalBytes(book.id))
   val undone=store.undoSelection(book.id,target,updated.chapters[50]);assertEquals(target.text,undone.chapters[50].text)
   val restored=Store(File(dir,"restored")).importBackup(store.exportBackup(book.id))
   assertEquals(undone.chapters.map{it.text},restored.chapters.map{it.text});assertEquals(undone.cards,restored.cards)
   File(dir,"${book.id}/book.json").writeText("corrupt")
   val recovered=store.load(book.id)!!;assertEquals(listOf(card),recovered.cards);assertArrayEquals(raw,store.originalBytes(book.id))
   assertFalse(a.matches(recovered.chapters[50]))
  }finally{dir.deleteRecursively()}
 }
}
