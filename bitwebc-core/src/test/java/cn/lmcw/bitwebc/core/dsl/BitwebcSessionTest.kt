package cn.lmcw.bitwebc.core.dsl

import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import cn.lmcw.bitwebc.core.api.WebLifecycle
import cn.lmcw.bitwebc.core.event.BitwebcEventHub
import cn.lmcw.bitwebc.core.lifecycle.BitwebcLifecycleObserver
import cn.lmcw.bitwebc.core.state.BitwebcPageStateStore
import cn.lmcw.bitwebc.core.testutil.UnsafeAndroidAllocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BitwebcSessionTest {

    @Test
    fun `release should detach observer and run lifecycle cleanup once`() {
        val recordingLifecycle = RecordingLifecycle()
        val lifecycleOwner = object : LifecycleOwner {
            override val lifecycle: Lifecycle = recordingLifecycle
        }
        val webView = UnsafeAndroidAllocator.allocate(WebView::class.java)
        var cleanupCount = 0
        val observer = BitwebcLifecycleObserver(
            webView = webView,
            lifeCycle = object : WebLifecycle {}
        ) { cleanupCount += 1 }
        recordingLifecycle.addObserver(observer)

        val session = BitwebcSession.create(
            webView = webView,
            lifecycleOwner = lifecycleOwner,
            lifecycleObserver = observer,
            backPressedCallback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            },
            eventHub = BitwebcEventHub()
        )

        session.release()
        session.release()

        assertTrue(recordingLifecycle.removedObservers.contains(observer))
        assertEquals(1, cleanupCount)
        assertTrue(session.isReleased)
        assertTrue(session.state.value.isReleased)
        listOf(
            "currentWebView",
            "lifecycleOwner",
            "lifecycleObserver",
            "backPressedCallback",
            "chromeClient",
            "pendingNavigation"
        ).forEach { fieldName ->
            val field = BitwebcSession::class.java.getDeclaredField(fieldName).apply {
                isAccessible = true
            }
            assertNull("$fieldName must not retain Activity-bound state", field.get(session))
        }
        val usableField = BitwebcSession::class.java.getDeclaredField("currentWebViewUsable").apply {
            isAccessible = true
        }
        assertFalse(usableField.getBoolean(session))
    }

    @Test
    fun `lifecycle release marks session released before user callbacks`() {
        val recordingLifecycle = RecordingLifecycle()
        val lifecycleOwner = object : LifecycleOwner {
            override val lifecycle: Lifecycle = recordingLifecycle
        }
        val webView = UnsafeAndroidAllocator.allocate(WebView::class.java)
        lateinit var session: BitwebcSession
        val observer = BitwebcLifecycleObserver(
            webView = webView,
            lifeCycle = object : WebLifecycle {
                override fun onDestroy(webView: WebView) {
                    assertTrue(session.isReleased)
                    assertThrows(IllegalStateException::class.java) {
                        session.loadUrl("https://example.test/too-late")
                    }
                }
            },
            onReleaseStarted = { session.beginReleaseFromLifecycle() },
            onDestroyed = { session.finishReleaseFromLifecycle() }
        )
        session = BitwebcSession.create(
            webView = webView,
            lifecycleOwner = lifecycleOwner,
            lifecycleObserver = observer,
            backPressedCallback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            },
            eventHub = BitwebcEventHub()
        )

        observer.release()

        assertTrue(session.isReleased)
        assertTrue(session.state.value.isReleased)
        val webViewField = BitwebcSession::class.java.getDeclaredField("currentWebView").apply {
            isAccessible = true
        }
        assertNull(webViewField.get(session))
    }

    @Test
    fun `load queued during recovery keeps headers until matching main frame starts`() {
        val recordingLifecycle = RecordingLifecycle()
        val lifecycleOwner = object : LifecycleOwner {
            override val lifecycle: Lifecycle = recordingLifecycle
        }
        val webView = UnsafeAndroidAllocator.allocate(WebView::class.java)
        val replacement = UnsafeAndroidAllocator.allocate(WebView::class.java)
        val observer = BitwebcLifecycleObserver(webView, object : WebLifecycle {})
        val session = BitwebcSession.create(
            webView = webView,
            lifecycleOwner = lifecycleOwner,
            lifecycleObserver = observer,
            backPressedCallback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            },
            eventHub = BitwebcEventHub(),
            pageStateStore = BitwebcPageStateStore("https://example.test/old")
        )
        session.markWebViewUnusable(webView)

        session.loadUrl(
            "https://example.test/next",
            mapOf("Authorization" to "Bearer test")
        )
        assertTrue(session.replaceWebView(webView, replacement))

        val pendingField = BitwebcSession::class.java.getDeclaredField("pendingNavigation").apply {
            isAccessible = true
        }
        val pending = pendingField.get(session)
        assertTrue(pending != null)
        val headersField = pending.javaClass.getDeclaredField("headers").apply { isAccessible = true }
        assertEquals(
            mapOf("Authorization" to "Bearer test"),
            headersField.get(pending)
        )
        session.confirmMainFrameNavigation(
            replacement,
            "https://example.test/old",
            allowRedirect = true
        )
        assertTrue(pendingField.get(session) != null)

        session.confirmMainFrameNavigation(
            replacement,
            "https://redirected.example.test/final",
            allowRedirect = true
        )

        assertNull(pendingField.get(session))
        session.release()
    }

    private class RecordingLifecycle : Lifecycle() {
        val removedObservers = mutableListOf<LifecycleObserver>()
        private val addedObservers = mutableListOf<LifecycleObserver>()

        override fun addObserver(observer: LifecycleObserver) {
            addedObservers += observer
        }

        override fun removeObserver(observer: LifecycleObserver) {
            removedObservers += observer
        }

        override val currentState: State
            get() = State.STARTED
    }
}
