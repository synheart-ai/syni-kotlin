package ai.synheart.syni

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.long

/**
 * Identifies a downloadable inference model.
 *
 * Mirrors `SyniModelSpec` from `package:syni`. V1 ships a small curated
 * catalog ([SyniModels]). Production builds should validate [sha256]
 * against a release manifest signed by Synheart's release key.
 */
data class SyniModelSpec(
    /** Stable identifier (e.g. `qwen2.5-1.5b-instruct-q4_k_m`). */
    val id: String,

    /** Filename used on disk (typically `<id>.gguf`). */
    val filename: String,

    /** HTTPS URL the GGUF model downloads from. */
    val downloadUrl: String,

    /** HTTPS URL for the matching `tokenizer.json`. */
    val tokenizerUrl: String,

    /** Lowercase hex SHA-256 of the downloaded GGUF. Empty = skip verification. */
    val sha256: String,

    /** Approximate file size in bytes. */
    val approxBytes: Long,
) {
    companion object {
        /** Parse the `local` block of a `/v1/models` manifest entry. */
        fun fromManifest(id: String, local: JsonObject): SyniModelSpec = SyniModelSpec(
            id = id,
            filename = local["filename"]!!.jsonPrimitive.content,
            downloadUrl = local["download_url"]!!.jsonPrimitive.content,
            tokenizerUrl = local["tokenizer_url"]!!.jsonPrimitive.content,
            sha256 = local["sha256"]?.jsonPrimitive?.contentOrNull ?: "",
            approxBytes = local["approx_bytes"]?.jsonPrimitive?.long ?: 0L,
        )
    }
}

/**
 * V1 model catalog. Mirrors `SyniModels` from `package:syni`. Replace with
 * a server-signed manifest once that exists.
 */
object SyniModels {
    /** Qwen2.5 1.5B Instruct, Q4_K_M quantization. ~1.1 GB. */
    val qwen25_15bInstructQ4 = SyniModelSpec(
        id = "qwen2.5-1.5b-instruct-q4_k_m",
        filename = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        downloadUrl =
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        tokenizerUrl =
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct/resolve/main/tokenizer.json",
        sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
        approxBytes = 1_117_320_736L,
    )

    /** Gemma 3 1B Instruct, Q4_K_M quantization. ~770 MB. */
    val gemma3_1bInstructQ4 = SyniModelSpec(
        id = "gemma-3-1b-it-q4_k_m",
        filename = "gemma-3-1b-it-q4_k_m.gguf",
        downloadUrl =
            "https://huggingface.co/Synheart/syni-life-gguf-gemma-3-1b/resolve/main/google_gemma-3-1b-it-Q4_K_M.gguf",
        tokenizerUrl =
            "https://huggingface.co/Synheart/syni-life-gguf-gemma-3-1b/resolve/main/tokenizer.json",
        sha256 = "12bf0fff8815d5f73a3c9b586bd8fee8e7b248c935de70dec367679873d0f29d",
        approxBytes = 806_058_496L,
    )
}
