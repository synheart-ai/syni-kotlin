package com.syni.sdk.cloud

import com.syni.sdk.BuildConfig
import com.syni.sdk.core.CloudConfig
import com.syni.sdk.core.EngineType
import com.syni.sdk.core.GenerationOptions
import com.syni.sdk.core.Persona
import com.syni.sdk.core.SyniError
import com.syni.sdk.core.SyniInput
import com.syni.sdk.engine.EngineAdapter
import com.syni.sdk.engine.EngineOutput
import com.syni.sdk.schema.Grammar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Client for the Syni cloud gateway.
 * Implements EngineAdapter for seamless integration with the routing system.
 */
class CloudClient(
    private val config: CloudConfig
) : EngineAdapter {

    override val engineType: EngineType = EngineType.CLOUD

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .apply {
            if (config.enableHttp2) {
                protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            }
        }
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun isAvailable(): Boolean {
        // Cloud is available if we have valid configuration
        return config.gatewayURL.isNotBlank() && config.authToken.isNotBlank()
    }

    override suspend fun generate(
        input: SyniInput,
        persona: Persona,
        grammar: Grammar?,
        options: GenerationOptions
    ): EngineOutput = withContext(Dispatchers.IO) {
        val request = buildRequest(input, persona, grammar, options)
        val httpRequest = buildHttpRequest(request)

        try {
            val response = executeRequest(httpRequest)
            parseResponse(response)
        } catch (e: IOException) {
            throw SyniError.CloudRequestFailed(
                statusCode = null,
                reason = "Network error: ${e.message}",
                cause = e
            )
        }
    }

    /**
     * Execute request with retry logic.
     */
    private suspend fun executeRequest(request: Request): CloudResponse {
        var lastException: Exception? = null
        var attempts = 0

        while (attempts <= config.maxRetries) {
            try {
                val response = httpClient.newCall(request).await()

                if (response.isSuccessful) {
                    val body = response.body?.string()
                        ?: throw SyniError.CloudRequestFailed(
                            statusCode = response.code,
                            reason = "Empty response body"
                        )

                    return json.decodeFromString<CloudResponse>(body)
                }

                // Handle non-retryable errors
                if (response.code in 400..499) {
                    val errorBody = response.body?.string()
                    throw SyniError.CloudRequestFailed(
                        statusCode = response.code,
                        reason = parseErrorMessage(errorBody) ?: "Client error"
                    )
                }

                // Server error - may retry
                lastException = SyniError.CloudRequestFailed(
                    statusCode = response.code,
                    reason = "Server error"
                )
            } catch (e: Exception) {
                if (e is SyniError) {
                    // Don't retry SyniErrors
                    if (e is SyniError.CloudRequestFailed && e.statusCode in 400..499) {
                        throw e
                    }
                }
                lastException = e
            }

            attempts++
        }

        throw lastException ?: SyniError.CloudRequestFailed(
            statusCode = null,
            reason = "Max retries exceeded"
        )
    }

    private fun buildRequest(
        input: SyniInput,
        persona: Persona,
        grammar: Grammar?,
        options: GenerationOptions
    ): CloudRequest {
        return CloudRequest(
            personaId = persona.id,
            input = CloudInput(
                text = input.text,
                context = input.context.ifEmpty { null }
            ),
            options = CloudOptions(
                maxTokens = options.maxTokens ?: persona.budget.maxTokens,
                temperature = options.temperature ?: persona.defaultParams.temperature,
                topP = persona.defaultParams.topP,
                systemPrompt = options.systemPrompt ?: persona.defaultParams.systemPrompt,
                grammarId = grammar?.id
            )
        )
    }

    private fun buildHttpRequest(request: CloudRequest): Request {
        val url = "${config.gatewayURL.trimEnd('/')}/v1/generate"
        val body = json.encodeToString(request)

        return Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Authorization", "Bearer ${config.authToken}")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Syni-Spec-Version", BuildConfig.SYNI_SPEC_VERSION)
            .addHeader("X-Syni-SDK-Version", BuildConfig.SYNI_SDK_VERSION)
            .addHeader("X-Syni-Platform", "android")
            .build()
    }

    private fun parseResponse(response: CloudResponse): EngineOutput {
        return EngineOutput(
            text = response.output,
            tokensGenerated = response.tokensGenerated,
            promptTokens = response.promptTokens,
            modelId = response.modelId,
            generationTimeMs = response.latencyMs
        )
    }

    private fun parseErrorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val error = json.decodeFromString<CloudErrorResponse>(body)
            error.error?.message ?: error.message
        } catch (e: Exception) {
            body.take(200)
        }
    }

    /**
     * Close the HTTP client and release resources.
     */
    fun close() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Suspend extension for OkHttp Call.
 */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        cancel()
    }

    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) {
                continuation.resumeWithException(e)
            }
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }
    })
}

// --- Internal request/response classes ---

@Serializable
internal data class CloudRequest(
    @SerialName("persona_id")
    val personaId: String,
    val input: CloudInput,
    val options: CloudOptions
)

@Serializable
internal data class CloudInput(
    val text: String,
    val context: Map<String, String>? = null
)

@Serializable
internal data class CloudOptions(
    @SerialName("max_tokens")
    val maxTokens: Int,
    val temperature: Float,
    @SerialName("top_p")
    val topP: Float,
    @SerialName("system_prompt")
    val systemPrompt: String? = null,
    @SerialName("grammar_id")
    val grammarId: String? = null
)

@Serializable
internal data class CloudResponse(
    val output: String,
    @SerialName("tokens_generated")
    val tokensGenerated: Int,
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("model_id")
    val modelId: String? = null,
    @SerialName("latency_ms")
    val latencyMs: Long? = null
)

@Serializable
internal data class CloudErrorResponse(
    val error: CloudError? = null,
    val message: String? = null
)

@Serializable
internal data class CloudError(
    val code: String? = null,
    val message: String? = null
)
