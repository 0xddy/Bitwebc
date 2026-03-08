package cn.lmcw.bitwebc.core.lifecycle

import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 按固定顺序释放 WebView，降低 DOM/JS 与视图树残留导致的泄漏风险。
 */
fun WebView.destroySafelyWithAboutBlank() {
    clearHistory()
    (parent as? ViewGroup)?.removeView(this) // 1. 切断视图树联系
    loadUrl("about:blank") // 2. 清空复杂 DOM 和 JS
    stopLoading()
    clearClientsToNullSafely()
    destroy() // 3. 最后销毁
}

private fun WebView.clearClientsToNullSafely() {
    // 某些 SDK 注解将属性声明为非空，这里用反射确保能按销毁流程置空。
    runCatching {
        javaClass.getMethod("setWebChromeClient", WebChromeClient::class.java).invoke(this, null)
    }
    runCatching {
        javaClass.getMethod("setWebViewClient", WebViewClient::class.java).invoke(this, null)
    }
}
