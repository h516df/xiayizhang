package cn.privatereader

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard

/** The working draft is plain text: never let HTML paste trigger remote image loading. */
internal class PlainTextClipboard(private val delegate:Clipboard):Clipboard by delegate {
 override suspend fun getClipEntry():ClipEntry? = plainTextClip(delegate.getClipEntry())
 override suspend fun setClipEntry(clipEntry:ClipEntry?) {delegate.setClipEntry(plainTextClip(clipEntry))}
}
internal fun plainTextClip(entry:ClipEntry?):ClipEntry? {
 val data=entry?.clipData?:return null
 if(data.itemCount==0)return null
 // Do not coerce URI/Intent clips via a content provider. Only explicit text is pasted.
 val text=data.getItemAt(0).text?.toString()?:return null
 return ClipEntry(ClipData.newPlainText("正文片段",text))
}
