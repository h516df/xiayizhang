package cn.privatereader

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
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
class ReferenceImportUiTest {
 @get:Rule val compose=createComposeRule()
 private lateinit var root:File
 private lateinit var store:Store
 @Before fun setup(){root=File(System.getProperty("java.io.tmpdir"),"reference-ui-${System.nanoTime()}");store=Store(root)}
 @After fun clean(){root.deleteRecursively()}
 private fun resource()=parseReference("fixture.json",javaClass.getResourceAsStream("/reference-import/valid-v2.json")!!.use{it.readBytes()})
 private fun capture(name:String,title:String?=null){compose.mainClock.advanceTimeByFrame();compose.waitForIdle();val f=File("build/outputs/reference-ui/$name.png");f.parentFile!!.mkdirs();compose.onNode(if(title==null)isDialog() else isDialog() and hasAnyDescendant(hasText(title))).captureRoboImage(f.absolutePath)}
 private fun workspace(book:Book,ai:WritingAi?=null){val context=RuntimeEnvironment.getApplication();compose.setContent{var current by remember{mutableStateOf(book)};ReaderTheme{Surface{WritingWorkspace(current,0,store,SecureSettings(context),20f,{current=it},{},{},{},{},{},ai)}}};compose.waitForIdle()}
 @Test fun previewCancelNeverPersists(){val b=store.importBook("合成.txt","正文".toByteArray());val r=resource();compose.setContent{var show by remember{mutableStateOf(true)};ReaderTheme{if(show)ReferenceImportPreview(r,b,"当前场景",false,{show=false},{error("must not confirm")})else Text("已取消")}};capture("01-import-preview");compose.onNodeWithText("取消 · 返回阅读").performClick();compose.onNodeWithText("已取消").assertExists();Assert.assertEquals(b,store.load(b.id));Assert.assertTrue(store.revisions(b.id).isEmpty())}
 @Test fun previewConfirmPersistsOneSnapshot(){val b=store.importBook("合成.txt","正文".toByteArray());val r=resource();compose.setContent{var done by remember{mutableStateOf(false)};ReaderTheme{if(!done)ReferenceImportPreview(r,b,"当前场景",false,{},{store.importReference(b.id,b.workspaceId,b.chapters[0].scene.id,r);done=true})else Text("导入完成")}};compose.onNodeWithText("确认导入到本书").performClick();compose.onNodeWithText("导入完成").assertExists();Assert.assertEquals(listOf(r),store.load(b.id)!!.resources)}
 @Test fun importedCharacterCanBecomeEditableLocalDraft(){val original=store.importBook("合成.txt","正文".toByteArray());val r=resource();val b=store.importReference(original.id,original.workspaceId,original.chapters[0].scene.id,r);workspace(b);compose.onNodeWithText("资料").performClick();compose.onNodeWithText("${r.title} · ${r.id.take(6)}").performScrollTo().performClick();compose.onNodeWithText("整理人物资料草稿").performClick();compose.onNodeWithText("内容 / 设定").performTextReplacement("本书手工改过的设定");compose.onNodeWithText("保存资料").performClick();compose.waitUntil(5000){store.load(b.id)!!.cards.isNotEmpty()};val saved=store.load(b.id)!!;Assert.assertEquals("本书手工改过的设定",saved.cards[0].notes);Assert.assertEquals(r.id,saved.cards[0].referenceId);Assert.assertEquals(r,saved.resources[0])}
 @Test fun contextPickerRequiresExplicitSelectionAndMenuShowsFullBackup(){val original=store.importBook("合成.txt","正文原句".toByteArray());val r=resource();val b=store.importReference(original.id,original.workspaceId,original.chapters[0].scene.id,r);var request:RewriteRequest?=null;workspace(b,object:WritingAi{override fun provider()=ProviderConfig("https://fixture.example/v1/chat/completions","offline");override fun generate(expectedProvider:ProviderConfig,requestValue:RewriteRequest):List<String>{request=requestValue;return listOf("新句","另句")}});compose.onNodeWithText("更多").performClick();compose.onNodeWithText("备份").assertIsDisplayed();compose.onNodeWithText("历史").performClick();compose.onNodeWithTag("reader-body").performTextInputSelection(TextRange(0,2));compose.onNodeWithText("AI 改").performClick();compose.onNodeWithText("更多要求与发送资料").performClick();compose.onNodeWithText("选择导入资料（已选 0）").performClick();compose.onAllNodes(isToggleable() and hasAnyAncestor(isDialog() and hasAnyDescendant(hasText("选择导入资料"))))[0].performClick();capture("02-context-picker","选择导入资料");compose.onNodeWithText("完成选择（1）").performClick();compose.onNodeWithText("确认发送").performClick();compose.waitUntil(5000){request!=null};Assert.assertTrue(request!!.facts.contains("摆渡人"));Assert.assertFalse(request!!.facts.contains("shell"));Assert.assertFalse(request!!.facts.contains("船要开了"));compose.onNodeWithText("取消，不改正文").performClick();Assert.assertEquals(b,store.load(b.id))}
}
