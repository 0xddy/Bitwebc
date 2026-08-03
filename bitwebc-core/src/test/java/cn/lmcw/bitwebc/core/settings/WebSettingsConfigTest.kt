package cn.lmcw.bitwebc.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSettingsConfigTest {

    @Test
    fun `web settings have independent typed scopes`() {
        val config = WebSettingsConfig().apply {
            scripting {
                enabled = false
                canOpenWindows = true
            }
            storage { domEnabled = false }
            cache { policy = WebResourceCachePolicy.CacheOnly }
            security {
                fileAccessEnabled = true
                contentAccessEnabled = true
                mixedContent = MixedContentPolicy.Compatibility
            }
            media { playbackRequiresUserGesture = false }
            viewport {
                overviewMode = false
                wide = false
                zoom {
                    enabled = true
                    builtInControls = true
                    controlsVisible = true
                }
            }
            userAgent { append(" Example/1.0 ") }
        }

        val snapshot = config.snapshot()

        assertFalse(snapshot.javaScriptEnabled)
        assertTrue(snapshot.javaScriptCanOpenWindowsAutomatically)
        assertFalse(snapshot.domStorageEnabled)
        assertEquals(WebResourceCachePolicy.CacheOnly, snapshot.cachePolicy)
        assertTrue(snapshot.fileAccessEnabled)
        assertTrue(snapshot.contentAccessEnabled)
        assertEquals(MixedContentPolicy.Compatibility, snapshot.mixedContentPolicy)
        assertFalse(snapshot.mediaPlaybackRequiresUserGesture)
        assertFalse(snapshot.loadWithOverviewMode)
        assertFalse(snapshot.useWideViewPort)
        assertTrue(snapshot.zoomEnabled)
        assertTrue(snapshot.builtInZoomControls)
        assertTrue(snapshot.zoomControlsVisible)
        assertEquals("Example/1.0", snapshot.userAgentSuffix)
    }

    @Test
    fun `snapshot cannot be changed through a captured dsl scope`() {
        val config = WebSettingsConfig()
        config.cache { policy = WebResourceCachePolicy.CacheFirst }
        val frozen = config.snapshot()

        config.cache { policy = WebResourceCachePolicy.NetworkOnly }

        assertEquals(WebResourceCachePolicy.CacheFirst, frozen.cachePolicy)
        assertEquals(WebResourceCachePolicy.NetworkOnly, config.snapshot().cachePolicy)
    }

    @Test
    fun `security defaults deny file and content access`() {
        val snapshot = WebSettingsConfig().snapshot()

        assertFalse(snapshot.fileAccessEnabled)
        assertFalse(snapshot.contentAccessEnabled)
        assertEquals(MixedContentPolicy.Block, snapshot.mixedContentPolicy)
    }

    @Test
    fun `user agent suffix is idempotent when settings are applied again`() {
        val first = resolveUserAgent(
            systemUserAgent = "SystemUA",
            currentUserAgent = "SystemUA",
            suffix = "MyApp/1.0",
            fullUserAgent = null
        )
        val second = resolveUserAgent(
            systemUserAgent = null,
            currentUserAgent = first,
            suffix = "MyApp/1.0",
            fullUserAgent = null
        )

        assertEquals("SystemUA MyApp/1.0", first)
        assertEquals(first, second)
    }

    @Test
    fun `user agent append and replacement are mutually exclusive`() {
        val config = WebSettingsConfig()
        config.userAgent {
            append("MyApp/1.0")
            replaceWith("CompleteUA")
        }

        val replaced = config.snapshot()
        assertEquals("", replaced.userAgentSuffix)
        assertEquals("CompleteUA", replaced.fullUserAgent)

        config.userAgent { append("MyApp/2.0") }
        val appended = config.snapshot()
        assertEquals("MyApp/2.0", appended.userAgentSuffix)
        assertEquals(null, appended.fullUserAgent)
    }
}
