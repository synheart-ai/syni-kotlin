package ai.synheart.syni

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Downloads + verifies a model (GGUF + its `tokenizer.json` sibling). Emits
 * progress via the supplied callback. Returns the final on-disk model path.
 *
 * Mirrors `SyniInstaller` from `package:syni`.
 */
class SyniInstaller(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    /**
     * Where downloaded models live on the local filesystem. V1 uses the
     * app's files directory (private storage).
     */
    private fun modelsDir(): File {
        val dir = File(context.filesDir, "synheart_syni_models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Whether the GGUF model + its sibling `tokenizer.json` are already on
     * disk for [spec].
     */
    fun isModelOnDisk(spec: SyniModelSpec): Boolean {
        val dir = modelsDir()
        return File(dir, spec.filename).exists() && File(dir, "tokenizer.json").exists()
    }

    /**
     * Returns the on-disk path the GGUF would live at — does NOT touch the
     * filesystem.
     */
    fun modelPathFor(spec: SyniModelSpec): String =
        File(modelsDir(), spec.filename).absolutePath

    /**
     * Returns the path to the model file. Downloads the GGUF and its
     * sibling `tokenizer.json` if not already on disk.
     */
    suspend fun ensureModel(
        spec: SyniModelSpec,
        onProgress: (SyniInstallStage, Double) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val dir = modelsDir()
        val file = File(dir, spec.filename)

        if (!file.exists()) {
            onProgress(SyniInstallStage.DOWNLOADING_MODEL, 0.0)
            download(spec.downloadUrl, file) { p ->
                onProgress(SyniInstallStage.DOWNLOADING_MODEL, p * 0.95)
            }
        }

        val tokenizerFile = File(dir, "tokenizer.json")
        if (!tokenizerFile.exists()) {
            download(spec.tokenizerUrl, tokenizerFile) { p ->
                onProgress(SyniInstallStage.DOWNLOADING_MODEL, 0.95 + p * 0.05)
            }
        }
        onProgress(SyniInstallStage.DOWNLOADING_MODEL, 1.0)

        if (spec.sha256.isNotEmpty()) {
            onProgress(SyniInstallStage.VERIFYING_MODEL, 0.0)
            val actual = sha256(file)
            onProgress(SyniInstallStage.VERIFYING_MODEL, 1.0)
            if (actual.lowercase() != spec.sha256.lowercase()) {
                runCatching { file.delete() }
                throw SyniInstallException(
                    "model SHA-256 mismatch: expected ${spec.sha256}, got $actual"
                )
            }
        }

        file.absolutePath
    }

    private fun download(
        url: String,
        outFile: File,
        onProgress: (Double) -> Unit,
    ) {
        val resp = httpClient.newCall(Request.Builder().url(url).build()).execute()
        resp.use { response ->
            if (!response.isSuccessful) {
                throw SyniInstallException(
                    "model download HTTP ${response.code} for $url"
                )
            }
            val body = response.body ?: throw SyniInstallException("empty body for $url")
            val total = body.contentLength().takeIf { it > 0 } ?: 0L
            body.byteStream().use { input ->
                outFile.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var received = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        received += n
                        if (total > 0) onProgress(received.toDouble() / total)
                    }
                    output.flush()
                }
            }
        }
        onProgress(1.0)
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Thrown when installation (download / verification) fails. */
class SyniInstallException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    override fun toString(): String = "SyniInstallException: $message"
}
