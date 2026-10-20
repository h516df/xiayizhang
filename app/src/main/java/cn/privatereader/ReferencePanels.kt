package cn.privatereader

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun ReferenceImportPreview(resource:ImportedResource,book:Book,sceneTitle:String,saving:Boolean,close:()->Unit,confirm:()->Unit){
 EditorFrame("导入预览",close,saving){Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
  Text(referenceTitle(resource.title),fontSize=22.sp)
  Text("格式：${resource.format}\n来源：${resource.sourceName}\n${resource.entries.size} 条背景条目（${resource.entries.count{it.enabled}} 条启用）",fontSize=13.sp)
  Text("只保存到《${book.title}》的本地资料工作区，关联 $sceneTitle。不替换同名资料，不自动发送给 AI。",fontSize=14.sp)
  if(book.resources.any{it.title==resource.title})Text("已有同名资料：本次会创建独立副本，不覆盖。",color=MaterialTheme.colorScheme.error)
  Text("外部 system_prompt、后置指令、脚本、正则、链接和图片只作为原始元数据保存，不执行；不是完整酒馆运行兼容。",fontSize=13.sp)
  resource.warnings.forEach{Text("提示：$it",fontSize=12.sp)}
  if(resource.characterText.isNotBlank())ReferenceTextPreview(resource.characterText)
  resource.entries.take(12).forEach{e->Column{Text("${if(e.enabled)"启用"else"禁用"} · ${referenceTitle(e.title)}");ReferenceTextPreview(e.content)}}
  if(resource.entries.size>12)Text("这里预览前 12 条；确认后可查看全部 ${resource.entries.size} 条。不会截断保存。",fontSize=12.sp)
 };Button(enabled=!saving,onClick=confirm){Text("确认导入到本书")}}
}

@Composable private fun ReferenceTextPreview(text:String){
 Text(text.take(2000),modifier=Modifier.heightIn(max=180.dp).verticalScroll(rememberScrollState()),fontSize=13.sp)
 if(text.length>2000)Text("界面预览前 2000 字；原文完整保存，未截断导入。",fontSize=11.sp)
}
@Composable internal fun ReferenceDetails(resource:ImportedResource,binding:ReferenceBinding?,saving:Boolean,close:()->Unit,editBinding:()->Unit,draft:(CardKind,String,String)->Unit){
 var raw by remember{mutableStateOf(false)}
 EditorFrame("导入资料",close,saving){LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)){
  item{Text(referenceTitle(resource.title),fontSize=22.sp);Text("${resource.format} · ${resource.sourceName}\n原始 JSON 完整保留；不可变导入快照 v1",fontSize=12.sp)
   Text(if(binding?.enabled==true)"本书绑定启用，仍需逐次选择才能发给 AI。"else"本书绑定禁用，不会进入 AI 上下文。",fontSize=12.sp)
   OutlinedButton(onClick=editBinding,enabled=!saving){Text("修改关联场景 / 启停")}
   Text("整理为资料草稿会新建可编辑副本，确认保存后才写入；不会修改导入原件。",fontSize=12.sp)
   if(resource.characterText.isNotBlank()){
    ReferenceTextPreview(resource.characterText)
    OutlinedButton(onClick={draft(CardKind.CHARACTER,resource.title,resource.characterText)},enabled=!saving&&binding?.enabled==true){Text("整理人物资料草稿")}
   }
  }
  items(resource.entries,key={it.id}){e->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
   Text("${if(e.enabled)"启用"else"禁用"} · ${referenceTitle(e.title)}")
   ReferenceTextPreview(e.content)
   if(e.unsupportedFeatures.isNotEmpty())Text("仅保留不执行：${e.unsupportedFeatures.take(20).joinToString{it.take(80)}}${if(e.unsupportedFeatures.size>20)"（其余保留于原JSON）"else""}",fontSize=12.sp)
   TextButton(enabled=e.enabled&&binding?.enabled==true&&!saving,onClick={draft(CardKind.OUTLINE,e.title,e.content)}){Text("整理为资料草稿")}
  }}}
  item{resource.warnings.forEach{Text(it,fontSize=12.sp)}
   TextButton(onClick={raw=!raw},enabled=!saving){Text(if(raw)"收起原始 JSON"else"预览原始 JSON（仅文本）")}
   if(raw)ReferenceTextPreview(resource.rawJson)
  }
 }}
}

@Composable internal fun ReferenceContextPicker(parts:List<ReferenceContextPart>,selected:Set<String>,change:(Set<String>)->Unit,close:()->Unit){
 var query by remember{mutableStateOf("")}
 EditorFrame("选择导入资料",close){
  Text("只列出本书当前场景启用条目。不会自动执行关键词、常驻或脚本。",fontSize=13.sp)
  OutlinedTextField(query,{query=it.take(100)},label={Text("按条目名称查找")},modifier=Modifier.fillMaxWidth())
  LazyColumn(Modifier.weight(1f)){items(parts.filter{query.isBlank()||it.title.contains(query,ignoreCase=true)},key={it.id}){part->Row{Checkbox(part.id in selected,{checked->change(if(checked)selected+part.id else selected-part.id)});Column(Modifier.padding(top=8.dp)){Text(part.title);Text("${part.content.length} 字 · ${part.resourceId.take(6)}",fontSize=12.sp)}}}}
  Button(onClick=close){Text("完成选择（${selected.size}）")}
 }
}

@Composable internal fun ReferenceBindingEditor(binding:ReferenceBinding,book:Book,saving:Boolean,close:()->Unit,save:(ReferenceBinding)->Unit){
 var enabled by remember{mutableStateOf(binding.enabled)};var scenes by remember{mutableStateOf(binding.sceneIds)}
 EditorFrame("关联本书场景",close,saving){Column(Modifier.weight(1f).verticalScroll(rememberScrollState())){
  Text("固定引用导入快照 v1；不跨书自动共享，不自动升级。",fontSize=13.sp)
  Row{Checkbox(enabled,{enabled=it},enabled=!saving);Text("允许本书显式选择此资料",Modifier.padding(top=12.dp))}
  book.chapters.forEach{c->Row{Checkbox(c.scene.id in scenes,{checked->scenes=if(checked)scenes+c.scene.id else scenes-c.scene.id},enabled=!saving);Text(c.title,Modifier.padding(top=12.dp))}}
 };Button(enabled=!saving,onClick={save(binding.copy(enabled=enabled,sceneIds=scenes.distinct()))}){Text("保存关联")}}
}
