package com.syni.sdk.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Response from a Syni generation request.
 */
@Serializable
data class SyniResponse(
    /** The request ID this response corresponds to. */
    val requestId: String,

    /** The generated output as validated JSON. */
    @SerialName("output")
    val outputJSON: JsonElement,

    /** Metadata about the generation. */
    val metadata: ResponseMetadata,

    /** Whether this response is a fallback due to generation failure. */
    val isFallback: Boolean = false
) {
    /**
     * Get the output as a raw JSON string.
     */
    fun outputString(): String = outputJSON.toString()
}

/**
 * Metadata about a generation response.
 */
@Serializable
data class ResponseMetadata(
    /** The engine that handled the request. */
    val engine: EngineType,

    /** Total latency in milliseconds. */
    val latencyMs: Long,

    /** Number of tokens generated in the output. */
    val tokensGenerated: Int,

    /** Number of tokens in the prompt. */
    val promptTokens: Int,

    /** Whether a retry occurred. */
    val didRetry: Boolean = false,

    /** The persona ID used. */
    val personaId: String,

    /** Model identifier used (if available). */
    val modelId: String? = null,

    /** Schema used for validation. */
    val schemaId: String? = null
)

/**
 * Type of engine used for generation.
 */
@Serializable
enum class EngineType {
    /** Local syni-runtime engine (llama.cpp-based). */
    @SerialName("local")
    LOCAL,

    /** Syni cloud gateway. */
    @SerialName("cloud")
    CLOUD,

    /** Fallback response (no actual generation). */
    @SerialName("fallback")
    FALLBACK
}
