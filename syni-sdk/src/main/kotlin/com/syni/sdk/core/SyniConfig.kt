package com.syni.sdk.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Configuration for the Syni SDK.
 */
@Serializable
data class SyniConfig(
    /** Spec version this config targets. */
    @SerialName("spec_version")
    val specVersion: String = "1.0",

    /** File paths configuration. */
    val paths: PathsConfig = PathsConfig(),

    /** Cloud gateway configuration. */
    @SerialName("cloud")
    val cloudConfig: CloudConfig? = null,

    /** Local engine configuration. */
    @SerialName("local_engine")
    val localEngineConfig: LocalEngineConfig = LocalEngineConfig(),

    /** Enable debug logging. */
    val debug: Boolean = false,

    /** Enable telemetry collection. */
    val telemetry: Boolean = true
) {
    companion object {
        /** Default configuration for local-only usage. */
        val LOCAL_ONLY = SyniConfig(
            cloudConfig = null
        )

        /** Create a config with cloud enabled. */
        fun withCloud(
            gatewayURL: String,
            authToken: String,
            timeoutSeconds: Int = 30
        ) = SyniConfig(
            cloudConfig = CloudConfig(
                gatewayURL = gatewayURL,
                authToken = authToken,
                timeoutSeconds = timeoutSeconds
            )
        )
    }
}

/**
 * File paths for SDK resources.
 */
@Serializable
data class PathsConfig(
    /** Directory for persona definitions. Null uses built-in personas. */
    @SerialName("personas_dir")
    val personasDir: String? = null,

    /** Directory for schema definitions. Null uses built-in schemas. */
    @SerialName("schemas_dir")
    val schemasDir: String? = null,

    /** Directory for grammar definitions. Null uses built-in grammars. */
    @SerialName("grammars_dir")
    val grammarsDir: String? = null,

    /** Directory for downloaded models. Null uses app's private files dir. */
    @SerialName("models_dir")
    val modelsDir: String? = null
)

/**
 * Cloud gateway configuration.
 */
@Serializable
data class CloudConfig(
    /** URL of the Syni cloud gateway. */
    @SerialName("gateway_url")
    val gatewayURL: String,

    /** Authentication token. */
    @SerialName("auth_token")
    val authToken: String,

    /** Request timeout in seconds. */
    @SerialName("timeout_seconds")
    val timeoutSeconds: Int = 30,

    /** Maximum retries for failed requests. */
    @SerialName("max_retries")
    val maxRetries: Int = 2,

    /** Enable HTTP/2 multiplexing. */
    @SerialName("enable_http2")
    val enableHttp2: Boolean = true
) {
    init {
        require(gatewayURL.isNotBlank()) { "gatewayURL cannot be blank" }
        require(authToken.isNotBlank()) { "authToken cannot be blank" }
        require(timeoutSeconds > 0) { "timeoutSeconds must be positive" }
        require(maxRetries >= 0) { "maxRetries cannot be negative" }
    }
}

/**
 * Local engine configuration.
 */
@Serializable
data class LocalEngineConfig(
    /** Number of threads for inference. 0 = auto-detect. */
    @SerialName("thread_count")
    val threadCount: Int = 0,

    /** Enable GPU acceleration (Vulkan on Android). */
    @SerialName("use_gpu")
    val useGPUAcceleration: Boolean = true,

    /** Maximum context length in tokens. */
    @SerialName("max_context_length")
    val maxContextLength: Int = 2048,

    /** Number of layers to offload to GPU. -1 = all. */
    @SerialName("gpu_layers")
    val gpuLayers: Int = -1,

    /** Batch size for prompt processing. */
    @SerialName("batch_size")
    val batchSize: Int = 512,

    /** Memory map model file (reduces RAM usage). */
    @SerialName("use_mmap")
    val useMmap: Boolean = true,

    /** Lock model in memory (prevents swapping). */
    @SerialName("use_mlock")
    val useMlock: Boolean = false
) {
    init {
        require(threadCount >= 0) { "threadCount cannot be negative" }
        require(maxContextLength > 0) { "maxContextLength must be positive" }
        require(batchSize > 0) { "batchSize must be positive" }
    }
}
