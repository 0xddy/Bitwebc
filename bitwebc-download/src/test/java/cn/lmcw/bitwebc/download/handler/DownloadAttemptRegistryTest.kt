package cn.lmcw.bitwebc.download.handler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadAttemptRegistryTest {

    @Test
    fun supersededAttemptCannotFinishReplacement() {
        val registry = DownloadAttemptRegistry()
        val first = registry.begin("task")
        val replacement = registry.begin("task")

        assertFalse(registry.isCurrent("task", first))
        assertTrue(registry.isCurrent("task", replacement))
        assertFalse(registry.finish("task", first))
        assertTrue(registry.isCurrent("task", replacement))
        assertTrue(registry.finish("task", replacement))
        assertFalse(registry.isCurrent("task", replacement))
    }

    @Test
    fun invalidationAndCompletionAreSerialized() {
        val registry = DownloadAttemptRegistry()
        val attempt = registry.begin("task")
        var completed = false

        assertTrue(registry.runIfCurrent("task", attempt) { completed = true })
        registry.invalidateAndRun("task") { Unit }

        assertTrue(completed)
        assertFalse(registry.isCurrent("task", attempt))
        assertFalse(registry.runIfCurrent("task", attempt) { error("must not run") })
    }

    @Test
    fun failedConditionalInvalidationKeepsCurrentAttempt() {
        val registry = DownloadAttemptRegistry()
        val attempt = registry.begin("task")

        val result = registry.invalidateIfAndRun("task", condition = { false }) {
            error("must not run")
        }

        assertNull(result)
        assertTrue(registry.isCurrent("task", attempt))
    }
}
