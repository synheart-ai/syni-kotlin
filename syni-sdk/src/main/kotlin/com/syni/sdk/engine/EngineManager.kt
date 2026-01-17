package com.syni.sdk.engine

import android.content.Context
import com.syni.sdk.core.LocalEngineConfig
import com.syni.sdk.core.SyniError
import com.syni.sdk.model.ModelManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the lifecycle of the local engine.
 * Ensures thread-safe initialization and cleanup.
 */
class EngineManager(
    private val context: Context,
    private val config: LocalEngineConfig,
    private val modelManager: ModelManager
) {
    private val mutex = Mutex()
    private var localEngine: PortableLocalEngine? = null
    private val isShutdown = AtomicBoolean(false)

    /**
     * Get or create the local engine instance.
     * Thread-safe and lazily initialized.
     *
     * @return The local engine adapter
     * @throws SyniError.EngineUnavailable if no model is available
     */
    suspend fun getLocalEngine(): PortableLocalEngine = mutex.withLock {
        if (isShutdown.get()) {
            throw SyniError.EngineUnavailable("Engine manager has been shut down")
        }

        localEngine?.let { return it }

        val modelPath = modelManager.getActiveModelPath()
            ?: throw SyniError.EngineUnavailable("No model available. Download a model first.")

        val engine = PortableLocalEngine(
            context = context,
            config = config,
            modelPath = modelPath
        )

        localEngine = engine
        engine
    }

    /**
     * Check if the local engine is available without initializing it.
     */
    suspend fun isLocalEngineAvailable(): Boolean {
        if (isShutdown.get()) return false

        // Check if already initialized
        mutex.withLock {
            localEngine?.let { return it.isAvailable() }
        }

        // Check if a model is available
        return modelManager.getActiveModelPath() != null
    }

    /**
     * Reload the engine with a new model.
     * Call this after downloading a new model.
     */
    suspend fun reloadEngine() = mutex.withLock {
        localEngine?.shutdown()
        localEngine = null
    }

    /**
     * Shutdown the engine and release resources.
     */
    suspend fun shutdown() {
        if (isShutdown.getAndSet(true)) return

        mutex.withLock {
            localEngine?.shutdown()
            localEngine = null
        }
    }

    /**
     * Get engine statistics if available.
     */
    suspend fun getStats(): EngineStats? = mutex.withLock {
        localEngine?.getStats()
    }
}

/**
 * Statistics about engine performance.
 */
data class EngineStats(
    /** Total number of generations performed. */
    val totalGenerations: Long,

    /** Total tokens generated. */
    val totalTokensGenerated: Long,

    /** Average generation latency in milliseconds. */
    val averageLatencyMs: Double,

    /** Current model ID. */
    val modelId: String?,

    /** Memory usage in bytes. */
    val memoryUsageBytes: Long?
)
