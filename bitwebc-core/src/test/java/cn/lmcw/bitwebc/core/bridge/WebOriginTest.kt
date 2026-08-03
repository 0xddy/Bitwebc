package cn.lmcw.bitwebc.core.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebOriginTest {

    @Test
    fun `full URLs normalize to exact origins`() {
        assertEquals("https://example.test", WebOrigin.fromUrl("HTTPS://Example.Test:443/path?q=1"))
        assertEquals("http://example.test:8080", WebOrigin.fromUrl("http://example.test:8080/a"))
        assertEquals("https://[::1]", WebOrigin.fromUrl("https://[::1]:443/a"))
    }

    @Test
    fun `origin rules reject paths credentials and unsupported schemes`() {
        assertEquals("https://example.test", WebOrigin.normalizeRule("https://example.test/"))
        assertNull(WebOrigin.normalizeRule("https://example.test/path"))
        assertNull(WebOrigin.normalizeRule("https://user@example.test"))
        assertNull(WebOrigin.normalizeRule("file:///android_asset/index.html"))
        assertNull(WebOrigin.normalizeRule("https://example.test:0"))
    }

    @Test
    fun `matching does not allow lookalike hosts`() {
        val allowed = setOf("https://example.test")
        assertTrue(WebOrigin.matches("https://example.test/page", allowed))
        assertTrue(!WebOrigin.matches("https://example.test.evil/page", allowed))
    }
}
