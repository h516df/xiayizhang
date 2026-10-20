package cn.privatereader

/** Offline, bounded, non-overlapping edits against the exact captured source. No regex from users. */
data class CleanupOptions(val navigation:Boolean=true,val promotions:Boolean=true,val repeatedHeading:Boolean=true,val spacing:Boolean=true,val joinWrappedLines:Boolean=false,val find:String="",val replacement:String="")
data class CleanupEdit(val start:Int,val end:Int,val before:String,val after:String,val reason:String,val recommended:Boolean=false) { val key:String get()="$start:$end:$reason" }
data class CleanupPlan(val source:String,val edits:List<CleanupEdit>) {
 fun apply(selected:Set<String>):String {
  require(source.length<=120000)
  require(selected.all{key->edits.any{it.key==key}}){"清理选项已变化，请重新预览"}
  val out=StringBuilder();var cursor=0
  edits.filter{it.key in selected}.forEach{e->
   require(e.start>=cursor&&e.end in e.start..source.length&&source.substring(e.start,e.end)==e.before){"原文已变化，请重新预览"}
   out.append(source,cursor,e.start).append(e.after);cursor=e.end
   require(out.length<=120000){"替换结果超过 12 万字符，请缩小范围"}
  }
  out.append(source,cursor,source.length)
  require(out.length<=120000){"替换结果超过 12 万字符，请缩小范围"}
  return out.toString()
 }
}
fun planCleanup(source:String,options:CleanupOptions=CleanupOptions()):CleanupPlan {
 require(source.length<=120000){"一次清理最多 12 万字符，请按章节处理"}
 require(options.find.length<=200&&options.replacement.length<=500){"查找最多 200 字符，替换最多 500 字符"}
 require(!options.find.contains('\n')&&!options.find.contains('\r')){"自定义查找请使用单行文字"}
 val edits=mutableListOf<CleanupEdit>()
 fun add(start:Int,end:Int,after:String,reason:String,recommended:Boolean=false){
  if(source.substring(start,end)==after)return
  require(edits.size<1000){"候选超过 1000 处，请缩小选区或减少规则；未截断处理"}
  edits+=CleanupEdit(start,end,source.substring(start,end),after,reason,recommended)
 }
 // Exact literal replacements are a separate pass: no overlap with built-in candidates.
 if(options.find.isNotEmpty()) {
  var start=0
  while(start<source.length){val p=source.indexOf(options.find,start);if(p<0)break
   require(!(p>0&&source[p].isLowSurrogate())&&!(p+options.find.length<source.length&&source[p+options.find.length].isLowSurrogate())){"查找不能切开字符"}
   add(p,p+options.find.length,options.replacement,"自定义文字替换");start=p+options.find.length
  }
  return CleanupPlan(source,edits)
 }
 data class Line(val start:Int,val content:String,val ending:String){val end get()=start+content.length+ending.length}
 val lines=mutableListOf<Line>();var at=0
 while(at<source.length){var end=at;while(end<source.length&&source[end]!='\r'&&source[end]!='\n')end++
  val stop=if(end==source.length)end else if(source[end]=='\r'&&end+1<source.length&&source[end+1]=='\n')end+2 else end+1
  lines+=Line(at,source.substring(at,end),source.substring(end,stop));at=stop
 }
 val heading=Regex("第[一二三四五六七八九十百千万零〇两0-9]{1,20}[章节回卷部篇][^\\r\\n]{0,70}")
 val nav=Regex("(?:上一[页章]|下一[页章]|返回目录|章节目录|加入书签|书签|目录)(?:[ \\t　|｜/＞>·]+(?:上一[页章]|下一[页章]|返回目录|章节目录|加入书签|书签|目录))*")
 var previousNonblank="";var emptyCount=0
 lines.forEachIndexed{i,line->
  val text=line.content.trim();val prev=previousNonblank
  if(text.isNotEmpty()){previousNonblank=text;emptyCount=0}else emptyCount++
  val reason=when {
   options.navigation&&nav.matches(text)->"疑似翻页 / 目录按钮（请核对）"
   options.promotions&&text.length<=300&&(text.startsWith("请收藏本站")||text.startsWith("最新网址：")||text.startsWith("最新域名：")||text.startsWith("本章未完，点击下一页")||text.startsWith("手机用户请浏览"))->"疑似站点推广（请核对）"
   options.repeatedHeading&&heading.matches(text)&&text==prev->"相邻重复章名（保留首次）"
   else->null
  }
  if(reason!=null){add(line.start,line.end,"",reason);return@forEachIndexed}
  if(options.spacing&&text.isEmpty()&&emptyCount>2){add(line.start,line.end,"","多余空行",true);return@forEachIndexed}
  val next=lines.getOrNull(i+1)?.content.orEmpty()
  val left=line.content.trimEnd(' ','\t','　')
  val join=options.joinWrappedLines&&left.length>=20&&next.length>=20&&left.lastOrNull()?.let{it in '\u4e00'..'\u9fff'}==true&&next.firstOrNull()?.let{it in '\u4e00'..'\u9fff'}==true&&!heading.matches(next.trim())&&line.ending.isNotEmpty()
  if(join){add(line.start+left.length,line.end,"","疑似硬换行（可能改变排版）");return@forEachIndexed}
  if(options.spacing){
   if(left.length<line.content.length)add(line.start+left.length,line.start+line.content.length,"","行尾空格",true)
   if(line.ending.isNotEmpty()&&line.ending!="\n")add(line.start+line.content.length,line.end,"\n","统一换行符",true)
  }
 }
 return CleanupPlan(source,edits)
}
