package com.syni.sdk.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import com.syni.sdk.core.SyniError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages model downloads, verification, and storage.
 */
class ModelManager(
    private val context: Context,
    private val modelsDir: String?
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val modelsDirFile: File by lazy {
        modelsDir?.let { File(it) }
            ?: File(context.filesDir, "models")
    }

    private val manifestFile: File by lazy {
        File(modelsDirFile, "manifest.json")
    }

    private val activeModelId = AtomicReference<String?>(null)
    private val downloadedModels = mutableMapOf<String, ModelInfo>()

    init {
        modelsDirFile.mkdirs()
        loadManifest()
    }

    /**
     * Get the path to the currently active model.
     */
    fun getActiveModelPath(): String? {
        val modelId = activeModelId.get() ?: return null
        val info = downloadedModels[modelId] ?: return null
        val file = File(info.localPath)
        return if (file.exists()) info.localPath else null
    }

    /**
     * Get the currently active model info.
     */
    fun getActiveModel(): ModelInfo? {
        val modelId = activeModelId.get() ?: return null
        return downloadedModels[modelId]
    }

    /**
     * Set the active model by ID.
     */
    fun setActiveModel(modelId: String): Boolean {
        if (!downloadedModels.containsKey(modelId)) return false
        activeModelId.set(modelId)
        saveManifest()
        return true
    }

    /**
     * Get all downloaded models.
     */
    fun getDownloadedModels(): List<ModelInfo> = downloadedModels.values.toList()

    /**
     * Check if a model is downloaded.
     */
    fun isModelDownloaded(modelId: String): Boolean {
        val info = downloadedModels[modelId] ?: return false
        return File(info.localPath).exists()
    }

    /**
     * Download a model with progress updates.
     *
     * @param url The URL to download from
     * @param modelId Unique identifier for the model
     * @param expectedChecksum Expected SHA256 checksum (optional)
     * @param wifiOnly Only download on Wi-Fi
     * @return Flow of download progress
     */
    fun downloadModel(
        url: String,
        modelId: String,
        expectedChecksum: String? = null,
        wifiOnly: Boolean = true
    ): Flow<DownloadProgress> = flow {
        // Check network
        if (wifiOnly && !isOnWifi()) {
            throw SyniError.ModelDownloadFailed(modelId, "Wi-Fi required for download")
        }

        emit(DownloadProgress.Starting(modelId))

        val request = Request.Builder()
            .url(url)
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw SyniError.ModelDownloadFailed(modelId, "Network error: ${e.message}")
        }

        if (!response.isSuccessful) {
            throw SyniError.ModelDownloadFailed(modelId, "HTTP ${response.code}")
        }

        val body = response.body
            ?: throw SyniError.ModelDownloadFailed(modelId, "Empty response")

        val contentLength = body.contentLength()

        // Check storage space
        if (contentLength > 0 && !hasStorageSpace(contentLength)) {
            throw SyniError.InsufficientStorage(
                required = contentLength,
                available = getAvailableStorage()
            )
        }

        val tempFile = File(modelsDirFile, "$modelId.tmp")
        val finalFile = File(modelsDirFile, "$modelId.gguf")

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytesDownloaded = 0L

            FileOutputStream(tempFile).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead

                        if (contentLength > 0) {
                            val progress = bytesDownloaded.toFloat() / contentLength
                            emit(DownloadProgress.Downloading(modelId, progress, bytesDownloaded, contentLength))
                        }
                    }
                }
            }

            emit(DownloadProgress.Verifying(modelId))

            // Verify checksum
            val actualChecksum = digest.digest().toHexString()
            if (expectedChecksum != null && actualChecksum != expectedChecksum.lowercase()) {
                tempFile.delete()
                throw SyniError.ModelChecksumMismatch(modelId, expectedChecksum, actualChecksum)
            }

            // Move to final location
            tempFile.renameTo(finalFile)

            // Register the model
            val modelInfo = ModelInfo(
                id = modelId,
                localPath = finalFile.absolutePath,
                checksum = actualChecksum,
                sizeBytes = finalFile.length(),
                downloadedAt = System.currentTimeMillis()
            )
            downloadedModels[modelId] = modelInfo

            // Set as active if it's the first model
            if (activeModelId.get() == null) {
                activeModelId.set(modelId)
            }

            saveManifest()

            emit(DownloadProgress.Completed(modelId, modelInfo))
        } catch (e: Exception) {
            tempFile.delete()
            if (e is SyniError) throw e
            throw SyniError.ModelDownloadFailed(modelId, e.message ?: "Unknown error")
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Delete a downloaded model.
     */
    suspend fun deleteModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        val info = downloadedModels[modelId] ?: return@withContext false

        val file = File(info.localPath)
        val deleted = !file.exists() || file.delete()

        if (deleted) {
            downloadedModels.remove(modelId)
            if (activeModelId.get() == modelId) {
                activeModelId.set(downloadedModels.keys.firstOrNull())
            }
            saveManifest()
        }

        deleted
    }

    /**
     * Get total storage used by models.
     */
    fun getStorageUsage(): Long {
        return downloadedModels.values.sumOf { it.sizeBytes }
    }

    /**
     * Get available storage space in bytes.
     */
    fun getAvailableStorage(): Long {
        val stat = StatFs(modelsDirFile.absolutePath)
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    /**
     * Check if there's enough storage space.
     */
    fun hasStorageSpace(requiredBytes: Long): Boolean {
        // Require 10% buffer
        val available = getAvailableStorage()
        return available > requiredBytes * 1.1
    }

    /**
     * Verify all downloaded models are valid.
     */
    suspend fun verifyModels(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        downloadedModels.mapValues { (_, info) ->
            val file = File(info.localPath)
            if (!file.exists()) return@mapValues false

            // Verify checksum
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val actualChecksum = digest.digest().toHexString()
            actualChecksum == info.checksum
        }
    }

    /**
     * Clear all downloaded models.
     */
    suspend fun clearAllModels() = withContext(Dispatchers.IO) {
        downloadedModels.keys.toList().forEach { modelId ->
            deleteModel(modelId)
        }
    }

    // --- Private implementation ---

    private fun isOnWifi(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun loadManifest() {
        if (!manifestFile.exists()) return

        try {
            val manifest = json.decodeFromString<ModelManifest>(manifestFile.readText())
            downloadedModels.clear()
            downloadedModels.putAll(manifest.models.associateBy { it.id })
            activeModelId.set(manifest.activeModelId)
        } catch (e: Exception) {
            // Manifest corrupted, start fresh
            manifestFile.delete()
        }
    }

    private fun saveManifest() {
        val manifest = ModelManifest(
            version = 1,
            activeModelId = activeModelId.get(),
            models = downloadedModels.values.toList()
        )
        manifestFile.writeText(json.encodeToString(manifest))
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}

/**
 * Information about a downloaded model.
 */
@Serializable
data class ModelInfo(
    /** Unique identifier. */
    val id: String,

    /** Path to the model file. */
    @SerialName("local_path")
    val localPath: String,

    /** SHA256 checksum. */
    val checksum: String,

    /** File size in bytes. */
    @SerialName("size_bytes")
    val sizeBytes: Long,

    /** Download timestamp. */
    @SerialName("downloaded_at")
    val downloadedAt: Long
)

/**
 * Download progress updates.
 */
sealed class DownloadProgress {
    abstract val modelId: String

    data class Starting(override val modelId: String) : DownloadProgress()

    data class Downloading(
        override val modelId: String,
        val progress: Float,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : DownloadProgress()

    data class Verifying(override val modelId: String) : DownloadProgress()

    data class Completed(
        override val modelId: String,
        val modelInfo: ModelInfo
    ) : DownloadProgress()
}

/**
 * Internal manifest format.
 */
@Serializable
internal data class ModelManifest(
    val version: Int,
    @SerialName("active_model_id")
    val activeModelId: String?,
    val models: List<ModelInfo>
)
