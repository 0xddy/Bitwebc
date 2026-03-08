package cn.lmcw.bitwebc.core.api

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView

interface IWebLayout {
    fun createRoot(context: Context): ViewGroup
    fun attach(activity: Activity, webView: WebView, indicatorView: View)
    fun showWebContent()
    fun showError(message: String?, onRetry: () -> Unit)
    fun root(): ViewGroup
}
