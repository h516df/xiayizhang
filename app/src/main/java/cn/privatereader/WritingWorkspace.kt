package cn.privatereader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*

/** Reading stays mounted underneath editors, so closing them never resets scroll position. */
@Composable fun WritingWorkspace(book:Book,index:Int,store:Store,settings:SecureSettings,font:Float,onSaved:(Book)->Unit,onChapter:(Int)->Unit,onHistory:()->Unit,onBackup:()->Unit,onSettings:()->Unit,onLegacy:()->Unit,aiOverride:WritingAi?=null,onFont:()->Unit={}) {
 val focusManager=androidx.compose.ui.platform.LocalFocusManager.current
 val gateway=remember(settings,aiOverride){aiOverride?:SettingsWritingAi(settings)}
 val chapter=book.chapters[index.coerceIn(book.chapters.indices)]
 var tab by rememberSaveable(book.id){mutableIntStateOf(0)}
 var toc by remember{mutableStateOf(false)}
 var selectionStart by rememberSaveable(chapter.id,chapter.revision,chapter.epoch){mutableIntStateOf(0)}
 var selectionEnd by rememberSaveable(chapter.id,chapter.revision,chapter.epoch){mutableIntStateOf(0)}
 val value=TextFieldValue(chapter.text,TextRange(selectionStart.coerceIn(0,chapter.text.length),selectionEnd.coerceIn(0,chapter.text.length)))
 val context=androidx.compose.ui.platform.LocalContext.current
 val preferences=remember(context){context.getSharedPreferences("reader_positions",0)}
 val positionKey="${book.id}:${chapter.id}"
 val scroll=rememberSaveable(positionKey,saver=ScrollState.Saver){ScrollState(preferences.getInt(positionKey,0))}
 DisposableEffect(positionKey,scroll){onDispose{preferences.edit().putInt(positionKey,scroll.value).apply()}}
 var anchor by remember{mutableStateOf<SourceAnchor?>(null)}
 var mode by remember{mutableStateOf("")}
 var error by remember{mutableStateOf("")}
 var saving by remember{mutableStateOf(false)}
 var undo by remember{mutableStateOf<Pair<Chapter,Chapter>?>(null)}
 var cardEditor by remember{mutableStateOf<StoryCard?>(null)}
 var relationEditor by remember{mutableStateOf<StoryRelation?>(null)}
 val scope=rememberCoroutineScope()
 fun save(next:Book,done:(Book)->Unit={}) {if(saving)return;saving=true;scope.launch{try{val saved=withContext(Dispatchers.IO){store.save(next)};onSaved(saved);done(saved)}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"保存失败，内容仍保留"}finally{saving=false}}}
 fun apply(a:SourceAnchor,text:String) {
  if(saving)return
  val before=book.chapters.find{it.id==a.chapterId}?:return
  saving=true
  scope.launch{try{val saved=withContext(Dispatchers.IO){store.editSelection(book.id,a,text)};onSaved(saved);undo=before to saved.chapters.first{it.id==a.chapterId};mode="";anchor=null}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"未采用，修改仍保留"}finally{saving=false}}
 }

 Column(Modifier.fillMaxSize()) {
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){listOf("正文","大纲","资料").forEachIndexed{i,label->TextButton(onClick={tab=i},enabled=!saving){Text(if(tab==i)"● $label" else label)}}}
  if(saving)LinearProgressIndicator(Modifier.fillMaxWidth())
  if(tab==0){
   Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp)){TextButton(enabled=!saving,onClick={toc=true}){Text("目录")};TextButton(enabled=!saving,onClick=onFont){Text("字 ${font.toInt()}")};TextButton(enabled=index>0&&!saving,onClick={onChapter(index-1)}){Text("上一章")};Text("${index+1}/${book.chapters.size}",Modifier.padding(12.dp));TextButton(enabled=index<book.chapters.lastIndex&&!saving,onClick={onChapter(index+1)}){Text("下一章")};TextButton(onClick=onHistory,enabled=!saving){Text("历史")};TextButton(onClick=onBackup,enabled=!saving){Text("备份")}}
   Column(Modifier.weight(1f).testTag("reader-scroll").verticalScroll(scroll).padding(horizontal=24.dp)){
    Text(chapter.title,fontSize=26.sp,modifier=Modifier.padding(vertical=14.dp))
    Text("长按正文选中，再选择自己改或 AI 改",fontSize=12.sp,color=MaterialTheme.colorScheme.secondary,modifier=Modifier.padding(bottom=16.dp))
    BasicTextField(value,onValueChange={if(it.text==chapter.text){selectionStart=it.selection.start;selectionEnd=it.selection.end}},readOnly=true,modifier=Modifier.testTag("reader-body").fillMaxWidth().padding(bottom=40.dp),textStyle=TextStyle(fontSize=font.sp,lineHeight=(font*1.8).sp,fontFamily=FontFamily.Serif,color=MaterialTheme.colorScheme.onSurface))
   }
   val selected=!value.selection.collapsed
   Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){
    fun capture():SourceAnchor=selectionAnchor(chapter,value.selection.min,value.selection.max)
    Button(enabled=selected&&!saving,onClick={anchor=capture();focusManager.clearFocus();mode="self"}){Text("自己改")}
    OutlinedButton(enabled=selected&&!saving,onClick={anchor=capture();focusManager.clearFocus();mode="ai"}){Text("AI 改")}
    TextButton(enabled=selected&&!saving,onClick={cardEditor=StoryCard(kind=CardKind.CHARACTER,name=value.selectedText().take(200),sceneIds=listOf(chapter.scene.id),source=capture())}){Text("存为资料")}
    TextButton(enabled=undo!=null&&!saving,onClick={val pair=undo?:return@TextButton;val current=book.chapters.find{it.id==pair.second.id};if(current!=pair.second){error="正文已变化，不能撤销覆盖；请使用历史恢复"}else {saving=true;scope.launch{try{val saved=withContext(Dispatchers.IO){store.undoSelection(book.id,pair.first,pair.second)};onSaved(saved);undo=null}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"未撤销"}finally{saving=false}}}}){Text("撤销")}
   }
  }else{
   val outline=tab==1
   Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
    Text(if(outline)"故事大纲"else"本书资料",fontSize=23.sp)
    Text("当前场景：${chapter.title} · ${chapter.scene.title}",fontSize=13.sp)
    Text("资料按稳定编号关联，同名可以并存。修改正文不会自动改动资料。",fontSize=12.sp)
    Row(Modifier.horizontalScroll(rememberScrollState())){(if(outline)listOf(CardKind.OUTLINE)else listOf(CardKind.CHARACTER,CardKind.PLACE)).forEach{k->OutlinedButton(enabled=!saving,onClick={cardEditor=StoryCard(kind=k,name="",sceneIds=listOf(chapter.scene.id))}){Text("新增${k.label}")}}}
    book.cards.filter{(it.kind==CardKind.OUTLINE)==outline}.forEach{card->OutlinedCard(onClick={cardEditor=card},modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text("${card.kind.label} · ${card.name} · ${card.id.take(6)}");Text(card.notes,maxLines=3);Text(if(chapter.scene.id in card.sceneIds)"已关联当前场景"else"未关联当前场景",fontSize=12.sp);card.source?.let{a->Text(if(book.chapters.any{a.matches(it)})"来自正文选区"else"来源版本已变化，锚点失效",fontSize=12.sp)}}}}
    if(!outline){
     Text("人物关系",fontSize=20.sp)
     val people=book.cards.filter{it.kind==CardKind.CHARACTER}
     OutlinedButton(enabled=people.size>=2&&!saving,onClick={relationEditor=StoryRelation(fromId=people[0].id,toId=people[1].id,label="",sceneIds=listOf(chapter.scene.id))}){Text("新增关系")}
     if(people.size<2)Text("建立两个人物后即可添加关系。",fontSize=12.sp)
     book.relations.forEach{r->TextButton(onClick={relationEditor=r}){Text("${people.find{it.id==r.fromId}?.name} ${if(r.directed)"→"else"↔"} ${people.find{it.id==r.toId}?.name}：${r.label}"+if(chapter.scene.id in r.sceneIds)" · 当前场景"else"")}}
     TextButton(onClick=onLegacy){Text("原有设定（保留风格、事实与保护原文）")}
    }
   }
  }
 }
 if(toc)AlertDialog(onDismissRequest={toc=false},title={Text("章节目录")},text={LazyColumn{itemsIndexed(book.chapters){i,c->TextButton(onClick={onChapter(i);toc=false}){Text("${i+1} ${c.title}")}}}},confirmButton={TextButton(onClick={toc=false}){Text("关闭")}})
 anchor?.let{a->if(mode=="self")SelfEditPanel(a,saving,{mode=""},{apply(a,it)}) else if(mode=="ai")SelectionAiPanel(book,chapter,a,gateway,saving,{mode=""},onSettings){apply(a,it)}}
 cardEditor?.let{card->CardEditor(card,book,saving,{cardEditor=null}){edited->save(book.copy(cards=if(book.cards.any{it.id==edited.id})book.cards.map{if(it.id==edited.id)edited else it}else book.cards+edited)){cardEditor=null}}}
 relationEditor?.let{r->RelationEditor(r,book,saving,{relationEditor=null}){edited->save(book.copy(relations=if(book.relations.any{it.id==edited.id})book.relations.map{if(it.id==edited.id)edited else it}else book.relations+edited)){relationEditor=null}}}
 if(error.isNotBlank())AlertDialog(onDismissRequest={error=""},title={Text("未完成操作")},text={Text(error)},confirmButton={TextButton(onClick={error=""}){Text("知道了")}})
}
private fun TextFieldValue.selectedText()=text.substring(selection.min,selection.max)
@Composable private fun EditorFrame(title:String,close:()->Unit,locked:Boolean=false,content:@Composable ColumnScope.()->Unit){
 Dialog(onDismissRequest={if(!locked)close()},properties=DialogProperties(usePlatformDefaultWidth=false,dismissOnBackPress=!locked,dismissOnClickOutside=false)){
  Surface(Modifier.fillMaxSize()){Column(Modifier.safeDrawingPadding().imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(title,fontSize=23.sp);TextButton(enabled=!locked,onClick=close){Text("取消 · 返回阅读")}};content()}}
 }
}
@Composable private fun SelfEditPanel(anchor:SourceAnchor,saving:Boolean,close:()->Unit,apply:(String)->Unit){
 var draft by rememberSaveable(anchor){mutableStateOf(anchor.source)}
 EditorFrame("自己改",close,saving){Text("只修改所选 ${anchor.source.length} 字；取消不写入正文。",fontSize=13.sp);OutlinedTextField(draft,{if(it.length<=Store.CHAPTER_SIZE)draft=it},modifier=Modifier.weight(1f).fillMaxWidth(),enabled=!saving,label={Text("选中片段")});Button(enabled=!saving,onClick={apply(draft)}){Text("保存并回到阅读")}}
}
@Composable private fun CardEditor(card:StoryCard,book:Book,saving:Boolean,close:()->Unit,save:(StoryCard)->Unit){
 var name by remember{mutableStateOf(card.name)};var notes by remember{mutableStateOf(card.notes)};var kind by remember{mutableStateOf(card.kind)};var scenes by remember{mutableStateOf(card.sceneIds)}
 EditorFrame("编辑${kind.label}",close,saving){Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
  if(book.cards.none{it.id==card.id})Row{CardKind.entries.forEach{k->FilterChip(enabled=!saving,selected=kind==k,onClick={kind=k},label={Text(k.label)})}}
  OutlinedTextField(name,{name=it.take(200)},enabled=!saving,label={Text("名称")},modifier=Modifier.fillMaxWidth());OutlinedTextField(notes,{notes=it.take(12000)},enabled=!saving,label={Text("内容 / 设定")},modifier=Modifier.fillMaxWidth().heightIn(min=140.dp))
  Text("关联场景（可多选，不会自动发送给 AI）")
  book.chapters.forEach{c->Row{Checkbox(c.scene.id in scenes,{checked->scenes=if(checked)scenes+c.scene.id else scenes-c.scene.id},enabled=!saving);Text("${c.title} · ${c.scene.title}",Modifier.padding(top=12.dp))}}
  card.source?.let{a->Text("来源：${a.source}",fontSize=12.sp);Text(if(book.chapters.any{a.matches(it)})"来源锚点有效"else"来源版本已改变；不会跳转到相似句子",fontSize=12.sp)}
 };Button(enabled=name.isNotBlank()&&!saving,onClick={save(card.copy(name=name,notes=notes,kind=kind,sceneIds=scenes.distinct()))}){Text("保存资料")}}
}
@Composable private fun RelationEditor(r:StoryRelation,book:Book,saving:Boolean,close:()->Unit,save:(StoryRelation)->Unit){
 var from by remember{mutableStateOf(r.fromId)};var to by remember{mutableStateOf(r.toId)};var label by remember{mutableStateOf(r.label)};var directed by remember{mutableStateOf(r.directed)};var scenes by remember{mutableStateOf(r.sceneIds)}
 EditorFrame("人物关系",close,saving){Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
  val people=book.cards.filter{it.kind==CardKind.CHARACTER}
  Text("关系起点");people.forEach{p->FilterChip(enabled=!saving,selected=from==p.id,onClick={from=p.id},label={Text("${p.name} · ${p.id.take(6)}")})}
  Text("关系终点");people.forEach{p->FilterChip(enabled=!saving,selected=to==p.id,onClick={to=p.id},label={Text("${p.name} · ${p.id.take(6)}")})}
  OutlinedTextField(label,{label=it.take(500)},enabled=!saving,label={Text("关系说明")});Row{Checkbox(directed,{directed=it},enabled=!saving);Text("有方向：起点 → 终点",Modifier.padding(top=12.dp))}
  Text("生效场景");book.chapters.forEach{c->Row{Checkbox(c.scene.id in scenes,{checked->scenes=if(checked)scenes+c.scene.id else scenes-c.scene.id},enabled=!saving);Text(c.title,Modifier.padding(top=12.dp))}}
 };Button(enabled=from!=to&&label.isNotBlank()&&!saving,onClick={save(r.copy(fromId=from,toId=to,label=label,directed=directed,sceneIds=scenes.distinct()))}){Text("保存关系")}}
}

@Composable private fun SelectionAiPanel(book:Book,chapter:Chapter,anchor:SourceAnchor,gateway:WritingAi,saving:Boolean,close:()->Unit,connect:()->Unit,apply:(String)->Unit){
 var preset by rememberSaveable{mutableStateOf("润色，保留原意")};var strength by rememberSaveable{mutableStateOf("轻")};var more by rememberSaveable{mutableStateOf(false)}
 var contextIncluded by rememberSaveable{mutableStateOf(false)};var legacy by rememberSaveable{mutableStateOf(false)}
 var included by remember{mutableStateOf(setOf<String>())};var includedRelations by remember{mutableStateOf(setOf<String>())}
 var choices by remember{mutableStateOf<List<String>>(emptyList())};var selected by remember{mutableIntStateOf(0)};var view by remember{mutableIntStateOf(1)}
 var running by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")};var config by remember{mutableStateOf<ProviderConfig?>(null)};var configured by remember{mutableStateOf(false)}
 val scope=rememberCoroutineScope();var job by remember{mutableStateOf<Job?>(null)}
 LaunchedEffect(Unit){try{config=withContext(Dispatchers.IO){gateway.provider()};configured=config!=null}catch(e:CancellationException){throw e}catch(e:Exception){error="无法读取服务设置；未发送正文"}}
 val cards=book.cards.filter{chapter.scene.id in it.sceneIds}
 val relations=book.relations.filter{chapter.scene.id in it.sceneIds}
 val request=selectionRequest(book,chapter,anchor,preset,strength,included,includedRelations,contextIncluded,legacy)
 val facts=request.facts
 val style=request.style
 val size=listOf(request.passage,request.style,request.facts,request.instruction).sumOf{it.length}
 EditorFrame("AI 改 · 所选片段",{job?.cancel();close()},saving){
  Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
   if(choices.isEmpty()){
    Text("已选 ${anchor.source.length} 字 · 正文暂不改变",fontSize=13.sp)
    Row(Modifier.horizontalScroll(rememberScrollState())){listOf("润色，保留原意","让对话更自然","精简重复描述").forEach{p->FilterChip(enabled=!running,selected=preset==p,onClick={preset=p},label={Text(p.substringBefore('，'))})}}
    Row{Text("强度",Modifier.padding(12.dp));listOf("轻","中","强").forEach{s->FilterChip(enabled=!running,selected=strength==s,onClick={strength=s},label={Text(s)})}}
    TextButton(enabled=!running,onClick={more=!more}){Text(if(more)"收起更多"else"更多要求与发送资料")}
    if(more){
     OutlinedTextField(preset,{preset=it.take(2000)},enabled=!running,label={Text("这次怎么改")},modifier=Modifier.fillMaxWidth())
     Text("只列出当前场景关联资料，默认不发送；勾选后可移除。",fontSize=12.sp)
     cards.forEach{c->Row{Checkbox(c.id in included,{checked->included=if(checked)included+c.id else included-c.id},enabled=!running);Text("${c.kind.label}：${c.name} · ${c.id.take(6)}",Modifier.padding(top=12.dp))}}
     relations.forEach{r->Row{Checkbox(r.id in includedRelations,{checked->includedRelations=if(checked)includedRelations+r.id else includedRelations-r.id},enabled=!running);Text("关系：${book.cards.find{it.id==r.fromId}?.name} ${if(r.directed)"→"else"↔"} ${book.cards.find{it.id==r.toId}?.name}：${r.label}",Modifier.padding(top=12.dp))}}
     Row{Checkbox(contextIncluded,{contextIncluded=it},enabled=!running);Text("附带前后各最多 300 字上下文",Modifier.padding(top=12.dp))}
     Row{Checkbox(legacy,{legacy=it},enabled=!running);Text("附带原有风格与事实设定",Modifier.padding(top=12.dp))}
    }
    Text("发送预览",fontSize=16.sp)
    Text(anchor.source,modifier=Modifier.fillMaxWidth().heightIn(max=180.dp).verticalScroll(rememberScrollState()).padding(10.dp),fontSize=14.sp)
    if(facts.isNotBlank()||style.isNotBlank())Text("风格：${style.ifBlank{"无"}}\n资料：${facts.ifBlank{"无"}}",modifier=Modifier.heightIn(max=160.dp).verticalScroll(rememberScrollState()),fontSize=12.sp)
    Text("指令：${request.instruction}\n共 $size / 12000 字符",fontSize=12.sp)
    Text(if(configured)"发送至 ${config?.endpoint}\n模型 ${config?.model}；服务可能收费。"else"尚未配置服务，不能生成。阅读和自己修改不受影响。",fontSize=12.sp)
   }else{
    Row{choices.indices.forEach{i->TextButton(onClick={selected=i},enabled=!running){Text(if(i==selected)"● 候选${i+1}"else"候选${i+1}")}}}
    Row{listOf("原文","候选","差异").forEachIndexed{i,label->FilterChip(selected=view==i,onClick={view=i},label={Text(label)})}}
    when(view){0->Text(anchor.source);1->Text(choices[selected]);else->{val diff=textChange(anchor.source,choices[selected]);Text("首尾相同内容已省略；这是变化范围，不是逐块采纳。",fontSize=12.sp);Text("删除：${diff.removed}");Text("新增：${diff.added}")}}
    Text("采用只替换选区，不改人物、大纲或其他资料。",fontSize=12.sp)
   }
   if(running)LinearProgressIndicator(Modifier.fillMaxWidth())
   if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
  }
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
   TextButton(enabled=!saving,onClick={job?.cancel();close()}){Text("取消，不改正文")}
   if(choices.isNotEmpty())Button(enabled=!saving&&!running,onClick={apply(choices[selected])}){Text("采用并回阅读")}
   else if(!configured)Button(onClick={close();connect()},enabled=!saving){Text("去设置连接")}
   else Button(enabled=!running&&!saving&&size<=12000&&anchor.matches(chapter),onClick={
    running=true;error="";val submitted=request
    job=scope.launch{try{val result=withContext(Dispatchers.IO){val c=config?:error("未配置服务");gateway.generate(c,submitted)};ensureActive();choices=result;selected=0}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"请求失败，选区与要求保留"}finally{running=false}}
   }){Text("确认发送")}
  }
 }
}
