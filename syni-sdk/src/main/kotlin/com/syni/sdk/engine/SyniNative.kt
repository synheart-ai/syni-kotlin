package com.syni.sdk.engine

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JNI bridge to syni-runtime native library.
 *
 * This singleton manages the native library loading and provides
 * type-safe Kotlin wrappers around the native methods.
 */
object SyniNative {
    private const val TAG = "SyniNative"

    private val isLoaded = AtomicBoolean(false)
    private var loadError: Throwable? = null

    /**
     * Performance presets matching syni_preset_t enum.
     */
    object Preset {
        const val KEYBOARD = 0  // Fast keyboard suggestions (150ms, 64 tokens)
        const val COACH = 1     // Coach responses (2000ms, 256 tokens)
        const val CHAT = 2      // General chat (5000ms, 512 tokens)
    }

    /**
     * Check if the native library is loaded and available.
     */
    val isAvailable: Boolean
        get() = isLoaded.get()

    /**
     * Get the error that occurred during library loading, if any.
     */
    val libraryLoadError: Throwable?
        get() = loadError

    /**
     * Load the native library.
     * This is idempotent - calling multiple times is safe.
     *
     * @return true if library is loaded successfully
     */
    @Synchronized
    fun loadLibrary(): Boolean {
        if (isLoaded.get()) return true

        return try {
            // Load syni_ffi first (the main runtime library)
            System.loadLibrary("syni_ffi")
            // Then load our JNI bridge
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

    /**
     * Ensure the library is loaded before calling native methods.
     *
     * @throws IllegalStateException if library cannot be loaded
     */
    private fun ensureLoaded() {
        if (!isLoaded.get()) {
            if (!loadLibrary()) {
                throw IllegalStateException(
                    "Native library not available: ${loadError?.message}",
                    loadError
                )
            }
        }
    }

    // ========================================================================
    // Public API wrappers
    // ========================================================================

    /**
     * Create a new engine with a model loaded.
     *
     * @param modelPath Path to the GGUF model file
     * @return Engine handle, or 0 on failure
     */
    fun engineCreate(modelPath: String): Long {
        ensureLoaded()
        return nativeEngineCreate(modelPath)
    }

    /**
     * Load a model into an existing engine.
     *
     * @param handle Engine handle
     * @param modelPath Path to the GGUF model file
     * @return true on success
     */
    fun engineLoadModel(handle: Long, modelPath: String): Boolean {
        ensureLoaded()
        return nativeEngineLoadModel(handle, modelPath)
    }

    /**
     * Free an engine instance.
     *
     * @param handle Engine handle
     */
    fun engineFree(handle: Long) {
        if (!isLoaded.get()) return  // Nothing to free if library not loaded
        nativeEngineFree(handle)
    }

    /**
     * Run health check on the engine.
     *
     * @param handle Engine handle
     * @return 0 on success, non-zero error code on failure
     */
    fun engineHealthcheck(handle: Long): Int {
        ensureLoaded()
        return nativeEngineHealthcheck(handle)
    }

    /**
     * Run synchronous JSON inference.
     *
     * @param handle Engine handle
     * @param preset Performance preset (use Preset constants)
     * @param seed Random seed for reproducibility
     * @param requestJson JSON request matching EngineRequest schema
     * @return JSON response string, or null on error
     */
    fun engineRunJson(handle: Long, preset: Int, seed: Long, requestJson: String): String? {
        ensureLoaded()
        return nativeEngineRunJson(handle, preset, seed, requestJson)
    }

    /**
     * Count tokens in text using the model's tokenizer.
     *
     * @param handle Engine handle
     * @param text Text to tokenize
     * @return Token count, or -1 on error
     */
    fun tokenCount(handle: Long, text: String): Int {
        ensureLoaded()
        return nativeTokenCount(handle, text)
    }

    /**
     * Get library version string.
     *
     * @return Version string
     */
    fun version(): String {
        ensureLoaded()
        return nativeVersion() ?: "unknown"
    }

    // ========================================================================
    // Native method declarations
    // ========================================================================

    @JvmStatic
    private external fun nativeEngineCreate(modelPath: String): Long

    @JvmStatic
    private external fun nativeEngineLoadModel(handle: Long, modelPath: String): Boolean

    @JvmStatic
    private external fun nativeEngineFree(handle: Long)

    @JvmStatic
    private external fun nativeEngineHealthcheck(handle: Long): Int

    @JvmStatic
    private external fun nativeEngineRunJson(
        handle: Long,
        preset: Int,
        seed: Long,
        requestJson: String
    ): String?

    @JvmStatic
    private external fun nativeTokenCount(handle: Long, text: String): Int

    @JvmStatic
    private external fun nativeVersion(): String?
}
