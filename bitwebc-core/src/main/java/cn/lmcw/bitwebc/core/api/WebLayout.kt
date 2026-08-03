package cn.lmcw.bitwebc.core.api

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView

interface WebLayout {
    /** Releases listeners and other resources owned by this Session's layout. */
    fun release() = Unit

    fun createRoot(context: Context): ViewGroup
    fun attach(activity: Activity, webView: WebView, indicatorView: View)
    /**
     * Renderer 退出后用一个全新的 WebView 替换旧实例。
     *
     * 自定义布局若在 [attach] 之外缓存了 WebView，应重写此方法并更新缓存。
     */
    fun replaceWebView(activity: Activity, oldWebView: WebView, newWebView: WebView, indicatorView: View) {
        (oldWebView.parent as? ViewGroup)?.removeView(oldWebView)
        (indicatorView.parent as? ViewGroup)?.removeView(indicatorView)
        attach(activity, newWebView, indicatorView)
    }
    fun showWebContent()
    fun showError(message: String?, onRetry: () -> Unit)
    fun root(): ViewGroup
}
