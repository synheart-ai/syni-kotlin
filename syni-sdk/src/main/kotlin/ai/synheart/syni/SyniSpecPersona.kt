package ai.synheart.syni

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Loads a [SyniPersona] from a bundled `syni-spec` JSON asset.
 *
 * Mirrors `SyniSpecPersona` from `package:syni`. V1 lookup is by id only
 * (e.g. `focus.coach.v1`) and searches the `prod` tier under
 * `assets/personas/prod/<id>.json`.
 */
object SyniSpecPersona {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Resolve and parse the bundled persona JSON for [id]. Throws
     * [SyniSpecPersonaException] when the id is unknown or the JSON
     * can't be projected onto [SyniPersona].
     */
    fun load(context: Context, id: String): SyniPersona {
        val assetPath = "personas/prod/$id.json"
        val raw = try {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            throw SyniSpecPersonaException(
                "persona \"$id\" not bundled at $assetPath",
                cause = e,
            )
        }

        val parsed = try {
            json.parseToJsonElement(raw).let { it as? JsonObject }
                ?: throw SyniSpecPersonaException("persona \"$id\": top-level JSON must be an object")
        } catch (e: SyniSpecPersonaException) {
            throw e
        } catch (e: Exception) {
            throw SyniSpecPersonaException("persona \"$id\": invalid JSON", cause = e)
        }

        return fromJson(parsed)
    }

    internal fun fromJson(j: JsonObject): SyniPersona {
        val id = j["id"]?.jsonPrimitive?.contentOrNull
        val name = j["name"]?.jsonPrimitive?.contentOrNull
        val systemPrompt = j["system_prompt"]?.jsonPrimitive?.contentOrNull
        val outputSchemaId = j["output_schema_id"]?.jsonPrimitive?.contentOrNull
        if (id == null || name == null || systemPrompt == null || outputSchemaId == null) {
            throw SyniSpecPersonaException(
                "spec persona missing one of {id, name, system_prompt, output_schema_id}"
            )
        }
        return SyniPersona(
            id = id,
            displayName = name,
            systemPrompt = systemPrompt,
            responseSchemaId = normalizeOutputSchema(outputSchemaId),
        )
    }

    /**
     * Map a versioned spec output schema id to the short name the runtime
     * expects (`coach`, `chat`, `suggestions`). Stripped of any `.response.vN`
     * suffix and lowercased.
     */
    internal fun normalizeOutputSchema(id: String): String =
        id.substringBefore('.').lowercase()
}

class SyniSpecPersonaException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    override fun toString(): String {
        val tail = cause?.let { " (cause: $it)" } ?: ""
        return "SyniSpecPersonaException: $message$tail"
    }
}
