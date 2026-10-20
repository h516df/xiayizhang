package cn.privatereader

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID

/** Import snapshots are inert, immutable reference material, never executable configuration. */
enum class ImportedKind { CHARACTER, LOREBOOK }
data class ImportedEntry(
    val id: String,
    val externalId: String?,
    val title: String,
    val content: String,
    val enabled: Boolean,
    val keys: List<String>,
    val secondaryKeys: List<String>,
    val constant: Boolean,
    val insertionOrder: Int,
    val unsupportedFeatures: List<String>
)
data class ImportedResource(
    val id: String,
    val kind: ImportedKind,
    val title: String,
    val format: String,
    val rawJson: String,
    val sourceName: String,
    val sha256: String,
    val warnings: List<String>,
    val entries: List<ImportedEntry>,
    val characterText: String
)

object ReferenceLimits {
    const val MAX_JSON_BYTES = 4 * 1024 * 1024
    const val MAX_FIELD_BYTES = 128 * 1024
    const val MAX_ENTRIES = 2000
    const val MAX_RESOURCES = 200
    const val MAX_TOTAL_SOURCE_BYTES = 16 * 1024 * 1024
}

fun parseReference(name: String, bytes: ByteArray): ImportedResource {
    require(name.length <= 500 && name.none { it.isISOControl() }) { "来源文件名过长或包含控制字符" }
    val extension = name.substringAfterLast('.', "").lowercase()
    require(extension !in setOf("png", "charx") && !bytes.startsWithBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)) && !bytes.startsWithBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) {
        "本切片只支持 JSON 基础资料导入，尚不支持 PNG 角色卡或 CHARX"
    }
    require(bytes.isNotEmpty() && bytes.size <= ReferenceLimits.MAX_JSON_BYTES) { "资料 JSON 必须非空且不超过 4 MiB；未截断导入" }
    val raw = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) { throw IllegalArgumentException("资料必须使用有效 UTF-8 编码") }
    ReferenceSyntax(raw).validate()
    require(raw.trimStart().startsWith('{')) { "资料 JSON 根节点必须是对象" }
    val root = JSONObject(raw)
    val warnings = mutableListOf("仅导入基础资料，不执行提示词覆盖、脚本、正则、模板、网络资源或自动触发；不代表完整酒馆运行兼容。")
    val spec = root.optionalString("spec", "根节点")
    val kind: ImportedKind
    val title: String
    val format: String
    val characterText: String
    val entries: List<ImportedEntry>
    if (spec.isNotEmpty()) {
        require(spec == "chara_card_v2" || spec == "chara_card_v3") { "不支持的角色卡 spec：仅支持 V2/V3 基础字段" }
        kind = ImportedKind.CHARACTER
        val version = root.requiredString("spec_version", "根节点")
        require(version.isNotBlank() && version.length <= 100) { "spec_version 无效" }
        val expected = if (spec == "chara_card_v2") "2.0" else "3.0"
        format = if (spec == "chara_card_v2") "CCV2" else "CCV3"
        if (version != expected) warnings += "声明版本 $version 与已识别版本 $expected 不同：仅按已知基础字段预览，未知字段保留但不执行。"
        val data = root.requiredObject("data", "根节点")
        title = data.requiredString("name", "data").also { require(it.isNotBlank()) { "data.name 不能为空" } }
        val description = data.requiredString("description", "data")
        val personality = data.requiredString("personality", "data")
        val scenario = data.requiredString("scenario", "data")
        data.requiredString("first_mes", "data")
        data.requiredString("mes_example", "data")
        listOf("creator_notes", "system_prompt", "post_history_instructions", "creator", "character_version", "nickname").forEach { data.optionalString(it, "data") }
        listOf("alternate_greetings", "tags", "group_only_greetings", "source").forEach { data.stringList(it, "data") }
        data.optionalObject("extensions", "data")
        if (data.has("assets")) data.requiredArray("assets", "data").let { assets ->
            for (i in 0 until assets.length()) {
                val a = assets.get(i)
                require(a is JSONObject) { "data.assets[$i] 必须是对象" }
                listOf("type", "uri", "name", "ext").forEach { a.optionalString(it, "data.assets[$i]") }
            }
        }
        characterText = listOf("描述" to description, "性格" to personality, "场景" to scenario).filter { it.second.isNotBlank() }.joinToString("\n") { "${it.first}：${it.second}" }
        warnings += "人物上下文仅包含描述、性格、场景；开场白、示例对话、作者信息和系统提示只保留于原始 JSON。"
        entries = data.optionalObject("character_book", "data")?.let { parseCharacterBook(it, "data.character_book") } ?: emptyList()
    } else {
        kind = ImportedKind.LOREBOOK
        characterText = ""
        title = root.optionalString("name", "根节点").ifBlank { "未命名世界书" }
        root.optionalObject("extensions", "根节点")
        when (val sourceEntries = root.opt("entries")) {
            is JSONArray -> { format = "CharacterBook"; entries = parseCharacterBook(root, "根节点") }
            is JSONObject -> { format = "SillyTavernWorldInfo"; entries = parseWorldInfo(sourceEntries) }
            else -> throw IllegalArgumentException("世界书 entries 必须是 CharacterBook 数组或 SillyTavern 对象")
        }
    }
    if (entries.any { !it.enabled }) warnings += "包含已禁用条目：即使选择，也不得加入生成上下文。"
    if (entries.any { it.unsupportedFeatures.isNotEmpty() }) warnings += "条目的位置、角色、概率、正则或其他触发选项仅保留，不执行。"
    return ImportedResource(UUID.randomUUID().toString(), kind, title, format, raw, name, referenceSha(bytes), warnings, entries, characterText)
}

private fun parseCharacterBook(book: JSONObject, path: String): List<ImportedEntry> {
    book.optionalString("name", path)
    book.optionalString("description", path)
    book.optionalObject("extensions", path)
    val list = book.requiredArray("entries", path)
    require(list.length() <= ReferenceLimits.MAX_ENTRIES) { "$path.entries 超过 2000 条，未截断导入" }
    return (0 until list.length()).map { i ->
        val p = "$path.entries[$i]"
        val e = list.get(i)
        require(e is JSONObject) { "$p 必须是对象" }
        val keys = e.stringList("keys", p, required = true)
        val content = e.requiredString("content", p)
        val enabled = e.boolean("enabled", p, required = true)
        val order = e.integer("insertion_order", p, required = true)
        e.optionalObject("extensions", p)
        val external = e.externalId("id", p)
        val title = e.optionalString("name", p).ifBlank { e.optionalString("comment", p) }.ifBlank { keys.firstOrNull().orEmpty() }.ifBlank { "条目 ${i + 1}" }
        // Validate known optional types even when their runtime meaning is unsupported.
        listOf("case_sensitive", "selective", "use_regex").forEach { e.boolean(it, p) }
        if (e.has("position")) e.optionalString("position", p)
        if (e.has("decorators")) e.stringList("decorators", p)
        ImportedEntry(UUID.randomUUID().toString(), external, title, content, enabled, keys,
            e.stringList("secondary_keys", p), e.boolean("constant", p), order,
            listOf("case_sensitive", "selective", "position", "use_regex", "decorators", "extensions").filter { e.has(it) })
    }
}

private fun parseWorldInfo(entries: JSONObject): List<ImportedEntry> {
    require(entries.length() <= ReferenceLimits.MAX_ENTRIES) { "entries 超过 2000 条，未截断导入" }
    return entries.keys().asSequence().toList().sorted().mapIndexed { index, key ->
        val p = "entries[$key]"
        val e = entries.get(key)
        require(e is JSONObject) { "$p 必须是对象" }
        val content = e.requiredString("content", p)
        val external = e.externalId("uid", p) ?: key
        val title = e.optionalString("comment", p).ifBlank { "条目 ${index + 1}" }
        val knownTypes = listOf("disable", "constant", "selective", "useProbability", "excludeRecursion", "preventRecursion", "delayUntilRecursion")
        // delayUntilRecursion may be bool or a nonnegative level in some ST exports; preserved only.
        knownTypes.filter { it != "delayUntilRecursion" && (!e.isNull(it) || it in setOf("disable", "constant")) }.forEach { e.boolean(it, p) }
        if (e.has("delayUntilRecursion") && !e.isNull("delayUntilRecursion")) { val v = e.get("delayUntilRecursion"); require(v is Boolean || v is Number && v.toString().toIntOrNull()?.let { it >= 0 } == true) { "$p.delayUntilRecursion 必须是布尔值或非负整数" } }
        listOf("order", "position", "depth", "probability", "groupWeight", "scanDepth", "selectiveLogic").filter { e.has(it) && (!e.isNull(it) || it == "order") }.forEach { e.integer(it, p) }
        if (e.has("role") && !e.isNull("role")) { val value = e.get("role"); require(value is String || value is Number && value.toString().toIntOrNull() != null) { "$p.role 必须是字符串或整数" } }
        val supported = setOf("uid", "key", "keysecondary", "comment", "content", "disable", "constant", "order")
        ImportedEntry(UUID.randomUUID().toString(), external, title, content, !e.boolean("disable", p),
            e.stringList("key", p), e.stringList("keysecondary", p), e.boolean("constant", p), e.integer("order", p),
            e.keys().asSequence().filter { it !in supported }.toList().sorted())
    }
}

/** Minimal persistence: normalized fields are rebuilt from the immutable source, not trusted from backup. */
object ResourceJson {
    fun encode(resources: List<ImportedResource>): JSONArray {
        validate(resources)
        return JSONArray(resources.map { r -> JSONObject().put("id", r.id).put("sourceName", r.sourceName).put("sha256", r.sha256).put("rawJson", r.rawJson).put("entryIds", JSONArray(r.entries.map { it.id })) })
    }
    fun decode(array: JSONArray?): List<ImportedResource> {
        if (array == null) return emptyList()
        require(array.length() <= ReferenceLimits.MAX_RESOURCES) { "导入资料数量超限" }
        var total = 0L
        val resources = (0 until array.length()).map { i ->
            val j = array.get(i)
            require(j is JSONObject) { "resources[$i] 必须是对象" }
            val raw = j.requiredString("rawJson", "resources[$i]", source = true)
            val bytes = raw.toByteArray(Charsets.UTF_8)
            total += bytes.size
            require(total <= ReferenceLimits.MAX_TOTAL_SOURCE_BYTES) { "导入资料原始内容合计超过 16 MiB" }
            val expectedSha = j.requiredString("sha256", "resources[$i]")
            require(expectedSha == referenceSha(bytes)) { "导入资料来源校验失败" }
            val parsed = parseReference(j.requiredString("sourceName", "resources[$i]"), bytes)
            val ids = j.stringList("entryIds", "resources[$i]", required = true)
            require(ids.size == parsed.entries.size) { "导入条目编号与来源不一致" }
            parsed.copy(id = j.requiredString("id", "resources[$i]"), entries = parsed.entries.mapIndexed { n, e -> e.copy(id = ids[n]) })
        }
        validate(resources)
        return resources
    }
    fun validate(resources: List<ImportedResource>) {
        require(resources.size <= ReferenceLimits.MAX_RESOURCES) { "导入资料数量超过 200 份" }
        val ids = mutableSetOf<String>()
        var total = 0L
        resources.forEach { r ->
            require(validUuid(r.id) && ids.add(r.id)) { "资料编号无效或重复" }
            val bytes = r.rawJson.toByteArray(Charsets.UTF_8)
            require(bytes.isNotEmpty() && bytes.size <= ReferenceLimits.MAX_JSON_BYTES) { "单份资料 JSON 超过 4 MiB" }
            total += bytes.size
            require(total <= ReferenceLimits.MAX_TOTAL_SOURCE_BYTES) { "导入资料原始内容合计超过 16 MiB" }
            require(r.sha256 == referenceSha(bytes)) { "导入资料来源校验失败" }
            require(r.sourceName.length <= 500 && r.sourceName.none { it.isISOControl() })
            require(r.entries.size <= ReferenceLimits.MAX_ENTRIES)
            r.entries.forEach { require(validUuid(it.id) && ids.add(it.id)) { "资料条目编号无效或重复" } }
        }
    }
}

private fun validUuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
private fun referenceSha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun ByteArray.startsWithBytes(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
private fun JSONObject.requiredString(key: String, path: String, source: Boolean = false): String {
    val v = opt(key)
    require(v is String) { "$path.$key 必须是字符串" }
    require(v.toByteArray(Charsets.UTF_8).size <= if (source) ReferenceLimits.MAX_JSON_BYTES else ReferenceLimits.MAX_FIELD_BYTES) { "$path.$key 超过字段限制，未截断导入" }
    return v
}
private fun JSONObject.optionalString(key: String, path: String): String = if (!has(key)) "" else requiredString(key, path)
private fun JSONObject.requiredObject(key: String, path: String): JSONObject = opt(key).also { require(it is JSONObject) { "$path.$key 必须是对象" } } as JSONObject
private fun JSONObject.optionalObject(key: String, path: String): JSONObject? = if (!has(key)) null else requiredObject(key, path)
private fun JSONObject.requiredArray(key: String, path: String): JSONArray = opt(key).also { require(it is JSONArray) { "$path.$key 必须是数组" } } as JSONArray
private fun JSONObject.stringList(key: String, path: String, required: Boolean = false): List<String> {
    if (!has(key) && !required) return emptyList()
    val a = requiredArray(key, path)
    require(a.length() <= ReferenceLimits.MAX_ENTRIES) { "$path.$key 数量超过限制" }
    return (0 until a.length()).map { i -> val v = a.get(i); require(v is String) { "$path.$key[$i] 必须是字符串" }; require(v.toByteArray(Charsets.UTF_8).size <= ReferenceLimits.MAX_FIELD_BYTES); v }
}
private fun JSONObject.boolean(key: String, path: String, required: Boolean = false): Boolean {
    if (!has(key) && !required) return false
    val v = opt(key)
    require(v is Boolean) { "$path.$key 必须是布尔值" }
    return v
}
private fun JSONObject.integer(key: String, path: String, required: Boolean = false): Int {
    if (!has(key) && !required) return 0
    val v = opt(key)
    require(v is Number && Regex("-?(0|[1-9][0-9]*)").matches(v.toString())) { "$path.$key 必须是整数" }
    return v.toString().toIntOrNull() ?: throw IllegalArgumentException("$path.$key 超过整数范围")
}
private fun JSONObject.externalId(key: String, path: String): String? {
    if (!has(key)) return null
    val v = opt(key)
    require(v is String || v is Number && v.toString().toLongOrNull() != null) { "$path.$key 必须是字符串或整数" }
    return v.toString()
}

/** Strict syntax/structure pass before org.json: no coercion, duplicate keys or non-finite numbers. */
private class ReferenceSyntax(private val text: String) {
    private var at = 0
    private var nodes = 0
    fun validate() { value(0); space(); require(at == text.length) { "JSON 存在多余内容" } }
    private fun space() { while (at < text.length && text[at] in " \n\r\t") at++ }
    private fun take(c: Char): Boolean { space(); return if (at < text.length && text[at] == c) { at++; true } else false }
    private fun string(): String {
        space(); require(at < text.length && text[at++] == '"') { "JSON 键/字符串格式错误" }
        val start = at - 1
        while (at < text.length) {
            val c = text[at++]
            if (c == '"') {
                val result = JSONTokener(text.substring(start, at)).nextValue() as String
                require(result.toByteArray(Charsets.UTF_8).size <= ReferenceLimits.MAX_FIELD_BYTES) { "JSON 字段超过 128 KiB，未截断导入" }
                var i = 0
                while (i < result.length) { val ch = result[i]; if (ch.isHighSurrogate()) { require(i + 1 < result.length && result[i + 1].isLowSurrogate()) { "JSON 包含不完整 Unicode 字符" }; i += 2 } else { require(!ch.isLowSurrogate()) { "JSON 包含不完整 Unicode 字符" }; i++ } }
                return result
            }
            require(c.code >= 32) { "JSON 字符串含未转义控制字符" }
            if (c == '\\') {
                require(at < text.length) { "JSON 转义不完整" }
                when (text[at++]) {
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                    'u' -> repeat(4) { require(at < text.length && text[at++] in "0123456789abcdefABCDEF") { "JSON Unicode 转义无效" } }
                    else -> throw IllegalArgumentException("JSON 转义无效")
                }
            }
        }
        throw IllegalArgumentException("JSON 字符串未结束")
    }
    private fun value(depth: Int) {
        require(depth <= 32) { "JSON 嵌套超过 32 层" }
        require(++nodes <= 100000) { "JSON 结构节点过多" }
        space(); require(at < text.length) { "JSON 不完整" }
        when (text[at]) {
            '{' -> { at++; val keys = HashSet<String>(); if (!take('}')) while (true) { val key = string(); require(keys.add(key)) { "JSON 包含重复键" }; require(take(':')) { "JSON 缺少冒号" }; value(depth + 1); if (take('}')) break; require(take(',')) { "JSON 对象分隔错误" } } }
            '[' -> { at++; if (!take(']')) while (true) { value(depth + 1); if (take(']')) break; require(take(',')) { "JSON 数组分隔错误" } } }
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> {
                val start = at
                while (at < text.length && text[at] in "-+0123456789.eE") at++
                val number = text.substring(start, at)
                require(number.length in 1..64 && Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").matches(number)) { "JSON 数字格式无效或过长" }
                require(number.toDoubleOrNull()?.isFinite() == true) { "JSON 数字不是有限值" }
                if (number.none { it in ".eE" }) require(number.toLongOrNull() != null) { "JSON 整数超过 64 位范围" }
            }
        }
    }
    private fun literal(word: String) { require(text.startsWith(word, at)) { "JSON 值无效" }; at += word.length }
}
