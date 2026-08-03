package cn.lmcw.bitwebc.core.lifecycle

import android.webkit.WebView
import cn.lmcw.bitwebc.core.api.WebLifecycle
import cn.lmcw.bitwebc.core.testutil.UnsafeAndroidAllocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BitwebcLifecycleObserverTest {

    @Test
    fun `release clears retained references and is reentrant safe`() {
        val webView = UnsafeAndroidAllocator.allocate(WebView::class.java)
        val callbackOrder = mutableListOf<String>()
        var releaseStartedCount = 0
        var lifecycleDestroyCount = 0
        var cleanupCount = 0
        lateinit var observer: BitwebcLifecycleObserver

        observer = BitwebcLifecycleObserver(
            webView = webView,
            lifeCycle = object : WebLifecycle {
                override fun onDestroy(webView: WebView) {
                    lifecycleDestroyCount += 1
                    callbackOrder += "lifecycle"
                    observer.release()
                }
            },
            onReleaseStarted = {
                releaseStartedCount += 1
                callbackOrder += "started"
                observer.release()
            }
        ) { releasedWebView ->
            cleanupCount += 1
            callbackOrder += "destroyed"
            assertSame(webView, releasedWebView)
            observer.release()
        }

        observer.release()
        observer.release()

        assertEquals(1, releaseStartedCount)
        assertEquals(1, lifecycleDestroyCount)
        assertEquals(1, cleanupCount)
        assertEquals(listOf("started", "lifecycle", "destroyed"), callbackOrder)
        listOf("webView", "lifeCycle", "onReleaseStarted", "onDestroyed").forEach { fieldName ->
            val field = BitwebcLifecycleObserver::class.java.getDeclaredField(fieldName).apply {
                isAccessible = true
            }
            assertNull("$fieldName must be cleared after release", field.get(observer))
        }
    }
}
