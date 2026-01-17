package com.syni.sdk

import com.syni.sdk.budget.BudgetEnforcer
import com.syni.sdk.core.BudgetType
import com.syni.sdk.core.PerformanceBudget
import com.syni.sdk.core.SyniError
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BudgetEnforcerTest {

    private lateinit var enforcer: BudgetEnforcer

    @Before
    fun setup() {
        enforcer = BudgetEnforcer()
    }

    @Test
    fun `starts tracking with correct budget`() {
        val budget = PerformanceBudget(
            maxLatencyMs = 1000,
            maxTokens = 100,
            allowRetry = true,
            maxRetries = 2
        )

        val context = enforcer.startTracking("test-1", budget)

        assertEquals("test-1", context.requestId)
        assertEquals(1000L, context.budget.maxLatencyMs)
        assertEquals(100, context.budget.maxTokens)
        assertEquals(0, context.retryCount.get())
    }

    @Test
    fun `getElapsedMs returns positive value`() {
        val budget = PerformanceBudget(maxLatencyMs = 10000)
        enforcer.startTracking("test-elapsed", budget)

        Thread.sleep(50)

        val elapsed = enforcer.getElapsedMs("test-elapsed")
        assertTrue("Elapsed should be at least 50ms", elapsed >= 50)
    }

    @Test
    fun `getRemainingLatencyMs calculates correctly`() {
        val budget = PerformanceBudget(maxLatencyMs = 1000)
        enforcer.startTracking("test-remaining", budget)

        val remaining = enforcer.getRemainingLatencyMs("test-remaining")
        assertTrue("Remaining should be close to 1000ms", remaining > 900 && remaining <= 1000)
    }

    @Test
    fun `canRetry returns true when allowed`() {
        val budget = PerformanceBudget(
            maxLatencyMs = 10000,
            allowRetry = true,
            maxRetries = 3
        )
        enforcer.startTracking("test-retry", budget)

        assertTrue(enforcer.canRetry("test-retry"))
    }

    @Test
    fun `canRetry returns false when not allowed`() {
        val budget = PerformanceBudget(
            maxLatencyMs = 10000,
            allowRetry = false,
            maxRetries = 0
        )
        enforcer.startTracking("test-no-retry", budget)

        assertFalse(enforcer.canRetry("test-no-retry"))
    }

    @Test
    fun `canRetry returns false when max retries reached`() {
        val budget = PerformanceBudget(
            maxLatencyMs = 10000,
            allowRetry = true,
            maxRetries = 1
        )
        enforcer.startTracking("test-max-retries", budget)

        // Record one retry
        enforcer.recordRetry("test-max-retries")

        assertFalse(enforcer.canRetry("test-max-retries"))
    }

    @Test
    fun `recordRetry increments count`() {
        val budget = PerformanceBudget(
            maxLatencyMs = 10000,
            allowRetry = true,
            maxRetries = 5
        )
        enforcer.startTracking("test-count", budget)

        assertEquals(1, enforcer.recordRetry("test-count"))
        assertEquals(2, enforcer.recordRetry("test-count"))
        assertEquals(3, enforcer.recordRetry("test-count"))
    }

    @Test(expected = SyniError.BudgetExceeded::class)
    fun `recordRetry throws when max exceeded`() {
        val budget = PerformanceBudget(
            maxLatencyMs = 10000,
            allowRetry = true,
            maxRetries = 1
        )
        enforcer.startTracking("test-exceed", budget)

        enforcer.recordRetry("test-exceed") // OK
        enforcer.recordRetry("test-exceed") // Should throw
    }

    @Test
    fun `checkBudget passes when within limits`() {
        val budget = PerformanceBudget(
            maxLatencyMs = 10000,
            maxTokens = 100
        )
        enforcer.startTracking("test-check", budget)

        // Should not throw
        enforcer.checkBudget("test-check", tokensGenerated = 50)
    }

    @Test
    fun `checkBudget throws on token budget exceeded`() {
        val budget = PerformanceBudget(
            maxLatencyMs = 10000,
            maxTokens = 100
        )
        enforcer.startTracking("test-tokens", budget)

        try {
            enforcer.checkBudget("test-tokens", tokensGenerated = 150)
            fail("Should have thrown BudgetExceeded")
        } catch (e: SyniError.BudgetExceeded) {
            assertEquals(BudgetType.TOKENS, e.budgetType)
            assertEquals(100L, e.limit)
            assertEquals(150L, e.actual)
        }
    }

    @Test
    fun `stopTracking removes context`() {
        val budget = PerformanceBudget(maxLatencyMs = 1000)
        enforcer.startTracking("test-stop", budget)

        assertNotNull(enforcer.getContext("test-stop"))

        val removed = enforcer.stopTracking("test-stop")
        assertNotNull(removed)

        assertNull(enforcer.getContext("test-stop"))
    }

    @Test
    fun `getBudgetSummary returns correct data`() {
        val budget = PerformanceBudget(
            maxLatencyMs = 1000,
            maxRetries = 3
        )
        enforcer.startTracking("test-summary", budget)
        enforcer.recordRetry("test-summary")

        Thread.sleep(50)

        val summary = enforcer.getBudgetSummary("test-summary")

        assertNotNull(summary)
        assertEquals("test-summary", summary!!.requestId)
        assertEquals(1000L, summary.maxLatencyMs)
        assertEquals(1, summary.retryCount)
        assertEquals(3, summary.maxRetries)
        assertTrue(summary.elapsedMs >= 50)
    }

    @Test
    fun `clear removes all contexts`() {
        enforcer.startTracking("test-1", PerformanceBudget())
        enforcer.startTracking("test-2", PerformanceBudget())
        enforcer.startTracking("test-3", PerformanceBudget())

        enforcer.clear()

        assertNull(enforcer.getContext("test-1"))
        assertNull(enforcer.getContext("test-2"))
        assertNull(enforcer.getContext("test-3"))
    }

    @Test
    fun `KEYBOARD budget preset has strict limits`() {
        val budget = PerformanceBudget.KEYBOARD

        assertEquals(150L, budget.maxLatencyMs)
        assertEquals(50, budget.maxTokens)
        assertFalse(budget.allowRetry)
        assertEquals(0, budget.maxRetries)
    }

    @Test
    fun `LIFE_COACH budget preset has relaxed limits`() {
        val budget = PerformanceBudget.LIFE_COACH

        assertEquals(10000L, budget.maxLatencyMs)
        assertEquals(512, budget.maxTokens)
        assertTrue(budget.allowRetry)
        assertEquals(2, budget.maxRetries)
    }
}
