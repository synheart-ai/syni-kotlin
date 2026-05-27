package ai.synheart.syni

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * One entry in the Syni model catalog — a **local** (on-device GGUF) or
 * **cloud** (server-side) model. Mirrors `SyniModelOption` from `package:syni`.
 */
sealed class SyniModelOption {
    abstract val id: String
    abstract val displayName: String
    abstract val description: String
    abstract val optimizedFor: Set<String>
    abstract val requires: Set<String>
    abstract val isDefault: Boolean

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Parse one `/v1/models` manifest entry. */
        fun fromJson(j: JsonObject): SyniModelOption {
            val id = j["id"]!!.jsonPrimitive.content
            val displayName = j["display_name"]?.jsonPrimitive?.contentOrNull ?: id
            val description = j["description"]?.jsonPrimitive?.contentOrNull ?: ""
            val optimizedFor = strSet(j["optimized_for"])
            val requires = strSet(j["requires"])
            val isDefault = j["default"]?.jsonPrimitive?.contentOrNull == "true"
            return when (j["kind"]?.jsonPrimitive?.contentOrNull) {
                "local" -> SyniLocalModel(
                    id = id, displayName = displayName, description = description,
                    optimizedFor = optimizedFor, requires = requires, isDefault = isDefault,
                    spec = SyniModelSpec.fromManifest(id, j["local"]!!.jsonObject),
                )
                "cloud" -> SyniCloudModel(
                    id = id, displayName = displayName, description = description,
                    optimizedFor = optimizedFor, requires = requires, isDefault = isDefault,
                    serviceModelId = j["cloud"]?.jsonObject?.get("service_model_id")
                        ?.jsonPrimitive?.contentOrNull ?: "default",
                )
                else -> throw IllegalArgumentException("unknown model kind: ${j["kind"]}")
            }
        }

        private fun strSet(raw: Any?): Set<String> {
            if (raw !is kotlinx.serialization.json.JsonArray) return emptySet()
            return raw.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
        }
    }
}

/** An on-device model — runs via the local runtime. */
data class SyniLocalModel(
    override val id: String,
    override val displayName: String,
    override val description: String,
    override val optimizedFor: Set<String>,
    override val requires: Set<String>,
    override val isDefault: Boolean,
    val spec: SyniModelSpec,
) : SyniModelOption()

/** A server-side model — runs via `syni-service`. */
data class SyniCloudModel(
    override val id: String,
    override val displayName: String,
    override val description: String,
    override val optimizedFor: Set<String>,
    override val requires: Set<String>,
    override val isDefault: Boolean,
    val serviceModelId: String,
) : SyniModelOption()

/**
 * Fetches + caches the Syni model catalog from `syni-service`
 * `GET /v1/models`. Mirrors `SyniModelCatalog` from `package:syni`.
 *
 * Auth token and base URL are injected by the host SDK — `syni-kotlin` does
 * not own credentials. With no base URL configured, or on any fetch failure,
 * falls back to [bundled].
 */
class SyniModelCatalog(
    private val baseUrl: String? = null,
    private val authHeaders: (suspend (method: String, url: String) -> Map<String, String>)? = null,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var cache: List<SyniModelOption>? = null

    /**
     * Available models. Fetches `/v1/models` on first call (when a base URL
     * is configured), caches the result, and falls back to [bundled] on any
     * failure. Pass [forceRefresh] to re-fetch.
     */
    suspend fun available(forceRefresh: Boolean = false): List<SyniModelOption> =
        withContext(Dispatchers.IO) {
            cache?.takeIf { !forceRefresh }?.let { return@withContext it }
            if (baseUrl == null) {
                cache = bundled
                return@withContext bundled
            }
            try {
                val url = "$baseUrl/v1/models"
                val builder = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                authHeaders?.invoke("GET", url)?.forEach { (k, v) -> builder.header(k, v) }
                val resp = httpClient.newCall(builder.build()).execute()
                resp.use {
                    if (!it.isSuccessful) {
                        cache = bundled; return@withContext bundled
                    }
                    val body = it.body?.string().orEmpty()
                    val decoded = json.parseToJsonElement(body) as JsonObject
                    val models = (decoded["models"] as? JsonArray)
                        ?.mapNotNull { e -> (e as? JsonObject)?.let(SyniModelOption.Companion::fromJson) }
                        .orEmpty()
                    cache = models.ifEmpty { bundled }
                    cache!!
                }
            } catch (_: Exception) {
                cache = bundled
                bundled
            }
        }

    companion object {
        /** The bundled fallback catalog — always available, no network. */
        val bundled: List<SyniModelOption> = listOf(
            SyniLocalModel(
                id = SyniModels.qwen25_15bInstructQ4.id,
                displayName = "Qwen2.5 1.5B (on-device)",
                description = "Runs fully on-device. Private, offline-capable, low-latency. " +
                    "Modest reasoning depth.",
                optimizedFor = setOf("offline", "privacy", "low_latency"),
                requires = emptySet(),
                isDefault = true,
                spec = SyniModels.qwen25_15bInstructQ4,
            ),
            SyniCloudModel(
                id = "cloud-default",
                displayName = "Syni Cloud",
                description = "Runs server-side via Syni Cloud. Deeper reasoning, longer " +
                    "context. Requires a network connection.",
                optimizedFor = setOf("deep_reasoning", "long_context", "quality"),
                requires = setOf("network"),
                isDefault = false,
                serviceModelId = "default",
            ),
        )
    }
}
