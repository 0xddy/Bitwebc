package cn.lmcw.bitwebc.core.api

import android.webkit.WebView

fun interface WebViewRecycler {
    fun recycle(webView: WebView)
}
