package com.syni.sdk.engine

import android.content.Context
import android.os.SystemClock
import com.syni.sdk.core.EngineType
import com.syni.sdk.core.GenerationOptions
import com.syni.sdk.core.LocalEngineConfig
import com.syni.sdk.core.Persona
import com.syni.sdk.core.SyniError
import com.syni.sdk.core.SyniInput
import com.syni.sdk.schema.Grammar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Local inference engine using syni-runtime via JNI.
 *
 * This engine uses GGUF models with grammar-constrained decoding
 * for deterministic, schema-compliant output.
 */
class LocalEngine(
    private val context: Context,
    private val config: LocalEngineConfig,
    private val modelPath: String
) : EngineAdapter {

    override val engineType: EngineType = EngineType.LOCAL

    private val isShuttingDown = AtomicBoolean(false)
    private val mutex = Mutex()

    // Native engine handle
    @Volatile
    private var nativeHandle: Long = 0

    // Statistics
    private val totalGenerations = AtomicLong(0)
    private val totalTokensGenerated = AtomicLong(0)
    private var totalLatencyMs = 0.0

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        // Verify model file exists
        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            throw SyniError.LocalEngineFailed("Model file not found: $modelPath")
        }
    }

    override suspend fun isAvailable(): Boolean {
        if (isShuttingDown.get()) return false

        // Check if native library is available
        if (!SyniNative.isAvailable && !SyniNative.loadLibrary()) {
            return false
        }

        return File(modelPath).exists()
    }

    override suspend fun generate(
        input: SyniInput,
        persona: Persona,
        grammar: Grammar?,
        options: GenerationOptions
    ): EngineOutput = withContext(Dispatchers.Default) {
        if (isShuttingDown.get()) {
            throw SyniError.LocalEngineFailed("Engine is shutting down")
        }

        ensureEngineReady()

        val startTime = SystemClock.elapsedRealtime()

        try {
            // Build the request JSON matching syni-runtime's EngineRequest
            val request = SyniEngineRequest(
                hsi = input.context,
                instruction = input.text,
                schema = null  // Schema validation done at Kotlin layer
            )
            val requestJson = json.encodeToString(request)

            // Determine preset based on persona budget
            val preset = presetForBudget(persona.budget.maxLatencyMs)

            // Generate random seed
            val seed = Random.nextLong()

            // Run inference via JNI
            val responseJson = SyniNative.engineRunJson(nativeHandle, preset, seed, requestJson)
                ?: throw SyniError.LocalEngineFailed("Inference returned null")

            val latencyMs = SystemClock.elapsedRealtime() - startTime

            // Parse response
            val output = parseResponse(responseJson)

            // Update statistics
            totalGenerations.incrementAndGet()
            totalTokensGenerated.addAndGet(output.tokensGenerated.toLong())
            totalLatencyMs += latencyMs

            output.copy(generationTimeMs = latencyMs)
        } catch (e: Exception) {
            if (e is SyniError) throw e
            throw SyniError.LocalEngineFailed(
                "Generation failed: ${e.message}",
                e
            )
        }
    }

    /**
     * Ensure the engine is initialized with the model.
     */
    private suspend fun ensureEngineReady() {
        if (nativeHandle != 0L) return

        mutex.withLock {
            if (nativeHandle != 0L) return

            withContext(Dispatchers.IO) {
                // Load native library if not already loaded
                if (!SyniNative.loadLibrary()) {
                    throw SyniError.LocalEngineFailed(
                        "Failed to load native library: ${SyniNative.libraryLoadError?.message}",
                        SyniNative.libraryLoadError
                    )
                }

                // Create engine with model
                val handle = SyniNative.engineCreate(modelPath)
                if (handle == 0L) {
                    throw SyniError.LocalEngineFailed("Failed to create engine with model: $modelPath")
                }

                // Health check
                val health = SyniNative.engineHealthcheck(handle)
                if (health != 0) {
                    SyniNative.engineFree(handle)
                    throw SyniError.LocalEngineFailed("Engine health check failed with code: $health")
                }

                nativeHandle = handle
            }
        }
    }

    /**
     * Parse the JSON response from syni-runtime.
     */
    private fun parseResponse(responseJson: String): EngineOutput {
        return try {
            val jsonObj = json.decodeFromString<JsonObject>(responseJson)

            // Extract message if present, otherwise use full response
            val text = jsonObj["message"]?.jsonPrimitive?.content ?: responseJson

            EngineOutput(
                text = text,
                tokensGenerated = 0,  // TODO: Parse from response if available
                promptTokens = 0,
                modelId = File(modelPath).name
            )
        } catch (e: Exception) {
            // Return raw response if not valid JSON
            EngineOutput(
                text = responseJson,
                tokensGenerated = 0,
                promptTokens = 0,
                modelId = File(modelPath).name
            )
        }
    }

    /**
     * Map budget latency constraints to syni-runtime presets.
     */
    private fun presetForBudget(maxLatencyMs: Long): Int {
        return when {
            maxLatencyMs <= 200 -> SyniNative.Preset.KEYBOARD
            maxLatencyMs <= 2500 -> SyniNative.Preset.COACH
            else -> SyniNative.Preset.CHAT
        }
    }

    /**
     * Get token count for text using the loaded model's tokenizer.
     *
     * @param text Text to tokenize
     * @return Token count, or null if engine not ready
     */
    fun tokenCount(text: String): Int? {
        if (nativeHandle == 0L) return null
        val count = SyniNative.tokenCount(nativeHandle, text)
        return if (count >= 0) count else null
    }

    /**
     * Shutdown the engine and release resources.
     */
    fun shutdown() {
        if (isShuttingDown.getAndSet(true)) return

        if (nativeHandle != 0L) {
            SyniNative.engineFree(nativeHandle)
            nativeHandle = 0
        }
    }

    /**
     * Get engine statistics.
     */
    fun getStats(): EngineStats {
        val generations = totalGenerations.get()
        return EngineStats(
            totalGenerations = generations,
            totalTokensGenerated = totalTokensGenerated.get(),
            averageLatencyMs = if (generations > 0) totalLatencyMs / generations else 0.0,
            modelId = File(modelPath).name,
            memoryUsageBytes = null // Would require additional native API
        )
    }

    companion object {
        private const val TAG = "LocalEngine"

        /**
         * Check if the native library is available.
         */
        fun isNativeLibraryAvailable(): Boolean {
            return SyniNative.isAvailable || SyniNative.loadLibrary()
        }

        /**
         * Get syni-runtime library version.
         */
        fun version(): String? {
            return try {
                if (SyniNative.loadLibrary()) {
                    SyniNative.version()
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Internal request structure matching syni-runtime's EngineRequest.
 */
@Serializable
private data class SyniEngineRequest(
    val hsi: Map<String, String>,
    val instruction: String,
    val schema: String?
)
