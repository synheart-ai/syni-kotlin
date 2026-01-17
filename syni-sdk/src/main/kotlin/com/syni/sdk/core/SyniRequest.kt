package com.syni.sdk.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Request to generate output from a Syni persona.
 */
@Serializable
data class SyniRequest(
    /** The persona ID to use for generation. */
    val personaId: String,

    /** The input for generation. */
    val input: SyniInput,

    /** Optional generation options. */
    val options: GenerationOptions = GenerationOptions(),

    /** Unique request identifier. Auto-generated if not provided. */
    val requestId: String = UUID.randomUUID().toString()
)

/**
 * Input data for generation.
 */
@Serializable
data class SyniInput(
    /** The primary text input/prompt. */
    val text: String,

    /** Additional context as key-value pairs. */
    val context: Map<String, String> = emptyMap()
) {
    companion object {
        /** Create input with just text. */
        fun text(value: String): SyniInput = SyniInput(text = value)

        /** Create input with text and context. */
        fun withContext(text: String, vararg pairs: Pair<String, String>): SyniInput =
            SyniInput(text = text, context = pairs.toMap())
    }
}

/**
 * Options controlling generation behavior.
 */
@Serializable
data class GenerationOptions(
    /** Maximum tokens to generate. Overrides persona default. */
    val maxTokens: Int? = null,

    /** Sampling temperature. Overrides persona default. */
    val temperature: Float? = null,

    /** Force local-only generation (no cloud fallback). */
    val localOnly: Boolean = false,

    /** Force cloud-only generation (skip local engine). */
    val cloudOnly: Boolean = false,

    /** Request timeout in milliseconds. Overrides persona budget. */
    val timeoutMs: Long? = null,

    /** Custom system prompt override. */
    @SerialName("system_prompt")
    val systemPrompt: String? = null
) {
    init {
        require(!(localOnly && cloudOnly)) {
            "Cannot set both localOnly and cloudOnly"
        }
        maxTokens?.let { require(it > 0) { "maxTokens must be positive" } }
        temperature?.let { require(it in 0f..2f) { "temperature must be between 0 and 2" } }
        timeoutMs?.let { require(it > 0) { "timeoutMs must be positive" } }
    }

    companion object {
        /** Default options. */
        val DEFAULT = GenerationOptions()

        /** Local-only generation. */
        val LOCAL_ONLY = GenerationOptions(localOnly = true)

        /** Cloud-only generation. */
        val CLOUD_ONLY = GenerationOptions(cloudOnly = true)

        /** Fast generation for keyboard input. */
        val KEYBOARD = GenerationOptions(
            maxTokens = 50,
            temperature = 0.7f,
            timeoutMs = 150,
            localOnly = true
        )
    }
}
