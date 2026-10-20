package cn.privatereader

data class TextChange(val prefix:String,val removed:String,val added:String,val suffix:String)
fun textChange(before:String,after:String):TextChange {
 var prefix=0;while(prefix<minOf(before.length,after.length)&&before[prefix]==after[prefix])prefix++
 if(prefix>0&&prefix<before.length&&before[prefix].isLowSurrogate())prefix--
 var suffix=0;while(suffix<before.length-prefix&&suffix<after.length-prefix&&before[before.lastIndex-suffix]==after[after.lastIndex-suffix])suffix++
 if(suffix>0&&before[before.length-suffix].isLowSurrogate())suffix--
 return TextChange(before.take(prefix),before.substring(prefix,before.length-suffix),after.substring(prefix,after.length-suffix),before.takeLast(suffix))
}
fun acceptRewrite(original:String,passage:String,candidate:String,protected:List<String>):String {
 require(passage.isNotBlank()){ "请选择非空原文" }
 val at=original.indexOf(passage)
 require(at>=0){"片段不是当前原文的连续内容，请重新选择"}
 require(original.indexOf(passage,at+1)<0){"片段出现多次，请选择更长的唯一片段"}
 val next=original.replaceRange(at,at+passage.length,candidate)
 require(next.length<=Store.CHAPTER_SIZE){"结果超过本章 12000 字符限制，请缩小片段"}
 fun count(text:String,part:String):Int {var found=0;var from=0;while(from<text.length){val p=text.indexOf(part,from);if(p<0)break;found++;from=p+part.length};return found}
 require(protected.filter{it.isNotEmpty()}.all{count(next,it)>=count(original,it)}){"结果改动了受保护原文，已阻止采用"}
 return next
}

/** Tickets are issued on user input, before IO dispatch. Late older writes are discarded. */
class OrderedDraftSaver(private val store:Store) {
 private val issued=java.util.concurrent.atomic.AtomicLong()
 fun nextTicket():Long=issued.incrementAndGet()
 @Synchronized fun save(book:Book,ticket:Long):Book? = if(ticket!=issued.get()) null else store.save(book)
}
