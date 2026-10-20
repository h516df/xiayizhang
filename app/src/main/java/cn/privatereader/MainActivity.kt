package cn.privatereader

import android.os.Bundle
import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.io.File

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val shared = if(intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty() else ""
  setContent { PrivateReader(Store(File(filesDir,"books")), SecureSettings(this), shared) }
 }
}
private val Paper=Color(0xFFF6F3EB)
private val Ink=Color(0xFF293A32)
private val Muted=Color(0xFF788178)
@Composable internal fun ReaderTheme(content:@Composable ()->Unit) {
 MaterialTheme(colorScheme=lightColorScheme(primary=Ink,onPrimary=Color.White,background=Paper,surface=Paper,secondary=Muted),content=content)
}
@Composable fun PrivateReader(store:Store, settings:SecureSettings, shared:String="") {
 val scope=rememberCoroutineScope()
 var books by remember { mutableStateOf<List<BookSummary>>(emptyList()) }
 var book by remember { mutableStateOf<Book?>(null) }
 var chapter by remember { mutableIntStateOf(0) }
 var route by remember { mutableStateOf(if(shared.isNotBlank()) "import" else "shelf") }
 var error by remember { mutableStateOf(if(shared.length>120000)"分享文字超过 12 万字符，请保存为 TXT 文件后导入。未截断保存。"else "") }
 var busy by remember { mutableStateOf(false) }
 var pasted by remember { mutableStateOf(if(shared.length<=120000)shared else "") }
 var title by remember { mutableStateOf("") }
 var revision by remember { mutableStateOf<List<String>?>(null) }
 val readerState=rememberSaveableStateHolder()
 var settingsFromReader by remember { mutableStateOf(false) }
 var font by remember { mutableFloatStateOf(20f) }
 var profile by remember { mutableStateOf(false) }
 val context=androidx.compose.ui.platform.LocalContext.current
 fun refresh() { scope.launch { try{books=withContext(Dispatchers.IO){store.summaries()}}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"书库读取失败"} } }
 fun task(block:suspend ()->Unit) { if(busy)return;busy=true;scope.launch { try { block() } catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"操作失败，请重试"} finally {busy=false} } }
 fun open(b:Book) {book=b;chapter=context.getSharedPreferences("reader",0).getInt(b.id,0).coerceIn(b.chapters.indices);route="reader";scope.launch{withContext(Dispatchers.IO){store.recoveryNotice(b.id)}?.let{error=it}}}
 val importFile=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){ uri -> if(uri!=null)task {
  val imported=withContext(Dispatchers.IO){
   var name="导入.txt"
   context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst())name=it.getString(0)}
   val bytes=context.contentResolver.openInputStream(uri)?.use { readLimited(it,20*1024*1024) }?:error("无法读取文件")
   require(bytes.size<=20*1024*1024){"文件超过 20 MB，请先拆分"}
   if(name.endsWith(".prbook",true)) store.importBackup(bytes) else store.importBook(name,bytes)
  }; refresh();open(imported)
 } }
 var exportBytes by remember { mutableStateOf<ByteArray?>(null) }
 val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
  val bytes=exportBytes
  if(uri!=null && bytes!=null) task {withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use {it.write(bytes)}?:error("无法写入文件")};error="已导出到你选择的位置"}; exportBytes=null
 }
 LaunchedEffect(Unit){refresh();font=context.getSharedPreferences("reader",0).getFloat("font",20f)}
 LaunchedEffect(chapter,book?.id,font){context.getSharedPreferences("reader",0).edit().putFloat("font",font).apply();book?.let{context.getSharedPreferences("reader",0).edit().putInt(it.id,chapter).apply()}}
 BackHandler(route!="shelf") {if(route=="settings"&&settingsFromReader){route="reader";settingsFromReader=false}else{route="shelf";refresh()}}
 ReaderTheme {
  Surface(Modifier.fillMaxSize(),color=Paper){Column(Modifier.safeDrawingPadding()) {
   Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=if(route=="reader")8.dp else 18.dp),horizontalArrangement=Arrangement.SpaceBetween){
    Text(if(route=="shelf")"下一章" else if(route=="reader")book?.title.orEmpty() else if(route=="settings")"AI 连接" else "收入书架",modifier=Modifier.weight(1f),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,fontSize=if(route=="reader")20.sp else 26.sp,fontWeight=FontWeight.SemiBold,color=Ink)
    TextButton(enabled=!busy,onClick={if(route=="shelf"){settingsFromReader=false;route="settings"}else if(route=="settings"&&settingsFromReader){route="reader";settingsFromReader=false}else {route="shelf";refresh()}}){Text(if(route=="shelf")"AI 设置" else if(route=="settings"&&settingsFromReader)"返回阅读"else"书架")}

   }
   if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
   when(route) {
    "shelf" -> LazyColumn(Modifier.fillMaxSize().padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
     item {Text("把故事收好，慢慢读成自己的版本。",color=Muted,fontSize=14.sp);Spacer(Modifier.height(20.dp))
      Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Button(onClick={importFile.launch(arrayOf("text/*","application/epub+zip","application/octet-stream","application/zip"))}){Text("导入文件")};OutlinedButton(onClick={route="import"}){Text("粘贴文字")}}
      Text("TXT · EPUB · 恢复备份  /  原稿始终保留",Modifier.padding(top=12.dp),fontSize=12.sp,color=Muted)
     }
     if(books.isEmpty())item{Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFE9EBDD)),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(24.dp)){Text("一方只属于你的书房",fontSize=22.sp,color=Ink);Text("导入一段文字，或先翻开示例。阅读、编辑和备份都留在手机上。",Modifier.padding(vertical=18.dp),lineHeight=25.sp);Button(onClick={task{val b=withContext(Dispatchers.IO){store.importBook("雾灯小记.txt",SAMPLE.toByteArray())};refresh();open(b)}}){Text("翻开示例")}}}}
     itemsIndexed(books){i,b->Card(onClick={task{val loaded=withContext(Dispatchers.IO){store.load(b.id)}?:error("未找到书籍");open(loaded)}},colors=CardDefaults.cardColors(containerColor=Color.White.copy(alpha=.7f)),shape=RoundedCornerShape(20.dp)){
      Row(Modifier.fillMaxWidth().padding(20.dp),horizontalArrangement=Arrangement.spacedBy(18.dp)){Surface(color=if(i%2==0)Color(0xFFDCE3D3)else Color(0xFFE8DCC9),shape=RoundedCornerShape(8.dp)){Text("${(i+1).toString().padStart(2,'0')}\n私 藏",Modifier.padding(18.dp),fontSize=19.sp,lineHeight=30.sp,color=Ink)};Column(Modifier.weight(1f)){Text(b.title,fontSize=21.sp,fontWeight=FontWeight.Medium);Text("${b.chapterCount} 个章节 · 本地保存",Modifier.padding(top=8.dp),color=Muted,fontSize=12.sp);Text(b.preview,Modifier.padding(top=12.dp),fontSize=13.sp,maxLines=2,color=Muted)}}
     }}
     item{Spacer(Modifier.height(24.dp))}
    }
    "import" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
     Text("先预览，再收入",fontSize=22.sp);Text("粘贴正文或从浏览器分享文字。仅网址不能离线提取正文；请在原页面复制可访问的内容。不会绕过登录或版权保护。",color=Muted,lineHeight=23.sp)
     OutlinedTextField(title,{title=it},label={Text("书名 / 笔记名")},modifier=Modifier.fillMaxWidth())
     OutlinedTextField(pasted,{if(it.length<=120000)pasted=it else error="文字超过 12 万字符，请使用 TXT 文件导入；未截断保存"},label={Text("粘贴文字（最多 12 万字）")},modifier=Modifier.fillMaxWidth().height(300.dp))
     Button(enabled=pasted.isNotBlank()&&!busy,onClick={task{val b=withContext(Dispatchers.IO){store.importBook((title.ifBlank{"未命名文字"})+".txt",pasted.toByteArray())};pasted="";title="";refresh();open(b)}}){Text("保留原文并收入书架")}
    }
    "reader" -> book?.let { b ->
     readerState.SaveableStateProvider(b.id){WritingWorkspace(b,chapter,store,settings,font,{saved->book=saved;refresh()},{chapter=it},
      {task{revision=withContext(Dispatchers.IO){store.revisions(b.id)}}},
      {task{exportBytes=withContext(Dispatchers.IO){store.exportBackup(b.id)};export.launch("${b.title}.prbook")}},
      {settingsFromReader=true;route="settings"},{profile=true},onFont={font=if(font>=26)18f else font+2})}
    }
    "settings" -> SettingsPanel(settings){error=it}
   }
  }}
  if(error.isNotBlank())AlertDialog(onDismissRequest={error=""},title={Text("提示")},text={Text(error)},confirmButton={TextButton(onClick={error=""}){Text("知道了")}})

  revision?.let{rs->AlertDialog(onDismissRequest={revision=null},title={Text("恢复历史版本")},text={LazyColumn{item{Text("恢复会保留当前版本。原始导入文件不会改变。",color=Muted)};itemsIndexed(rs){_,r->TextButton(onClick={task{book=withContext(Dispatchers.IO){store.rollback(book!!.id,r)};chapter=0;revision=null}}){Text(r)}}}},confirmButton={TextButton(onClick={revision=null}){Text("取消")}})}
  if(profile && book!=null)ProfileDialog(book!!,{profile=false}){next->task{book=withContext(Dispatchers.IO){store.save(next)};profile=false;refresh()}}

 }
}

@Composable private fun ProfileDialog(book:Book,close:()->Unit,save:(Book)->Unit){
 var style by remember{mutableStateOf(book.style)};var facts by remember{mutableStateOf(book.facts)};var protected by remember{mutableStateOf(book.protectedText.joinToString("\n"))}
 AlertDialog(onDismissRequest=close,title={Text("原有设定")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){Text("这些旧设定原样保留；只有你在 AI 面板主动勾选后才会发送。",fontSize=13.sp,color=Muted);Row(Modifier.horizontalScroll(rememberScrollState())){listOf("克制细腻","轻快自然","只润色，不改剧情").forEach{v->SuggestionChip(onClick={style=v},label={Text(v)})}};OutlinedTextField(style,{style=it.take(3000)},label={Text("风格偏好")},placeholder={Text("克制、细腻，保留人物语气")});OutlinedTextField(facts,{facts=it.take(3000)},label={Text("不可改变的事实 / 人物设定")});Text("原有指令（仅保留，尚未转换为结构化动作）：\n"+book.buttons.joinToString("\n"),fontSize=12.sp);OutlinedTextField(protected,{protected=it.take(2000)},label={Text("禁止改动的原文，每行一处")})}},confirmButton={TextButton(onClick={save(book.copy(style=style,facts=facts,protectedText=protected.lines().filter{it.isNotBlank()}.take(20)))}){Text("保存")}},dismissButton={TextButton(onClick=close){Text("取消")}})
}
@Composable private fun SettingsPanel(settings:SecureSettings,notice:(String)->Unit){
 val scope=rememberCoroutineScope();var endpoint by remember{mutableStateOf("")};var model by remember{mutableStateOf("")};var key by remember{mutableStateOf("")};var configured by remember{mutableStateOf(false)}
 LaunchedEffect(Unit){withContext(Dispatchers.IO){settings.config()}?.let{endpoint=it.endpoint;model=it.model;configured=true}}
 Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
  Text(if(configured)"已配置 · 请求时才联网" else "尚未连接 AI",fontSize=24.sp,color=Ink)
  Text("阅读和编辑不需要 AI。连接后，每次生成只发送选区、指令和你主动勾选且预览的资料给下方服务商；不上传整库、图片或录音。服务商可能按量收费，请核对其隐私和价格。",lineHeight=25.sp,color=Muted)
  OutlinedTextField(endpoint,{endpoint=it},label={Text("兼容服务完整 HTTPS 接口")},placeholder={Text("https://api.example.com/v1/chat/completions")},modifier=Modifier.fillMaxWidth())
  OutlinedTextField(model,{model=it},label={Text("模型名称")},modifier=Modifier.fillMaxWidth())
  OutlinedTextField(key,{key=it},label={Text(if(configured)"新密钥（留空保留原密钥）"else "API 密钥")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
  Text("密钥使用 Android Keystore 加密，仅留在本机，不写入导出备份。请在手机里填入，不要发到聊天。",fontSize=13.sp,color=Muted)
  Button(onClick={scope.launch{try{withContext(Dispatchers.IO){settings.save(ProviderConfig(endpoint.trim(),model.trim()),key.ifBlank{settings.key().orEmpty()})};key="";configured=true;notice("连接设置已保存，尚未进行收费请求")}catch(e:CancellationException){throw e}catch(e:Exception){notice(e.message?:"保存失败")}}}){Text("保存连接")}
  OutlinedButton(onClick={scope.launch{try{withContext(Dispatchers.IO){settings.clear()};key="";configured=false;notice("已移除本机密钥")}catch(e:CancellationException){throw e}catch(e:Exception){notice(e.message?:"清除连接失败，请重试")}}}){Text("移除连接与密钥")}
 }
}
private const val SAMPLE="""第一章 雾中的灯

林遥在雨停后回到小镇。巷口那盏灯还亮着，灯下摆着一只空木箱，像是有人刚刚离开。

她把湿透的信封放进口袋，没有敲门。屋里传来水壶轻响，随后是一句熟悉的话：“你终于肯回来了。”

第二章 没有寄出的信

桌上放着三封没有地址的信。她认得纸上的字，却不记得自己什么时候写过。

窗外，最后一班渡船正慢慢靠岸。她决定先喝完这杯茶，再问那个已经等了很久的问题。

（原创演示文字，仅用于体验阅读和编辑。）"""

private fun readLimited(input:java.io.InputStream,limit:Int):ByteArray { val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=limit){"文件超过 20 MB，请先拆分"};out.write(buffer,0,n)};return out.toByteArray() }
