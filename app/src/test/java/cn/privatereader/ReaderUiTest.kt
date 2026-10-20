package cn.privatereader

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w393dp-h852dp-xhdpi")
class ReaderUiTest {
 @get:Rule val compose=createComposeRule()
 private lateinit var store:Store
 private lateinit var root:File
 @Before fun setup(){root=File(System.getProperty("java.io.tmpdir"),"novel-ui-${System.nanoTime()}");store=Store(root)}
 @After fun cleanup(){root.deleteRecursively()}
 private fun start(){val context=RuntimeEnvironment.getApplication();compose.setContent{PrivateReader(store,SecureSettings(context))};compose.waitForIdle()}
 private fun capture(name:String,dialog:Boolean=false){val f=File("build/outputs/reader-ui/$name.png");f.parentFile!!.mkdirs();(if(dialog)compose.onNode(isDialog())else compose.onRoot()).captureRoboImage(f.absolutePath)}
 @Test fun emptyShelf(){start();compose.onNodeWithText("一方只属于你的书房").assertExists();capture("01-empty-shelf")}
 @Test fun readerAndEditor(){
  val book=store.importBook("雾灯小记.txt","第一章 雾中的灯\n\n林遥在雨停后回到小镇。巷口那盏灯还亮着，灯下摆着一只空木箱。\n\n她把信封放进口袋，轻轻敲了三下门。\n\n第二章 渡船\n\n最后一班渡船正慢慢靠岸。".toByteArray())
  start()
  compose.waitUntil(5000){compose.onAllNodesWithText("雾灯小记").fetchSemanticsNodes().isNotEmpty()}
  capture("02-bookshelf")
  compose.onNodeWithText("雾灯小记").performClick()
  compose.onNodeWithText("编辑本章").assertExists()
  capture("03-reader")
  compose.onNodeWithText("编辑本章").performClick()
  compose.onNodeWithText("正在编辑当前章节 · 停笔自动保存").assertExists()
  capture("04-editor")
  compose.onNodeWithText("完成编辑").performClick()
  compose.waitUntil(5000){compose.onAllNodesWithText("正在编辑当前章节 · 停笔自动保存").fetchSemanticsNodes().isEmpty()}
  Assert.assertEquals(book.chapters,store.load(book.id)!!.chapters)
 }
 @Test fun aiPreviewClosesWithoutChange(){
  val book=store.importBook("AI 预览实验.txt","保留的原文，不应被自动改变。".toByteArray())
  start()
  compose.waitUntil(5000){compose.onAllNodesWithText("AI 预览实验").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("AI 预览实验").performClick()
  compose.onNodeWithText("AI 改写").assertIsEnabled().performClick()
  compose.onNodeWithText("先比较，再采用").assertExists()
  compose.onNodeWithText("连接 AI").assertIsEnabled()
  compose.onNodeWithText("确认发送并生成").assertDoesNotExist()
  capture("05-ai-workbench")
  compose.onNodeWithText("本次改写指令").performTextReplacement("只修辞，不改剧情")
  compose.onNodeWithText("连接 AI").performClick()
  compose.onNodeWithText("尚未连接 AI").assertExists()
  compose.onNodeWithText("返回改写").performClick()
  compose.onNodeWithText("只修辞，不改剧情").assertExists()
  compose.onNodeWithText("保留原文").performClick()
  compose.onNodeWithText("编辑本章").assertExists()
  Assert.assertEquals(book.chapters,store.load(book.id)!!.chapters)
  compose.onNodeWithText("AI 改写").performClick()
  compose.onNodeWithText("只修辞，不改剧情").assertExists()
  compose.onNodeWithText("返回阅读").performClick()
  Assert.assertEquals(book.chapters,store.load(book.id)!!.chapters)
 }
 @Test fun editedTextPersistsAndOriginalSurvives(){
  val original="保留原稿".toByteArray()
  val book=store.importBook("编辑实验.txt",original)
  start()
  compose.waitUntil(5000){compose.onAllNodesWithText("编辑实验").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("编辑实验").performClick()
  compose.onNodeWithText("编辑本章").performClick()
  compose.onNode(hasSetTextAction()).performTextReplacement("这是真实编辑后的文字")
  compose.mainClock.advanceTimeBy(800)
  compose.waitUntil(5000){store.load(book.id)?.chapters?.first()?.text=="这是真实编辑后的文字"}
  compose.onNodeWithText("完成编辑").performClick()
  compose.waitUntil(5000){compose.onAllNodesWithText("正在编辑当前章节 · 停笔自动保存").fetchSemanticsNodes().isEmpty()}
  compose.onNodeWithText("这是真实编辑后的文字").assertExists()
  Assert.assertArrayEquals(original,store.originalBytes(book.id))
  Assert.assertTrue(store.revisions(book.id).isNotEmpty())
 }
 @Test fun settingsAndImport(){start();compose.onNodeWithText("AI 设置").performClick();compose.onNodeWithText("尚未连接 AI").assertExists();capture("06-settings");compose.onNodeWithText("书架").performClick();compose.onNodeWithText("粘贴文字").performClick();compose.onNodeWithText("先预览，再收入").assertExists();capture("07-import")}
}
