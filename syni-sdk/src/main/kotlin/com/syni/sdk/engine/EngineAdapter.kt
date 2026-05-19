package com.syni.sdk.engine

import com.syni.sdk.core.EngineType
import com.syni.sdk.core.GenerationOptions
import com.syni.sdk.core.Persona
import com.syni.sdk.core.SyniInput
import com.syni.sdk.schema.Grammar
import kotlinx.serialization.Serializable

/**
 * Interface for generation engines.
 * Implemented by LocalEngine and CloudClient.
 */
interface EngineAdapter {
    /**
     * The type of engine.
     */
    val engineType: EngineType

    /**
     * Check if the engine is currently available.
     */
    suspend fun isAvailable(): Boolean

    /**
     * Generate output for the given input.
     *
     * @param input The input text and context
     * @param persona The persona defining behavior
     * @param grammar Optional GBNF grammar for constrained generation
     * @param options Generation options
     * @return The generated output
     * @throws com.syni.sdk.core.SyniError on failure
     */
    suspend fun generate(
        input: SyniInput,
        persona: Persona,
        grammar: Grammar?,
        options: GenerationOptions
    ): EngineOutput
}

/**
 * Output from an engine generation.
 */
@Serializable
data class EngineOutput(
    /** The generated text. */
    val text: String,

    /** Number of tokens generated. */
    val tokensGenerated: Int,

    /** Number of tokens in the prompt. */
    val promptTokens: Int,

    /** Model ID used for generation (if available). */
    val modelId: String? = null,

    /** Raw generation time in milliseconds (engine-internal). */
    val generationTimeMs: Long? = null
)
