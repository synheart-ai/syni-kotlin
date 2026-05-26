package ai.synheart.syni.runtime

import com.sun.jna.Pointer
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
    KEYBOARD(SyniNative.PRESET_KEYBOARD),
    COACH(SyniNative.PRESET_COACH),
    CHAT(SyniNative.PRESET_CHAT),
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
 * Thin Kotlin wrapper around the [SyniNative] JNA bridge. Mirrors the
 * `SyniRuntime` worker-isolate facade used by the Flutter SDK — same
 * `initialize / loadModel / run / runStream / dispose / getVersion`
 * surface, but everything runs on `Dispatchers.Default` here.
 *
 * Internal — consumers should use [ai.synheart.syni.SyniAgent].
 */
internal class SyniRuntime {
    private var handle: Pointer? = null
    private val lock = Any()

    private fun native(): SyniNative = SyniNative.INSTANCE
        ?: error("libsyni_ffi not loaded — did you run `synheart install runtime syni` and wire the vendor path into your app's jniLibs.srcDirs?")

    suspend fun initialize() {
        // Touch version() to force JNA load + report readiness.
        withContext(Dispatchers.Default) { getVersion() }
    }

    suspend fun loadModel(modelPath: String): Unit = withContext(Dispatchers.Default) {
        val n = native()
        synchronized(lock) {
            val prev = handle
            if (prev != null) {
                n.syni_engine_free(prev)
                handle = null
            }
            val h = n.syni_engine_new_with_model(modelPath)
                ?: error("syni_engine_new_with_model returned null for $modelPath")
            val hc = n.syni_engine_healthcheck(h)
            if (hc != 0) {
                n.syni_engine_free(h)
                error("engine healthcheck failed with code $hc")
            }
            handle = h
        }
    }

    suspend fun getVersion(): String? = withContext(Dispatchers.Default) {
        runCatching {
            val n = native()
            SyniNative.readAndFreeString(n, n.syni_version())
        }.getOrNull()
    }

    suspend fun run(
        request: SyniRuntimeRequest,
        preset: SyniPreset = SyniPreset.CHAT,
        seed: Long = 0L,
    ): SyniRuntimeResult = withContext(Dispatchers.Default) {
        val n = native()
        val h = synchronized(lock) { handle }
            ?: error("runtime not initialized — call loadModel() first")
        val body = encodeRequest(request)
        val rawPtr = n.syni_engine_run_json(h, preset.raw, seed, body)
            ?: error("syni_engine_run_json returned null")
        val raw = SyniNative.readAndFreeString(n, rawPtr)
            ?: error("syni_engine_run_json returned empty pointer")
        SyniRuntimeResult(raw)
    }

    /**
     * Streaming variant. V1 emits a single [SyniRuntimeStreamDelta] with
     * the full text followed by a [SyniRuntimeStreamFinal] — the FFI does
     * not yet expose `syni_engine_run_stream_json`. Contract holds
     * (delta(s) then exactly one final) so consumers don't need to
     * change when token-level streaming lands.
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
            val h = handle
            val n = SyniNative.INSTANCE
            if (h != null && n != null) n.syni_engine_free(h)
            handle = null
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
