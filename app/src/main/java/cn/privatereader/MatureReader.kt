@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class, org.readium.r2.shared.InternalReadiumApi::class)
package cn.privatereader

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import kotlinx.coroutines.launch
import org.json.JSONTokener
import org.json.JSONObject
import org.readium.r2.navigator.epub.*
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.*
import org.readium.r2.shared.publication.services.PerResourcePositionsService
import org.readium.r2.shared.publication.services.positionsServiceFactory
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.StringResource
import org.readium.r2.shared.util.mediatype.MediaType

/** Readium owns layout and navigation. Only a bounded, escaped working chapter is exposed. */
internal fun chapterPublication(chapter:Chapter,offset:Int=0):Publication {
 val href=Url("chapter.xhtml")!!
 val link=Link(href=href,mediaType=MediaType.XHTML,title=chapter.title)
 val container=object:Container<Resource>{
  override val entries=setOf(href)
  override fun get(url:Url):Resource? = if(url==href)StringResource(ReadingProjection.html(chapter,offset)) else null
  override fun close(){}
 }
 return Publication(Manifest(metadata=Metadata(identifier=chapter.id,localizedTitle=LocalizedString(chapter.title),conformsTo=setOf(Publication.Profile.EPUB),languages=listOf("zh-CN")),readingOrder=listOf(link),tableOfContents=listOf(link)),container,Publication.ServicesBuilder().apply {positionsServiceFactory=PerResourcePositionsService.createFactory(MediaType.XHTML)})
}

@Composable internal fun MatureReader(bookId:String,chapter:Chapter,font:Float,modifier:Modifier=Modifier,onFont:(Float)->Unit,onSelection:(SourceAnchor,String)->Unit,onError:(String)->Unit) {
 val context=LocalContext.current
 val activity=context as? FragmentActivity
 if(activity==null){Text("阅读器需要主应用入口",modifier);return}
 val scope=rememberCoroutineScope()
 val saved=context.getSharedPreferences("readium_positions",0)
 val prefs=context.getSharedPreferences("reader",0)
 val key="$bookId:${chapter.id}"
 var appearance by remember { mutableStateOf(false) }
 var lineHeight by remember { mutableDoubleStateOf(prefs.getFloat("leading",1.8f).toDouble()) }
 var theme by remember { mutableStateOf(runCatching{Theme.valueOf(prefs.getString("theme","SEPIA")!!)}.getOrDefault(Theme.SEPIA)) }
 val legacyPixels=remember(key){context.getSharedPreferences("reader_positions",0).getInt(key,0)}
 val legacyNeedsMigration=remember(key){legacyPixels>0&&!saved.contains("$key:offset")&&!saved.getBoolean("$key:legacyMigrated",false)}
 var scrolling by remember(key) { mutableStateOf(if(legacyNeedsMigration)true else prefs.getBoolean("scrolling",false)) }
 var page by remember { mutableStateOf("") }
 val selectedCallback by rememberUpdatedState(onSelection)
 val errorCallback by rememberUpdatedState(onError)
 val storedOffset=remember(chapter.id,chapter.revision,chapter.epoch){saved.getInt("$key:offset",0)}
 val publication=remember(chapter.id,chapter.revision,chapter.epoch){saved.edit().putLong("$key:revision",chapter.revision).apply();chapterPublication(chapter,storedOffset)}
 val active=remember(publication){java.util.concurrent.atomic.AtomicBoolean(true)}
 var navigator by remember(publication){mutableStateOf<EpubNavigatorFragment?>(null)}
 val configuration=remember(publication){EpubNavigatorFragment.Configuration(selectionActionModeCallback=object:ActionMode.Callback{
  override fun onCreateActionMode(mode:ActionMode,menu:Menu):Boolean {menu.clear();menu.add(0,1,0,"自己改");menu.add(0,2,1,"AI 改");menu.add(0,3,2,"存为资料");menu.add(0,4,3,"复制");return true}
  override fun onPrepareActionMode(mode:ActionMode,menu:Menu)=false
  override fun onDestroyActionMode(mode:ActionMode){}
  override fun onActionItemClicked(mode:ActionMode,item:MenuItem):Boolean {
   if(item.itemId !in 1..4)return false
   scope.launch { try {
    val raw=navigator?.evaluateJavascript(ReadingProjection.SELECTION_SCRIPT)?:error("尚未取得选区，请重新选择")
    val decoded=JSONTokener(raw).nextValue() as? String ?: error("请先长按选择正文")
    val anchor=ReadingProjection.selection(chapter,decoded)
    val readingOffset=navigator?.evaluateJavascript(ReadingProjection.POSITION_SCRIPT)?.toIntOrNull()
    if(readingOffset!=null&&active.get()&&saved.getLong("$key:revision",-1)==chapter.revision)saved.edit().putInt("$key:offset",readingOffset).apply()
    if(item.itemId==4)(context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("选中正文",anchor.source))
    else selectedCallback(anchor,when(item.itemId){1->"self";2->"ai";else->"card"})
    mode.finish()
   }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){errorCallback(e.message?:"无法定位选区，未修改正文")} }
   return true
  }
 })}
 val initial=remember(publication){
  if(saved.contains("$key:offset"))Locator(href=Url("chapter.xhtml")!!,mediaType=MediaType.XHTML,locations=Locator.Locations(fragments=listOf("resume")))
  else runCatching { saved.getString(key,null)?.let{Locator.fromJSON(JSONObject(it))} }.getOrNull()
 }
 val options=EpubPreferences(fontSize=font.toDouble()/16.0,lineHeight=lineHeight,theme=theme,scroll=scrolling,publisherStyles=false)
 Column(modifier){
  Text(chapter.title,Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.titleMedium,maxLines=1)
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
   TextButton(onClick={navigator?.goBackward(true)}){Text("上一页")}
   TextButton(onClick={appearance=true}){Text("排版 · ${if(scrolling)"滚动"else"翻页"}")}
   TextButton(onClick={navigator?.goForward(true)}){Text("下一页")}
  }
  key(publication){
   val viewId=remember{View.generateViewId()}
   AndroidView(modifier=Modifier.weight(1f).fillMaxWidth().testTag("readium-reader"),factory={ctx->FragmentContainerView(ctx).also {it.id=viewId}},update={})
   DisposableEffect(publication,viewId){
    val factory=EpubNavigatorFactory(publication).createFragmentFactory(initialLocator=initial,initialPreferences=options,configuration=configuration,listener=object:EpubNavigatorFragment.Listener{
     override fun onExternalLinkActivated(url:org.readium.r2.shared.util.AbsoluteUrl){errorCallback("工作稿不打开外部链接")}
     override fun onResourceLoadFailed(href:Url,error:org.readium.r2.shared.util.data.ReadError){errorCallback("正文加载失败，原书和工作稿未更改；请返回书架重试")}
    },paginationListener=object:EpubNavigatorFragment.PaginationListener{
     override fun onPageLoaded(){
      if(legacyNeedsMigration&&!saved.getBoolean("$key:legacyMigrated",false))scope.launch{
       navigator?.evaluateJavascript("window.scrollTo(0,${legacyPixels.coerceAtLeast(0)});")
       saved.edit().putBoolean("$key:legacyMigrated",true).apply()
       page="旧版滚动位置已迁移；重新排版可能有少量偏移"
      }
     }
     override fun onPageChanged(pageIndex:Int,totalPages:Int,locator:Locator){
      page="${pageIndex+1} / $totalPages 页";saved.edit().putString(key,locator.toJSON().toString()).apply()
      scope.launch { navigator?.evaluateJavascript(ReadingProjection.POSITION_SCRIPT)?.toIntOrNull()?.let{offset->if(active.get()&&saved.getLong("$key:revision",-1)==chapter.revision)saved.edit().putInt("$key:offset",ReadingProjection.safeOffset(chapter.text,offset)).apply()} }
     }
    })
    val fragment=factory.instantiate(activity.classLoader,EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
    activity.supportFragmentManager.beginTransaction().add(viewId,fragment,"readium-$key").commitNow()
    navigator=fragment
    onDispose {
     active.set(false)
     saved.edit().putString(key,fragment.currentLocator.value.toJSON().toString()).apply()
     activity.supportFragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
     navigator=null
     publication.close()
    }
   }
  }
  Text(page.ifBlank{"长按正文，选择自己改或 AI 改"},Modifier.padding(horizontal=20.dp,vertical=4.dp),style=MaterialTheme.typography.labelSmall)
 }
 LaunchedEffect(navigator,font,lineHeight,theme,scrolling){navigator?.submitPreferences(options);prefs.edit().putFloat("leading",lineHeight.toFloat()).putString("theme",theme.name).putBoolean("scrolling",scrolling).apply()}
 if(appearance)AlertDialog(onDismissRequest={appearance=false},title={Text("阅读排版")},text={Column{
  Text("字号 ${font.toInt()}")
  Slider(value=font,onValueChange=onFont,valueRange=16f..32f,steps=7)
  Text("行距 ${"%.1f".format(lineHeight)}")
  Slider(value=lineHeight.toFloat(),onValueChange={lineHeight=it.toDouble()},valueRange=1.2f..2.4f,steps=5)
  Row{listOf(Theme.LIGHT to "日间",Theme.SEPIA to "纸色",Theme.DARK to "夜间").forEach{(value,label)->FilterChip(selected=theme==value,onClick={theme=value},label={Text(label)})}}
  Row{Switch(checked=scrolling,onCheckedChange={scrolling=it});Text("连续滚动",Modifier.padding(12.dp))}
 }},confirmButton={TextButton(onClick={appearance=false}){Text("继续阅读")}})
}
