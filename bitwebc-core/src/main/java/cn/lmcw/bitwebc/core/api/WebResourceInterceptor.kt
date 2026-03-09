package cn.lmcw.bitwebc.core.api

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView

/** 资源请求拦截器，责任链风格 */
public fun interface WebResourceInterceptor {
    public fun intercept(chain: Chain): WebResourceResponse?

    public interface Chain {
        public val view: WebView
        public val request: WebResourceRequest
        public fun proceed(request: WebResourceRequest): WebResourceResponse?
    }
}
