package cn.lmcw.bitwebc.filechooser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileChooserLauncherRegistryTest {
    @Test
    fun `stable host key hashes the complete input without lossy truncation`() {
        val sharedPrefix = "host/" + "a".repeat(200)
        val first = stableFileChooserHostKey("$sharedPrefix/first")
        val second = stableFileChooserHostKey("$sharedPrefix/second")

        assertEquals(64, first.length)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(first, second)
        assertEquals(first, stableFileChooserHostKey("$sharedPrefix/first"))
    }
}
