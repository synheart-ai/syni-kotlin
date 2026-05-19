package com.syni.sdk.router

import android.content.Context
import com.syni.sdk.core.EngineType
import com.syni.sdk.core.GenerationOptions
import com.syni.sdk.core.Persona
import com.syni.sdk.core.SyniError
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Routes requests to personas and selects appropriate engines.
 */
class PersonaRouter(
    private val context: Context,
    private val personasDir: String?
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val personas = mutableMapOf<String, Persona>()

    init {
        // Load built-in personas
        loadBuiltInPersonas()

        // Load custom personas from directory
        personasDir?.let { loadPersonasFromDirectory(it) }
    }

    /**
     * Get a persona by ID.
     *
     * @throws SyniError.PersonaNotFound if the persona doesn't exist
     */
    fun getPersona(personaId: String): Persona {
        return personas[personaId]
            ?: throw SyniError.PersonaNotFound(personaId)
    }

    /**
     * Check if a persona exists.
     */
    fun hasPersona(personaId: String): Boolean = personas.containsKey(personaId)

    /**
     * Register a custom persona at runtime.
     */
    fun registerPersona(persona: Persona) {
        personas[persona.id] = persona
    }

    /**
     * Unregister a persona.
     */
    fun unregisterPersona(personaId: String) {
        personas.remove(personaId)
    }

    /**
     * Get all available persona IDs.
     */
    fun availablePersonas(): Set<String> = personas.keys.toSet()

    /**
     * Get all personas.
     */
    fun getAllPersonas(): List<Persona> = personas.values.toList()

    /**
     * Select the engine to use for a request.
     *
     * @param persona The persona for the request
     * @param options Generation options that may override routing
     * @param isLocalAvailable Whether the local engine is available
     * @param isCloudAvailable Whether cloud is configured and available
     * @return The selected engine type
     * @throws SyniError.EngineUnavailable if no suitable engine is available
     */
    fun selectEngine(
        persona: Persona,
        options: GenerationOptions,
        isLocalAvailable: Boolean,
        isCloudAvailable: Boolean
    ): EngineType {
        val policy = persona.routingPolicy

        // Handle explicit options overrides
        if (options.localOnly) {
            if (!policy.allowLocal) {
                throw SyniError.EngineUnavailable(
                    "Persona ${persona.id} does not allow local execution"
                )
            }
            if (!isLocalAvailable) {
                throw SyniError.EngineUnavailable(
                    "Local engine requested but not available"
                )
            }
            return EngineType.LOCAL
        }

        if (options.cloudOnly) {
            if (!policy.allowCloud) {
                throw SyniError.EngineUnavailable(
                    "Persona ${persona.id} does not allow cloud execution"
                )
            }
            if (!isCloudAvailable) {
                throw SyniError.EngineUnavailable(
                    "Cloud engine requested but not configured"
                )
            }
            return EngineType.CLOUD
        }

        // Follow the routing policy's preferred engine order
        for (engine in policy.preferredEngines) {
            when (engine) {
                EngineType.LOCAL -> {
                    if (policy.allowLocal && isLocalAvailable) {
                        return EngineType.LOCAL
                    }
                }
                EngineType.CLOUD -> {
                    if (policy.allowCloud && isCloudAvailable) {
                        return EngineType.CLOUD
                    }
                }
                EngineType.FALLBACK -> {
                    // Fallback is always available as last resort
                    return EngineType.FALLBACK
                }
            }
        }

        // If no preferred engine is available, try any allowed engine
        if (policy.allowLocal && isLocalAvailable) {
            return EngineType.LOCAL
        }
        if (policy.allowCloud && isCloudAvailable) {
            return EngineType.CLOUD
        }

        throw SyniError.EngineUnavailable(
            buildString {
                append("No engine available for persona ${persona.id}. ")
                append("Local available: $isLocalAvailable (allowed: ${policy.allowLocal}), ")
                append("Cloud available: $isCloudAvailable (allowed: ${policy.allowCloud})")
            }
        )
    }

    /**
     * Get the fallback engine if the primary engine fails.
     */
    fun getFallbackEngine(
        persona: Persona,
        failedEngine: EngineType,
        options: GenerationOptions,
        isLocalAvailable: Boolean,
        isCloudAvailable: Boolean
    ): EngineType? {
        val policy = persona.routingPolicy

        // Cannot fall back if options restrict to specific engine
        if (options.localOnly || options.cloudOnly) {
            return null
        }

        return when (failedEngine) {
            EngineType.LOCAL -> {
                if (policy.allowCloud && isCloudAvailable) {
                    EngineType.CLOUD
                } else null
            }
            EngineType.CLOUD -> {
                if (policy.allowLocal && isLocalAvailable) {
                    EngineType.LOCAL
                } else null
            }
            EngineType.FALLBACK -> null
        }
    }

    // --- Private implementation ---

    private fun loadBuiltInPersonas() {
        personas[Persona.KEYBOARD_V1.id] = Persona.KEYBOARD_V1
        personas[Persona.LIFE_COACH_V1.id] = Persona.LIFE_COACH_V1
    }

    private fun loadPersonasFromDirectory(dirPath: String) {
        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) return

        dir.listFiles { file -> file.extension == "json" }?.forEach { file ->
            try {
                val content = file.readText()
                val persona = json.decodeFromString<Persona>(content)
                personas[persona.id] = persona
            } catch (e: Exception) {
                // Log error but continue loading other personas
            }
        }
    }
}
