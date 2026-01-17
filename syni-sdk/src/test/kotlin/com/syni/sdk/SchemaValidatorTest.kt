package com.syni.sdk

import com.syni.sdk.schema.JSONSchema
import com.syni.sdk.schema.JSONType
import com.syni.sdk.schema.SchemaProperty
import com.syni.sdk.schema.SchemaValidator
import com.syni.sdk.schema.ValidationResult
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import android.content.Context

@RunWith(MockitoJUnitRunner::class)
class SchemaValidatorTest {

    @Mock
    private lateinit var mockContext: Context

    private lateinit var validator: SchemaValidator

    @Before
    fun setup() {
        validator = SchemaValidator(mockContext, null, null)
    }

    @Test
    fun `validates simple object with required fields`() {
        val jsonText = """{"suggestions":["hello","world"]}"""
        val result = validator.validate(jsonText, "keyboard.v1")

        assertTrue("Should be valid: ${result.errors}", result.isValid)
    }

    @Test
    fun `rejects missing required field`() {
        val jsonText = """{"confidence":0.5}"""
        val result = validator.validate(jsonText, "keyboard.v1")

        assertFalse("Should reject missing suggestions", result.isValid)
        assertTrue(result.errors.any { it.contains("suggestions") })
    }

    @Test
    fun `validates array constraints`() {
        // Empty array should be valid (minItems = 0)
        val emptyResult = validator.validate("""{"suggestions":[]}""", "keyboard.v1")
        assertTrue("Empty array should be valid", emptyResult.isValid)

        // Array with items should be valid
        val withItemsResult = validator.validate("""{"suggestions":["one","two"]}""", "keyboard.v1")
        assertTrue("Array with items should be valid", withItemsResult.isValid)
    }

    @Test
    fun `validates number constraints`() {
        // Valid confidence
        val validResult = validator.validate(
            """{"suggestions":[],"confidence":0.5}""",
            "keyboard.v1"
        )
        assertTrue("Valid confidence should pass", validResult.isValid)
    }

    @Test
    fun `repairs malformed JSON`() {
        // Single quotes
        val repaired1 = validator.attemptJSONRepair("{'suggestions': ['hello']}")
        assertNotNull("Should repair single quotes", repaired1)

        // Unquoted keys
        val repaired2 = validator.attemptJSONRepair("{suggestions: [\"hello\"]}")
        assertNotNull("Should repair unquoted keys", repaired2)

        // Trailing comma
        val repaired3 = validator.attemptJSONRepair("""{"suggestions": ["hello",]}""")
        assertNotNull("Should repair trailing comma", repaired3)
    }

    @Test
    fun `extracts JSON from surrounding text`() {
        val textWithJson = """
            Here is the response:
            {"suggestions": ["hello", "world"]}
            That was the output.
        """.trimIndent()

        val extracted = validator.attemptJSONRepair(textWithJson)
        assertNotNull("Should extract JSON from text", extracted)
        assertTrue(extracted!!.contains("suggestions"))
    }

    @Test
    fun `creates valid fallback JSON`() {
        val fallback = validator.createFallbackJSON("keyboard.v1")
        val fallbackStr = fallback.toString()

        // Validate the fallback against the schema
        val result = validator.validate(fallbackStr, "keyboard.v1")
        assertTrue("Fallback should be valid: ${result.errors}", result.isValid)
    }

    @Test
    fun `built-in schemas are available`() {
        assertTrue(validator.availableSchemas().contains("keyboard.v1"))
        assertTrue(validator.availableSchemas().contains("life.coach.v1"))
    }

    @Test
    fun `built-in grammars are available`() {
        assertTrue(validator.availableGrammars().contains("keyboard.v1"))
        assertTrue(validator.availableGrammars().contains("life.coach.v1"))
    }

    @Test
    fun `registers custom schema`() {
        val customSchema = JSONSchema(
            id = "custom.test.v1",
            version = "1.0",
            description = "Test schema",
            rootProperty = SchemaProperty(
                type = JSONType.OBJECT,
                properties = mapOf(
                    "message" to SchemaProperty(type = JSONType.STRING)
                ),
                required = listOf("message")
            )
        )

        validator.registerSchema(customSchema)

        assertTrue(validator.availableSchemas().contains("custom.test.v1"))

        val result = validator.validate("""{"message":"hello"}""", "custom.test.v1")
        assertTrue(result.isValid)
    }

    @Test
    fun `returns error for unknown schema`() {
        val result = validator.validate("{}", "unknown.schema")

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("not found") })
    }

    @Test
    fun `validates life coach schema`() {
        val validJson = """{
            "advice": "Take a deep breath and focus on one thing at a time.",
            "sentiment": "supportive",
            "follow_up_questions": ["How are you feeling now?"],
            "confidence": 0.8
        }"""

        val result = validator.validate(validJson, "life.coach.v1")
        assertTrue("Life coach response should be valid: ${result.errors}", result.isValid)
    }

    @Test
    fun `validates enum values`() {
        // Valid sentiment
        val validResult = validator.validate(
            """{"advice":"test","sentiment":"positive"}""",
            "life.coach.v1"
        )
        assertTrue(validResult.isValid)

        // Invalid sentiment
        val invalidResult = validator.validate(
            """{"advice":"test","sentiment":"angry"}""",
            "life.coach.v1"
        )
        assertFalse("Invalid enum should fail", invalidResult.isValid)
    }
}
