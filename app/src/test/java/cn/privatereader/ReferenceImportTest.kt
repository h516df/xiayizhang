package cn.privatereader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReferenceImportTest {
    private fun fixture(name: String): ByteArray = javaClass.getResourceAsStream("/reference-import/$name")!!.use { it.readBytes() }
    private fun parsed(name: String) = parseReference(name, fixture(name))
    private fun bytes(json: JSONObject) = json.toString().toByteArray(Charsets.UTF_8)
    private fun rejected(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test fun v2ImportsOnlyBasicCharacterMaterialAndRetainsUnknownSource() {
        val bytes = fixture("valid-v2.json")
        val r = parseReference("valid-v2.json", bytes)
        assertEquals(ImportedKind.CHARACTER, r.kind)
        assertEquals("CCV2", r.format)
        assertEquals("林舟", r.title)
        assertEquals(1, r.entries.size)
        assertTrue(r.characterText.contains("摆渡人"))
        assertFalse(r.characterText.contains("shell"))
        assertFalse(r.characterText.contains("船要开了"))
        assertFalse(r.characterText.contains("fixture"))
        assertArrayEquals(bytes, r.rawJson.toByteArray(Charsets.UTF_8))
        assertTrue(JSONObject(r.rawJson).getJSONObject("data").getJSONObject("extensions").has("example/opaque"))
        assertTrue(r.warnings.any { it.contains("不执行") })
    }
    @Test fun v3RetainsRemoteAssetsWithoutFetchingAndWarnsOnFutureVersion() {
        val r = parsed("valid-v3.json")
        assertEquals("CCV3", r.format)
        assertTrue(r.rawJson.contains("https://example.invalid/no-auto-fetch.png"))
        assertFalse(r.characterText.contains("example.invalid"))
        val future = JSONObject(r.rawJson).put("spec_version", "3.1")
        assertTrue(parseReference("future.json", bytes(future)).warnings.any { it.contains("3.1") && it.contains("未知字段") })
    }
    @Test fun standaloneBookArrayAndWorldInfoObjectAreDistinctSupportedFormats() {
        val book = parsed("valid-character-book.json")
        val world = parsed("valid-st-world.json")
        assertEquals(ImportedKind.LOREBOOK, book.kind)
        assertEquals("CharacterBook", book.format)
        assertEquals("SillyTavernWorldInfo", world.format)
        assertEquals(2, world.entries.size)
        val enabled = world.entries.single { it.externalId == "7" }
        assertEquals("雾港渡船", enabled.title)
        assertTrue(enabled.enabled)
        assertFalse(world.entries.single { it.externalId == "8" }.enabled)
        assertEquals(10, enabled.insertionOrder)
        assertEquals(listOf("雾港"), enabled.keys)
        assertTrue(enabled.unsupportedFeatures.contains("position"))
        assertTrue(world.warnings.any { it.contains("已禁用") })
    }
    @Test fun sameNameAndExternalIdsNeverBecomeInternalIdentity() {
        val a = parsed("valid-v2.json"); val b = parsed("valid-v2.json")
        assertNotEquals(a.id, b.id)
        assertNotEquals(a.entries[0].id, b.entries[0].id)
        assertEquals("7", a.entries[0].externalId)
        assertNotEquals("7", a.entries[0].id)
        assertEquals(a.sha256, b.sha256)
    }
    @Test fun roundTripPreservesIdsAndExactRawSource() {
        val all = listOf(parsed("valid-v2.json"), parsed("valid-v3.json"), parsed("valid-st-world.json"))
        assertEquals(all, ResourceJson.decode(JSONArray(ResourceJson.encode(all).toString())))
        assertTrue(ResourceJson.decode(null).isEmpty())
    }
    @Test fun backupCannotForgeNormalizedEnabledOrContent() {
        val original = parsed("valid-st-world.json")
        val stored = ResourceJson.encode(listOf(original))
        stored.getJSONObject(0).put("entries", JSONArray().put(JSONObject().put("enabled", true).put("content", "forged")))
        val restored = ResourceJson.decode(stored).single()
        assertEquals(original, restored)
        assertFalse(restored.entries.single { it.externalId == "8" }.enabled)
        stored.getJSONObject(0).put("rawJson", original.rawJson.replace("已禁用", "篡改"))
        rejected { ResourceJson.decode(stored) }
    }
    @Test fun wrongTypesAndDuplicateIncludingEscapedKeysAreRejected() {
        rejected { parsed("invalid-field-types.json") }
        rejected { parsed("invalid-duplicate-key.json") }
        rejected { parseReference("bad.json", "{\"entries\":{},\"entr\\u0069es\":{}}".toByteArray()) }
        val world = JSONObject(String(fixture("valid-st-world.json"), Charsets.UTF_8))
        world.getJSONObject("entries").getJSONObject("7").put("disable", "false")
        rejected { parseReference("world.json", bytes(world)) }
        val card = JSONObject(String(fixture("valid-v2.json"), Charsets.UTF_8))
        card.getJSONObject("data").put("character_book", "not an object")
        rejected { parseReference("card.json", bytes(card)) }
    }
    @Test fun strictUtf8SyntaxFiniteAndBoundedNumbers() {
        rejected { parseReference("bad.json", byteArrayOf(0xc3.toByte(), 0x28)) }
        listOf("NaN", "Infinity", "1e999", "999999999999999999999999999", "01", "+1").forEach { n ->
            rejected { parseReference("bad.json", "{\"entries\":{},\"unknown\":$n}".toByteArray()) }
        }
        rejected { parseReference("bad.json", "{'entries':{}}".toByteArray()) }
        rejected { parseReference("bad.json", "{\"entries\":{},}".toByteArray()) }
        rejected { parseReference("bad.json", "{\"entries\":{}} trailing".toByteArray()) }
        rejected { parseReference("bad.json", "{\"entries\":{},\"bad\":\"\\uD800\"}".toByteArray()) }
    }
    @Test fun totalFieldEntryAndDepthBudgetsRejectWithoutTruncation() {
        rejected { parseReference("big.json", ByteArray(ReferenceLimits.MAX_JSON_BYTES + 1) { ' '.code.toByte() }) }
        val field = JSONObject().put("entries", JSONObject()).put("unknown", "汉".repeat(ReferenceLimits.MAX_FIELD_BYTES / 3 + 1))
        rejected { parseReference("field.json", bytes(field)) }
        val entries = JSONArray(List(2001) { JSONObject().put("keys", JSONArray()).put("content", "").put("enabled", true).put("insertion_order", 0) })
        rejected { parseReference("entries.json", bytes(JSONObject().put("entries", entries))) }
        rejected { parseReference("deep.json", ("{\"entries\":{},\"unknown\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}").toByteArray()) }
    }
    @Test fun pngAndCharxAreExplicitlyUnsupportedEvenWithMisleadingNames() {
        rejected { parseReference("card.png", fixture("valid-v2.json")) }
        rejected { parseReference("card.charx", fixture("valid-v2.json")) }
        rejected { parseReference("card.json", byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)) }
        rejected { parseReference("card.json", byteArrayOf(0x50, 0x4b, 0x03, 0x04)) }
    }
    @Test fun backupIdsAndEntryCountsAreValidated() {
        val resource = parsed("valid-v2.json")
        rejected { ResourceJson.validate(listOf(resource, resource)) }
        rejected { ResourceJson.validate(listOf(resource.copy(id = "not-uuid"))) }
        val encoded = ResourceJson.encode(listOf(resource))
        encoded.getJSONObject(0).put("entryIds", JSONArray())
        rejected { ResourceJson.decode(encoded) }
        rejected { ResourceJson.validate(List(201) { resource.copy(id = java.util.UUID.randomUUID().toString()) }) }
    }
    @Test fun unsupportedRuntimeFieldsRemainInertIncludingNullableStDefaults() {
        val j = JSONObject(String(fixture("valid-st-world.json"), Charsets.UTF_8))
        val e = j.getJSONObject("entries").getJSONObject("7")
        e.put("scanDepth", JSONObject.NULL).put("role", "system").put("automationId", "run-shell").put("useProbability", true).put("probability", 50).put("delayUntilRecursion", 2)
        val r = parseReference("world.json", bytes(j))
        assertTrue(r.entries.single { it.externalId == "7" }.unsupportedFeatures.contains("automationId"))
        assertTrue(r.rawJson.contains("run-shell"))
        assertFalse(r.entries.any { it.content.contains("run-shell") })
    }
}
