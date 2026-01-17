package com.syni.sdk

import android.content.Context
import android.os.SystemClock
import com.syni.sdk.budget.BudgetEnforcer
import com.syni.sdk.cloud.CloudClient
import com.syni.sdk.core.EngineType
import com.syni.sdk.core.GenerationOptions
import com.syni.sdk.core.Persona
import com.syni.sdk.core.ResponseMetadata
import com.syni.sdk.core.SyniConfig
import com.syni.sdk.core.SyniError
import com.syni.sdk.core.SyniInput
import com.syni.sdk.core.SyniRequest
import com.syni.sdk.core.SyniResponse
import com.syni.sdk.core.SyniResult
import com.syni.sdk.engine.EngineAdapter
import com.syni.sdk.engine.EngineManager
import com.syni.sdk.engine.EngineOutput
import com.syni.sdk.model.DownloadProgress
import com.syni.sdk.model.ModelInfo
import com.syni.sdk.model.ModelManager
import com.syni.sdk.router.PersonaRouter
import com.syni.sdk.schema.Grammar
import com.syni.sdk.schema.SchemaValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Main entry point for the Syni SDK.
 *
 * Usage:
 * ```kotlin
 * // Initialize once
 * Syni.initialize(context, SyniConfig())
 *
 * // Generate with a persona
 * val response = Syni.generate(
 *     SyniRequest(
 *         personaId = "keyboard.v1",
 *         input = SyniInput.text("Hello")
 *     )
 * )
 * ```
 */
object Syni {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val initialized = AtomicBoolean(false)

    private lateinit var config: SyniConfig
    private lateinit var personaRouter: PersonaRouter
    private lateinit var schemaValidator: SchemaValidator
    private lateinit var budgetEnforcer: BudgetEnforcer
    private lateinit var engineManager: EngineManager
    private lateinit var modelManager: ModelManager
    private var cloudClient: CloudClient? = null

    /**
     * Initialize the Syni SDK.
     * Must be called before any other operations.
     *
     * @param context Android context (application context recommended)
     * @param config SDK configuration
     */
    @Synchronized
    fun initialize(context: Context, config: SyniConfig = SyniConfig()) {
        if (initialized.get()) {
            throw SyniError.InvalidConfiguration("Syni already initialized. Call shutdown() first to reinitialize.")
        }

        val appContext = context.applicationContext
        this.config = config

        // Initialize components
        personaRouter = PersonaRouter(
            context = appContext,
            personasDir = config.paths.personasDir
        )

        schemaValidator = SchemaValidator(
            context = appContext,
            schemasDir = config.paths.schemasDir,
            grammarsDir = config.paths.grammarsDir
        )

        budgetEnforcer = BudgetEnforcer()

        modelManager = ModelManager(
            context = appContext,
            modelsDir = config.paths.modelsDir
        )

        engineManager = EngineManager(
            context = appContext,
            config = config.localEngineConfig,
            modelManager = modelManager
        )

        // Initialize cloud client if configured
        config.cloudConfig?.let {
            cloudClient = CloudClient(it)
        }

        initialized.set(true)
    }

    /**
     * Check if the SDK is initialized.
     */
    val isInitialized: Boolean
        get() = initialized.get()

    /**
     * Check if the SDK is ready to generate (has a model loaded).
     */
    suspend fun isReady(): Boolean {
        ensureInitialized()
        return engineManager.isLocalEngineAvailable() || cloudClient != null
    }

    /**
     * Generate output for a request.
     * This is the main generation API.
     *
     * @param request The generation request
     * @return SyniResult containing the response or error
     */
    suspend fun generate(request: SyniRequest): SyniResult<SyniResponse> {
        return try {
            SyniResult.Success(generateAsync(request))
        } catch (e: SyniError) {
            SyniResult.Failure(e)
        } catch (e: CancellationException) {
            SyniResult.Failure(SyniError.Cancelled)
        } catch (e: Exception) {
            SyniResult.Failure(SyniError.InternalError(e.message ?: "Unknown error", e))
        }
    }

    /**
     * Generate output for a request.
     * Throws exceptions on failure.
     *
     * @param request The generation request
     * @return The generation response
     * @throws SyniError on failure
     */
    suspend fun generateAsync(request: SyniRequest): SyniResponse = withContext(Dispatchers.Default) {
        ensureInitialized()

        val startTime = SystemClock.elapsedRealtime()

        // 1. Get persona
        val persona = personaRouter.getPersona(request.personaId)

        // 2. Get schema and grammar
        val schema = schemaValidator.getSchema(persona.schemaId)
            ?: throw SyniError.SchemaNotFound(persona.schemaId)
        val grammar = schemaValidator.getGrammar(persona.grammarId)
            ?: throw SyniError.GrammarNotFound(persona.grammarId)

        // 3. Start budget tracking
        val effectiveBudget = persona.budget.copy(
            maxLatencyMs = request.options.timeoutMs ?: persona.budget.maxLatencyMs,
            maxTokens = request.options.maxTokens ?: persona.budget.maxTokens
        )
        budgetEnforcer.startTracking(request.requestId, effectiveBudget)

        try {
            // 4. Select engine
            val isLocalAvailable = engineManager.isLocalEngineAvailable()
            val isCloudAvailable = cloudClient?.isAvailable() ?: false

            val selectedEngine = personaRouter.selectEngine(
                persona = persona,
                options = request.options,
                isLocalAvailable = isLocalAvailable,
                isCloudAvailable = isCloudAvailable
            )

            // 5. Generate with timeout
            val timeoutMs = budgetEnforcer.getRemainingLatencyMs(request.requestId)

            val output = withTimeout(timeoutMs) {
                generateWithRetry(
                    request = request,
                    persona = persona,
                    grammar = grammar,
                    initialEngine = selectedEngine,
                    isLocalAvailable = isLocalAvailable,
                    isCloudAvailable = isCloudAvailable
                )
            }

            // 6. Validate output
            val validatedOutput = validateAndRepair(output.text, persona.schemaId)

            val latencyMs = SystemClock.elapsedRealtime() - startTime

            SyniResponse(
                requestId = request.requestId,
                outputJSON = validatedOutput,
                metadata = ResponseMetadata(
                    engine = output.engine,
                    latencyMs = latencyMs,
                    tokensGenerated = output.tokensGenerated,
                    promptTokens = output.promptTokens,
                    didRetry = output.didRetry,
                    personaId = persona.id,
                    modelId = output.modelId,
                    schemaId = persona.schemaId
                ),
                isFallback = false
            )
        } catch (e: Exception) {
            // Create fallback response on failure
            val latencyMs = SystemClock.elapsedRealtime() - startTime

            if (config.debug) {
                throw e
            }

            createFallbackResponse(request, persona, latencyMs, e)
        } finally {
            budgetEnforcer.stopTracking(request.requestId)
        }
    }

    /**
     * Generate with a simple text input.
     * Convenience method for simple use cases.
     */
    suspend fun generate(personaId: String, text: String): SyniResult<SyniResponse> {
        return generate(
            SyniRequest(
                personaId = personaId,
                input = SyniInput.text(text)
            )
        )
    }

    // --- Model Management ---

    /**
     * Download a model.
     */
    fun downloadModel(
        url: String,
        modelId: String,
        expectedChecksum: String? = null,
        wifiOnly: Boolean = true
    ): Flow<DownloadProgress> {
        ensureInitialized()
        return modelManager.downloadModel(url, modelId, expectedChecksum, wifiOnly)
    }

    /**
     * Get all downloaded models.
     */
    fun getDownloadedModels(): List<ModelInfo> {
        ensureInitialized()
        return modelManager.getDownloadedModels()
    }

    /**
     * Delete a model.
     */
    suspend fun deleteModel(modelId: String): Boolean {
        ensureInitialized()
        val deleted = modelManager.deleteModel(modelId)
        if (deleted) {
            engineManager.reloadEngine()
        }
        return deleted
    }

    /**
     * Get storage usage.
     */
    fun getStorageUsage(): Long {
        ensureInitialized()
        return modelManager.getStorageUsage()
    }

    // --- Persona Management ---

    /**
     * Get all available persona IDs.
     */
    fun availablePersonas(): Set<String> {
        ensureInitialized()
        return personaRouter.availablePersonas()
    }

    /**
     * Get a persona by ID.
     */
    fun getPersona(personaId: String): Persona {
        ensureInitialized()
        return personaRouter.getPersona(personaId)
    }

    /**
     * Register a custom persona.
     */
    fun registerPersona(persona: Persona) {
        ensureInitialized()
        personaRouter.registerPersona(persona)
    }

    // --- Lifecycle ---

    /**
     * Shutdown the SDK and release resources.
     */
    suspend fun shutdown() {
        if (!initialized.getAndSet(false)) return

        engineManager.shutdown()
        cloudClient?.close()
        budgetEnforcer.clear()
    }

    // --- Private Implementation ---

    private fun ensureInitialized() {
        if (!initialized.get()) {
            throw SyniError.NotInitialized
        }
    }

    private suspend fun generateWithRetry(
        request: SyniRequest,
        persona: Persona,
        grammar: Grammar,
        initialEngine: EngineType,
        isLocalAvailable: Boolean,
        isCloudAvailable: Boolean
    ): GenerationResult {
        var currentEngine = initialEngine
        var didRetry = false
        var lastError: Exception? = null

        while (true) {
            try {
                val adapter = getEngineAdapter(currentEngine)
                    ?: throw SyniError.EngineUnavailable("Engine $currentEngine not available")

                val output = adapter.generate(
                    input = request.input,
                    persona = persona,
                    grammar = grammar,
                    options = request.options
                )

                return GenerationResult(
                    text = output.text,
                    tokensGenerated = output.tokensGenerated,
                    promptTokens = output.promptTokens,
                    engine = currentEngine,
                    modelId = output.modelId,
                    didRetry = didRetry
                )
            } catch (e: Exception) {
                lastError = e

                // Check if we can retry
                if (!budgetEnforcer.canRetry(request.requestId)) {
                    throw lastError
                }

                // Try to get fallback engine
                val fallbackEngine = personaRouter.getFallbackEngine(
                    persona = persona,
                    failedEngine = currentEngine,
                    options = request.options,
                    isLocalAvailable = isLocalAvailable,
                    isCloudAvailable = isCloudAvailable
                )

                if (fallbackEngine == null) {
                    throw lastError
                }

                budgetEnforcer.recordRetry(request.requestId)
                currentEngine = fallbackEngine
                didRetry = true
            }
        }
    }

    private suspend fun getEngineAdapter(engineType: EngineType): EngineAdapter? {
        return when (engineType) {
            EngineType.PORTABLE_LOCAL_ENGINE -> {
                try {
                    engineManager.getLocalEngine()
                } catch (e: Exception) {
                    null
                }
            }
            EngineType.CLOUD -> cloudClient
            EngineType.FALLBACK -> null
        }
    }

    private fun validateAndRepair(text: String, schemaId: String): JsonElement {
        // First try direct validation
        val result = schemaValidator.validate(text, schemaId)
        if (result.isValid) {
            return json.parseToJsonElement(text)
        }

        // Try to repair
        val repaired = schemaValidator.attemptJSONRepair(text)
        if (repaired != null) {
            val repairedResult = schemaValidator.validate(repaired, schemaId)
            if (repairedResult.isValid) {
                return json.parseToJsonElement(repaired)
            }
        }

        // Return fallback
        return schemaValidator.createFallbackJSON(schemaId)
    }

    private fun createFallbackResponse(
        request: SyniRequest,
        persona: Persona,
        latencyMs: Long,
        error: Exception
    ): SyniResponse {
        val fallbackJson = schemaValidator.createFallbackJSON(persona.schemaId)

        return SyniResponse(
            requestId = request.requestId,
            outputJSON = fallbackJson,
            metadata = ResponseMetadata(
                engine = EngineType.FALLBACK,
                latencyMs = latencyMs,
                tokensGenerated = 0,
                promptTokens = 0,
                didRetry = false,
                personaId = persona.id,
                schemaId = persona.schemaId
            ),
            isFallback = true
        )
    }

    private data class GenerationResult(
        val text: String,
        val tokensGenerated: Int,
        val promptTokens: Int,
        val engine: EngineType,
        val modelId: String?,
        val didRetry: Boolean
    )
}
