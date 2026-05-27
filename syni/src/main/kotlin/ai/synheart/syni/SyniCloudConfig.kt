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
     * Per-request auth-header provider. Given the HTTP [method] and the
     * absolute request [url], returns the headers to attach — e.g.
     * `mapOf("X-Synheart-Proof" to "<jws>")` for device-attestation auth.
     *
     * It is request-aware because an `X-Synheart-Proof` is signed over
     * the method and URL and so cannot be a static token. Return an
     * empty map when no credential is available (the cloud rejects
     * unauthenticated requests unless `DISABLE_CHAT_AUTH=true` in test
     * mode).
     */
    val authHeaders: suspend (method: String, url: String) -> Map<String, String>,

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
