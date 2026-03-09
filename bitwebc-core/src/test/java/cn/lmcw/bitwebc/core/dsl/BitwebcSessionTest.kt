package cn.lmcw.bitwebc.core.dsl

import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import cn.lmcw.bitwebc.core.api.WebLifecycle
import cn.lmcw.bitwebc.core.api.WebViewRecycler
import cn.lmcw.bitwebc.core.event.BitwebcEventHub
import cn.lmcw.bitwebc.core.lifecycle.BitwebcLifecycleObserver
import cn.lmcw.bitwebc.core.testutil.UnsafeAndroidAllocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BitwebcSessionTest {

    @Test
    fun `release should detach observer from lifecycle and recycle webview`() {
        val recordingLifecycle = RecordingLifecycle()
        val lifecycleOwner = object : LifecycleOwner {
            override val lifecycle: Lifecycle = recordingLifecycle
        }
        val webView = UnsafeAndroidAllocator.allocate(WebView::class.java)
        var recycledCount = 0
        val observer = BitwebcLifecycleObserver(
            webView = webView,
            lifeCycle = object : WebLifecycle {},
            recycler = object : WebViewRecycler {
                override fun recycle(webView: WebView) {
                    recycledCount += 1
                }
            }
        )
        recordingLifecycle.addObserver(observer)

        val session = BitwebcSession(
            webView = webView,
            lifecycleOwner = lifecycleOwner,
            lifecycleObserver = observer,
            backPressedCallback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            },
            eventHub = BitwebcEventHub()
        )

        session.release()

        assertTrue(recordingLifecycle.removedObservers.contains(observer))
        assertEquals(1, recycledCount)
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
