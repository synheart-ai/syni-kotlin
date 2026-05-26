package ai.synheart.syni

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyniSpecPersonaTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(s: String) =
        json.parseToJsonElement(s) as JsonObject

    @Test fun `fromJson maps required fields`() {
        val j = parse("""
            {"id":"focus.coach.v1","name":"Coach","system_prompt":"sp","output_schema_id":"coach.response.v1"}
        """.trimIndent())
        val p = SyniSpecPersona.fromJson(j)
        assertEquals("focus.coach.v1", p.id)
        assertEquals("Coach", p.displayName)
        assertEquals("sp", p.systemPrompt)
        assertEquals("coach", p.responseSchemaId) // stripped of .response.vN
    }

    @Test fun `fromJson throws on missing fields`() {
        val j = parse("""{"id":"x"}""")
        assertThrows(SyniSpecPersonaException::class.java) {
            SyniSpecPersona.fromJson(j)
        }
    }

    @Test fun `normalizes schema id`() {
        assertEquals("coach", SyniSpecPersona.normalizeOutputSchema("coach.response.v1"))
        assertEquals("chat", SyniSpecPersona.normalizeOutputSchema("chat"))
        assertEquals("suggestions", SyniSpecPersona.normalizeOutputSchema("SUGGESTIONS.response.v2"))
    }
}
