package cn.lmcw.bitwebc.download.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CookieHeaderMergeTest {

    @Test
    fun `jar cookies take precedence while webview cookies augment other names`() {
        assertEquals(
            "jarOnly=1; session=jar; webOnly=2",
            mergeCookieHeaders("jarOnly=1; session=jar", "session=web; webOnly=2")
        )
    }

    @Test
    fun `same-name path cookies retain their source order`() {
        assertEquals(
            "session=narrow; session=wide; webOnly=2",
            mergeCookieHeaders(
                "session=narrow; session=wide",
                "session=web; webOnly=2"
            )
        )
    }

    @Test
    fun `blank cookie inputs produce no header`() {
        assertNull(mergeCookieHeaders(null, "  "))
    }
}
