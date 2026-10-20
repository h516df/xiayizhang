package cn.privatereader

import org.json.JSONObject

/** An ephemeral projection, never a replacement for original EPUB bytes or the working model. */
internal object ReadingProjection {
 fun escape(text:String):String = buildString(text.length) { text.forEach { c -> append(when(c){'&'->"&amp;";'<'->"&lt;";'>'->"&gt;";'\"'->"&quot;";'\''->"&#39;";'\r'->"&#13;";else->c.toString()}) } }
 fun html(chapter:Chapter,offset:Int=0):String {
  val at=safeOffset(chapter.text,offset)
  val body=escape(chapter.text.substring(0,at))+"<span id=\"resume\" aria-hidden=\"true\"></span>"+escape(chapter.text.substring(at))
  return """<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src https://readium/assets/readium/scripts/readium-reflowable.js; style-src 'unsafe-inline' https://readium/assets/readium/; font-src https://readium/assets/readium/; img-src 'none'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"/><style>#source{white-space:pre-wrap;overflow-wrap:anywhere}#resume{display:inline-block;width:0;height:1em;pointer-events:none}body{margin:0}</style></head><body><div id="source">${body}</div></body></html>"""
 }
 fun safeOffset(text:String,offset:Int):Int {val at=offset.coerceIn(0,text.length);return if(at>0&&at<text.length&&text[at].isLowSurrogate()&&text[at-1].isHighSurrogate())at-1 else at}
 fun remapOffset(offset:Int,start:Int,end:Int,replacementLength:Int):Int = when {offset<=start->offset;offset>=end->offset+replacementLength-(end-start);else->start}
 // Only returns a character offset. Application-owned script, no text or external IO.
 const val POSITION_SCRIPT = """(function(){const root=document.getElementById('source');if(!root)return null;for(let y=8;y<innerHeight;y+=12){for(let x=8;x<innerWidth;x+=12){const r=document.caretRangeFromPoint(x,y);if(!r||!root.contains(r.startContainer))continue;const box=r.getBoundingClientRect();if(box.top<0||box.left<0||box.left>=innerWidth||box.top>=innerHeight)continue;const a=document.createRange();a.selectNodeContents(root);a.setEnd(r.startContainer,r.startOffset);return a.toString().length;}}return null;})()"""
 // This fixed program receives no interpolated book content. Range lengths and Kotlin offsets are
 // both UTF-16 code units. No find(), trimmed string, whitespace normalization, or external bridge.
 const val SELECTION_SCRIPT = """(function(){const root=document.getElementById('source'),s=window.getSelection();if(!root||!s||!s.rangeCount||s.isCollapsed)return null;const r=s.getRangeAt(0);if(!root.contains(r.startContainer)||!root.contains(r.endContainer))return null;const a=document.createRange();a.selectNodeContents(root);a.setEnd(r.startContainer,r.startOffset);const b=document.createRange();b.selectNodeContents(root);b.setEnd(r.endContainer,r.endOffset);return JSON.stringify({start:a.toString().length,end:b.toString().length,text:r.toString()});})()"""
 fun selection(chapter:Chapter,json:String):SourceAnchor {
  val value=JSONObject(json);val start=value.getInt("start");val end=value.getInt("end")
  val anchor=selectionAnchor(chapter,start,end)
  require(anchor.source==value.getString("text")){"选区与当前工作稿不一致，请重新选择；未修改正文"}
  return anchor
 }
}
