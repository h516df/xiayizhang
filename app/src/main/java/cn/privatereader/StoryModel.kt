package cn.privatereader

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
/** Offsets are UTF-16 offsets, exactly as Android selection reports them. */
data class SourceAnchor(val chapterId:String, val revision:Long, val start:Int, val end:Int, val source:String, val epoch:String="") {
 fun matches(chapter:Chapter):Boolean = chapter.id==chapterId && chapter.epoch==epoch && chapter.revision==revision && start>=0 && end>start && end<=chapter.text.length && chapter.text.substring(start,end)==source
}
enum class CardKind(val label:String) { CHARACTER("人物"), PLACE("地点"), OUTLINE("大纲") }
data class StoryCard(val id:String=newId(),val kind:CardKind,val name:String,val notes:String="",val sceneIds:List<String> = emptyList(),val source:SourceAnchor?=null,val referenceId:String?=null)
data class StoryRelation(val id:String=newId(),val fromId:String,val toId:String,val label:String,val sceneIds:List<String> = emptyList(),val directed:Boolean=true)
data class Scene(val id:String=newId(),val title:String="本章场景")

fun selectionAnchor(chapter:Chapter,start:Int,end:Int):SourceAnchor {
 require(start>=0 && end>start && end<=chapter.text.length){"请先选中一段正文"}
 require(!(start>0 && chapter.text[start].isLowSurrogate()) && !(end<chapter.text.length && chapter.text[end].isLowSurrogate())){"选区不能截断字符"}
 return SourceAnchor(chapter.id,chapter.revision,start,end,chapter.text.substring(start,end),chapter.epoch)
}
fun applyAnchoredEdit(chapter:Chapter,anchor:SourceAnchor,candidate:String,protected:List<String>):Chapter {
 require(anchor.matches(chapter)){"正文版本或选区已变化，请返回阅读重新选择。旧候选未采用。"}
 selectionAnchor(chapter,anchor.start,anchor.end)
 require(chapter.revision<Long.MAX_VALUE){"版本号已到上限，未修改正文"}
 val text=chapter.text.replaceRange(anchor.start,anchor.end,candidate)
 require(text.length<=Store.CHAPTER_SIZE){"结果超过本章 12000 字符限制"}
 fun count(s:String,p:String):Int {var n=0;var at=0;while(at<s.length){val i=s.indexOf(p,at);if(i<0)break;n++;at=i+p.length};return n}
 require(protected.filter{it.isNotEmpty()}.all{count(text,it)>=count(chapter.text,it)}){"结果改动了受保护原文，已阻止采用"}
 return chapter.copy(text=text,revision=chapter.revision+1)
}
internal object StoryJson {
 fun anchor(a:SourceAnchor)=JSONObject().put("chapterId",a.chapterId).put("revision",a.revision).put("start",a.start).put("end",a.end).put("source",a.source).put("epoch",a.epoch)
 fun anchor(j:JSONObject)=SourceAnchor(j.getString("chapterId"),j.getLong("revision"),j.getInt("start"),j.getInt("end"),j.getString("source"),j.optString("epoch"))
 fun strings(j:JSONArray?):List<String> {require((j?.length()?:0)<=5000);return (0 until (j?.length()?:0)).map{j!!.getString(it)}}
 fun cards(cards:List<StoryCard>)=JSONArray(cards.map{c->JSONObject().put("id",c.id).put("kind",c.kind.name).put("name",c.name).put("notes",c.notes).put("sceneIds",JSONArray(c.sceneIds)).also{j->c.source?.let{j.put("source",anchor(it))};c.referenceId?.let{j.put("referenceId",it)}}})
 fun cards(a:JSONArray?)=(0 until (a?.length()?:0).also{require(it<=2000)}).map{a!!.getJSONObject(it).let{j->StoryCard(j.getString("id"),CardKind.valueOf(j.getString("kind")),j.getString("name"),j.optString("notes"),strings(j.optJSONArray("sceneIds")),j.optJSONObject("source")?.let(::anchor),j.optString("referenceId").takeIf{it.isNotBlank()})}}
 fun relations(rs:List<StoryRelation>)=JSONArray(rs.map{r->JSONObject().put("id",r.id).put("fromId",r.fromId).put("toId",r.toId).put("label",r.label).put("sceneIds",JSONArray(r.sceneIds)).put("directed",r.directed)})
 fun relations(a:JSONArray?)=(0 until (a?.length()?:0).also{require(it<=5000)}).map{a!!.getJSONObject(it).let{j->StoryRelation(j.getString("id"),j.getString("fromId"),j.getString("toId"),j.getString("label"),strings(j.optJSONArray("sceneIds")),j.optBoolean("directed",true))}}
 fun validate(b:Book) {
  require(b.chapters.isNotEmpty() && b.chapters.size<=5000 && b.cards.size<=2000 && b.relations.size<=5000)
  val ids=b.chapters.map{it.id}.toSet();val scenes=b.chapters.map{it.scene.id}.toSet();val cards=b.cards.map{it.id}.toSet()
  require(ids.size==b.chapters.size && scenes.size==b.chapters.size && cards.size==b.cards.size)
  val validId=Regex("[A-Za-z0-9_-]{1,100}")
  require((ids+scenes+cards+b.relations.map{it.id}).all{validId.matches(it)})
  require(b.chapters.all{it.revision>=0 && it.scene.title.length<=500 && validId.matches(it.epoch)})
  fun linked(values:List<String>)=values.size<=scenes.size && values.toSet().size==values.size && values.all{it in scenes}
  require(b.cards.all{it.name.isNotBlank() && it.name.length<=200 && it.notes.length<=12000 && linked(it.sceneIds)})
  require(b.cards.all{it.source?.let{a->a.chapterId in ids && a.revision>=0 && a.start>=0 && a.end>a.start && a.end<=Store.CHAPTER_SIZE && a.source.length==a.end-a.start && a.epoch.length<=100}?:true})
  val people=b.cards.filter{it.kind==CardKind.CHARACTER}.map{it.id}.toSet()
  require(b.relations.map{it.id}.toSet().size==b.relations.size && b.relations.all{it.fromId in people && it.toId in people && it.fromId!=it.toId && it.label.isNotBlank() && it.label.length<=500 && linked(it.sceneIds)})

 }
}

/** Build only the selection and explicit current-scene opt-ins. Never traverse relationships. */
fun selectionRequest(book:Book,chapter:Chapter,anchor:SourceAnchor,instruction:String,strength:String,
 cardIds:Set<String> = emptySet(),relationIds:Set<String> = emptySet(),includeContext:Boolean=false,includeLegacy:Boolean=false,referencePartIds:Set<String> = emptySet(),expectedWorkspaceId:String=book.workspaceId):RewriteRequest {
 require(book.workspaceId==expectedWorkspaceId){"工作区已变化，请重新确认"}
 require(anchor.matches(chapter)){"选区已变化，请重新选择"}
 val cards=book.cards.filter{it.id in cardIds && chapter.scene.id in it.sceneIds}
 val relations=book.relations.filter{it.id in relationIds && chapter.scene.id in it.sceneIds}
 require(cards.map{it.id}.toSet()==cardIds && relations.map{it.id}.toSet()==relationIds){"所选资料已不属于当前场景，请重新确认"}
 val referenceParts=referenceContextParts(book,chapter.scene.id).filter{it.id in referencePartIds}
 require(referenceParts.map{it.id}.toSet()==referencePartIds){"导入条目已禁用、未关联当前场景或不属于本工作区，请重新确认"}
 val facts=buildString{
  referenceParts.forEach{append("明确选择的导入资料[id=${it.id}]：${it.title}\n${it.content}\n")}
  cards.forEach{append("${it.kind.label}[id=${it.id}]：${it.name}\n${it.notes}\n")}
  relations.forEach{r->append("关系[id=${r.id}]：${book.cards.find{it.id==r.fromId}?.name}[id=${r.fromId}]${if(r.directed)"→"else"↔"}${book.cards.find{it.id==r.toId}?.name}[id=${r.toId}]：${r.label}\n")}
  if(includeLegacy)append("原有设定：${book.facts}\n")
  if(includeContext){
   var start=maxOf(0,anchor.start-300);var end=minOf(chapter.text.length,anchor.end+300)
   if(start>0 && chapter.text[start].isLowSurrogate())start++
   if(end<chapter.text.length && chapter.text[end].isLowSurrogate())end--
   append("仅用于理解、不得改写的邻近上下文：\n${chapter.text.substring(start,anchor.start)}\n[所选片段]\n${chapter.text.substring(anchor.end,end)}")
  }
 }
 return RewriteRequest(anchor.source,if(includeLegacy)book.style else "",facts,"$instruction；改动强度：$strength。只返回所选片段的改写，不续写，不总结。")
}
