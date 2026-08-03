package cn.lmcw.bitwebc.compose

import cn.lmcw.bitwebc.core.state.BitwebcPageError
import cn.lmcw.bitwebc.core.state.BitwebcPageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BitwebcStateTest {

    @Test
    fun `load before binding becomes the next session URL`() {
        val state = BitwebcState.create(
            initialUrl = "https://example.test/first",
            instanceId = "test-instance",
            savePolicy = BitwebcSavePolicy.None
        )

        state.loadUrl("https://example.test/queued")

        assertEquals("https://example.test/queued", state.requestedUrl)
        assertNull(
            state.prepareForSession(
                url = state.requestedUrl,
                sessionKey = "default",
                urlIsExternalInput = false
            )
        )
    }

    @Test
    fun `same external URL is ignored while a changed URL becomes requested`() {
        val state = BitwebcState.create(
            initialUrl = "https://example.test/first",
            instanceId = "test-instance",
            savePolicy = BitwebcSavePolicy.None
        )

        state.acceptExternalUrl("https://example.test/first")
        assertEquals("https://example.test/first", state.requestedUrl)

        state.acceptExternalUrl("https://example.test/second")
        assertEquals("https://example.test/second", state.requestedUrl)
    }

    @Test
    fun `one state cannot be leased by two hosts`() {
        val state = BitwebcState.create(
            initialUrl = "https://example.test/first",
            instanceId = "test-instance",
            savePolicy = BitwebcSavePolicy.None
        )

        state.beginBinding("first-host")

        assertThrows(IllegalStateException::class.java) {
            state.beginBinding("second-host")
        }
    }

    @Test
    fun `failed replacement restores the previous host claim`() {
        val state = BitwebcState.create(
            initialUrl = "https://example.test/first",
            instanceId = "test-instance",
            savePolicy = BitwebcSavePolicy.None
        )
        val original = state.beginBinding("first-host")
        val replacement = state.beginBinding("first-host")

        state.rollbackBinding(replacement)

        assertThrows(IllegalStateException::class.java) {
            state.beginBinding("second-host")
        }
        state.rollbackBinding(original)
        state.beginBinding("second-host")
    }

    @Test
    fun `only the requested main frame URL acknowledges a queued command`() {
        val state = BitwebcState.create(
            initialUrl = "https://example.test/current",
            instanceId = "test-instance",
            savePolicy = BitwebcSavePolicy.None
        )
        state.loadUrl("https://example.test/queued")

        assertFalse(
            state.acknowledgeRequestedNavigation(
                BitwebcPageState(
                    url = "https://example.test/current",
                    error = BitwebcPageError(
                        url = "https://example.test/current",
                        message = "renderer exited"
                    )
                )
            )
        )
        assertTrue(
            state.acknowledgeRequestedNavigation(
                BitwebcPageState(
                    url = "https://example.test/queued",
                    isLoading = true
                )
            )
        )
    }

    @Test
    fun `an old loading page cannot acknowledge a newer URL command`() {
        val state = BitwebcState.create(
            initialUrl = "https://example.test/old",
            instanceId = "test-instance",
            savePolicy = BitwebcSavePolicy.None
        )
        state.loadUrl("https://example.test/new")

        assertFalse(
            state.acknowledgeRequestedNavigation(
                BitwebcPageState(
                    url = "https://example.test/old",
                    isLoading = true,
                    progress = 80
                )
            )
        )
        assertTrue(
            state.acknowledgeRequestedNavigation(
                BitwebcPageState(
                    url = "https://redirected.example.test/final",
                    isLoading = true
                )
            )
        )
    }

    @Test
    fun `a superseded load callback cannot acknowledge the latest command`() {
        val state = BitwebcState.create(
            initialUrl = "https://example.test/original",
            instanceId = "test-instance",
            savePolicy = BitwebcSavePolicy.None
        )
        state.loadUrl("https://example.test/first")
        state.loadUrl("https://example.test/latest")

        assertFalse(
            state.acknowledgeRequestedNavigation(
                BitwebcPageState(
                    url = "https://example.test/first",
                    isLoading = true
                )
            )
        )
        assertTrue(
            state.acknowledgeRequestedNavigation(
                BitwebcPageState(
                    url = "https://example.test/latest",
                    isLoading = true
                )
            )
        )
    }
}
