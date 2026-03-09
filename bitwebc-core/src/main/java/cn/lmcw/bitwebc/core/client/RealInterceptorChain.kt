package cn.lmcw.bitwebc.core.client

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import cn.lmcw.bitwebc.core.api.WebResourceInterceptor

internal class RealInterceptorChain(
    private val interceptors: List<WebResourceInterceptor>,
    private val index: Int,
    override val view: WebView,
    override val request: WebResourceRequest,
    private val terminal: (WebResourceRequest) -> WebResourceResponse?
) : WebResourceInterceptor.Chain {
    override fun proceed(request: WebResourceRequest): WebResourceResponse? {
        val nextRequest = request
        return if (index >= interceptors.size) {
            terminal(nextRequest)
        } else {
            interceptors[index].intercept(
                RealInterceptorChain(interceptors, index + 1, view, nextRequest, terminal)
            )
        }
    }
}
