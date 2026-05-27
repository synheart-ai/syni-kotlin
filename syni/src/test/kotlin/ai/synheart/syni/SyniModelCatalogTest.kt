package ai.synheart.syni

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyniModelCatalogTest {

    @Test fun `bundled catalog contains local + cloud entries`() {
        val bundled = SyniModelCatalog.bundled
        assertTrue(bundled.any { it is SyniLocalModel && it.isDefault })
        assertTrue(bundled.any { it is SyniCloudModel })
    }

    @Test fun `no baseUrl falls back to bundled`() = runBlocking {
        val c = SyniModelCatalog(baseUrl = null)
        val list = c.available()
        assertEquals(SyniModelCatalog.bundled.size, list.size)
    }

    @Test fun `qwen spec has signed sha256`() {
        val spec = SyniModels.qwen25_15bInstructQ4
        assertEquals(64, spec.sha256.length)
        assertTrue(spec.downloadUrl.startsWith("https://"))
        assertTrue(spec.tokenizerUrl.startsWith("https://"))
    }
}
