package cn.lmcw.bitwebc.core.pool

import android.content.Context
import android.content.MutableContextWrapper
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.ConcurrentLinkedDeque

object BitwebcWebViewPool {
    private const val MAX_POOL_SIZE = 3
    private val pool = ConcurrentLinkedDeque<WebView>()

    fun prewarm(context: Context, count: Int = 1) {
        val appContext = context.applicationContext
        repeat(count.coerceAtLeast(0)) {
            if (pool.size >= MAX_POOL_SIZE) return
            pool.add(createPooledWebView(appContext))
        }
    }

    fun acquire(context: Context): WebView {
        val webView = pool.pollFirst()
        if (webView != null) {
            (webView.context as? MutableContextWrapper)?.baseContext = context
            return webView
        }
        return createPooledWebView(context)
    }

    fun recycle(webView: WebView) {
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        (webView.parent as? ViewGroup)?.removeView(webView)
        val appContext = webView.context.applicationContext
        (webView.context as? MutableContextWrapper)?.baseContext = appContext
        if (pool.size >= MAX_POOL_SIZE) {
            webView.destroy()
            return
        }
        pool.add(webView)
    }

    fun clear() {
        while (true) {
            val webView = pool.pollFirst() ?: break
            webView.destroy()
        }
    }

    private fun createPooledWebView(context: Context): WebView {
        val wrapper = MutableContextWrapper(context.applicationContext)
        return WebView(wrapper)
    }
}
