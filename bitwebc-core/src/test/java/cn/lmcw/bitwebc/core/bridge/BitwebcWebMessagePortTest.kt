package cn.lmcw.bitwebc.core.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BitwebcWebMessagePortTest {

    @Test
    fun `target origin should preserve explicit port and discard non-origin URL parts`() {
        assertEquals(
            "https://example.com:8443",
            webMessageTargetOrigin("https://Example.com:8443/path?q=1#fragment")
        )
        assertEquals(
            "https://example.com:443",
            webMessageTargetOrigin("https://example.com:443/")
        )
    }

    @Test
    fun `target origin should reject opaque malformed and unsupported URLs`() {
        assertNull(webMessageTargetOrigin(null))
        assertNull(webMessageTargetOrigin("relative/path"))
        assertNull(webMessageTargetOrigin("about:blank"))
        assertNull(webMessageTargetOrigin("file:///android_asset/index.html"))
        assertNull(webMessageTargetOrigin("https://example.com:99999/path"))
        assertNull(webMessageTargetOrigin("not a url"))
    }
}
