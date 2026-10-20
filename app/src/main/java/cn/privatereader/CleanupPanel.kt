package cn.privatereader

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

@Composable internal fun CleanupPanel(source:String,saving:Boolean,importing:Boolean=false,allowEmpty:Boolean=false,close:()->Unit,apply:(String)->Unit){
 var options by remember(source){mutableStateOf(CleanupOptions())}
 var plan by remember(source){mutableStateOf<CleanupPlan?>(null)}
 var selected by remember(source){mutableStateOf(setOf<String>())}
 var advanced by remember{mutableStateOf(false)}
 var preview by remember{mutableStateOf(false)}
 var busy by remember{mutableStateOf(false)}
 var error by remember{mutableStateOf("")}
 val scope=rememberCoroutineScope()
 var job by remember{mutableStateOf<Job?>(null)}
 val locked=busy||saving
 fun update(next:CleanupOptions){options=next;plan=null;selected=emptySet();error=""}
 val candidate=remember(plan,selected){runCatching{plan?.apply(selected)}}
 EditorFrame("清理正文",{job?.cancel();close()},saving,closeLabel=if(importing)"取消 · 返回粘贴"else"取消 · 返回阅读"){
  Text(if(importing)"只在本机处理。确认后同时保存原稿、清理稿和可恢复历史。" else "只处理当前选区 / 章节；确认前不会改正文，可撤销。",fontSize=13.sp)
  if(plan==null){
   Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
    Text("先找出疑似杂文，再由你决定删哪些。广告和翻页提示默认不勾选，剧情不会自动删除。",fontSize=14.sp)
    Row(Modifier.horizontalScroll(rememberScrollState())){
     FilterChip(selected=options.navigation,onClick={update(options.copy(navigation=!options.navigation))},enabled=!locked,label={Text("翻页提示")})
     FilterChip(selected=options.promotions,onClick={update(options.copy(promotions=!options.promotions))},enabled=!locked,label={Text("站点推广")})
     FilterChip(selected=options.repeatedHeading,onClick={update(options.copy(repeatedHeading=!options.repeatedHeading))},enabled=!locked,label={Text("重复章名")})
     FilterChip(selected=options.spacing,onClick={update(options.copy(spacing=!options.spacing))},enabled=!locked,label={Text("空格换行")})
    }
    TextButton(enabled=!locked,onClick={advanced=!advanced}){Text(if(advanced)"收起自定义" else "自定义与硬换行")}
    if(advanced){
     Row{Checkbox(checked=options.joinWrappedLines,onCheckedChange={update(options.copy(joinWrappedLines=it))},enabled=!locked);Text("查找疑似硬换行（默认不合并）",Modifier.padding(top=12.dp))}
     Text("仅建议较长的连续中文行；对白、诗歌、列表可能被误判，请逐项检查。",fontSize=12.sp)
     OutlinedTextField(options.find,{if(it.length<=200)update(options.copy(find=it))},enabled=!locked,label={Text("精确查找文字")},singleLine=true,modifier=Modifier.fillMaxWidth())
     OutlinedTextField(options.replacement,{if(it.length<=500)update(options.copy(replacement=it))},enabled=!locked,label={Text("替换为（留空表示删除）")},modifier=Modifier.fillMaxWidth())
     Text("填写查找后，本次仅预览精确文字替换，不执行其他规则。不支持正则表达式，不会执行代码。",fontSize=12.sp)
    }
    Text("原文 · ${source.length} 字符",fontSize=16.sp)
    Text(source.take(1000)+if(source.length>1000)"\n……这里只展示开头，候选预览处理完整选区。"else"",fontSize=14.sp)
   }
   if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
   if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
   Button(enabled=!locked&&source.isNotBlank(),onClick={busy=true;error="";val captured=options;job=scope.launch{try{val p=withContext(Dispatchers.Default){planCleanup(source,captured)};ensureActive();plan=p;selected=p.edits.filter{it.recommended}.map{it.key}.toSet()}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"无法清理；原文未变"}finally{busy=false}}}){Text("预览清理")}
  }else{
   val p=plan!!
   Row(Modifier.horizontalScroll(rememberScrollState())){TextButton(enabled=!saving,onClick={plan=null;preview=false}){Text("调整规则")};TextButton(onClick={preview=!preview}){Text(if(preview)"逐项核对"else"看完整结果")}}
   Text("找到 ${p.edits.size} 处 · 已选 ${selected.size} 处",fontSize=14.sp)
   if(p.edits.isEmpty())Text("没有找到候选。不会猜测删除其他内容；可用精确查找自定义。")
   if(preview){Column(Modifier.weight(1f).verticalScroll(rememberScrollState())){Text("清理后预览（尚未保存）");Text(candidate.getOrNull().orEmpty(),Modifier.testTag("cleanup-result"))}}
   else LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){
    items(p.edits,key={it.key}){e->OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp)){
     Row{Checkbox(checked=e.key in selected,onCheckedChange={if(it)selected=selected+e.key else selected=selected-e.key},enabled=!saving,modifier=Modifier.testTag("cleanup-${e.start}"));Column(Modifier.weight(1f)){Text(e.reason,fontSize=14.sp);Text("原文位置 ${e.start+1}–${e.end}",fontSize=12.sp)}}
     Text("原：${visibleCleanupText(e.before)}",fontSize=14.sp)
     Text("改：${if(e.after.isEmpty())"（删除）"else visibleCleanupText(e.after)}",fontSize=14.sp,color=MaterialTheme.colorScheme.primary)
    }}}
   }
   candidate.exceptionOrNull()?.let{Text(it.message?:"结果无法应用",color=MaterialTheme.colorScheme.error)}
   if(candidate.getOrNull()?.isBlank()==true)Text(if(allowEmpty)"所选杂文将被删除，其他正文保留，可撤销。"else"清理后没有正文，请保留需要的候选。",color=MaterialTheme.colorScheme.error)
   Button(enabled=!saving&&selected.isNotEmpty()&&candidate.isSuccess&&(allowEmpty||!candidate.getOrNull().isNullOrBlank()),onClick={candidate.getOrNull()?.let(apply)}){Text(if(importing)"确认清理并收入书架"else"确认清理并回阅读")}
  }
 }
}
private fun visibleCleanupText(text:String):String=text.replace("\r","␍").replace("\n","↵\n").let{if(it.length>1200)it.take(1200)+"…（完整改动请看结果）"else it}
