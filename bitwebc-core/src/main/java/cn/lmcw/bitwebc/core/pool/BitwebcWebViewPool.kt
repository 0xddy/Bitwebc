package cn.lmcw.bitwebc.core.pool

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import cn.lmcw.bitwebc.core.bridge.BitwebcJsBridge
import cn.lmcw.bitwebc.core.extensions.createPooledWebView
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

object BitwebcWebViewPool {
    data class RecyclePolicy(
        val clearCacheOnRecycle: Boolean = false,
        val clearDiskCacheOnRecycle: Boolean = false
    )

    private var maxPoolSize = 1
    private val pool = ConcurrentLinkedDeque<WebView>()
    private val currentSize = AtomicInteger(0)
    private val defaultPolicy = RecyclePolicy()

    fun setMaxPoolSize(size: Int) {
        maxPoolSize = size.coerceAtLeast(0)
        while (currentSize.get() > maxPoolSize) {
            val webView = pool.pollLast()
            if (webView != null) {
                currentSize.decrementAndGet()
                webView.destroy()
            } else {
                break
            }
        }
    }

    fun prewarm(context: Context, count: Int = 1) {
        val appContext = context.applicationContext
        repeat(count.coerceAtLeast(0)) {
            while (true) {
                val size = currentSize.get()
                if (size >= maxPoolSize) return
                if (currentSize.compareAndSet(size, size + 1)) {
                    pool.add(appContext.createPooledWebView())
                    break
                }
            }
        }
    }

    fun acquire(context: Context): WebView {
        val webView = pool.pollFirst()
        if (webView != null) {
            currentSize.decrementAndGet()
            (webView.context as? MutableContextWrapper)?.baseContext = context
            return webView
        }
        return context.createPooledWebView()
    }

    /**
     * 回收 WebView 到池中。必须在主线程调用（WebView 操作要求主线程）。
     * 注意：[WebView.clearCache] 可能耗时，大量 WebView 同时回收时应关注 ANR 风险。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun recycle(webView: WebView, policy: RecyclePolicy = defaultPolicy) {
        val wrapper = webView.context as? MutableContextWrapper
        if (wrapper == null) {
            webView.destroy()
            return
        }

        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        if (policy.clearCacheOnRecycle || policy.clearDiskCacheOnRecycle) {
            webView.clearCache(policy.clearDiskCacheOnRecycle)
        }
        webView.clearFormData()
        webView.clearSslPreferences()
        webView.clearMatches()

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.allowFileAccess = false
        settings.loadWithOverviewMode = false
        settings.useWideViewPort = false
        try {
            settings.userAgentString = android.webkit.WebSettings.getDefaultUserAgent(webView.context)
        } catch (_: Exception) {
            // Ignore
        }
        
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        (webView.parent as? ViewGroup)?.removeView(webView)
        BitwebcJsBridge.removeSafely(webView)
        val appContext = webView.context.applicationContext
        wrapper.baseContext = appContext
        
        while (true) {
            val size = currentSize.get()
            if (size >= maxPoolSize) {
                webView.destroy()
                return
            }
            if (currentSize.compareAndSet(size, size + 1)) {
                pool.add(webView)
                return
            }
        }
    }

    fun clear() {
        while (true) {
            val webView = pool.pollFirst() ?: break
            currentSize.decrementAndGet()
            webView.destroy()
        }
    }
}
