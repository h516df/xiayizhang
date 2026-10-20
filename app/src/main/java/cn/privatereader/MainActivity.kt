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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
@Composable fun PrivateReader(store:Store, settings:SecureSettings, shared:String="") {
 val scope=rememberCoroutineScope()
 var books by remember { mutableStateOf<List<BookSummary>>(emptyList()) }
 var book by remember { mutableStateOf<Book?>(null) }
 var chapter by remember { mutableIntStateOf(0) }
 var route by remember { mutableStateOf(if(shared.isNotBlank()) "import" else "shelf") }
 var error by remember { mutableStateOf(if(shared.length>120000)"分享文字超过 12 万字符，请保存为 TXT 文件后导入。未截断保存。"else "") }
 var busy by remember { mutableStateOf(false) }
 var edit by remember { mutableStateOf(false) }
 var draft by remember { mutableStateOf("") }
 val draftSaver=remember(store){OrderedDraftSaver(store)}
 var editTicket by remember{mutableLongStateOf(0L)}
 var pasted by remember { mutableStateOf(if(shared.length<=120000)shared else "") }
 var title by remember { mutableStateOf("") }
 var revision by remember { mutableStateOf<List<String>?>(null) }
 var toc by remember { mutableStateOf(false) }
 var font by remember { mutableFloatStateOf(20f) }
 var ai by remember { mutableStateOf(false) }
 var returnToRewrite by remember { mutableStateOf(false) }
 val rewriteState=rememberSaveableStateHolder()
 var rewriteKey by rememberSaveable{mutableStateOf("")}
 var profile by remember { mutableStateOf(false) }
 val context=androidx.compose.ui.platform.LocalContext.current
 fun refresh() { scope.launch { try{books=withContext(Dispatchers.IO){store.summaries()}}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"书库读取失败"} } }
 fun task(block:suspend ()->Unit) { if(busy)return;busy=true;scope.launch { try { block() } catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"操作失败，请重试"} finally {busy=false} } }
 fun open(b:Book) {book=b;chapter=context.getSharedPreferences("reader",0).getInt(b.id,0).coerceIn(b.chapters.indices);edit=false;route="reader";scope.launch{withContext(Dispatchers.IO){store.recoveryNotice(b.id)}?.let{error=it}}}
 fun persist(b:Book) {task {book=withContext(Dispatchers.IO){store.save(b)};refresh()} }
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
 val latestDraft by rememberUpdatedState(draft)
 val latestBook by rememberUpdatedState(book)
 val latestChapter by rememberUpdatedState(chapter)
 val latestEdit by rememberUpdatedState(edit)
 DisposableEffect(Unit){
  val owner=context as? androidx.lifecycle.LifecycleOwner
  val observer=androidx.lifecycle.LifecycleEventObserver{_,event->
   if(event==androidx.lifecycle.Lifecycle.Event.ON_STOP && latestEdit){latestBook?.let{b->try{draftSaver.save(b.copy(chapters=b.chapters.mapIndexed{i,c->if(i==latestChapter)c.copy(text=latestDraft)else c}),draftSaver.nextTicket())}catch(e:CancellationException){throw e}catch(e:Exception){error="保存失败：${e.message}"}}}
  }
  owner?.lifecycle?.addObserver(observer)
  onDispose{owner?.lifecycle?.removeObserver(observer)}
 }
 // Debounce writes while typing; onDispose below is backed by an immediate IO save on navigation.
 LaunchedEffect(draft,edit,chapter){if(edit){val ticket=editTicket;delay(650);val b=book?:return@LaunchedEffect;val next=b.copy(chapters=b.chapters.mapIndexed{i,c->if(i==chapter)c.copy(text=draft)else c}); try{withContext(Dispatchers.IO){draftSaver.save(next,ticket)}?.let{book=it}}catch(e:CancellationException){throw e}catch(e:Exception){error="保存失败：${e.message}"}}}
 fun finishEdit() {if(busy)return;val b=book?:return;val next=b.copy(chapters=b.chapters.mapIndexed{i,c->if(i==chapter)c.copy(text=draft)else c});val ticket=draftSaver.nextTicket();task{withContext(Dispatchers.IO){draftSaver.save(next,ticket)}?.let{book=it};edit=false;refresh()}}
 BackHandler(route!="shelf"&&!ai) {if(edit)finishEdit() else if(returnToRewrite){returnToRewrite=false;route="reader";ai=true}else {route="shelf";refresh()} }
 MaterialTheme(colorScheme=lightColorScheme(primary=Ink,onPrimary=Color.White,background=Paper,surface=Paper,secondary=Muted)) {
  if(!ai) Surface(Modifier.fillMaxSize(),color=Paper){Column(Modifier.safeDrawingPadding()) {
   Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=if(route=="reader")8.dp else 18.dp),horizontalArrangement=Arrangement.SpaceBetween){
    Text(if(route=="shelf")"下一章" else if(route=="reader")book?.title.orEmpty() else if(route=="settings")"AI 连接" else "收入书架",modifier=Modifier.weight(1f),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,fontSize=if(route=="reader")20.sp else 26.sp,fontWeight=FontWeight.SemiBold,color=Ink)
    TextButton(enabled=!busy,onClick={if(edit)finishEdit()else if(route=="shelf")route="settings"else if(returnToRewrite){returnToRewrite=false;route="reader";ai=true}else {route="shelf";refresh()}}){Text(if(route=="shelf")"AI 设置" else if(edit)"完成编辑" else if(returnToRewrite)"返回改写" else "书架")}
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
     val c=b.chapters[chapter.coerceIn(b.chapters.indices)]
     Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={toc=true},enabled=!edit&&!busy){Text("目录 · ${chapter+1}/${b.chapters.size}")};TextButton(onClick={font=if(font>=26)18f else font+2}){Text("字 ${font.toInt()}")};TextButton(onClick={profile=true},enabled=!edit&&!busy){Text("定制")};TextButton(onClick={task{revision=withContext(Dispatchers.IO){store.revisions(b.id)}}},enabled=!edit&&!busy){Text("版本")}}
     if(edit){Text("正在编辑当前章节 · 停笔自动保存",Modifier.padding(horizontal=24.dp),color=Muted,fontSize=12.sp);OutlinedTextField(draft,{if(it.length<=12000){draft=it;editTicket=draftSaver.nextTicket()}else error="本章最多 12000 字符；刚才的输入未采用，原文保留"},Modifier.weight(1f).fillMaxWidth().padding(20.dp),enabled=!busy,textStyle=androidx.compose.ui.text.TextStyle(fontSize=font.sp,lineHeight=(font*1.8).sp,fontFamily=FontFamily.Serif))}
     else LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal=28.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
      item{Text(c.title,Modifier.padding(top=16.dp,bottom=18.dp),fontSize=28.sp,fontFamily=FontFamily.Serif,color=Ink)}
      itemsIndexed(c.text.split(Regex("\n\\s*\n|\n")).let{ps->if(ps.firstOrNull()?.trim()==c.title.trim())ps.drop(1)else ps}){_,p->SelectionContainer{Text(p,fontSize=font.sp,lineHeight=(font*1.9).sp,fontFamily=FontFamily.Serif,color=Ink)}}
      item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(enabled=chapter>0,onClick={chapter--}){Text("上一章")};TextButton(enabled=chapter<b.chapters.lastIndex,onClick={chapter++}){Text("下一章")}}}
     }
     Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=10.dp),horizontalArrangement=Arrangement.SpaceBetween){OutlinedButton(enabled=!edit&&!busy,onClick={draft=c.text;editTicket=draftSaver.nextTicket();edit=true}){Text("编辑本章")};Button(enabled=!edit&&!busy,onClick={val key="${b.id}:$chapter:${c.text.hashCode()}";if(rewriteKey.isNotEmpty()&&rewriteKey!=key)rewriteState.removeState(rewriteKey);rewriteKey=key;ai=true}){Text("AI 改写")};TextButton(enabled=!edit&&!busy,onClick={task{exportBytes=withContext(Dispatchers.IO){store.exportBackup(b.id)};export.launch("${b.title}.prbook")}}){Text("备份")}}
    }
    "settings" -> SettingsPanel(settings){error=it}
   }
  }}
  if(error.isNotBlank())AlertDialog(onDismissRequest={error=""},title={Text("提示")},text={Text(error)},confirmButton={TextButton(onClick={error=""}){Text("知道了")}})
  if(toc)AlertDialog(onDismissRequest={toc=false},title={Text("章节目录")},text={LazyColumn{itemsIndexed(book?.chapters.orEmpty()){i,c->TextButton(onClick={chapter=i;toc=false}){Text("${i+1}  ${c.title}")}}}},confirmButton={TextButton(onClick={toc=false}){Text("关闭")}})
  revision?.let{rs->AlertDialog(onDismissRequest={revision=null},title={Text("恢复历史版本")},text={LazyColumn{item{Text("恢复会保留当前版本。原始导入文件不会改变。",color=Muted)};itemsIndexed(rs){_,r->TextButton(onClick={task{book=withContext(Dispatchers.IO){store.rollback(book!!.id,r)};chapter=0;revision=null}}){Text(r)}}}},confirmButton={TextButton(onClick={revision=null}){Text("取消")}})}
  if(profile && book!=null)ProfileDialog(book!!,{profile=false}){next->task{book=withContext(Dispatchers.IO){store.save(next)};profile=false;refresh()}}
  if(ai && book!=null)rewriteState.SaveableStateProvider(rewriteKey){RewritePanel(book!!,chapter,settings,busy,{ai=false},{ai=false;returnToRewrite=true;route="settings"}){newText->val b=book!!;val next=b.copy(chapters=b.chapters.mapIndexed{i,c->if(i==chapter)c.copy(text=newText)else c});task{book=withContext(Dispatchers.IO){store.save(next)};ai=false;rewriteState.removeState(rewriteKey);refresh()}}}
 }
}

@Composable private fun ProfileDialog(book:Book,close:()->Unit,save:(Book)->Unit){
 var style by remember{mutableStateOf(book.style)};var facts by remember{mutableStateOf(book.facts)};var buttons by remember{mutableStateOf(book.buttons.joinToString("\n"))};var protected by remember{mutableStateOf(book.protectedText.joinToString("\n"))}
 AlertDialog(onDismissRequest=close,title={Text("这本书，按你的喜好")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){Text("只在你主动改写时发送这些设定。事实约束可减少偏离，但不能保证情节一致。",fontSize=13.sp,color=Muted);Row(Modifier.horizontalScroll(rememberScrollState())){listOf("克制细腻","轻快自然","只润色，不改剧情").forEach{v->SuggestionChip(onClick={style=v},label={Text(v)})}};OutlinedTextField(style,{style=it.take(3000)},label={Text("风格偏好")},placeholder={Text("克制、细腻，保留人物语气")});OutlinedTextField(facts,{facts=it.take(3000)},label={Text("不可改变的事实 / 人物设定")});OutlinedTextField(buttons,{buttons=it.take(2000)},label={Text("自定义指令，每行一个")});OutlinedTextField(protected,{protected=it.take(2000)},label={Text("禁止改动的原文，每行一处")})}},confirmButton={TextButton(onClick={save(book.copy(style=style,facts=facts,buttons=buttons.lines().filter{it.isNotBlank()}.take(8),protectedText=protected.lines().filter{it.isNotBlank()}.take(20)))}){Text("保存")}},dismissButton={TextButton(onClick=close){Text("取消")}})
}
@Composable private fun SettingsPanel(settings:SecureSettings,notice:(String)->Unit){
 val scope=rememberCoroutineScope();var endpoint by remember{mutableStateOf("")};var model by remember{mutableStateOf("")};var key by remember{mutableStateOf("")};var configured by remember{mutableStateOf(false)}
 LaunchedEffect(Unit){withContext(Dispatchers.IO){settings.config()}?.let{endpoint=it.endpoint;model=it.model;configured=true}}
 Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
  Text(if(configured)"已配置 · 请求时才联网" else "尚未连接 AI",fontSize=24.sp,color=Ink)
  Text("阅读和编辑不需要 AI。连接后，每次生成只发送你确认的片段、本书风格、事实和指令给下方服务商；不上传整库、图片或录音。服务商可能按量收费，请核对其隐私和价格。",lineHeight=25.sp,color=Muted)
  OutlinedTextField(endpoint,{endpoint=it},label={Text("兼容服务完整 HTTPS 接口")},placeholder={Text("https://api.example.com/v1/chat/completions")},modifier=Modifier.fillMaxWidth())
  OutlinedTextField(model,{model=it},label={Text("模型名称")},modifier=Modifier.fillMaxWidth())
  OutlinedTextField(key,{key=it},label={Text(if(configured)"新密钥（留空保留原密钥）"else "API 密钥")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
  Text("密钥使用 Android Keystore 加密，仅留在本机，不写入导出备份。请在手机里填入，不要发到聊天。",fontSize=13.sp,color=Muted)
  Button(onClick={scope.launch{try{withContext(Dispatchers.IO){settings.save(ProviderConfig(endpoint.trim(),model.trim()),key.ifBlank{settings.key().orEmpty()})};key="";configured=true;notice("连接设置已保存，尚未进行收费请求")}catch(e:CancellationException){throw e}catch(e:Exception){notice(e.message?:"保存失败")}}}){Text("保存连接")}
  OutlinedButton(onClick={scope.launch{try{withContext(Dispatchers.IO){settings.clear()};key="";configured=false;notice("已移除本机密钥")}catch(e:CancellationException){throw e}catch(e:Exception){notice(e.message?:"清除连接失败，请重试")}}}){Text("移除连接与密钥")}
 }
}
@Composable private fun RewritePanel(book:Book,index:Int,settings:SecureSettings,saving:Boolean,close:()->Unit,connect:()->Unit,apply:(String)->Unit){
 val original=remember(book.id,index){book.chapters[index].text}
 val requestFacts=book.facts + if(book.protectedText.isEmpty()) "" else "\n必须保留的原文：\n"+book.protectedText.joinToString("\n")
 var submitted by remember{mutableStateOf<RewriteRequest?>(null)}
 var provider by remember{mutableStateOf<ProviderConfig?>(null)}
 var configured by remember{mutableStateOf(false)}
 LaunchedEffect(Unit){withContext(Dispatchers.IO){settings.config() to !settings.key().isNullOrBlank()}.let{provider=it.first;configured=it.first!=null&&it.second}}
 var requestStyle by rememberSaveable{mutableStateOf(book.style.ifBlank{"忠于原意，保留人物语气与情节事实"})};var passage by rememberSaveable{mutableStateOf(original)};var instruction by rememberSaveable{mutableStateOf(book.buttons.firstOrNull()?:"润色，保留原意")};var choices by remember{mutableStateOf<List<String>>(emptyList())};var selected by remember{mutableIntStateOf(0)};var running by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")};val scope=rememberCoroutineScope()
 val locked=running||saving
 val comparedPassage=submitted?.passage?:passage
 val inputSize=passage.length+requestStyle.length+requestFacts.length+instruction.length
 BackHandler{if(!locked)close()}
 Surface(Modifier.fillMaxSize(),color=Paper){Column(Modifier.safeDrawingPadding()){
  Row(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=12.dp),horizontalArrangement=Arrangement.SpaceBetween){Column(Modifier.weight(1f)){Text("AI 改写",fontSize=23.sp,fontWeight=FontWeight.SemiBold,color=Ink);Text("先比较，再采用",fontSize=13.sp,color=Muted)};TextButton(enabled=!locked,onClick=close){Text("返回阅读")}}
  Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
  Text("服务：${provider?.endpoint?:"尚未连接"}\n模型：${provider?.model?:"未设置"}",fontSize=12.sp,color=Muted)
  Text("发送范围：下方正文 + 本书风格与事实 + 指令。默认当前章节，可删减为其中一段。不会自动覆盖。",fontSize=13.sp,color=Muted)
  if(choices.isEmpty()){
   OutlinedTextField(passage,{passage=it.take(12000)},enabled=!locked,label={Text("本次发送的正文")},modifier=Modifier.fillMaxWidth().height(160.dp))
   Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){book.buttons.forEach{b->SuggestionChip(enabled=!locked,onClick={instruction=b},label={Text(b.take(20))})}}
   OutlinedTextField(instruction,{instruction=it.take(2000)},enabled=!locked,label={Text("本次改写指令")})
   OutlinedTextField(requestStyle,{requestStyle=it.take(3000)},enabled=!locked,label={Text("本次风格（不改本书设定）")});Text("事实与保护原文：${requestFacts.ifBlank{"未设置"}}",fontSize=12.sp,color=Muted)
  }else{
   Row{choices.indices.forEach{i->TextButton(enabled=!locked,onClick={selected=i}){Text(if(selected==i)"● 方案 ${i+1}"else "方案 ${i+1}")}}}
   Text("原文",color=Muted);Text(comparedPassage,Modifier.background(Color(0xFFECE5DE)).padding(12.dp),fontSize=14.sp)
   Text("候选",color=Muted);Text(choices[selected],Modifier.background(Color(0xFFE0EADB)).padding(12.dp),fontSize=14.sp)
   val diff=textChange(comparedPassage,choices[selected]);Text("差异范围（首尾相同部分省略）",color=Muted,fontSize=12.sp);if(diff.removed.isNotEmpty())Text("− ${diff.removed}",color=Color(0xFF944941),fontSize=13.sp);if(diff.added.isNotEmpty())Text("+ ${diff.added}",color=Color(0xFF38664C),fontSize=13.sp)
   Text("采用会替换上方原文对应的一处。整章与历史版本可随时恢复。",fontSize=12.sp,color=Muted)
  }
  if(choices.isEmpty())Text("本次合计 $inputSize / 12000 字符"+if(inputSize>12000)"，请缩小正文或设定后再发送"else "",fontSize=12.sp,color=if(inputSize>12000)MaterialTheme.colorScheme.error else Muted)
  if(saving)Text("正在保存采用的版本…",fontSize=13.sp,color=Muted)
  if(running)LinearProgressIndicator(Modifier.fillMaxWidth());if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
 }
 Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.SpaceBetween){TextButton(enabled=!locked,onClick=close){Text("保留原文")}
 if(!configured)Button(enabled=!locked,onClick=connect){Text("连接 AI")}
 else if(choices.isEmpty())Button(enabled=!locked&&passage.isNotBlank()&&inputSize<=12000,onClick={val request=RewriteRequest(passage,requestStyle,requestFacts,instruction);submitted=request;running=true;error="";scope.launch{try{choices=withContext(Dispatchers.IO){acceptRewrite(original,request.passage,request.passage,book.protectedText);val config=settings.config()?:error("请先在书架右上角配置 AI 服务");val key=settings.key()?:error("尚未配置密钥");OpenAiCompatibleAdapter().rewrite(config,key,request)}}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"请求失败"}finally{running=false}}}){Text("确认发送并生成")}
 else Button(enabled=!locked,onClick={try{apply(acceptRewrite(original,comparedPassage,choices[selected],book.protectedText))}catch(e:IllegalArgumentException){error=e.message.orEmpty()}}){Text("采用这个版本")}
 }}}
}
private const val SAMPLE="""第一章 雾中的灯

林遥在雨停后回到小镇。巷口那盏灯还亮着，灯下摆着一只空木箱，像是有人刚刚离开。

她把湿透的信封放进口袋，没有敲门。屋里传来水壶轻响，随后是一句熟悉的话：“你终于肯回来了。”

第二章 没有寄出的信

桌上放着三封没有地址的信。她认得纸上的字，却不记得自己什么时候写过。

窗外，最后一班渡船正慢慢靠岸。她决定先喝完这杯茶，再问那个已经等了很久的问题。

（原创演示文字，仅用于体验阅读和编辑。）"""

private fun readLimited(input:java.io.InputStream,limit:Int):ByteArray { val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=limit){"文件超过 20 MB，请先拆分"};out.write(buffer,0,n)};return out.toByteArray() }
