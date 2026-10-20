package cn.privatereader
import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w393dp-h852dp-xhdpi")
class CleanupUiTest {
 private val selectionEvents=ReaderSelectionEvents()
 @get:Rule val compose=createComposeRule()
 private lateinit var dir:File
 private lateinit var store:Store
 @Before fun setup(){dir=File(System.getProperty("java.io.tmpdir"),"cleanup-ui-${System.nanoTime()}");store=Store(dir)}
 @After fun cleanup(){dir.deleteRecursively()}
 private fun workspace(book:Book){val context=RuntimeEnvironment.getApplication();compose.setContent{var b by remember{mutableStateOf(book)};ReaderTheme{Surface{WritingWorkspace(b,0,store,SecureSettings(context),20f,{b=it},{},{},{},{},{},selectionEvents=selectionEvents)}}}}
 private fun candidates(){compose.onNodeWithText("预览清理").performClick();compose.waitUntil(5000){compose.onAllNodesWithText("调整规则").fetchSemanticsNodes().isNotEmpty()}}
 @Test fun cleanupChapterReviewApplyUndo(){val raw="合成正文\n下一章\n后文";val b=store.importBook("合成.txt",raw.toByteArray());workspace(b);compose.onNodeWithText("更多").performClick();compose.onNodeWithText("清理本章").performClick();candidates();compose.onNodeWithText("确认清理并回阅读").assertIsNotEnabled();compose.onNodeWithTag("cleanup-5").performClick();compose.mainClock.advanceTimeByFrame();compose.waitForIdle();val f=File("build/outputs/cleanup-ui/review.png");f.parentFile!!.mkdirs();compose.onNode(isDialog()).captureRoboImage(f.absolutePath);compose.onNodeWithText("确认清理并回阅读").performClick();compose.waitUntil(5000){store.load(b.id)!!.chapters[0].text=="合成正文\n后文"};compose.onNodeWithText("撤销").performClick();compose.waitUntil(5000){store.load(b.id)!!.chapters[0].text==raw};Assert.assertEquals(raw,String(store.originalBytes(b.id)))}
 @Test fun selectedNoiseCanBeDeletedWithoutEmptyingChapter(){val raw="前文\n下一章\n后文";val b=store.importBook("合成.txt",raw.toByteArray());workspace(b);compose.runOnIdle{selectionEvents.select(selectionAnchor(b.chapters[0],3,7),"select")};compose.waitForIdle();compose.onNodeWithText("清理选区").performClick();candidates();compose.onNodeWithTag("cleanup-0").performClick();compose.onNodeWithText("确认清理并回阅读").assertIsEnabled().performClick();compose.waitUntil(5000){store.load(b.id)!!.chapters[0].text=="前文\n后文"};compose.onNodeWithText("撤销").performClick();compose.waitUntil(5000){store.load(b.id)!!.chapters[0].text==raw}}
 @Test fun cancelDoesNotWrite(){val b=store.importBook("合成.txt","正文\n下一章".toByteArray());workspace(b);compose.onNodeWithText("更多").performClick();compose.onNodeWithText("清理本章").performClick();candidates();compose.onNodeWithText("取消 · 返回阅读").performClick();Assert.assertEquals(b,store.load(b.id));Assert.assertTrue(store.revisions(b.id).isEmpty())}
 @Test fun literalPreviewIsExactAndRequiresSelection(){var result="";compose.setContent{ReaderTheme{CleanupPanel("原文广告原文",false,close={},apply={result=it})}};compose.onNodeWithText("自定义与硬换行").performClick();compose.onNodeWithText("精确查找文字").performTextInput("广告");candidates();compose.onNodeWithText("确认清理并回阅读").assertIsNotEnabled();compose.onNodeWithTag("cleanup-2").performClick();compose.onNodeWithText("看完整结果").performClick();compose.onNodeWithTag("cleanup-result").assertTextContains("原文原文");compose.onNodeWithText("确认清理并回阅读").performClick();Assert.assertEquals("原文原文",result)}
 @Test fun pastedImportPreservesOriginalAndHistory(){val context=RuntimeEnvironment.getApplication();val raw="合成正文\n下一章\n后文";compose.setContent{PrivateReader(store,SecureSettings(context),raw)};compose.onNodeWithText("清理杂文再收入").performScrollTo().performClick();candidates();compose.onNodeWithTag("cleanup-5").performClick();compose.onNodeWithText("确认清理并收入书架").performClick();compose.waitUntil(5000){store.summaries().size==1};val b=store.list().single();Assert.assertEquals("合成正文\n后文",b.chapters[0].text);Assert.assertEquals(raw,String(store.originalBytes(b.id)));Assert.assertEquals(1,store.revisions(b.id).size)}
}
