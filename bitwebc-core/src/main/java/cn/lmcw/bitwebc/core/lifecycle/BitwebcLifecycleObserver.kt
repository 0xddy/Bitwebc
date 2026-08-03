package cn.lmcw.bitwebc.core.lifecycle

import android.webkit.WebView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.annotation.MainThread
import cn.lmcw.bitwebc.core.api.WebLifecycle

internal class BitwebcLifecycleObserver(
    webView: WebView,
    lifeCycle: WebLifecycle,
    onReleaseStarted: () -> Unit = {},
    onDestroyed: (WebView) -> Unit = {}
) : DefaultLifecycleObserver {

    private var webView: WebView? = webView
    private var lifeCycle: WebLifecycle? = lifeCycle
    private var onReleaseStarted: (() -> Unit)? = onReleaseStarted
    private var onDestroyed: ((WebView) -> Unit)? = onDestroyed
    private var destroyed = false
    private var resumed = false
    private var currentRendererGone = false

    override fun onResume(owner: LifecycleOwner) {
        if (destroyed) return
        val currentWebView = webView ?: return
        val currentLifeCycle = lifeCycle ?: return
        if (!currentRendererGone) currentWebView.onResume()
        if (!resumed) {
            resumed = true
        }
        currentLifeCycle.onResume(currentWebView)
    }

    override fun onPause(owner: LifecycleOwner) {
        if (destroyed) return
        val currentWebView = webView ?: return
        val currentLifeCycle = lifeCycle ?: return
        if (!currentRendererGone) currentWebView.onPause()
        if (resumed) {
            resumed = false
        }
        currentLifeCycle.onPause(currentWebView)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        release()
    }

    /** 把生命周期接管到 renderer 退出后创建的新 WebView。 */
    fun replaceWebView(
        owner: LifecycleOwner,
        replacement: WebView,
        notifyPreviousLifecycle: Boolean = true
    ): Boolean {
        if (destroyed) {
            replacement.destroySafelyWithAboutBlank()
            return false
        }
        val previous = webView ?: run {
            replacement.destroySafelyWithAboutBlank()
            return false
        }
        val currentLifeCycle = lifeCycle ?: run {
            replacement.destroySafelyWithAboutBlank()
            return false
        }
        if (notifyPreviousLifecycle) {
            if (resumed) runCatching { currentLifeCycle.onPause(previous) }
            runCatching { currentLifeCycle.onDestroy(previous) }
        }
        if (destroyed) {
            replacement.destroySafelyWithAboutBlank()
            return false
        }
        webView = replacement
        currentRendererGone = false
        currentLifeCycle.onAttach(owner, replacement)
        if (destroyed) return false
        if (resumed) {
            replacement.onResume()
            currentLifeCycle.onResume(replacement)
        } else {
            replacement.onPause()
        }
        return true
    }

    /** Marks the renderer-owned instance as unsafe for normal WebView calls. */
    fun markWebViewUnusable(expected: WebView) {
        if (webView === expected) {
            currentRendererGone = true
        }
    }

    fun ownsWebView(expected: WebView): Boolean = webView === expected

    fun canUseWebView(expected: WebView): Boolean =
        webView === expected && !currentRendererGone

    @MainThread
    fun release() {
        if (destroyed) return
        destroyed = true
        val releasedWebView = webView
        val releasedLifeCycle = lifeCycle
        val releasedOnReleaseStarted = onReleaseStarted
        val releasedOnDestroyed = onDestroyed
        // Drop stored callbacks before invoking user code so re-entrant release cannot retain them.
        onReleaseStarted = null
        onDestroyed = null
        runCatching { releasedOnReleaseStarted?.invoke() }
        if (resumed) {
            resumed = false
            if (!currentRendererGone && releasedWebView != null) {
                runCatching { releasedWebView.onPause() }
                runCatching { releasedLifeCycle?.onPause(releasedWebView) }
            }
        }
        if (!currentRendererGone && releasedWebView != null) {
            runCatching { releasedLifeCycle?.onDestroy(releasedWebView) }
        }
        try {
            if (releasedWebView != null) {
                runCatching { releasedOnDestroyed?.invoke(releasedWebView) }
            }
        } finally {
            try {
                if (releasedWebView != null) {
                    if (currentRendererGone) {
                        releasedWebView.destroyAfterRendererGone()
                    } else {
                        releasedWebView.destroySafelyWithAboutBlank()
                    }
                }
            } finally {
                webView = null
                lifeCycle = null
                onReleaseStarted = null
                onDestroyed = null
            }
        }
    }
}
