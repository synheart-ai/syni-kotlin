package ai.synheart.syni

import ai.synheart.syni.runtime.SyniPreset
import ai.synheart.syni.runtime.SyniRuntime
import ai.synheart.syni.runtime.SyniRuntimeRequest
import ai.synheart.syni.runtime.SyniRuntimeStreamDelta
import ai.synheart.syni.runtime.SyniRuntimeStreamFinal
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Where a chat call should run. Mirrors `SyniExecutionMode` from
 * `package:syni`.
 *
 * - [LOCAL_ONLY]   — always runs on the local runtime. Throws if no model is
 *   installed. Offline-safe; never touches the network.
 * - [CLOUD_ONLY]   — always `syni-service`. Throws if no cloud config was
 *   injected.
 * - [LOCAL_FIRST]  — try local, fall back to cloud on failure or when local
 *   isn't installed. Sensible default.
 */
enum class SyniExecutionMode { LOCAL_ONLY, CLOUD_ONLY, LOCAL_FIRST }

/**
 * Orchestrates an installed, persona-bound Syni: install lifecycle, model
 * management, and chat over the local runtime. Mirrors `SyniAgent` from
 * `package:syni`.
 *
 * **Layering:** this is Syni's orchestration layer. It is HSI-agnostic — it
 * takes the conditioning context as a plain `Map<String, Any?>?`
 * (`hsiContext`) and never imports a host-SDK type. The host SDK
 * (`synheart-core-kotlin`) wraps this with its four-authority gate and its
 * HSI context builder.
 */
class SyniAgent(
    private val context: Context,
    installer: SyniInstaller? = null,
    cloudConfig: SyniCloudConfig? = null,
) {
    private val installer: SyniInstaller = installer ?: SyniInstaller(context.applicationContext)
    private val runtime = SyniRuntime()
    private val cloudClient: SyniCloudClient? = cloudConfig?.let { SyniCloudClient(it) }
    private val state = MutableStateFlow<SyniInstallState>(SyniInstallState.NotInstalled)

    @Volatile private var persona: SyniPersona? = null

    /** Whether a cloud client is configured (i.e. cloud chat is reachable). */
    val hasCloud: Boolean get() = cloudClient != null

    /** Hot stream of installation lifecycle events. */
    val installState: StateFlow<SyniInstallState> get() = state.asStateFlow()

    /** Current installation state. */
    val currentState: SyniInstallState get() = state.value

    /** True iff [currentState] is [SyniInstallState.Installed]. */
    val isInstalled: Boolean get() = state.value is SyniInstallState.Installed

    // -------------------------------------------------------------------------
    // Install / uninstall
    // -------------------------------------------------------------------------

    /**
     * Install Syni: download + verify the model, load the engine, bind
     * [persona]. Emits lifecycle events on [installState]; throws on
     * failure (after emitting [SyniInstallState.Failed]).
     */
    suspend fun install(persona: SyniPersona, model: SyniModelSpec) {
        if (currentState is SyniInstallState.Installing) {
            throw IllegalStateException("install already in progress")
        }
        try {
            fun emit(stage: SyniInstallStage, progress: Double) {
                state.value = SyniInstallState.Installing(stage, progress)
            }
            emit(SyniInstallStage.PREFLIGHT, 0.0)

            val modelPath = installer.ensureModel(model) { stage, progress -> emit(stage, progress) }

            emit(SyniInstallStage.MATERIALIZING_PERSONA, 0.0)
            this.persona = persona
            emit(SyniInstallStage.MATERIALIZING_PERSONA, 1.0)

            emit(SyniInstallStage.LOADING_ENGINE, 0.0)
            runtime.initialize()
            runtime.loadModel(modelPath)
            val version = runtime.getVersion() ?: "unknown"
            emit(SyniInstallStage.LOADING_ENGINE, 1.0)

            state.value = SyniInstallState.Installed(
                personaId = persona.id,
                modelPath = modelPath,
                runtimeVersion = version,
            )
        } catch (e: Throwable) {
            state.value = SyniInstallState.Failed(reason = e.message ?: e.toString(), cause = e)
            throw e
        }
    }

    /**
     * Cold-start restore: if the model + tokenizer are already on disk for
     * [model], bind [persona] and load the engine — no download. Otherwise
     * leaves state as [SyniInstallState.NotInstalled] and returns false.
     */
    suspend fun restoreInstallIfReady(persona: SyniPersona, model: SyniModelSpec): Boolean {
        if (currentState is SyniInstallState.Installed || currentState is SyniInstallState.Installing) {
            return isInstalled
        }
        if (!installer.isModelOnDisk(model)) return false
        try {
            install(persona, model)
        } catch (_: Throwable) {
            // Failure already emitted SyniInstallFailed; the screen handles it.
        }
        return isInstalled
    }

    /**
     * Free the engine. Keeps the downloaded model on disk — re-installing
     * reuses it.
     */
    suspend fun uninstall() {
        runtime.dispose()
        persona = null
        state.value = SyniInstallState.NotInstalled
    }

    // -------------------------------------------------------------------------
    // Chat
    // -------------------------------------------------------------------------

    /**
     * Run a single chat turn. [hsiContext] is the conditioning payload built
     * by the host SDK (or null). [mode] picks local vs cloud — see
     * [SyniExecutionMode].
     */
    suspend fun chat(
        message: String,
        hsiContext: Map<String, Any?>? = null,
        seed: Long = 0L,
        mode: SyniExecutionMode = SyniExecutionMode.LOCAL_FIRST,
    ): SyniChatResponse {
        val (_, p) = requireReady()
        return route(
            mode,
            local = { localChat(p, message, hsiContext, seed) },
            cloud = { cloudChat(p, message, hsiContext) },
        )
    }

    /**
     * Streaming counterpart to [chat]. Emits [SyniChatDelta]s as tokens
     * arrive, then exactly one [SyniChatFinal].
     */
    fun chatStream(
        message: String,
        hsiContext: Map<String, Any?>? = null,
        seed: Long = 0L,
        mode: SyniExecutionMode = SyniExecutionMode.LOCAL_FIRST,
    ): Flow<SyniChatEvent> = flow {
        val (_, p) = requireReady()
        val flowToEmit = routeStream(
            mode,
            local = { localChatStream(p, message, hsiContext, seed) },
            cloud = { cloudChatStream(p, message, hsiContext) },
        )
        flowToEmit.collect { emit(it) }
    }

    /** For testing / shutdown. Disposes the underlying runtime. */
    suspend fun dispose() {
        runtime.dispose()
    }

    // -------------------------------------------------------------------------
    // Routing
    // -------------------------------------------------------------------------

    private suspend fun route(
        mode: SyniExecutionMode,
        local: suspend () -> SyniChatResponse,
        cloud: suspend () -> SyniChatResponse,
    ): SyniChatResponse = when (mode) {
        SyniExecutionMode.LOCAL_ONLY -> local()
        SyniExecutionMode.CLOUD_ONLY -> {
            if (cloudClient == null) {
                throw IllegalStateException("CLOUD_ONLY requested but no SyniCloudConfig injected")
            }
            cloud()
        }
        SyniExecutionMode.LOCAL_FIRST -> try {
            local()
        } catch (e: Throwable) {
            if (cloudClient == null) throw e
            cloud()
        }
    }

    private fun routeStream(
        mode: SyniExecutionMode,
        local: () -> Flow<SyniChatEvent>,
        cloud: () -> Flow<SyniChatEvent>,
    ): Flow<SyniChatEvent> = flow {
        when (mode) {
            SyniExecutionMode.LOCAL_ONLY -> local().collect { emit(it) }
            SyniExecutionMode.CLOUD_ONLY -> {
                if (cloudClient == null) {
                    throw IllegalStateException("CLOUD_ONLY requested but no SyniCloudConfig injected")
                }
                cloud().collect { emit(it) }
            }
            SyniExecutionMode.LOCAL_FIRST -> {
                var producedAny = false
                try {
                    local().collect {
                        producedAny = true
                        emit(it)
                    }
                } catch (e: Throwable) {
                    if (producedAny || cloudClient == null) throw e
                    cloud().collect { emit(it) }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Local path
    // -------------------------------------------------------------------------

    private suspend fun localChat(
        persona: SyniPersona,
        message: String,
        hsiContext: Map<String, Any?>?,
        seed: Long,
    ): SyniChatResponse {
        val installed = currentState as SyniInstallState.Installed
        val raw = runtime.run(
            SyniRuntimeRequest(
                instruction = formatInstruction(persona, message),
                hsi = hsiContext?.mapValues { it.value?.toString() ?: "" },
                schema = persona.responseSchemaId,
            ),
            preset = presetForSchema(persona.responseSchemaId),
            seed = seed,
        )
        return SyniChatResponse.fromRuntimeJson(
            raw.rawJson,
            personaId = persona.id,
            runtimeVersion = installed.runtimeVersion,
        )
    }

    private fun localChatStream(
        persona: SyniPersona,
        message: String,
        hsiContext: Map<String, Any?>?,
        seed: Long,
    ): Flow<SyniChatEvent> = flow {
        val installed = currentState as SyniInstallState.Installed
        val stream = runtime.runStream(
            SyniRuntimeRequest(
                instruction = formatInstruction(persona, message),
                hsi = hsiContext?.mapValues { it.value?.toString() ?: "" },
                schema = persona.responseSchemaId,
            ),
            preset = presetForSchema(persona.responseSchemaId),
            seed = seed,
        )
        stream.collect { evt ->
            when (evt) {
                is SyniRuntimeStreamDelta -> emit(SyniChatDelta(evt.text))
                is SyniRuntimeStreamFinal -> emit(
                    SyniChatFinal(
                        SyniChatResponse.fromRuntimeJson(
                            evt.rawJson,
                            personaId = persona.id,
                            runtimeVersion = installed.runtimeVersion,
                        )
                    )
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Cloud path
    // -------------------------------------------------------------------------

    private suspend fun cloudChat(
        persona: SyniPersona,
        message: String,
        hsiContext: Map<String, Any?>?,
    ): SyniChatResponse =
        cloudClient!!.chat(message = message, persona = persona, hsiContext = hsiContext)

    private fun cloudChatStream(
        persona: SyniPersona,
        message: String,
        hsiContext: Map<String, Any?>?,
    ): Flow<SyniChatEvent> =
        cloudClient!!.chatStream(message = message, persona = persona, hsiContext = hsiContext)

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private fun requireReady(): Pair<SyniInstallState.Installed, SyniPersona> {
        val s = currentState
        if (s !is SyniInstallState.Installed) {
            throw IllegalStateException("Syni is not installed. Call install() first.")
        }
        val p = persona ?: throw IllegalStateException("persona missing (internal)")
        return s to p
    }

    /**
     * V1: prepend the persona's system prompt inline. V2: have the runtime
     * resolve the persona rather than baking it into the instruction string.
     */
    private fun formatInstruction(persona: SyniPersona, userMessage: String): String =
        "${persona.systemPrompt}\n\nUser: $userMessage"

    private fun presetForSchema(schemaId: String): SyniPreset = when (schemaId) {
        "suggestions" -> SyniPreset.KEYBOARD
        "coach" -> SyniPreset.COACH
        else -> SyniPreset.CHAT
    }
}
