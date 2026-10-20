package dev.h516.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdatePublishedCatalogTest {
    private fun release(version: String, draft: Boolean = false, preview: Boolean = false) = JSONObject()
        .put("tag_name", "v" + version).put("draft", draft).put("prerelease", preview)
        .put("assets", JSONArray().put(JSONObject().put("id", 1).put("size", 200)
            .put("name", UpdateConfig.notesName(version))))

    @Test fun higherDraftAndPreviewNeverDisplaceThePublishedVersion() {
        val data = JSONArray().put(release("0.3")).put(release("0.9", draft = true))
            .put(release("0.8", preview = true)).put(release("0.7", draft = true, preview = true))
        val published = UpdateCatalog.parse(data.toString())
        assertEquals(listOf("0.3"), published.map { it.version })
        assertEquals("0.3", UpdateCatalog.newest(published)?.version)
    }

    @Test fun onlyUnpublishedEntriesDoNotOfferAnUpdate() {
        val data = JSONArray().put(release("0.9", draft = true)).put(release("0.8", preview = true))
        assertTrue(UpdateCatalog.parse(data.toString()).isEmpty())
        assertNull(UpdateCatalog.newest(UpdateCatalog.parse(data.toString())))
    }
}
