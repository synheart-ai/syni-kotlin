package ai.synheart.syni.runtime

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JNI bridge to syni-runtime native library.
 *
 * Internal to the SDK — consumers should not import this directly. Public
 * orchestration lives in `ai.synheart.syni.SyniAgent`.
 */
internal object SyniNative {
    private const val TAG = "SyniNative"

    private val isLoaded = AtomicBoolean(false)
    private var loadError: Throwable? = null

    /** Performance presets matching `syni_preset_t`. */
    object Preset {
        const val KEYBOARD = 0  // ~150ms, 64 tokens
        const val COACH = 1     // ~2000ms, 256 tokens
        const val CHAT = 2      // ~5000ms, 512 tokens
    }

    val isAvailable: Boolean
        get() = isLoaded.get()

    val libraryLoadError: Throwable?
        get() = loadError

    @Synchronized
    fun loadLibrary(): Boolean {
        if (isLoaded.get()) return true
        return try {
            System.loadLibrary("syni_ffi")
            System.loadLibrary("syni_jni")
            isLoaded.set(true)
            Log.i(TAG, "Native library loaded successfully, version: ${version()}")
            true
        } catch (e: UnsatisfiedLinkError) {
            loadError = e
            Log.e(TAG, "Failed to load native library", e)
            false
        } catch (e: SecurityException) {
            loadError = e
            Log.e(TAG, "Security error loading native library", e)
            false
        }
    }

    private fun ensureLoaded() {
        if (!isLoaded.get() && !loadLibrary()) {
            throw IllegalStateException(
                "Native library not available: ${loadError?.message}",
                loadError,
            )
        }
    }

    fun engineCreate(modelPath: String): Long {
        ensureLoaded(); return nativeEngineCreate(modelPath)
    }

    fun engineLoadModel(handle: Long, modelPath: String): Boolean {
        ensureLoaded(); return nativeEngineLoadModel(handle, modelPath)
    }

    fun engineFree(handle: Long) {
        if (!isLoaded.get()) return
        nativeEngineFree(handle)
    }

    fun engineHealthcheck(handle: Long): Int {
        ensureLoaded(); return nativeEngineHealthcheck(handle)
    }

    fun engineRunJson(handle: Long, preset: Int, seed: Long, requestJson: String): String? {
        ensureLoaded(); return nativeEngineRunJson(handle, preset, seed, requestJson)
    }

    fun tokenCount(handle: Long, text: String): Int {
        ensureLoaded(); return nativeTokenCount(handle, text)
    }

    fun version(): String {
        ensureLoaded(); return nativeVersion() ?: "unknown"
    }

    @JvmStatic private external fun nativeEngineCreate(modelPath: String): Long
    @JvmStatic private external fun nativeEngineLoadModel(handle: Long, modelPath: String): Boolean
    @JvmStatic private external fun nativeEngineFree(handle: Long)
    @JvmStatic private external fun nativeEngineHealthcheck(handle: Long): Int
    @JvmStatic private external fun nativeEngineRunJson(
        handle: Long, preset: Int, seed: Long, requestJson: String,
    ): String?
    @JvmStatic private external fun nativeTokenCount(handle: Long, text: String): Int
    @JvmStatic private external fun nativeVersion(): String?
}
