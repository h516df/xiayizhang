package cn.privatereader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

class AiTest {
    private fun response(content: String, finish: String = "stop"): String = JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("finish_reason", finish)
            .put("message", JSONObject().put("content", content)))).toString()

    @Test fun parsesTwoCandidates() {
        assertEquals(listOf("第一段。", "第二段。"), OpenAiCompatibleAdapter.parseResponse(response("{\"candidates\":[\"第一段。\",\"第二段。\"]}")))
    }
    @Test fun rejectsMalformedOrUnsafeResponses() {
        listOf("{\"candidates\":[\"one\"]}", "{\"candidates\":[1,2]}", "{\"candidates\":[\"same\",\"same\"]}", "{\"candidates\":[\"one\",\"two\"]} trailing", "```json\n{}\n```", "{\"candidates\":[\"one\",\"two\"],\"extra\":true}").forEach {
            assertThrows(IOException::class.java) { OpenAiCompatibleAdapter.parseResponse(response(it)) }
        }
        assertThrows(IOException::class.java) { OpenAiCompatibleAdapter.parseResponse(response("{\"candidates\":[\"one\",\"two\"]}", "length")) }
    }
    @Test fun rejectsLenientJsonExtensionsAndDuplicateKeys() {
        listOf(
            "{candidates:['one','two']}",
            "{\"candidates\":[\"one\",\"two\",]}",
            "{\"candidates\":[\"one\",\"two\"],}",
            "{\"candidates\":[\"one\",\"two\"],\"candidates\":[\"three\",\"four\"]}",
            "{\"candidates\":[\"one\",\"two\"]} /* comment */"
        ).forEach { candidate ->
            assertThrows(IOException::class.java) { OpenAiCompatibleAdapter.parseResponse(response(candidate)) }
        }
    }
    @Test fun rejectsOversizedContext() {
        assertThrows(IllegalArgumentException::class.java) { RewriteRequest("a".repeat(12000), "b", "", "").validate() }
        RewriteRequest("a".repeat(12000), "", "", "").validate()
    }
    @Test fun validatesEndpointWithoutNetwork() {
        ProviderConfig("https://api.example.com/v1/chat/completions", "chosen-model").validate()
        listOf("http://api.example.com", "https://user:pass@api.example.com", "https://localhost/v1", "https://a.local/v1", "https://api.example.com/?key=abc", "https://api.example.com/#fragment", "https://api.example.com:8080/v1").forEach {
            assertThrows(IllegalArgumentException::class.java) { ProviderConfig(it, "model").validate() }
        }
    }
    @Test fun blocksPrivateAddresses() {
        listOf("127.0.0.1", "10.0.0.1", "192.168.0.1", "172.16.0.1", "169.254.169.254", "100.64.0.1", "::1", "fc00::1").forEach {
            assertFalse(it, OpenAiCompatibleAdapter.isPublicAddress(InetAddress.getByName(it)))
        }
        assertTrue(OpenAiCompatibleAdapter.isPublicAddress(InetAddress.getByName("8.8.8.8")))
    }
    @Test fun bodyContainsOnlyExplicitContextAndNoKey() {
        val raw = OpenAiCompatibleAdapter.buildBody(ProviderConfig("https://api.example.com/v1/chat/completions", "chosen"), RewriteRequest("selected", "style", "facts", "instruction"))
        val root = JSONObject(raw)
        val selected = JSONObject(root.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(4, selected.length())
        assertEquals("selected", selected.getString("passage"))
        assertEquals("chosen", root.getString("model"))
        assertFalse(root.getBoolean("stream"))
        assertFalse(raw.contains("apiKey"))
    }
}
