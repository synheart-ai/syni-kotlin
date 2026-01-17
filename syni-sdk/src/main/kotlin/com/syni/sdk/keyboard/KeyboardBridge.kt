package com.syni.sdk.keyboard

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import com.syni.sdk.Syni
import com.syni.sdk.core.GenerationOptions
import com.syni.sdk.core.SyniError
import com.syni.sdk.core.SyniInput
import com.syni.sdk.core.SyniRequest
import com.syni.sdk.core.SyniResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Bridge for communication between keyboard IME extension and host app.
 * Uses ContentProvider for IPC and SharedPreferences for quick state sharing.
 */
class KeyboardBridge(
    private val context: Context
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val pendingRequests = ConcurrentHashMap<String, IPCRequest>()
    private val completedResponses = ConcurrentHashMap<String, IPCResponse>()
    private val requestTimeout = AtomicLong(DEFAULT_TIMEOUT_MS)

    /**
     * Send a request from the keyboard extension.
     * This method blocks and waits for a response.
     *
     * @param text The input text for suggestions
     * @param context Additional context
     * @param timeoutMs Timeout in milliseconds
     * @return The suggestions response or null on timeout/error
     */
    fun sendRequestSync(
        text: String,
        context: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): KeyboardResponse? {
        return runBlocking {
            sendRequest(text, context, timeoutMs)
        }
    }

    /**
     * Send a request from the keyboard extension (suspend version).
     */
    suspend fun sendRequest(
        text: String,
        context: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): KeyboardResponse? = withContext(Dispatchers.IO) {
        val requestId = UUID.randomUUID().toString()

        val request = IPCRequest(
            requestId = requestId,
            text = text,
            context = context,
            timestamp = SystemClock.elapsedRealtime()
        )

        try {
            // Write request to ContentProvider
            val contentUri = getContentUri()
            val values = ContentValues().apply {
                put(COLUMN_REQUEST_ID, requestId)
                put(COLUMN_DATA, json.encodeToString(request))
                put(COLUMN_TYPE, TYPE_REQUEST)
            }

            this@KeyboardBridge.context.contentResolver.insert(contentUri, values)

            // Wait for response with timeout
            withTimeout(timeoutMs) {
                waitForResponse(requestId)
            }
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Process an incoming request from the keyboard.
     * Called by the host app's ContentProvider.
     */
    suspend fun processIncomingRequest(request: IPCRequest): IPCResponse {
        val startTime = SystemClock.elapsedRealtime()

        return try {
            // Ensure Syni is initialized
            if (!Syni.isInitialized) {
                return IPCResponse(
                    requestId = request.requestId,
                    success = false,
                    error = "SDK not initialized",
                    suggestions = emptyList(),
                    latencyMs = SystemClock.elapsedRealtime() - startTime
                )
            }

            // Generate suggestions using keyboard persona
            val result = Syni.generate(
                SyniRequest(
                    personaId = KEYBOARD_PERSONA_ID,
                    input = SyniInput(
                        text = request.text,
                        context = request.context
                    ),
                    options = GenerationOptions.KEYBOARD,
                    requestId = request.requestId
                )
            )

            val response = result.getOrNull()
            val latencyMs = SystemClock.elapsedRealtime() - startTime

            if (response != null) {
                // Parse suggestions from response
                val suggestions = parseSuggestions(response)

                IPCResponse(
                    requestId = request.requestId,
                    success = true,
                    suggestions = suggestions,
                    latencyMs = latencyMs,
                    isFallback = response.isFallback
                )
            } else {
                IPCResponse(
                    requestId = request.requestId,
                    success = false,
                    error = result.errorOrNull()?.message ?: "Generation failed",
                    suggestions = emptyList(),
                    latencyMs = latencyMs
                )
            }
        } catch (e: Exception) {
            IPCResponse(
                requestId = request.requestId,
                success = false,
                error = e.message ?: "Unknown error",
                suggestions = emptyList(),
                latencyMs = SystemClock.elapsedRealtime() - startTime
            )
        }
    }

    /**
     * Store a completed response for retrieval.
     */
    fun storeResponse(response: IPCResponse) {
        completedResponses[response.requestId] = response

        // Clean up old responses
        cleanupOldResponses()
    }

    /**
     * Retrieve a response by request ID.
     */
    fun getResponse(requestId: String): IPCResponse? {
        return completedResponses.remove(requestId)
    }

    /**
     * Set the request timeout.
     */
    fun setRequestTimeout(timeoutMs: Long) {
        requestTimeout.set(timeoutMs)
    }

    // --- Private Implementation ---

    private fun getContentUri(): Uri {
        val authority = "${context.packageName}.syni.provider"
        return Uri.parse("content://$authority/$PATH_REQUESTS")
    }

    private suspend fun waitForResponse(requestId: String): KeyboardResponse? {
        val pollInterval = 10L // ms
        val startTime = SystemClock.elapsedRealtime()
        val timeout = requestTimeout.get()

        while (SystemClock.elapsedRealtime() - startTime < timeout) {
            // Check for response via ContentProvider
            val contentUri = getContentUri()
            val cursor = context.contentResolver.query(
                Uri.withAppendedPath(contentUri, requestId),
                null,
                null,
                null,
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val dataIndex = it.getColumnIndex(COLUMN_DATA)
                    if (dataIndex >= 0) {
                        val data = it.getString(dataIndex)
                        val response = json.decodeFromString<IPCResponse>(data)
                        return KeyboardResponse(
                            suggestions = response.suggestions,
                            confidence = response.confidence,
                            latencyMs = response.latencyMs
                        )
                    }
                }
            }

            // Small delay before next poll
            kotlinx.coroutines.delay(pollInterval)
        }

        return null
    }

    private fun parseSuggestions(response: SyniResponse): List<String> {
        return try {
            val output = response.outputJSON
            val suggestionsArray = output.toString()
            // Parse the suggestions array from the JSON output
            val regex = Regex(""""suggestions"\s*:\s*\[(.*?)\]""")
            val match = regex.find(suggestionsArray)
            if (match != null) {
                val arrayContent = match.groupValues[1]
                Regex(""""([^"]+)"""").findAll(arrayContent)
                    .map { it.groupValues[1] }
                    .toList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun cleanupOldResponses() {
        val cutoff = SystemClock.elapsedRealtime() - RESPONSE_TTL_MS
        completedResponses.entries.removeIf { (_, response) ->
            response.latencyMs < cutoff
        }
    }

    companion object {
        const val KEYBOARD_PERSONA_ID = "keyboard.v1"
        const val DEFAULT_TIMEOUT_MS = 150L
        const val RESPONSE_TTL_MS = 5000L

        // ContentProvider constants
        const val PATH_REQUESTS = "requests"
        const val COLUMN_REQUEST_ID = "request_id"
        const val COLUMN_DATA = "data"
        const val COLUMN_TYPE = "type"
        const val TYPE_REQUEST = "request"
        const val TYPE_RESPONSE = "response"
    }
}

/**
 * IPC request from keyboard to host app.
 */
@Serializable
data class IPCRequest(
    @SerialName("request_id")
    val requestId: String,
    val text: String,
    val context: Map<String, String> = emptyMap(),
    val timestamp: Long
)

/**
 * IPC response from host app to keyboard.
 */
@Serializable
data class IPCResponse(
    @SerialName("request_id")
    val requestId: String,
    val success: Boolean,
    val suggestions: List<String> = emptyList(),
    val confidence: Float? = null,
    val error: String? = null,
    @SerialName("latency_ms")
    val latencyMs: Long,
    @SerialName("is_fallback")
    val isFallback: Boolean = false
)

/**
 * Simple response for keyboard clients.
 */
data class KeyboardResponse(
    val suggestions: List<String>,
    val confidence: Float?,
    val latencyMs: Long
)
