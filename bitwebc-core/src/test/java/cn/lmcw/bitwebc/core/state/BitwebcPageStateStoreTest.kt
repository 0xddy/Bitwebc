package cn.lmcw.bitwebc.core.state

import cn.lmcw.bitwebc.core.event.BitwebcEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitwebcPageStateStoreTest {

    @Test
    fun `page state keeps an error until the next navigation starts`() {
        val store = BitwebcPageStateStore("https://example.test")

        store.onEvent(BitwebcEvent.PageStarted("https://example.test/a"), null)
        assertTrue(store.state.value.isLoading)
        assertEquals(0, store.state.value.progress)

        store.onEvent(
            BitwebcEvent.PageError("https://example.test/a", "offline"),
            null
        )
        store.onEvent(BitwebcEvent.PageFinished("https://example.test/a"), null)

        assertFalse(store.state.value.isLoading)
        assertEquals("offline", store.state.value.error?.message)

        store.onEvent(BitwebcEvent.PageStarted("https://example.test/b"), null)
        assertNull(store.state.value.error)
    }

    @Test
    fun `fullscreen and release are stable state rather than transient events`() {
        val store = BitwebcPageStateStore(null)

        store.onEvent(BitwebcEvent.FullscreenChanged(true), null)
        assertTrue(store.state.value.isFullscreen)

        store.markReleased()
        assertTrue(store.state.value.isReleased)
        assertFalse(store.state.value.isLoading)
        assertFalse(store.state.value.canGoBack)
        assertFalse(store.state.value.canGoForward)
        assertFalse(store.state.value.isFullscreen)
    }

    @Test
    fun `stopping a load clears loading without discarding the current page`() {
        val store = BitwebcPageStateStore("https://example.test/first")
        store.onEvent(BitwebcEvent.PageStarted("https://example.test/loading"), null)

        store.markLoadingStopped()

        assertFalse(store.state.value.isLoading)
        assertEquals("https://example.test/loading", store.state.value.url)
    }

    @Test
    fun `visited history updates same-document navigation URL`() {
        val store = BitwebcPageStateStore("https://example.test/page")

        store.onVisitedHistoryChanged(null, "https://example.test/page#section")

        assertEquals("https://example.test/page#section", store.state.value.url)
    }

    @Test
    fun `renderer exit is observable until retry navigation starts`() {
        val store = BitwebcPageStateStore("https://example.test/page")

        store.onEvent(BitwebcEvent.RenderProcessGone(didCrash = true, priorityAtExit = 0), null)

        assertEquals("The WebView renderer exited", store.state.value.error?.message)

        store.onEvent(BitwebcEvent.PageStarted("https://example.test/page"), null)
        assertNull(store.state.value.error)
    }

    @Test
    fun `release is terminal even when callbacks arrive reentrantly`() {
        val store = BitwebcPageStateStore("https://example.test")
        store.markReleased()

        store.onEvent(BitwebcEvent.PageStarted("https://example.test/late"), null)
        store.onEvent(BitwebcEvent.FullscreenChanged(true), null)
        store.onVisitedHistoryChanged(null, "https://example.test/late#history")
        store.markLoadingStopped()

        assertTrue(store.state.value.isReleased)
        assertFalse(store.state.value.isLoading)
        assertFalse(store.state.value.isFullscreen)
        assertEquals("https://example.test", store.state.value.url)
    }
}
