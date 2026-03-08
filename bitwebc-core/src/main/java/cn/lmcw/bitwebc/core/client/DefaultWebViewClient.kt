package cn.lmcw.bitwebc.core.client

import android.graphics.Bitmap
import android.net.http.SslError
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import cn.lmcw.bitwebc.core.api.IWebIndicator
import cn.lmcw.bitwebc.core.api.IWebLayout
import cn.lmcw.bitwebc.core.api.IWebUIProvider
import androidx.webkit.WebMessagePortCompat
import cn.lmcw.bitwebc.core.bridge.BitwebcWebMessagePort
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import cn.lmcw.bitwebc.core.route.BitwebcSchemeRouter

class DefaultWebViewClient(
    private val webLayout: IWebLayout,
    private val indicator: IWebIndicator,
    private val schemeRouter: BitwebcSchemeRouter = BitwebcSchemeRouter(),
    private val uiProvider: IWebUIProvider? = null,
    private val messagePortSetup: ((WebView, WebMessagePortCompat, WebMessagePortCompat) -> Unit)? = null,
    private val eventReporter: ((BitwebcEvent) -> Unit)? = null,
    next: WebViewClient? = null
) : MiddlewareWebClientBase(next) {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url ?: return super.shouldOverrideUrlLoading(view, request)
        if (schemeRouter.handle(view, uri)) return true
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https" || scheme == "about") return false
        eventReporter?.invoke(
            BitwebcEvent.SchemeFallback(
                rawUrl = uri.toString(),
                reason = "外部Scheme未被处理，已降级忽略"
            )
        )
        return super.shouldOverrideUrlLoading(view, request)
    }

    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        if (url.isBlank()) return super.shouldOverrideUrlLoading(view, url)
        val uri = Uri.parse(url)
        if (schemeRouter.handle(view, uri)) return true
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https" || scheme == "about") return false
        eventReporter?.invoke(
            BitwebcEvent.SchemeFallback(
                rawUrl = uri.toString(),
                reason = "外部Scheme未被处理，已降级忽略"
            )
        )
        return super.shouldOverrideUrlLoading(view, url)
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        webLayout.showWebContent()
        indicator.onPageStarted()
        eventReporter?.invoke(BitwebcEvent.PageStarted(url))
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        indicator.onPageFinished()
        CookieManager.getInstance().flush()
        eventReporter?.invoke(BitwebcEvent.PageFinished(url))
        messagePortSetup?.let { setup ->
            BitwebcWebMessagePort.setupOnPageFinished(view, url) { receivePort, sendToJsPort ->
                setup(view, receivePort, sendToJsPort)
            }
        }
        super.onPageFinished(view, url)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        if (request.isForMainFrame) {
            showErrorAndRetry(view, error.description?.toString()) {
                webLayout.showWebContent()
                view.reload()
            }
            indicator.reset()
            eventReporter?.invoke(
                BitwebcEvent.PageError(
                    url = request.url?.toString(),
                    message = error.description?.toString()
                )
            )
        }
        super.onReceivedError(view, request, error)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        showErrorAndRetry(view, "SSL 证书异常，已阻止继续加载") {
            webLayout.showWebContent()
            view.reload()
        }
        indicator.reset()
        eventReporter?.invoke(
            BitwebcEvent.SslError(
                url = view.url,
                message = error.toString()
            )
        )
        handler.cancel()
        super.onReceivedSslError(view, handler, error)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        showErrorAndRetry(view, "渲染进程异常退出，点击重试恢复页面") {
            webLayout.showWebContent()
            view.reload()
        }
        indicator.reset()
        eventReporter?.invoke(
            BitwebcEvent.RenderProcessGone(
                didCrash = detail.didCrash(),
                priorityAtExit = detail.rendererPriorityAtExit()
            )
        )
        return true
    }

    private fun showErrorAndRetry(view: WebView, message: String?, onRetry: () -> Unit) {
        if (uiProvider != null) {
            uiProvider.showErrorRetry(view.context, message, onRetry)
        } else {
            webLayout.showError(message, onRetry)
        }
    }
}
