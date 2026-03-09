package cn.lmcw.bitwebc.core.lifecycle

import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/** 按顺序释放 WebView，减少泄漏 */
fun WebView.destroySafelyWithAboutBlank() {
    clearHistory()
    (parent as? ViewGroup)?.removeView(this)
    loadUrl("about:blank")
    stopLoading()
    clearClientsToNullSafely()
    destroy()
}

private fun WebView.clearClientsToNullSafely() {
    runCatching { webChromeClient = null }
    runCatching { webViewClient = WebViewClient() }
}
