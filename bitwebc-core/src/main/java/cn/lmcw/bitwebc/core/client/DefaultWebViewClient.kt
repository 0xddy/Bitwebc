package cn.lmcw.bitwebc.core.client

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import androidx.webkit.WebMessagePortCompat
import cn.lmcw.bitwebc.core.api.WebIndicator
import cn.lmcw.bitwebc.core.api.WebLayout
import cn.lmcw.bitwebc.core.api.WebResourceInterceptor
import cn.lmcw.bitwebc.core.api.WebUIProvider
import cn.lmcw.bitwebc.core.bridge.BitwebcWebMessagePort
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import cn.lmcw.bitwebc.core.route.BitwebcSchemeRouter

class DefaultWebViewClient(
    private val webLayout: WebLayout,
    private val indicator: WebIndicator,
    private val schemeRouter: BitwebcSchemeRouter = BitwebcSchemeRouter(),
    private val nativeUiDelegate: WebUIProvider? = null,
    private val sslErrorPolicy: ((android.net.Uri, SslError) -> Boolean)? = null,
    private val messagePortSetup: ((WebView, WebMessagePortCompat, WebMessagePortCompat) -> Unit)? = null,
    private val eventReporter: ((BitwebcEvent) -> Unit)? = null,
    private val resourceInterceptors: List<WebResourceInterceptor> = emptyList(),
    next: WebViewClient? = null
) : MiddlewareWebClientBase(next) {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        if (resourceInterceptors.isEmpty()) {
            return super.shouldInterceptRequest(view, request)
        }
        val terminal: (WebResourceRequest) -> WebResourceResponse? = { req ->
            nextClient()?.shouldInterceptRequest(view, req)
        }
        val chain = RealInterceptorChain(resourceInterceptors, 0, view, request, terminal)
        return chain.proceed(request)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url ?: return super.shouldOverrideUrlLoading(view, request)
        if (schemeRouter.handle(view, uri)) return true
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https" || scheme == "about") {
            return super.shouldOverrideUrlLoading(view, request)
        }
        eventReporter?.invoke(
            BitwebcEvent.SchemeFallback(
                rawUrl = uri.toString(),
                reason = "外部Scheme未被处理，已降级忽略"
            )
        )
        return super.shouldOverrideUrlLoading(view, request)
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

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val onDecision = { proceed: Boolean ->
            eventReporter?.invoke(
                BitwebcEvent.SslError(url = view.url, message = error.toString())
            )
            if (proceed) {
                handler.proceed()
            } else {
                indicator.reset()
                handler.cancel()
            }
        }

        val errorUrl = (error.url ?: view.url.orEmpty()).toUri()
        if (sslErrorPolicy != null && sslErrorPolicy.invoke(errorUrl, error)) {
            onDecision(true)
            return
        }

        if (nativeUiDelegate != null) {
            nativeUiDelegate.showSslError(view, error, onDecision)
        } else {
            webLayout.showError("SSL 证书异常，已阻止继续加载") {
                webLayout.showWebContent()
                view.reload()
            }
            onDecision(false)
        }
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        showErrorAndRetry(view, "渲染进程异常退出，点击重试恢复页面") {
            webLayout.showWebContent()
            view.reload()
        }
        indicator.reset()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            eventReporter?.invoke(
                BitwebcEvent.RenderProcessGone(
                    didCrash = detail.didCrash(),
                    priorityAtExit = detail.rendererPriorityAtExit()
                )
            )
        } else {
            eventReporter?.invoke(
                BitwebcEvent.RenderProcessGone(
                    didCrash = true,
                    priorityAtExit = 0
                )
            )
        }
        return true
    }

    private fun showErrorAndRetry(view: WebView, message: String?, onRetry: () -> Unit) {
        if (nativeUiDelegate != null) {
            nativeUiDelegate.showErrorRetry(view.context, message, onRetry)
        } else {
            webLayout.showError(message, onRetry)
        }
    }
}
