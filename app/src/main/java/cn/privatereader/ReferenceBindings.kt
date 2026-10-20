package cn.privatereader

import org.json.JSONArray
import org.json.JSONObject

/** Bindings are local to a Book.workspaceId. This slice has no implicit global/shared library. */
data class ReferenceBinding(val id:String=newId(),val resourceId:String,val sceneIds:List<String>,val enabled:Boolean=true,val pinnedRevision:Int=1)
internal object BindingJson {
 fun encode(bindings:List<ReferenceBinding>)=JSONArray(bindings.map{b->JSONObject().put("id",b.id).put("resourceId",b.resourceId).put("sceneIds",JSONArray(b.sceneIds)).put("enabled",b.enabled).put("pinnedRevision",b.pinnedRevision)})
 fun decode(a:JSONArray?):List<ReferenceBinding>{require((a?.length()?:0)<=200);return (0 until (a?.length()?:0)).map{a!!.getJSONObject(it).let{b->ReferenceBinding(b.getString("id"),b.getString("resourceId"),StoryJson.strings(strictOptionalArray(b,"sceneIds")),if(b.has("enabled")) (b.get("enabled") as? Boolean ?: error("启用状态类型无效")) else true,if(b.has("pinnedRevision")) {val v=b.get("pinnedRevision");require(v is Int && v==1){"资料版本无效"};v} else 1)}}}
}

fun validateReferences(book:Book) {
 require(runCatching{java.util.UUID.fromString(book.workspaceId)}.isSuccess){"无效工作区标识"}
 ResourceJson.validate(book.resources)
 require(book.bindings.size<=200)
 val resources=book.resources.map{it.id}.toSet();val scenes=book.chapters.map{it.scene.id}.toSet()
 require(book.bindings.map{it.id}.toSet().size==book.bindings.size && book.bindings.map{it.resourceId}.toSet().size==book.bindings.size)
 require(book.bindings.all{b->runCatching{java.util.UUID.fromString(b.id)}.isSuccess && b.resourceId in resources && b.pinnedRevision==1 && b.sceneIds.size<=scenes.size && b.sceneIds.toSet().size==b.sceneIds.size && b.sceneIds.all{it in scenes}}){"资料绑定无效"}
 require(book.cards.all{it.referenceId==null || it.referenceId in resources}){"资料来源不属于本工作区"}
}

fun referenceTitle(title:String)=if(title.length<=200)title else title.take(200)+"…（名称完整保存）"

data class ReferenceContextPart(val id:String,val resourceId:String,val title:String,val content:String)
fun referenceContextParts(book:Book,sceneId:String):List<ReferenceContextPart> {
 val bound=book.bindings.filter{it.enabled && it.pinnedRevision==1 && sceneId in it.sceneIds}.map{it.resourceId}.toSet()
 return book.resources.filter{it.id in bound}.flatMap{r->buildList{
  if(r.kind==ImportedKind.CHARACTER && r.characterText.isNotBlank())add(ReferenceContextPart("${r.id}:character",r.id,"人物基础资料：${referenceTitle(r.title)}",r.characterText))
  r.entries.filter{it.enabled}.forEach{e->add(ReferenceContextPart("${r.id}:entry:${e.id}",r.id,"${referenceTitle(r.title)} · ${referenceTitle(e.title)}",e.content))}
 }}
}

internal fun strictOptionalArray(json:JSONObject,key:String):JSONArray? = if(!json.has(key)) null else json.get(key) as? JSONArray ?: error("$key 必须是数组")
