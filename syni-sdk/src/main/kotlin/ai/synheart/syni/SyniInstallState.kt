package ai.synheart.syni

/**
 * Installation lifecycle of the Syni agent.
 *
 * Mirrors `SyniInstallState` from `package:syni`. The host SDK's "Activation"
 * authority is gated on this reaching [SyniInstalled]; until then, chat
 * calls throw.
 */
sealed class SyniInstallState {

    /** No model downloaded, no persona bound. Default state. */
    data object NotInstalled : SyniInstallState() {
        override fun toString(): String = "SyniNotInstalled"
    }

    /**
     * Install in progress. [progress] is in `[0.0, 1.0]`; [stage] explains
     * which sub-step is running.
     */
    data class Installing(
        val stage: SyniInstallStage,
        val progress: Double,
    ) : SyniInstallState() {
        override fun toString(): String =
            "SyniInstalling(stage=$stage, progress=${"%.1f".format(progress * 100)}%)"
    }

    /**
     * Successfully installed. The runtime engine has loaded the model and
     * is ready to receive chat calls.
     */
    data class Installed(
        val personaId: String,
        val modelPath: String,
        val runtimeVersion: String,
    ) : SyniInstallState() {
        override fun toString(): String =
            "SyniInstalled(personaId=$personaId, runtime=$runtimeVersion)"
    }

    /**
     * Install failed. [reason] is human-readable; [cause] is the underlying
     * exception when available.
     */
    data class Failed(
        val reason: String,
        val cause: Throwable? = null,
    ) : SyniInstallState() {
        override fun toString(): String = "SyniInstallFailed(reason=$reason)"
    }
}

/** Convenience aliases matching the Flutter type names. */
typealias SyniNotInstalled = SyniInstallState.NotInstalled
typealias SyniInstalling = SyniInstallState.Installing
typealias SyniInstalled = SyniInstallState.Installed
typealias SyniInstallFailed = SyniInstallState.Failed

enum class SyniInstallStage {
    /** Pre-flight checks. */
    PREFLIGHT,

    /** Model + tokenizer download. */
    DOWNLOADING_MODEL,

    /** SHA-256 verification of the downloaded model. */
    VERIFYING_MODEL,

    /** Persona binding. */
    MATERIALIZING_PERSONA,

    /** libsyni_ffi engine spawn + model load. */
    LOADING_ENGINE,
}
