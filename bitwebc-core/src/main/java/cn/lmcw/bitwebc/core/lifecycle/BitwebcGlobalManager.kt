package cn.lmcw.bitwebc.core.lifecycle

import android.webkit.WebView
import java.util.concurrent.atomic.AtomicInteger

/** 全局 WebView 计数，避免 pauseTimers 影响其它 WebView */
object BitwebcGlobalManager {
    private val resumedWebViewCount = AtomicInteger(0)

    fun onWebViewResumed(webView: WebView) {
        val before = resumedWebViewCount.getAndIncrement()
        if (before == 0) {
            webView.resumeTimers()
        }
    }

    fun onWebViewPaused(webView: WebView) {
        while (true) {
            val current = resumedWebViewCount.get()
            if (current <= 0) return
            if (resumedWebViewCount.compareAndSet(current, current - 1)) {
                if (current - 1 == 0) {
                    webView.pauseTimers()
                }
                return
            }
        }
    }
}
