package ai.synheart.syni

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyniChatResponseTest {

    @Test fun `parses coach envelope`() {
        val raw = """
            {"type":"coach","data":{"message":"breathe","suggestions":[{"text":"in"},{"text":"out"}]}}
        """.trimIndent()
        val r = SyniChatResponse.fromRuntimeJson(raw, personaId = "p", runtimeVersion = "v")
        assertEquals(SyniResponseKind.COACH, r.kind)
        assertEquals("breathe", r.message)
        assertEquals(listOf("in", "out"), r.suggestions)
        assertEquals("breathe", r.displayText)
    }

    @Test fun `parses chat envelope`() {
        val raw = """{"type":"chat","data":{"message":"hello"}}"""
        val r = SyniChatResponse.fromRuntimeJson(raw, personaId = "p", runtimeVersion = "v")
        assertEquals(SyniResponseKind.CHAT, r.kind)
        assertEquals("hello", r.message)
        assertTrue(r.suggestions.isEmpty())
    }

    @Test fun `parses suggestions envelope`() {
        val raw = """{"type":"suggestions","data":{"suggestions":[{"text":"a"},{"text":"b"}]}}"""
        val r = SyniChatResponse.fromRuntimeJson(raw, personaId = "p", runtimeVersion = "v")
        assertEquals(SyniResponseKind.SUGGESTIONS, r.kind)
        assertNull(r.message)
        assertEquals(listOf("a", "b"), r.suggestions)
        assertEquals("a", r.displayText)
    }

    @Test fun `falls back to unknown on malformed`() {
        val r = SyniChatResponse.fromRuntimeJson("not json", personaId = "p", runtimeVersion = "v")
        assertEquals(SyniResponseKind.UNKNOWN, r.kind)
        assertEquals("Syni had no response.", r.displayText)
    }

    @Test fun `cloud reply builds chat response`() {
        val r = SyniChatResponse.fromCloudReply("hi there", personaId = "p", runtimeVersion = "cloud")
        assertEquals(SyniResponseKind.CHAT, r.kind)
        assertEquals("hi there", r.message)
        assertEquals("hi there", r.displayText)
    }
}
