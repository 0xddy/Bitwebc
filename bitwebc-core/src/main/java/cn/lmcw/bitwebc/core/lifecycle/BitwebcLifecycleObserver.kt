package cn.lmcw.bitwebc.core.lifecycle

import android.webkit.WebView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import cn.lmcw.bitwebc.core.api.WebLifecycle
import cn.lmcw.bitwebc.core.api.WebViewRecycler

class BitwebcLifecycleObserver(
    private val webView: WebView,
    private val lifeCycle: WebLifecycle,
    private val recycler: WebViewRecycler? = null,
    private val onDestroyed: (() -> Unit)? = null
) : DefaultLifecycleObserver {

    private var destroyed = false
    private var resumed = false

    override fun onResume(owner: LifecycleOwner) {
        if (destroyed) return
        webView.onResume()
        if (!resumed) {
            resumed = true
            BitwebcGlobalManager.onWebViewResumed(webView)
        }
        lifeCycle.onResume(webView)
    }

    override fun onPause(owner: LifecycleOwner) {
        if (destroyed) return
        webView.onPause()
        if (resumed) {
            resumed = false
            BitwebcGlobalManager.onWebViewPaused(webView)
        }
        lifeCycle.onPause(webView)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        release()
    }

    fun release() {
        if (destroyed) return
        destroyed = true
        if (resumed) {
            resumed = false
            BitwebcGlobalManager.onWebViewPaused(webView)
        }
        lifeCycle.onDestroy(webView)
        onDestroyed?.invoke()
        if (recycler != null) {
            recycler.recycle(webView)
        } else {
            webView.destroySafelyWithAboutBlank()
        }
    }
}
