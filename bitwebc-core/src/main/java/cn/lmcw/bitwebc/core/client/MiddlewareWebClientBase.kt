package cn.lmcw.bitwebc.core.client

import android.graphics.Bitmap
import android.os.Message
import android.view.KeyEvent
import android.webkit.ClientCertRequest
import android.webkit.SafeBrowsingResponse
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi

open class MiddlewareWebClientBase(
    private val next: WebViewClient? = null
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return next?.shouldOverrideUrlLoading(view, request) ?: false
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean {
        val nextClient = next
        return if (nextClient != null) {
            nextClient.shouldOverrideUrlLoading(view, url)
        } else {
            super.shouldOverrideUrlLoading(view, url)
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        return next?.shouldInterceptRequest(view, request)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun shouldInterceptRequest(view: WebView, url: String?): WebResourceResponse? {
        val nextClient = next
        return if (nextClient != null) {
            nextClient.shouldInterceptRequest(view, url)
        } else {
            super.shouldInterceptRequest(view, url)
        }
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

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onReceivedError(
        view: WebView,
        errorCode: Int,
        description: String?,
        failingUrl: String?
    ) {
        val nextClient = next
        if (nextClient != null) {
            nextClient.onReceivedError(view, errorCode, description, failingUrl)
        } else {
            super.onReceivedError(view, errorCode, description, failingUrl)
        }
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

    override fun shouldOverrideKeyEvent(view: WebView, event: KeyEvent): Boolean {
        return next?.shouldOverrideKeyEvent(view, event) ?: false
    }

    override fun onUnhandledKeyEvent(view: WebView, event: KeyEvent) {
        val nextClient = next
        if (nextClient != null) {
            nextClient.onUnhandledKeyEvent(view, event)
        } else {
            super.onUnhandledKeyEvent(view, event)
        }
    }

    override fun onPageCommitVisible(view: WebView, url: String?) {
        next?.onPageCommitVisible(view, url)
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: android.webkit.HttpAuthHandler,
        host: String?,
        realm: String?
    ) {
        val nextClient = next
        if (nextClient != null) nextClient.onReceivedHttpAuthRequest(view, handler, host, realm)
        else handler.cancel()
    }

    override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) {
        val nextClient = next
        if (nextClient != null) nextClient.onFormResubmission(view, dontResend, resend)
        else dontResend.sendToTarget()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onTooManyRedirects(view: WebView, cancelMsg: Message, continueMsg: Message) {
        val nextClient = next
        if (nextClient != null) {
            nextClient.onTooManyRedirects(view, cancelMsg, continueMsg)
        } else {
            super.onTooManyRedirects(view, cancelMsg, continueMsg)
        }
    }

    override fun onReceivedSslError(
        view: WebView,
        handler: android.webkit.SslErrorHandler,
        error: android.net.http.SslError
    ) {
        val nextClient = next
        if (nextClient != null) nextClient.onReceivedSslError(view, handler, error)
        else handler.cancel()
    }

    override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
        val nextClient = next
        if (nextClient != null) {
            nextClient.onReceivedClientCertRequest(view, request)
        } else {
            request.cancel()
        }
    }

    @RequiresApi(27)
    override fun onSafeBrowsingHit(
        view: WebView,
        request: WebResourceRequest,
        threatType: Int,
        callback: SafeBrowsingResponse
    ) {
        val nextClient = next
        if (nextClient != null) {
            nextClient.onSafeBrowsingHit(view, request, threatType, callback)
        } else {
            callback.showInterstitial(true)
        }
    }

    override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
        next?.onScaleChanged(view, oldScale, newScale)
    }

    override fun onReceivedLoginRequest(view: WebView, realm: String?, account: String?, args: String?) {
        next?.onReceivedLoginRequest(view, realm, account, args)
    }

    @RequiresApi(26)
    override fun onRenderProcessGone(
        view: WebView,
        detail: android.webkit.RenderProcessGoneDetail
    ): Boolean {
        return next?.onRenderProcessGone(view, detail) ?: false
    }

    open fun nextClient(): WebViewClient? = next
}
