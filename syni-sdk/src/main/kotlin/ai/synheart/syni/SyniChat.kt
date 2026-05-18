package ai.synheart.syni

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Which `EngineResponse` variant the runtime returned. */
enum class SyniResponseKind { COACH, CHAT, SUGGESTIONS, UNKNOWN }

/**
 * A completed Syni response.
 *
 * Mirrors `SyniChatResponse` from `package:syni`. Parses the runtime's
 * tagged `EngineResponse` envelope — `{"type":"coach","data":{...}}` — into
 * a clean typed surface.
 */
class SyniChatResponse private constructor(
    /** The persona that produced this response. */
    val personaId: String,

    /** Runtime semver reported by `libsyni_ffi`. */
    val runtimeVersion: String,

    /** The runtime's raw final JSON — kept for debugging / telemetry. */
    val rawJson: String,

    /** Which `EngineResponse` variant this is. */
    val kind: SyniResponseKind,

    /** Primary reply text. Null for a pure `suggestions` response. */
    val message: String?,

    /** Suggestion texts. Populated for `coach` and `suggestions`. */
    val suggestions: List<String>,
) {
    /**
     * Best-effort display string — [message] if present, else the first
     * suggestion, else a neutral placeholder.
     */
    val displayText: String
        get() {
            val m = message?.trim()
            if (!m.isNullOrEmpty()) return m
            if (suggestions.isNotEmpty()) return suggestions.first()
            return "Syni had no response."
        }

    override fun toString(): String =
        "SyniChatResponse(persona=$personaId, kind=$kind, message=${message?.take(40)})"

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Parse the runtime's final `EngineResponse` JSON string. Tolerant
         * of unexpected shapes — falls back to [SyniResponseKind.UNKNOWN].
         */
        fun fromRuntimeJson(
            rawJson: String,
            personaId: String,
            runtimeVersion: String,
        ): SyniChatResponse {
            var kind = SyniResponseKind.UNKNOWN
            var message: String? = null
            val suggestions = mutableListOf<String>()

            try {
                val obj = json.parseToJsonElement(rawJson) as? JsonObject
                val data = obj?.get("data") as? JsonObject
                if (obj != null && data != null) {
                    when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                        "coach" -> {
                            kind = SyniResponseKind.COACH
                            message = data["message"]?.jsonPrimitive?.contentOrNull
                            suggestions.addAll(suggestionTexts(data["suggestions"]))
                        }
                        "chat" -> {
                            kind = SyniResponseKind.CHAT
                            message = data["message"]?.jsonPrimitive?.contentOrNull
                        }
                        "suggestions" -> {
                            kind = SyniResponseKind.SUGGESTIONS
                            suggestions.addAll(suggestionTexts(data["suggestions"]))
                        }
                    }
                }
            } catch (_: Exception) {
                // leave as unknown
            }

            return SyniChatResponse(
                personaId = personaId,
                runtimeVersion = runtimeVersion,
                rawJson = rawJson,
                kind = kind,
                message = message,
                suggestions = suggestions.toList(),
            )
        }

        /**
         * Build a response from a cloud `reply` string. `syni-service`'s
         * non-streaming response is `{reply: "<plain text>", …}`.
         */
        fun fromCloudReply(
            reply: String,
            personaId: String,
            runtimeVersion: String,
        ): SyniChatResponse = SyniChatResponse(
            personaId = personaId,
            runtimeVersion = runtimeVersion,
            rawJson = reply,
            kind = SyniResponseKind.CHAT,
            message = reply,
            suggestions = emptyList(),
        )

        private fun suggestionTexts(raw: kotlinx.serialization.json.JsonElement?): List<String> {
            val arr = raw as? JsonArray ?: return emptyList()
            return arr.mapNotNull {
                (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Streaming events — emitted by `SyniAgent.chatStream`.
// ---------------------------------------------------------------------------

/**
 * One event in a streaming chat response. Discriminated union over
 * incremental deltas and the final structured response.
 */
sealed class SyniChatEvent

/**
 * An incremental token chunk emitted during generation.
 *
 * For structured (coach / chat) schemas these are raw JSON tokens — not
 * directly user-presentable. UI typically shows a "thinking" state until
 * [SyniChatFinal] arrives.
 */
data class SyniChatDelta(val text: String) : SyniChatEvent() {
    override fun toString(): String = "SyniChatDelta(${text.length} chars)"
}

/**
 * The final parsed response. Always emitted exactly once at the end of a
 * successful stream.
 */
data class SyniChatFinal(val response: SyniChatResponse) : SyniChatEvent() {
    override fun toString(): String = "SyniChatFinal(persona=${response.personaId})"
}
