package com.syni.sdk.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A Syni Persona defines the behavior, constraints, and routing for a specific use case.
 */
@Serializable
data class Persona(
    /** Unique identifier for this persona. */
    val id: String,

    /** Human-readable name. */
    val name: String,

    /** Version string. */
    val version: String,

    /** ID of the JSON schema for output validation. */
    @SerialName("schema_id")
    val schemaId: String,

    /** ID of the GBNF grammar for constrained generation. */
    @SerialName("grammar_id")
    val grammarId: String,

    /** Routing policy for engine selection. */
    @SerialName("routing")
    val routingPolicy: RoutingPolicy = RoutingPolicy(),

    /** Performance budget constraints. */
    val budget: PerformanceBudget = PerformanceBudget(),

    /** Default generation parameters. */
    @SerialName("params")
    val defaultParams: PersonaParams = PersonaParams()
) {
    companion object {
        /** Built-in keyboard persona for IME suggestions. */
        val KEYBOARD_V1 = Persona(
            id = "keyboard.v1",
            name = "Keyboard Suggestions",
            version = "1.0.0",
            schemaId = "keyboard.v1",
            grammarId = "keyboard.v1",
            routingPolicy = RoutingPolicy.LOCAL_PREFERRED,
            budget = PerformanceBudget.KEYBOARD,
            defaultParams = PersonaParams(
                temperature = 0.7f,
                topP = 0.9f,
                systemPrompt = "You are a keyboard assistant. Suggest completions for the user's text. Respond with a JSON object containing an array of suggestions."
            )
        )

        /** Built-in life coach persona for thoughtful guidance. */
        val LIFE_COACH_V1 = Persona(
            id = "life.coach.v1",
            name = "Life Coach",
            version = "1.0.0",
            schemaId = "life.coach.v1",
            grammarId = "life.coach.v1",
            routingPolicy = RoutingPolicy.CLOUD_PREFERRED,
            budget = PerformanceBudget.LIFE_COACH,
            defaultParams = PersonaParams(
                temperature = 0.8f,
                topP = 0.95f,
                systemPrompt = "You are a supportive life coach. Provide thoughtful, empathetic guidance. Respond with structured advice in JSON format."
            )
        )
    }
}

/**
 * Policy for selecting which engine handles requests.
 */
@Serializable
data class RoutingPolicy(
    /** Ordered list of preferred engines. */
    @SerialName("preferred_engines")
    val preferredEngines: List<EngineType> = listOf(EngineType.PORTABLE_LOCAL_ENGINE, EngineType.CLOUD),

    /** Allow local engine execution. */
    @SerialName("allow_local")
    val allowLocal: Boolean = true,

    /** Allow cloud execution. */
    @SerialName("allow_cloud")
    val allowCloud: Boolean = true
) {
    init {
        require(allowLocal || allowCloud) { "At least one of allowLocal or allowCloud must be true" }
    }

    companion object {
        /** Prefer local engine, fall back to cloud. */
        val LOCAL_PREFERRED = RoutingPolicy(
            preferredEngines = listOf(EngineType.PORTABLE_LOCAL_ENGINE, EngineType.CLOUD),
            allowLocal = true,
            allowCloud = true
        )

        /** Prefer cloud, fall back to local. */
        val CLOUD_PREFERRED = RoutingPolicy(
            preferredEngines = listOf(EngineType.CLOUD, EngineType.PORTABLE_LOCAL_ENGINE),
            allowLocal = true,
            allowCloud = true
        )

        /** Local only, no cloud fallback. */
        val LOCAL_ONLY = RoutingPolicy(
            preferredEngines = listOf(EngineType.PORTABLE_LOCAL_ENGINE),
            allowLocal = true,
            allowCloud = false
        )

        /** Cloud only, no local execution. */
        val CLOUD_ONLY = RoutingPolicy(
            preferredEngines = listOf(EngineType.CLOUD),
            allowLocal = false,
            allowCloud = true
        )
    }
}

/**
 * Performance budget constraints for a persona.
 */
@Serializable
data class PerformanceBudget(
    /** Maximum allowed latency in milliseconds. */
    @SerialName("max_latency_ms")
    val maxLatencyMs: Long = 5000,

    /** Maximum tokens to generate. */
    @SerialName("max_tokens")
    val maxTokens: Int = 256,

    /** Allow retry on failure. */
    @SerialName("allow_retry")
    val allowRetry: Boolean = true,

    /** Maximum number of retries. */
    @SerialName("max_retries")
    val maxRetries: Int = 1
) {
    init {
        require(maxLatencyMs > 0) { "maxLatencyMs must be positive" }
        require(maxTokens > 0) { "maxTokens must be positive" }
        require(maxRetries >= 0) { "maxRetries cannot be negative" }
    }

    companion object {
        /** Strict budget for keyboard input with 150ms latency. */
        val KEYBOARD = PerformanceBudget(
            maxLatencyMs = 150,
            maxTokens = 50,
            allowRetry = false,
            maxRetries = 0
        )

        /** Relaxed budget for life coach with higher latency tolerance. */
        val LIFE_COACH = PerformanceBudget(
            maxLatencyMs = 10000,
            maxTokens = 512,
            allowRetry = true,
            maxRetries = 2
        )

        /** Default balanced budget. */
        val DEFAULT = PerformanceBudget()
    }
}

/**
 * Default generation parameters for a persona.
 */
@Serializable
data class PersonaParams(
    /** Sampling temperature (0.0 - 2.0). */
    val temperature: Float = 0.7f,

    /** Top-p (nucleus) sampling threshold. */
    @SerialName("top_p")
    val topP: Float = 0.9f,

    /** System prompt for the persona. */
    @SerialName("system_prompt")
    val systemPrompt: String? = null,

    /** Stop sequences to end generation. */
    @SerialName("stop_sequences")
    val stopSequences: List<String> = emptyList()
) {
    init {
        require(temperature in 0f..2f) { "temperature must be between 0 and 2" }
        require(topP in 0f..1f) { "topP must be between 0 and 1" }
    }
}
