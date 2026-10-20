package cn.privatereader

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File

/** Real Android entry/Fragment wiring under Robolectric, NOT WebView render/selection acceptance. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w393dp-h852dp-xhdpi")
class ReadiumEntryTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 @Test fun publicationContainerRejectsExternalUrlsAndTraversal(){
  val chapter=Chapter("合成","<script>不会运行</script>\n重复😀。")
  val publication=chapterPublication(chapter,0)
  try{
   listOf("https://untrusted.invalid/x","file:///secret","../source.bin").forEach{value->
    Assert.assertNull(publication.get(org.readium.r2.shared.util.Url(value)!!))
   }
   val resource=publication.get(org.readium.r2.shared.util.Url("chapter.xhtml")!!)!!
   val html=kotlinx.coroutines.runBlocking{resource.read().getOrNull()!!.toString(Charsets.UTF_8)}
   Assert.assertTrue(html.contains("&lt;script&gt;不会运行&lt;/script&gt;"));Assert.assertFalse(html.contains("<script>"))
  }finally{publication.close()}
 }
 @Test fun actualActivityMountsReadiumForLongBookAndReturnsToShelfWithoutChangingBytes(){
  val root=File(System.getProperty("java.io.tmpdir"),"readium-entry-${System.nanoTime()}")
  try {
   val store=Store(root)
   val raw=(1..120).joinToString("\n"){"第${it}章 合成章节\n"+"重复对白😀。她看向远方。\n".repeat(650)}.toByteArray()
   val book=store.importBook("Readium合成长书.txt",raw)
   compose.activityRule.scenario.onActivity{activity->activity.setContent{PrivateReader(store,SecureSettings(activity))}}
   compose.waitUntil(10000){compose.onAllNodesWithText(book.title).fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithText(book.title).performClick()
   compose.waitUntil(10000){compose.onAllNodesWithTag("readium-reader").fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithText("排版 · 翻页").assertExists()
   compose.runOnIdle{Assert.assertTrue(compose.activity.supportFragmentManager.fragments.any{it is EpubNavigatorFragment})}
   compose.onNodeWithText("书架").performClick()
   compose.waitUntil(10000){compose.onAllNodesWithText("AI 设置").fetchSemanticsNodes().isNotEmpty()}
   Assert.assertArrayEquals(raw,store.originalBytes(book.id));Assert.assertEquals(book,store.load(book.id))
   Assert.assertTrue(store.revisions(book.id).isEmpty())
  }finally{root.deleteRecursively()}
 }
}
