package com.syni.sdk

import android.content.Context
import com.syni.sdk.core.EngineType
import com.syni.sdk.core.GenerationOptions
import com.syni.sdk.core.Persona
import com.syni.sdk.core.PerformanceBudget
import com.syni.sdk.core.PersonaParams
import com.syni.sdk.core.RoutingPolicy
import com.syni.sdk.core.SyniError
import com.syni.sdk.router.PersonaRouter
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class PersonaRouterTest {

    @Mock
    private lateinit var mockContext: Context

    private lateinit var router: PersonaRouter

    @Before
    fun setup() {
        router = PersonaRouter(mockContext, null)
    }

    @Test
    fun `loads built-in keyboard persona`() {
        val persona = router.getPersona("keyboard.v1")

        assertEquals("keyboard.v1", persona.id)
        assertEquals("Keyboard Suggestions", persona.name)
        assertEquals("keyboard.v1", persona.schemaId)
        assertEquals("keyboard.v1", persona.grammarId)
    }

    @Test
    fun `loads built-in life coach persona`() {
        val persona = router.getPersona("life.coach.v1")

        assertEquals("life.coach.v1", persona.id)
        assertEquals("Life Coach", persona.name)
        assertEquals("life.coach.v1", persona.schemaId)
    }

    @Test(expected = SyniError.PersonaNotFound::class)
    fun `throws for unknown persona`() {
        router.getPersona("unknown.persona")
    }

    @Test
    fun `hasPersona returns true for existing persona`() {
        assertTrue(router.hasPersona("keyboard.v1"))
        assertTrue(router.hasPersona("life.coach.v1"))
    }

    @Test
    fun `hasPersona returns false for unknown persona`() {
        assertFalse(router.hasPersona("unknown.persona"))
    }

    @Test
    fun `availablePersonas returns all personas`() {
        val personas = router.availablePersonas()

        assertTrue(personas.contains("keyboard.v1"))
        assertTrue(personas.contains("life.coach.v1"))
    }

    @Test
    fun `registers custom persona`() {
        val customPersona = Persona(
            id = "custom.test.v1",
            name = "Test Persona",
            version = "1.0",
            schemaId = "keyboard.v1",
            grammarId = "keyboard.v1"
        )

        router.registerPersona(customPersona)

        assertTrue(router.hasPersona("custom.test.v1"))
        assertEquals(customPersona, router.getPersona("custom.test.v1"))
    }

    @Test
    fun `unregisters persona`() {
        router.unregisterPersona("keyboard.v1")

        assertFalse(router.hasPersona("keyboard.v1"))
    }

    @Test
    fun `selects local engine when preferred and available`() {
        val persona = Persona(
            id = "test",
            name = "Test",
            version = "1.0",
            schemaId = "keyboard.v1",
            grammarId = "keyboard.v1",
            routingPolicy = RoutingPolicy.LOCAL_PREFERRED
        )

        val engine = router.selectEngine(
            persona = persona,
            options = GenerationOptions.DEFAULT,
            isLocalAvailable = true,
            isCloudAvailable = true
        )

        assertEquals(EngineType.LOCAL, engine)
    }

    @Test
    fun `selects cloud engine when preferred and available`() {
        val persona = Persona(
            id = "test",
            name = "Test",
            version = "1.0",
            schemaId = "keyboard.v1",
            grammarId = "keyboard.v1",
            routingPolicy = RoutingPolicy.CLOUD_PREFERRED
        )

        val engine = router.selectEngine(
            persona = persona,
            options = GenerationOptions.DEFAULT,
            isLocalAvailable = true,
            isCloudAvailable = true
        )

        assertEquals(EngineType.CLOUD, engine)
    }

    @Test
    fun `falls back to cloud when local unavailable`() {
        val persona = Persona(
            id = "test",
            name = "Test",
            version = "1.0",
            schemaId = "keyboard.v1",
            grammarId = "keyboard.v1",
            routingPolicy = RoutingPolicy.LOCAL_PREFERRED
        )

        val engine = router.selectEngine(
            persona = persona,
            options = GenerationOptions.DEFAULT,
            isLocalAvailable = false,
            isCloudAvailable = true
        )

        assertEquals(EngineType.CLOUD, engine)
    }

    @Test
    fun `respects localOnly option`() {
        val persona = Persona(
            id = "test",
            name = "Test",
            version = "1.0",
            schemaId = "keyboard.v1",
            grammarId = "keyboard.v1",
            routingPolicy = RoutingPolicy.CLOUD_PREFERRED
        )

        val engine = router.selectEngine(
            persona = persona,
            options = GenerationOptions.LOCAL_ONLY,
            isLocalAvailable = true,
            isCloudAvailable = true
        )

        assertEquals(EngineType.LOCAL, engine)
    }

    @Test
    fun `respects cloudOnly option`() {
        val persona = Persona(
            id = "test",
            name = "Test",
            version = "1.0",
            schemaId = "keyboard.v1",
            grammarId = "keyboard.v1",
            routingPolicy = RoutingPolicy.LOCAL_PREFERRED
        )

        val engine = router.selectEngine(
            persona = persona,
            options = GenerationOptions.CLOUD_ONLY,
            isLocalAvailable = true,
            isCloudAvailable = true
        )

        assertEquals(EngineType.CLOUD, engine)
    }

    @Test(expected = SyniError.EngineUnavailable::class)
    fun `throws when local requested but unavailable`() {
        val persona = Persona(
            id = "test",
            name = "Test",
            version = "1.0",
            schemaId = "keyboard.v1",
            grammarId = "keyboard.v1",
            routingPolicy = RoutingPolicy.LOCAL_ONLY
        )

        router.selectEngine(
            persona = persona,
            options = GenerationOptions.LOCAL_ONLY,
            isLocalAvailable = false,
            isCloudAvailable = true
        )
    }

    @Test(expected = SyniError.EngineUnavailable::class)
    fun `throws when cloud requested but unavailable`() {
        val persona = Persona(
            id = "test",
            name = "Test",
            version = "1.0",
            schemaId = "keyboard.v1",
            grammarId = "keyboard.v1",
            routingPolicy = RoutingPolicy.CLOUD_ONLY
        )

        router.selectEngine(
            persona = persona,
            options = GenerationOptions.CLOUD_ONLY,
            isLocalAvailable = true,
            isCloudAvailable = false
        )
    }

    @Test(expected = SyniError.EngineUnavailable::class)
    fun `throws when no engine available`() {
        val persona = Persona(
            id = "test",
            name = "Test",
            version = "1.0",
            schemaId = "keyboard.v1",
            grammarId = "keyboard.v1",
            routingPolicy = RoutingPolicy.LOCAL_PREFERRED
        )

        router.selectEngine(
            persona = persona,
            options = GenerationOptions.DEFAULT,
            isLocalAvailable = false,
            isCloudAvailable = false
        )
    }

    @Test
    fun `getFallbackEngine returns cloud after local failure`() {
        val persona = Persona(
            id = "test",
            name = "Test",
            version = "1.0",
            schemaId = "keyboard.v1",
            grammarId = "keyboard.v1",
            routingPolicy = RoutingPolicy.LOCAL_PREFERRED
        )

        val fallback = router.getFallbackEngine(
            persona = persona,
            failedEngine = EngineType.LOCAL,
            options = GenerationOptions.DEFAULT,
            isLocalAvailable = true,
            isCloudAvailable = true
        )

        assertEquals(EngineType.CLOUD, fallback)
    }

    @Test
    fun `getFallbackEngine returns local after cloud failure`() {
        val persona = Persona(
            id = "test",
            name = "Test",
            version = "1.0",
            schemaId = "keyboard.v1",
            grammarId = "keyboard.v1",
            routingPolicy = RoutingPolicy.CLOUD_PREFERRED
        )

        val fallback = router.getFallbackEngine(
            persona = persona,
            failedEngine = EngineType.CLOUD,
            options = GenerationOptions.DEFAULT,
            isLocalAvailable = true,
            isCloudAvailable = true
        )

        assertEquals(EngineType.LOCAL, fallback)
    }

    @Test
    fun `getFallbackEngine returns null when localOnly`() {
        val persona = Persona(
            id = "test",
            name = "Test",
            version = "1.0",
            schemaId = "keyboard.v1",
            grammarId = "keyboard.v1",
            routingPolicy = RoutingPolicy.LOCAL_PREFERRED
        )

        val fallback = router.getFallbackEngine(
            persona = persona,
            failedEngine = EngineType.LOCAL,
            options = GenerationOptions.LOCAL_ONLY,
            isLocalAvailable = true,
            isCloudAvailable = true
        )

        assertNull(fallback)
    }

    @Test
    fun `keyboard persona has strict budget`() {
        val persona = router.getPersona("keyboard.v1")

        assertEquals(150L, persona.budget.maxLatencyMs)
        assertEquals(50, persona.budget.maxTokens)
        assertFalse(persona.budget.allowRetry)
    }

    @Test
    fun `life coach persona has relaxed budget`() {
        val persona = router.getPersona("life.coach.v1")

        assertEquals(10000L, persona.budget.maxLatencyMs)
        assertEquals(512, persona.budget.maxTokens)
        assertTrue(persona.budget.allowRetry)
    }
}
