package cn.lmcw.bitwebc.core.extensions

import android.content.Context
import android.content.MutableContextWrapper
import android.webkit.WebView

internal fun Context.createPooledWebView(): WebView {
    val wrapper = MutableContextWrapper(applicationContext)
    return WebView(wrapper)
}
