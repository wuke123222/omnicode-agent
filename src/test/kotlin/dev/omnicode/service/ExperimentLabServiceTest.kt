package dev.omnicode.service

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExperimentLabServiceTest {
    @Test
    fun `assignment is stable and observations are bounded`() {
        val service = ExperimentLabService(project())
        val experiment = service.create("摘要提示实验", "结构化摘要是否减少返工", listOf("对照", "结构化"))
        assertFailsWith<IllegalStateException> { service.assign(experiment.id, "user-1") }
        service.setActive(experiment.id, true)
        val first = service.assign(experiment.id, "user-1")
        val second = service.assign(experiment.id, "user-1")
        assertEquals(first.id, second.id)
        service.record(experiment.id, "user-1", success = true, latencyMillis = 500, inputTokens = 10, outputTokens = 20)
        assertTrue(service.record(experiment.id, "user-1", success = false, latencyMillis = 900, inputTokens = 10, outputTokens = 20, idempotencyKey = "sample-1"))
        assertFalse(service.record(experiment.id, "user-1", success = false, latencyMillis = 900, inputTokens = 10, outputTokens = 20, idempotencyKey = "sample-1"))
        val snapshot = service.list().single()
        assertEquals(1, snapshot.observations[first.id]?.samples)
        assertEquals(1, snapshot.observations[first.id]?.successes)
    }

    @Test
    fun `invalid experiment shape is rejected`() {
        val service = ExperimentLabService(project())
        assertFailsWith<IllegalArgumentException> { service.create("", "x", listOf("a", "b")) }
        assertFailsWith<IllegalArgumentException> { service.create("x", "h", listOf("a")) }
        assertTrue(service.list().isEmpty())
    }

    private fun project(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "isDisposed" -> false
            "getName" -> "experiment-test"
            "toString" -> "experiment-test"
            else -> null
        }
    } as Project
}
