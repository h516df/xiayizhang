package cn.privatereader
import android.app.Application
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
class UpdateEntryUiTest {
 @get:Rule val compose=createComposeRule()
 @Test fun localReleaseNotesOpenWithoutChangingBooksOrStartingNetwork() {
  val context=RuntimeEnvironment.getApplication()
  val root=File(System.getProperty("java.io.tmpdir"),"update-entry-${System.nanoTime()}")
  try {
   val store=Store(root)
   val original=store.importBook("合成.txt","保留的正文".toByteArray())
   compose.setContent{PrivateReader(store,SecureSettings(context))}
   compose.onNodeWithText("检查更新").performClick()
   compose.onNodeWithText("本版说明 0.3").assertExists()
   compose.onNodeWithText("检查更新").assertIsEnabled()
   compose.onNodeWithText("正在检查…").assertDoesNotExist()
   compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
   val image=File("build/outputs/release-ui/updates.png");image.parentFile!!.mkdirs()
   compose.onRoot().captureRoboImage(image.absolutePath)
   compose.onNodeWithText("书架").performClick()
   compose.onNodeWithText("AI 设置").assertExists()
   Assert.assertEquals(original,store.load(original.id));Assert.assertTrue(store.revisions(original.id).isEmpty())
  } finally {root.deleteRecursively()}
 }
}
