/**
 * Syni Runtime JNI Bridge
 *
 * This file provides JNI bindings from Kotlin/Android to the syni-runtime C API.
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdbool.h>
#include <stdint.h>

// Forward declarations for syni-runtime C API
typedef struct syni_engine_t syni_engine_t;

typedef enum syni_preset_t {
    SYNI_PRESET_KEYBOARD = 0,
    SYNI_PRESET_COACH = 1,
    SYNI_PRESET_CHAT = 2,
} syni_preset_t;

// syni-runtime C API functions (linked from libsyni_ffi.so)
extern syni_engine_t* syni_engine_new(void);
extern syni_engine_t* syni_engine_new_with_model(const char* model_path);
extern bool syni_engine_load_model(syni_engine_t* engine, const char* model_path);
extern void syni_engine_free(syni_engine_t* engine);
extern void syni_string_free(char* s);
extern char* syni_engine_run_json(syni_engine_t* engine, syni_preset_t preset, uint64_t seed, const char* request_json);
extern int32_t syni_engine_healthcheck(syni_engine_t* engine);
extern int32_t syni_token_count(syni_engine_t* engine, const char* model_path, const char* text);
extern char* syni_version(void);

// JNI helper macros — package path matches `ai.synheart.syni.runtime.SyniNative`.
#define JNI_METHOD(returnType, methodName) \
    JNIEXPORT returnType JNICALL Java_ai_synheart_syni_runtime_SyniNative_##methodName

// Convert Java string to C string (caller must free)
static char* jstring_to_cstring(JNIEnv *env, jstring jstr) {
    if (jstr == NULL) return NULL;
    const char *utf = (*env)->GetStringUTFChars(env, jstr, NULL);
    if (utf == NULL) return NULL;
    char *copy = strdup(utf);
    (*env)->ReleaseStringUTFChars(env, jstr, utf);
    return copy;
}

// Convert C string to Java string (handles NULL)
static jstring cstring_to_jstring(JNIEnv *env, const char *str) {
    if (str == NULL) return NULL;
    return (*env)->NewStringUTF(env, str);
}

/**
 * Create a new engine instance with a model.
 *
 * @param modelPath Path to the GGUF model file
 * @return Native handle (pointer), or 0 on failure
 */
JNI_METHOD(jlong, nativeEngineCreate)(JNIEnv *env, jclass clazz, jstring modelPath) {
    char *path = jstring_to_cstring(env, modelPath);
    if (path == NULL) return 0;

    syni_engine_t *engine = syni_engine_new_with_model(path);
    free(path);

    return (jlong)(intptr_t)engine;
}

/**
 * Load a model into an existing engine.
 *
 * @param handle Native engine handle
 * @param modelPath Path to the GGUF model file
 * @return true on success
 */
JNI_METHOD(jboolean, nativeEngineLoadModel)(JNIEnv *env, jclass clazz, jlong handle, jstring modelPath) {
    if (handle == 0) return JNI_FALSE;

    syni_engine_t *engine = (syni_engine_t *)(intptr_t)handle;
    char *path = jstring_to_cstring(env, modelPath);
    if (path == NULL) return JNI_FALSE;

    bool result = syni_engine_load_model(engine, path);
    free(path);

    return result ? JNI_TRUE : JNI_FALSE;
}

/**
 * Free an engine instance.
 *
 * @param handle Native engine handle
 */
JNI_METHOD(void, nativeEngineFree)(JNIEnv *env, jclass clazz, jlong handle) {
    if (handle == 0) return;
    syni_engine_t *engine = (syni_engine_t *)(intptr_t)handle;
    syni_engine_free(engine);
}

/**
 * Run health check on the engine.
 *
 * @param handle Native engine handle
 * @return 0 on success, non-zero on error
 */
JNI_METHOD(jint, nativeEngineHealthcheck)(JNIEnv *env, jclass clazz, jlong handle) {
    if (handle == 0) return -1;
    syni_engine_t *engine = (syni_engine_t *)(intptr_t)handle;
    return syni_engine_healthcheck(engine);
}

/**
 * Run synchronous JSON inference.
 *
 * @param handle Native engine handle
 * @param preset Performance preset (0=keyboard, 1=coach, 2=chat)
 * @param seed Random seed
 * @param requestJson JSON request string
 * @return JSON response string, or null on error
 */
JNI_METHOD(jstring, nativeEngineRunJson)(JNIEnv *env, jclass clazz, jlong handle, jint preset, jlong seed, jstring requestJson) {
    if (handle == 0) return NULL;

    syni_engine_t *engine = (syni_engine_t *)(intptr_t)handle;
    char *request = jstring_to_cstring(env, requestJson);
    if (request == NULL) return NULL;

    char *response = syni_engine_run_json(engine, (syni_preset_t)preset, (uint64_t)seed, request);
    free(request);

    if (response == NULL) return NULL;

    jstring result = cstring_to_jstring(env, response);
    syni_string_free(response);

    return result;
}

/**
 * Count tokens in text.
 *
 * @param handle Native engine handle
 * @param text Text to count tokens for
 * @return Token count, or -1 on error
 */
JNI_METHOD(jint, nativeTokenCount)(JNIEnv *env, jclass clazz, jlong handle, jstring text) {
    if (handle == 0) return -1;

    syni_engine_t *engine = (syni_engine_t *)(intptr_t)handle;
    char *text_str = jstring_to_cstring(env, text);
    if (text_str == NULL) return -1;

    int32_t count = syni_token_count(engine, NULL, text_str);
    free(text_str);

    return count;
}

/**
 * Get library version.
 *
 * @return Version string
 */
JNI_METHOD(jstring, nativeVersion)(JNIEnv *env, jclass clazz) {
    char *version = syni_version();
    if (version == NULL) return cstring_to_jstring(env, "unknown");

    jstring result = cstring_to_jstring(env, version);
    syni_string_free(version);

    return result;
}
