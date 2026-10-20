package cn.privatereader

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
 private fun capture(name:String,dialog:Boolean=false){compose.mainClock.advanceTimeByFrame();compose.waitForIdle();val f=File("build/outputs/reader-ui/$name.png");f.parentFile!!.mkdirs();(if(dialog)compose.onNode(isDialog())else compose.onRoot()).captureRoboImage(f.absolutePath)}
 private fun workspace(book:Book,ai:WritingAi?=null){val context=RuntimeEnvironment.getApplication();compose.setContent{var current by remember{mutableStateOf(book)};ReaderTheme{Surface{WritingWorkspace(current,0,store,SecureSettings(context),20f,{current=it},{},{},{},{},{},ai)}}};compose.waitForIdle()}
 private fun select(start:Int,end:Int){compose.onNodeWithTag("reader-body").performTextInputSelection(TextRange(start,end));compose.waitForIdle()}
 @Test fun emptyShelf(){start();compose.onNodeWithText("一方只属于你的书房").assertExists();capture("01-empty-shelf")}
 @Test fun selectionEditReturnsToReaderAndUndoPreservesOriginal(){
  val raw="他说再见。她说再见。".toByteArray();val book=store.importBook("合成.txt",raw);workspace(book)
  compose.onNodeWithText("自己改").assertIsNotEnabled();select(7,9);capture("07-reader-selection");compose.onNodeWithText("自己改").performClick()
  compose.onNodeWithText("选中片段").performTextReplacement("你好");capture("02-selection-editor",true)
  compose.onNodeWithText("保存并回到阅读").performClick();compose.waitUntil(5000){store.load(book.id)!!.chapters[0].text=="他说再见。她说你好。"}
  compose.onNodeWithTag("reader-body").assertTextContains("他说再见。她说你好。");compose.onNodeWithText("撤销").performClick();compose.waitUntil(5000){store.load(book.id)!!.chapters[0].text=="他说再见。她说再见。"}
  Assert.assertArrayEquals(raw,store.originalBytes(book.id));Assert.assertTrue(store.revisions(book.id).size>=2)
 }
 @Test fun cancelOwnEditDoesNotWrite(){val book=store.importBook("合成.txt","不变原文".toByteArray());workspace(book);select(0,2);compose.onNodeWithText("自己改").performClick();compose.onNodeWithText("选中片段").performTextReplacement("取消草稿");compose.onNodeWithText("取消 · 返回阅读").performClick();Assert.assertEquals(book,store.load(book.id));Assert.assertTrue(store.revisions(book.id).isEmpty())}
 @Test fun unconfiguredAiNeverPretendsToGenerate(){val book=store.importBook("合成.txt","所选片段和不发送的余文".toByteArray());workspace(book,object:WritingAi{override fun provider():ProviderConfig?=null;override fun generate(expectedProvider:ProviderConfig,request:RewriteRequest):List<String> = error("must not generate")});select(0,4);compose.onNodeWithText("AI 改").performClick();compose.onNodeWithText("去设置连接").assertExists();compose.onNodeWithText("确认发送").assertDoesNotExist();capture("03-selection-ai",true);compose.onNodeWithText("取消，不改正文").performClick();Assert.assertEquals(book,store.load(book.id))}
 @Test fun syntheticCandidateOnlyReceivesSelectionAndCanBeReviewedThenApplied(){
  val book=store.importBook("合成.txt","前文旧句后文".toByteArray());var received:RewriteRequest?=null
  workspace(book,object:WritingAi{override fun provider()=ProviderConfig("https://fixture.example/v1/chat/completions","offline-fixture");override fun generate(expectedProvider:ProviderConfig,request:RewriteRequest):List<String>{received=request;return listOf("新句","另句")}})
  select(2,4);compose.onNodeWithText("AI 改").performClick();compose.waitUntil(5000){compose.onAllNodesWithText("确认发送").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("确认发送").performClick();compose.waitUntil(5000){compose.onAllNodesWithText("采用并回阅读").fetchSemanticsNodes().isNotEmpty()}
  Assert.assertEquals("旧句",received!!.passage);Assert.assertEquals("",received.facts);Assert.assertEquals("",received.style);Assert.assertEquals(book,store.load(book.id));compose.onNodeWithText("差异").performClick();capture("04-candidate-diff",true);compose.onNodeWithText("采用并回阅读").performClick();compose.waitUntil(5000){store.load(book.id)!!.chapters[0].text=="前文新句后文"};compose.onNodeWithTag("reader-body").assertTextContains("前文新句后文")
 }
 @Test fun createAndEditStoryCardAndOutline(){val book=store.importBook("合成.txt","角色出场".toByteArray());workspace(book);compose.onNodeWithText("资料").performClick();compose.onNodeWithText("新增人物").performClick();compose.onNodeWithText("名称").performTextInput("林遥");compose.onNodeWithText("内容 / 设定").performTextInput("合成人物");compose.onNodeWithText("保存资料").performClick();compose.waitUntil(5000){store.load(book.id)!!.cards.size==1};Assert.assertEquals(listOf(book.chapters[0].scene.id),store.load(book.id)!!.cards[0].sceneIds);val card=store.load(book.id)!!.cards[0];compose.onNodeWithText("人物 · 林遥 · ${card.id.take(6)}").performClick();compose.onNodeWithText("内容 / 设定").performTextReplacement("修改后的合成人物");compose.onNodeWithText("保存资料").performClick();compose.waitUntil(5000){store.load(book.id)!!.cards[0].notes=="修改后的合成人物"};compose.onNodeWithText("大纲").performClick();compose.onNodeWithText("新增大纲").performClick();compose.onNodeWithText("名称").performTextInput("雨后相遇");compose.onNodeWithText("保存资料").performClick();compose.waitUntil(5000){store.load(book.id)!!.cards.size==2};Assert.assertEquals(CardKind.OUTLINE,store.load(book.id)!!.cards[1].kind)}
 @Test fun failedGenerationKeepsInstructionAndCanRetry(){
  val book=store.importBook("合成.txt","所选原文".toByteArray());var calls=0
  workspace(book,object:WritingAi{override fun provider()=ProviderConfig("https://fixture.example/v1/chat/completions","offline");override fun generate(expectedProvider:ProviderConfig,request:RewriteRequest):List<String>{calls++;if(calls==1)throw java.io.IOException("合成失败");return listOf("候选一","候选二")}})
  select(0,2);compose.onNodeWithText("AI 改").performClick();compose.onNodeWithText("更多要求与发送资料").performClick();compose.onNodeWithText("这次怎么改").performTextReplacement("只改节奏");compose.onNodeWithText("确认发送").performClick();compose.waitUntil(5000){compose.onAllNodesWithText("合成失败").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("只改节奏").assertExists();Assert.assertEquals(book,store.load(book.id));compose.onNodeWithText("确认发送").performClick();compose.waitUntil(5000){compose.onAllNodesWithText("采用并回阅读").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("取消，不改正文").performClick();Assert.assertEquals(book,store.load(book.id))
 }
 @Test fun staleCandidateNeverOverwritesChangedStoredChapter(){
  val book=store.importBook("合成.txt","旧句后文".toByteArray())
  workspace(book,object:WritingAi{override fun provider()=ProviderConfig("https://fixture.example/v1/chat/completions","offline");override fun generate(expectedProvider:ProviderConfig,request:RewriteRequest)=listOf("候选","另选")})
  select(0,2);compose.onNodeWithText("AI 改").performClick();compose.waitUntil(5000){compose.onAllNodesWithText("确认发送").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("确认发送").performClick();compose.waitUntil(5000){compose.onAllNodesWithText("采用并回阅读").fetchSemanticsNodes().isNotEmpty()};val updated=store.editSelection(book.id,selectionAnchor(book.chapters[0],0,2),"已改");compose.onNodeWithText("采用并回阅读").performClick();compose.waitUntil(5000){compose.onAllNodesWithText("未完成操作").fetchSemanticsNodes().isNotEmpty()};Assert.assertEquals(updated,store.load(book.id))
 }
 @Test fun cancelEditPreservesReadingScroll(){
  val book=store.importBook("合成长章.txt",("连续阅读片段。\n".repeat(90)).toByteArray());workspace(book);select(0,2)
  compose.onNodeWithTag("reader-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,700f)};compose.waitForIdle()
  val before=compose.onNodeWithTag("reader-scroll").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
  Assert.assertTrue(before>0f);compose.onNodeWithText("自己改").performClick();compose.onNodeWithText("取消 · 返回阅读").performClick();compose.waitForIdle()
  val after=compose.onNodeWithTag("reader-scroll").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value();Assert.assertEquals(before,after,1f)
 }
 @Test fun settingsAndImport(){start();compose.onNodeWithText("AI 设置").performClick();compose.onNodeWithText("尚未连接 AI").assertExists();capture("05-settings");compose.onNodeWithText("书架").performClick();compose.onNodeWithText("粘贴文字").performClick();compose.onNodeWithText("先预览，再收入").assertExists();capture("06-import")}
}
