package com.syni.sdk.budget

import android.os.SystemClock
import com.syni.sdk.core.BudgetType
import com.syni.sdk.core.PerformanceBudget
import com.syni.sdk.core.SyniError
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Enforces performance budgets for requests.
 * Tracks latency, tokens, and retry counts.
 */
class BudgetEnforcer {

    private val activeContexts = ConcurrentHashMap<String, BudgetContext>()

    /**
     * Start tracking a request's budget.
     *
     * @param requestId The unique request ID
     * @param budget The performance budget to enforce
     * @return BudgetContext for the request
     */
    fun startTracking(requestId: String, budget: PerformanceBudget): BudgetContext {
        val context = BudgetContext(
            requestId = requestId,
            budget = budget,
            startTimeMs = SystemClock.elapsedRealtime()
        )
        activeContexts[requestId] = context
        return context
    }

    /**
     * Get the budget context for a request.
     */
    fun getContext(requestId: String): BudgetContext? = activeContexts[requestId]

    /**
     * Check if the request is within budget.
     * Throws SyniError.BudgetExceeded if any budget is exceeded.
     *
     * @param requestId The request ID
     * @param tokensGenerated Optional number of tokens generated so far
     * @throws SyniError.BudgetExceeded if budget is exceeded
     */
    fun checkBudget(requestId: String, tokensGenerated: Int? = null) {
        val context = activeContexts[requestId] ?: return

        // Check latency
        val elapsedMs = getElapsedMs(requestId)
        if (elapsedMs > context.budget.maxLatencyMs) {
            throw SyniError.BudgetExceeded(
                budgetType = BudgetType.LATENCY,
                limit = context.budget.maxLatencyMs,
                actual = elapsedMs
            )
        }

        // Check tokens if provided
        tokensGenerated?.let { tokens ->
            if (tokens > context.budget.maxTokens) {
                throw SyniError.BudgetExceeded(
                    budgetType = BudgetType.TOKENS,
                    limit = context.budget.maxTokens.toLong(),
                    actual = tokens.toLong()
                )
            }
        }
    }

    /**
     * Check latency budget and return remaining time.
     *
     * @param requestId The request ID
     * @return Remaining milliseconds before latency budget is exceeded, or 0 if exceeded
     */
    fun getRemainingLatencyMs(requestId: String): Long {
        val context = activeContexts[requestId] ?: return 0
        val elapsed = getElapsedMs(requestId)
        return maxOf(0, context.budget.maxLatencyMs - elapsed)
    }

    /**
     * Get elapsed time for a request.
     *
     * @param requestId The request ID
     * @return Elapsed milliseconds, or 0 if not tracking
     */
    fun getElapsedMs(requestId: String): Long {
        val context = activeContexts[requestId] ?: return 0
        return SystemClock.elapsedRealtime() - context.startTimeMs
    }

    /**
     * Check if a retry is allowed for this request.
     *
     * @param requestId The request ID
     * @return true if retry is allowed
     */
    fun canRetry(requestId: String): Boolean {
        val context = activeContexts[requestId] ?: return false

        if (!context.budget.allowRetry) return false
        if (context.retryCount.get() >= context.budget.maxRetries) return false

        // Also check if we have enough time remaining
        val remainingMs = getRemainingLatencyMs(requestId)
        return remainingMs > MIN_RETRY_LATENCY_MS
    }

    /**
     * Record a retry attempt.
     *
     * @param requestId The request ID
     * @return The new retry count
     * @throws SyniError.BudgetExceeded if max retries exceeded
     */
    fun recordRetry(requestId: String): Int {
        val context = activeContexts[requestId]
            ?: throw SyniError.InternalError("No budget context for request: $requestId")

        val newCount = context.retryCount.incrementAndGet()

        if (newCount > context.budget.maxRetries) {
            throw SyniError.BudgetExceeded(
                budgetType = BudgetType.RETRIES,
                limit = context.budget.maxRetries.toLong(),
                actual = newCount.toLong()
            )
        }

        return newCount
    }

    /**
     * Stop tracking a request.
     * Should be called when the request completes or fails.
     *
     * @param requestId The request ID
     * @return The final budget context, or null if not found
     */
    fun stopTracking(requestId: String): BudgetContext? {
        return activeContexts.remove(requestId)
    }

    /**
     * Get summary of a completed request's budget usage.
     */
    fun getBudgetSummary(requestId: String): BudgetSummary? {
        val context = activeContexts[requestId] ?: return null
        val elapsedMs = getElapsedMs(requestId)

        return BudgetSummary(
            requestId = requestId,
            elapsedMs = elapsedMs,
            maxLatencyMs = context.budget.maxLatencyMs,
            latencyUtilization = elapsedMs.toDouble() / context.budget.maxLatencyMs,
            retryCount = context.retryCount.get(),
            maxRetries = context.budget.maxRetries
        )
    }

    /**
     * Clear all tracking data.
     */
    fun clear() {
        activeContexts.clear()
    }

    companion object {
        /** Minimum remaining latency to allow a retry (ms) */
        private const val MIN_RETRY_LATENCY_MS = 50L
    }
}

/**
 * Context for tracking a request's budget.
 */
data class BudgetContext(
    /** The request ID. */
    val requestId: String,

    /** The budget being enforced. */
    val budget: PerformanceBudget,

    /** Start time in SystemClock.elapsedRealtime() milliseconds. */
    val startTimeMs: Long,

    /** Number of retry attempts. */
    val retryCount: AtomicInteger = AtomicInteger(0)
)

/**
 * Summary of budget usage for a request.
 */
data class BudgetSummary(
    val requestId: String,
    val elapsedMs: Long,
    val maxLatencyMs: Long,
    val latencyUtilization: Double,
    val retryCount: Int,
    val maxRetries: Int
) {
    val isWithinBudget: Boolean
        get() = elapsedMs <= maxLatencyMs && retryCount <= maxRetries
}
