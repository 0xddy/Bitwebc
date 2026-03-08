package cn.lmcw.bitwebc.core.lifecycle

import android.webkit.WebView
import java.util.concurrent.atomic.AtomicInteger

/**
 * 全局 WebView 计数器：规避 pauseTimers() 误伤宿主其他 WebView。
 */
object BitwebcGlobalManager {
    private val resumedWebViewCount = AtomicInteger(0)

    fun onWebViewResumed(webView: WebView) {
        val before = resumedWebViewCount.getAndIncrement()
        if (before == 0) {
            webView.resumeTimers()
        }
    }

    fun onWebViewPaused(webView: WebView) {
        val after = resumedWebViewCount.decrementAndGet().coerceAtLeast(0)
        if (resumedWebViewCount.get() < 0) {
            resumedWebViewCount.set(0)
        }
        if (after == 0) {
            webView.pauseTimers()
        }
    }
}
