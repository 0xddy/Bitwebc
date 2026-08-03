package cn.lmcw.bitwebc.core.lifecycle

import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import cn.lmcw.bitwebc.core.bridge.BitwebcJsBridge

/** 按顺序释放 WebView，减少泄漏 */
fun WebView.destroySafelyWithAboutBlank() {
    runCatching { (parent as? ViewGroup)?.removeView(this) }
    runCatching { stopLoading() }
    BitwebcJsBridge.removeSafely(this)
    runCatching { setDownloadListener(null) }
    clearClientsToNullSafely()
    runCatching { loadUrl("about:blank") }
    runCatching { clearHistory() }
    runCatching { destroy() }
}

/** Renderer-gone WebViews may only be detached and destroyed; other calls are invalid. */
internal fun WebView.destroyAfterRendererGone() {
    runCatching { (parent as? ViewGroup)?.removeView(this) }
    runCatching { destroy() }
}

private fun WebView.clearClientsToNullSafely() {
    runCatching { webChromeClient = null }
    runCatching { webViewClient = WebViewClient() }
}
