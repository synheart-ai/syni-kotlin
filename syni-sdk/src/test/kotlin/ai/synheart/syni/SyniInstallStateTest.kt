package ai.synheart.syni

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyniInstallStateTest {

    @Test fun `not-installed is a singleton object`() {
        assertTrue(SyniInstallState.NotInstalled === SyniNotInstalled)
    }

    @Test fun `installing toString formats percent`() {
        val s = SyniInstallState.Installing(SyniInstallStage.DOWNLOADING_MODEL, 0.42)
        assertTrue(s.toString().contains("42.0%"))
    }

    @Test fun `installed exposes runtime version`() {
        val s = SyniInstallState.Installed(
            personaId = "p",
            modelPath = "/tmp/m.gguf",
            runtimeVersion = "1.2.3",
        )
        assertEquals("p", s.personaId)
        assertEquals("1.2.3", s.runtimeVersion)
    }

    @Test fun `failed carries reason`() {
        val s = SyniInstallState.Failed("offline")
        assertTrue(s.toString().contains("offline"))
    }
}
