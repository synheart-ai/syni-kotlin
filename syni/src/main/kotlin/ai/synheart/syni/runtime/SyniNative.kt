package ai.synheart.syni.runtime

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * JNA bridge to `libsyni_ffi` (the syni-runtime C ABI).
 *
 * The native library is **not** shipped inside this AAR. Consumers install
 * it once with the `synheart` CLI:
 *
 *     synheart install runtime syni
 *
 * which writes `libsyni_ffi.so` for every Android ABI under
 * `<app>/synheart/vendor/syni/android/jniLibs/`. To make JNA find the
 * library at runtime, consumers add that directory to their **app**
 * module's `sourceSets.main.jniLibs.srcDirs` — see README.
 *
 * Internal to the SDK — public orchestration lives in
 * [ai.synheart.syni.SyniAgent].
 */
internal interface SyniNative : Library {

    fun syni_engine_new_with_model(modelPath: String?): Pointer?
    fun syni_engine_free(handle: Pointer?)
    fun syni_engine_healthcheck(handle: Pointer?): Int
    fun syni_engine_run_json(handle: Pointer?, preset: Int, seed: Long, requestJson: String?): Pointer?
    fun syni_string_free(ptr: Pointer?)
    fun syni_version(): Pointer?

    companion object {
        /** Performance presets matching `syni_preset_t`. */
        const val PRESET_KEYBOARD = 0
        const val PRESET_COACH = 1
        const val PRESET_CHAT = 2

        /**
         * Lazily loaded native library instance. `null` if `libsyni_ffi`
         * is not on the JNA library path — typically because the consumer
         * forgot to run `synheart install runtime syni` or wire the
         * vendor path into their app's `jniLibs.srcDirs`.
         */
        val INSTANCE: SyniNative? = try {
            Native.load("syni_ffi", SyniNative::class.java)
        } catch (_: UnsatisfiedLinkError) {
            null
        }

        /**
         * Read and free a C string returned by the runtime. The pointer
         * must have come from a `syni_*` function that documents its
         * return as a transfer-of-ownership string.
         */
        fun readAndFreeString(native: SyniNative, ptr: Pointer?): String? {
            if (ptr == null) return null
            return try {
                ptr.getString(0)
            } finally {
                native.syni_string_free(ptr)
            }
        }
    }
}
