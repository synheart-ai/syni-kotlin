package ai.synheart.syni

/**
 * Configuration the cloud client needs from the host SDK.
 *
 * Mirrors `SyniCloudConfig` from `package:syni`. `syni-kotlin` owns no
 * credentials and no tenant identity — those belong to the host SDK
 * (`synheart-core-kotlin`). The host SDK constructs this and injects it
 * when constructing the agent. With no config injected, the agent runs
 * local-only.
 */
data class SyniCloudConfig(
    /** `syni-service` base URL, e.g. `https://api.synheart.ai`. */
    val baseUrl: String,

    /**
     * Async bearer token provider. Returns `null` when no token is
     * available.
     */
    val authToken: suspend () -> String?,

    val tenantId: String,
    val userId: String,
    val projectId: String = "",
    val orgId: String = "",
    val appId: String = "",
    val deviceId: String = "",
)

/** Thrown when a cloud chat call fails. */
class SyniCloudException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    override fun toString(): String = "SyniCloudException: $message"
}
