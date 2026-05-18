package ai.synheart.syni.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Preset selector matching the C ABI's `syni_preset_t`. */
enum class SyniPreset(internal val raw: Int) {
    KEYBOARD(SyniNative.Preset.KEYBOARD),
    COACH(SyniNative.Preset.COACH),
    CHAT(SyniNative.Preset.CHAT),
}

/** A single inference request to the local runtime. */
data class SyniRuntimeRequest(
    val instruction: String,
    val hsi: Map<String, String>? = null,
    val schema: String? = null,
)

/** Wrapper around the final raw JSON returned by the runtime. */
data class SyniRuntimeResult(val rawJson: String)

/** Streaming event from [SyniRuntime.runStream]. */
sealed class SyniRuntimeStreamEvent
data class SyniRuntimeStreamDelta(val text: String) : SyniRuntimeStreamEvent()
data class SyniRuntimeStreamFinal(val rawJson: String) : SyniRuntimeStreamEvent()

/**
 * Thin Kotlin wrapper around the [SyniNative] JNI bridge. Mirrors the
 * `SyniRuntime` worker-isolate facade used by the Flutter SDK — same
 * `initialize / loadModel / run / runStream / dispose / getVersion`
 * surface, but everything runs on `Dispatchers.Default` here.
 *
 * Internal — consumers should use [ai.synheart.syni.SyniAgent].
 */
internal class SyniRuntime {
    private var handle: Long = 0L
    private val lock = Any()

    suspend fun initialize() {
        // Loading is lazy in SyniNative — touching version() forces it.
        withContext(Dispatchers.Default) { SyniNative.version() }
    }

    suspend fun loadModel(modelPath: String): Unit = withContext(Dispatchers.Default) {
        synchronized(lock) {
            if (handle != 0L) {
                SyniNative.engineFree(handle); handle = 0L
            }
            handle = SyniNative.engineCreate(modelPath)
            if (handle == 0L) error("syni_engine_new_with_model returned null for $modelPath")
            val hc = SyniNative.engineHealthcheck(handle)
            if (hc != 0) {
                SyniNative.engineFree(handle); handle = 0L
                error("engine healthcheck failed with code $hc")
            }
        }
    }

    suspend fun getVersion(): String? = withContext(Dispatchers.Default) {
        runCatching { SyniNative.version() }.getOrNull()
    }

    suspend fun run(
        request: SyniRuntimeRequest,
        preset: SyniPreset = SyniPreset.CHAT,
        seed: Long = 0L,
    ): SyniRuntimeResult = withContext(Dispatchers.Default) {
        val h = synchronized(lock) { handle }
        if (h == 0L) error("runtime not initialized — call loadModel() first")
        val body = encodeRequest(request)
        val raw = SyniNative.engineRunJson(h, preset.raw, seed, body)
            ?: error("syni_engine_run_json returned null")
        SyniRuntimeResult(raw)
    }

    /**
     * Streaming variant. V1 emits a single [SyniRuntimeStreamDelta] with the
     * full text followed by a [SyniRuntimeStreamFinal] — the JNI layer does
     * not yet expose `syni_engine_run_stream_json`. The API contract holds
     * (delta(s) then exactly one final) so consumers don't need to change
     * when token-level streaming lands.
     */
    fun runStream(
        request: SyniRuntimeRequest,
        preset: SyniPreset = SyniPreset.CHAT,
        seed: Long = 0L,
    ): Flow<SyniRuntimeStreamEvent> = flow {
        val result = run(request, preset, seed)
        emit(SyniRuntimeStreamDelta(result.rawJson))
        emit(SyniRuntimeStreamFinal(result.rawJson))
    }.flowOn(Dispatchers.Default)

    suspend fun dispose(): Unit = withContext(Dispatchers.Default) {
        synchronized(lock) {
            if (handle != 0L) {
                SyniNative.engineFree(handle); handle = 0L
            }
        }
    }

    private fun encodeRequest(r: SyniRuntimeRequest): String {
        val obj: JsonObject = buildJsonObject {
            put("instruction", r.instruction)
            r.schema?.let { put("schema", it) }
            r.hsi?.let { hsi ->
                put("hsi", buildJsonObject { hsi.forEach { (k, v) -> put(k, v) } })
            }
        }
        return obj.toString()
    }
}
