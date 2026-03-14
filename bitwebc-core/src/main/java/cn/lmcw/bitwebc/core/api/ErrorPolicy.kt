package cn.lmcw.bitwebc.core.api

import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView

fun interface ErrorPolicy {
    fun shouldShowError(context: ErrorContext): Boolean
}

sealed class ErrorContext {
    abstract val view: WebView
    abstract val request: WebResourceRequest

    data class Network(
        override val view: WebView,
        override val request: WebResourceRequest,
        val error: WebResourceError
    ) : ErrorContext()

    data class Http(
        override val view: WebView,
        override val request: WebResourceRequest,
        val response: WebResourceResponse
    ) : ErrorContext()
}
