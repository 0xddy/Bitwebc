package cn.lmcw.bitwebc.core.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PermissionHostKeyTest {
    @Test
    fun `result keys retain distinctions lost by legacy sanitizing`() {
        assertNotEquals(stablePermissionHostKey("a/b"), stablePermissionHostKey("a?b"))
    }

    @Test
    fun `result keys include the complete host key`() {
        val sharedPrefix = "a".repeat(128)
        assertNotEquals(
            stablePermissionHostKey(sharedPrefix + "first"),
            stablePermissionHostKey(sharedPrefix + "second")
        )
    }

    @Test
    fun `result keys are stable and bundle friendly`() {
        val key = stablePermissionHostKey("compose/session:账户")

        assertEquals(key, stablePermissionHostKey("compose/session:账户"))
        assertEquals(64, key.length)
        assertEquals(true, key.all { it.isDigit() || it in 'a'..'f' })
    }
}
