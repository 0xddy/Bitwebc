package cn.lmcw.bitwebc.core.client

import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

open class MiddlewareWebClientBase(
    private val next: WebViewClient? = null
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return next?.shouldOverrideUrlLoading(view, request) ?: false
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        return next?.shouldInterceptRequest(view, request)
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        next?.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        next?.onPageFinished(view, url)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        next?.onReceivedError(view, request, error)
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        next?.onReceivedHttpError(view, request, errorResponse)
    }

    override fun onLoadResource(view: WebView, url: String) {
        next?.onLoadResource(view, url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        next?.doUpdateVisitedHistory(view, url, isReload)
    }

    override fun shouldOverrideKeyEvent(view: WebView, event: android.view.KeyEvent): Boolean {
        return next?.shouldOverrideKeyEvent(view, event) ?: false
    }

    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        return next?.shouldOverrideUrlLoading(view, url) ?: false
    }

    override fun onPageCommitVisible(view: WebView, url: String?) {
        next?.onPageCommitVisible(view, url)
    }

    override fun onReceivedError(
        view: WebView,
        errorCode: Int,
        description: String?,
        failingUrl: String?
    ) {
        next?.onReceivedError(view, errorCode, description, failingUrl)
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: android.webkit.HttpAuthHandler,
        host: String?,
        realm: String?
    ) {
        next?.onReceivedHttpAuthRequest(view, handler, host, realm)
    }

    override fun onTooManyRedirects(
        view: WebView,
        cancelMsg: android.os.Message,
        continueMsg: android.os.Message
    ) {
        next?.onTooManyRedirects(view, cancelMsg, continueMsg)
    }

    override fun onFormResubmission(view: WebView, dontResend: android.os.Message, resend: android.os.Message) {
        next?.onFormResubmission(view, dontResend, resend)
    }

    override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
        return next?.shouldInterceptRequest(view, url)
    }

    override fun onReceivedSslError(
        view: WebView,
        handler: android.webkit.SslErrorHandler,
        error: android.net.http.SslError
    ) {
        next?.onReceivedSslError(view, handler, error)
    }

    override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
        next?.onScaleChanged(view, oldScale, newScale)
    }

    override fun onReceivedLoginRequest(view: WebView, realm: String?, account: String?, args: String?) {
        next?.onReceivedLoginRequest(view, realm, account, args)
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: android.webkit.RenderProcessGoneDetail
    ): Boolean {
        return next?.onRenderProcessGone(view, detail) ?: false
    }

    open fun nextClient(): WebViewClient? = next
}
