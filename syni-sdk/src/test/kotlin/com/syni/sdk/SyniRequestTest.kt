package com.syni.sdk

import com.syni.sdk.core.GenerationOptions
import com.syni.sdk.core.SyniInput
import com.syni.sdk.core.SyniRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SyniRequestTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `SyniInput text factory creates correct input`() {
        val input = SyniInput.text("Hello world")

        assertEquals("Hello world", input.text)
        assertTrue(input.context.isEmpty())
    }

    @Test
    fun `SyniInput withContext factory creates correct input`() {
        val input = SyniInput.withContext(
            "Hello",
            "key1" to "value1",
            "key2" to "value2"
        )

        assertEquals("Hello", input.text)
        assertEquals(2, input.context.size)
        assertEquals("value1", input.context["key1"])
        assertEquals("value2", input.context["key2"])
    }

    @Test
    fun `SyniRequest serializes correctly`() {
        val request = SyniRequest(
            personaId = "keyboard.v1",
            input = SyniInput.text("Test input"),
            options = GenerationOptions(maxTokens = 100),
            requestId = "test-123"
        )

        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<SyniRequest>(serialized)

        assertEquals(request.personaId, deserialized.personaId)
        assertEquals(request.input.text, deserialized.input.text)
        assertEquals(request.options.maxTokens, deserialized.options.maxTokens)
        assertEquals(request.requestId, deserialized.requestId)
    }

    @Test
    fun `GenerationOptions validates maxTokens`() {
        val validOptions = GenerationOptions(maxTokens = 100)
        assertEquals(100, validOptions.maxTokens)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `GenerationOptions rejects negative maxTokens`() {
        GenerationOptions(maxTokens = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `GenerationOptions rejects zero maxTokens`() {
        GenerationOptions(maxTokens = 0)
    }

    @Test
    fun `GenerationOptions validates temperature range`() {
        val validOptions = GenerationOptions(temperature = 1.5f)
        assertEquals(1.5f, validOptions.temperature)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `GenerationOptions rejects temperature above 2`() {
        GenerationOptions(temperature = 2.5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `GenerationOptions rejects negative temperature`() {
        GenerationOptions(temperature = -0.5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `GenerationOptions rejects both localOnly and cloudOnly`() {
        GenerationOptions(localOnly = true, cloudOnly = true)
    }

    @Test
    fun `GenerationOptions DEFAULT preset is valid`() {
        val options = GenerationOptions.DEFAULT
        assertNull(options.maxTokens)
        assertNull(options.temperature)
        assertFalse(options.localOnly)
        assertFalse(options.cloudOnly)
    }

    @Test
    fun `GenerationOptions KEYBOARD preset has correct values`() {
        val options = GenerationOptions.KEYBOARD
        assertEquals(50, options.maxTokens)
        assertEquals(0.7f, options.temperature)
        assertEquals(150L, options.timeoutMs)
        assertTrue(options.localOnly)
    }
}
