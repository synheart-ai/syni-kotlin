package ai.synheart.syni

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * HTTP + SSE client for `syni-service` `/v1/chat[/stream]`. Mirrors
 * `SyniCloudClient` from `package:syni`.
 *
 * Owns a sticky `session_id` for the agent's lifetime so the server can
 * thread multi-turn context.
 *
 * Internal — consumers should drive this through [SyniAgent].
 */
internal class SyniCloudClient(
    private val config: SyniCloudConfig,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val jsonCodec = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMediaType = "application/json".toMediaTypeOrNull()

    @Volatile private var sessionId: String? = null

    suspend fun chat(
        message: String,
        persona: SyniPersona,
        hsiContext: Map<String, Any?>? = null,
    ): SyniChatResponse = withContext(Dispatchers.IO) {
        val body = buildRequest(message, persona, hsiContext, stream = false)
            .toString().toRequestBody(jsonMediaType)
        val req = Request.Builder()
            .url("${config.baseUrl}/v1/chat")
            .post(body)
            .build()
            .withAuthHeaders()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw SyniCloudException(
                    "POST /v1/chat HTTP ${resp.code}: ${resp.body?.string().orEmpty()}"
                )
            }
            val payload = jsonCodec.parseToJsonElement(resp.body?.string().orEmpty()) as JsonObject
            sessionId = payload["session_id"]?.jsonPrimitive?.contentOrNull ?: sessionId
            SyniChatResponse.fromCloudReply(
                payload["reply"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                personaId = persona.id,
                runtimeVersion = "cloud",
            )
        }
    }

    fun chatStream(
        message: String,
        persona: SyniPersona,
        hsiContext: Map<String, Any?>? = null,
    ): Flow<SyniChatEvent> = callbackFlow {
        val body = buildRequest(message, persona, hsiContext, stream = true)
            .toString().toRequestBody(jsonMediaType)
        val req = Request.Builder()
            .url("${config.baseUrl}/v1/chat/stream")
            .post(body)
            .header("Accept", "text/event-stream")
            .build()
            .withAuthHeaders()

        val call = httpClient.newCall(req)
        try {
            val resp = call.execute()
            resp.use { r ->
                if (!r.isSuccessful) {
                    throw SyniCloudException("POST /v1/chat/stream HTTP ${r.code}")
                }
                val source = r.body?.source() ?: throw SyniCloudException("empty SSE body")
                val messageBuf = StringBuilder()
                val frameBuf = StringBuilder()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty()) {
                        // dispatch frame
                        for (frameLine in frameBuf.toString().split('\n')) {
                            if (!frameLine.startsWith("data:")) continue
                            val payload = frameLine.removePrefix("data:").trim()
                            if (payload.isEmpty()) continue
                            val evt = try {
                                jsonCodec.parseToJsonElement(payload) as? JsonObject
                            } catch (_: Exception) {
                                null
                            } ?: continue
                            when (evt["type"]?.jsonPrimitive?.contentOrNull) {
                                "content" -> {
                                    val content = evt["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    messageBuf.append(content)
                                    trySend(SyniChatDelta(content))
                                }
                                "done" -> {
                                    sessionId = evt["session_id"]?.jsonPrimitive?.contentOrNull ?: sessionId
                                    trySend(
                                        SyniChatFinal(
                                            SyniChatResponse.fromCloudReply(
                                                messageBuf.toString(),
                                                personaId = persona.id,
                                                runtimeVersion = "cloud",
                                            )
                                        )
                                    )
                                    close()
                                    return@callbackFlow
                                }
                                "error" -> {
                                    throw SyniCloudException(
                                        evt["error"]?.jsonPrimitive?.contentOrNull ?: "unknown server error"
                                    )
                                }
                            }
                        }
                        frameBuf.clear()
                    } else {
                        frameBuf.append(line).append('\n')
                    }
                }
                if (messageBuf.isNotEmpty()) {
                    trySend(
                        SyniChatFinal(
                            SyniChatResponse.fromCloudReply(
                                messageBuf.toString(),
                                personaId = persona.id,
                                runtimeVersion = "cloud",
                            )
                        )
                    )
                }
                close()
            }
        } catch (e: Throwable) {
            close(e)
        }
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    private suspend fun Request.withAuthHeaders(): Request {
        val builder = newBuilder()
            .header("Content-Type", "application/json")
            .header("Accept", header("Accept") ?: "application/json")
        val headers = config.authHeaders(method, url.toString())
        for ((k, v) in headers) builder.header(k, v)
        return builder.build()
    }

    private fun buildRequest(
        message: String,
        persona: SyniPersona,
        hsiContext: Map<String, Any?>?,
        stream: Boolean,
    ): JsonObject = buildJsonObject {
        put("message", message)
        put("persona_id", persona.id)
        put("tenant_id", config.tenantId)
        put("user_id", config.userId)
        if (config.projectId.isNotEmpty()) put("project_id", config.projectId)
        if (config.orgId.isNotEmpty()) put("org_id", config.orgId)
        if (config.appId.isNotEmpty()) put("app_id", config.appId)
        if (config.deviceId.isNotEmpty()) put("device_id", config.deviceId)
        sessionId?.let { put("session_id", it) }
        put("execution_mode", "cloud_only")
        put("include_state", true)
        put("stream", stream)
        if (hsiContext != null) {
            put("context", encodeAny(hsiContext))
        }
    }

    private fun encodeAny(value: Any?): kotlinx.serialization.json.JsonElement {
        // Minimal Map-of-strings/numbers/bools/lists/maps encoder for the
        // host SDK's HSI payload. Avoids pulling in kotlinx Reflective.
        return when (value) {
            null -> kotlinx.serialization.json.JsonNull
            is String -> kotlinx.serialization.json.JsonPrimitive(value)
            is Number -> kotlinx.serialization.json.JsonPrimitive(value)
            is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
            is Map<*, *> -> buildJsonObject {
                value.forEach { (k, v) -> put(k.toString(), encodeAny(v)) }
            }
            is List<*> -> kotlinx.serialization.json.buildJsonArray {
                value.forEach { add(encodeAny(it)) }
            }
            else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
        }
    }
}
