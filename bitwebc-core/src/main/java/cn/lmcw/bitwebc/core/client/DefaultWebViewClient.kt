package cn.lmcw.bitwebc.core.client

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.view.ViewGroup
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
import cn.lmcw.bitwebc.core.api.ErrorContext
import cn.lmcw.bitwebc.core.api.ErrorPolicy
import cn.lmcw.bitwebc.core.api.WebIndicator
import cn.lmcw.bitwebc.core.api.WebLayout
import cn.lmcw.bitwebc.core.api.WebResourceInterceptor
import cn.lmcw.bitwebc.core.bridge.BitwebcWebMessagePort
import cn.lmcw.bitwebc.core.bridge.WebOrigin
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import cn.lmcw.bitwebc.core.route.BitwebcSchemeRouter

internal class DefaultWebViewClient(
    private val webLayout: WebLayout,
    private val indicator: WebIndicator,
    private val schemeRouter: BitwebcSchemeRouter,
    private val sslErrorPolicy: ((android.net.Uri, SslError) -> Boolean)?,
    private val messagePortSetup: ((WebView, WebMessagePortCompat, WebMessagePortCompat) -> Unit)?,
    private val eventReporter: ((BitwebcEvent) -> Unit)?,
    private val resourceInterceptors: List<WebResourceInterceptor>,
    private val errorPolicy: ErrorPolicy,
    private val rendererRecovery: ((failedView: WebView, lastMainFrameUrl: String?) -> WebView?)?,
    private val rendererRetry: ((replacement: WebView) -> Unit)?,
    private val fallbackMainFrameUrl: () -> String?,
    next: WebViewClient?,
    private val messagePortAllowedOrigins: Set<String>,
    private val rendererQuarantine: ((failedView: WebView) -> Unit)?,
    private val rendererRecoveryFailed: ((failedView: WebView) -> Unit)?
) : MiddlewareWebClientBase(next) {

    internal var visitedHistoryListener: ((WebView, String?) -> Unit)? = null
    internal var mainFrameNavigationListener: (
        (WebView, String?, allowRedirect: Boolean) -> Unit
    )? = null

    private var recoveringFromError = false
    private val errorUrls = mutableSetOf<String>()
    private val pendingUrls = mutableSetOf<String>()
    private var lastMainFrameUrl: String? = null

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        if (resourceInterceptors.isEmpty()) return super.shouldInterceptRequest(view, request)
        val terminal: (WebResourceRequest) -> WebResourceResponse? = { req ->
            nextClient()?.shouldInterceptRequest(view, req)
        }
        return RealInterceptorChain(resourceInterceptors, 0, view, request, terminal).proceed(request)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url ?: return super.shouldOverrideUrlLoading(view, request)
        if (nextClient()?.shouldOverrideUrlLoading(view, request) == true) return true
        val scheme = uri.scheme?.lowercase()
        val isExternalScheme = scheme != null && scheme !in setOf("http", "https", "about")
        if (isExternalScheme && (!request.isForMainFrame || !request.hasGesture())) {
            reportEvent(
                BitwebcEvent.SchemeFallback(
                    rawUrl = uri.toString(),
                    reason = "External schemes require a user gesture in the main frame"
                )
            )
            return true
        }
        return when (schemeRouter.route(view, uri)) {
            BitwebcSchemeRouter.Result.PASS_THROUGH -> false
            BitwebcSchemeRouter.Result.HANDLED -> true
            BitwebcSchemeRouter.Result.CONSUMED -> {
                reportEvent(
                    BitwebcEvent.SchemeFallback(
                        rawUrl = uri.toString(),
                        reason = "No activity could handle this external scheme"
                    )
                )
                true
            }
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        if (!url.isNullOrBlank()) {
            lastMainFrameUrl = url
            pendingUrls += url
        }
        runCatching { mainFrameNavigationListener?.invoke(view, url, true) }
        if (!recoveringFromError) webLayout.showWebContent()
        indicator.onPageStarted()
        super.onPageStarted(view, url, favicon)
        reportEvent(BitwebcEvent.PageStarted(url))
    }

    override fun onPageCommitVisible(view: WebView, url: String?) {
        super.onPageCommitVisible(view, url)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (!url.isNullOrBlank()) lastMainFrameUrl = url
        runCatching { mainFrameNavigationListener?.invoke(view, url, false) }
        indicator.onPageFinished()
        CookieManager.getInstance().flush()
        if (url != null && url !in errorUrls && url in pendingUrls && recoveringFromError) {
            recoveringFromError = false
            webLayout.showWebContent()
        }
        if (url != null) pendingUrls -= url
        errorUrls.clear()
        val pageOrigin = WebOrigin.fromUrl(url)
        messagePortSetup?.takeIf {
            pageOrigin != null && pageOrigin in messagePortAllowedOrigins
        }?.let { setup ->
            BitwebcWebMessagePort.setupOnPageFinished(view, url) { receivePort, sendToJsPort ->
                setup(view, receivePort, sendToJsPort)
            }
        }
        super.onPageFinished(view, url)
        reportEvent(BitwebcEvent.PageFinished(url))
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        super.onReceivedError(view, request, error)
        val context = ErrorContext.Network(view, request, error)
        if (runCatching { errorPolicy.shouldShowError(context) }.getOrDefault(false)) {
            val failingUrl = request.url?.toString()
            failingUrl?.let { errorUrls += it }
            recoveringFromError = false
            showErrorAndRetry(view, error.description?.toString())
            indicator.reset()
            reportEvent(BitwebcEvent.PageError(failingUrl, error.description?.toString()))
        }
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        val requestUrl = request.url?.toString()
        val context = ErrorContext.Http(view, request, errorResponse)
        var pageErrorMessage: String? = null
        if (runCatching { errorPolicy.shouldShowError(context) }.getOrDefault(false)) {
            requestUrl?.let { errorUrls += it }
            recoveringFromError = false
            runCatching {
                showErrorAndRetry(view, "Server returned HTTP ${errorResponse.statusCode}. Tap retry.")
            }
            runCatching { indicator.reset() }
            pageErrorMessage = "HTTP ${errorResponse.statusCode}"
        }
        reportEvent(BitwebcEvent.HttpError(requestUrl, errorResponse.statusCode))
        pageErrorMessage?.let { reportEvent(BitwebcEvent.PageError(requestUrl, it)) }
    }

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        var decisionMade = false
        val onDecision = decision@{ proceed: Boolean ->
            if (decisionMade) return@decision
            decisionMade = true
            val failingUrl = runCatching { view.url }.getOrNull()
            if (proceed) {
                runCatching { handler.proceed() }
            } else {
                runCatching { indicator.reset() }
                runCatching { handler.cancel() }
            }
            val message = error.toString()
            reportEvent(BitwebcEvent.SslError(failingUrl, message))
            if (!proceed) reportEvent(BitwebcEvent.PageError(failingUrl, message))
        }

        val errorUrl = (error.url ?: view.url.orEmpty()).toUri()
        val policy = sslErrorPolicy
        if (policy != null && runCatching { policy.invoke(errorUrl, error) }.getOrDefault(false)) {
            onDecision(true)
            return
        }
        runCatching {
            webLayout.showError("SSL certificate validation failed. Loading was blocked.") {
                recoveringFromError = true
                view.reload()
            }
        }
        onDecision(false)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        if (!url.isNullOrBlank()) lastMainFrameUrl = url
        runCatching { mainFrameNavigationListener?.invoke(view, url, false) }
        visitedHistoryListener?.invoke(view, url)
        super.doUpdateVisitedHistory(view, url, isReload)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        runCatching { rendererQuarantine?.invoke(view) }
        runCatching { indicator.reset() }

        // Navigation callbacks are not guaranteed to arrive before a renderer exits. This is
        // especially common immediately after restoreState(), so retain the stable Session state
        // as a fallback instead of turning a valid page into about:blank on retry.
        val retryUrl = lastMainFrameUrl
            ?: runCatching(fallbackMainFrameUrl).getOrNull()?.takeIf(String::isNotBlank)
        val replacement = runCatching { rendererRecovery?.invoke(view, retryUrl) }.getOrNull()
        recoveringFromError = false
        pendingUrls.clear()
        errorUrls.clear()
        if (replacement != null) {
            runCatching {
                showErrorAndRetry(
                    replacement,
                    "The WebView renderer exited. Tap retry to restore the page."
                ) {
                    recoveringFromError = true
                    val retry = rendererRetry
                    if (retry != null) {
                        retry(replacement)
                    } else {
                        replacement.loadUrl(retryUrl ?: "about:blank")
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            reportEvent(
                BitwebcEvent.RenderProcessGone(detail.didCrash(), detail.rendererPriorityAtExit())
            )
        } else {
            reportEvent(BitwebcEvent.RenderProcessGone(didCrash = true, priorityAtExit = 0))
        }
        if (replacement == null) {
            val ownerCleanup = rendererRecoveryFailed
            if (ownerCleanup != null) {
                runCatching { ownerCleanup.invoke(view) }
            } else {
                runCatching { (view.parent as? ViewGroup)?.removeView(view) }
                runCatching { view.destroy() }
            }
        }
        return true
    }

    private fun showErrorAndRetry(
        view: WebView,
        message: String?,
        retry: () -> Unit = {
            recoveringFromError = true
            view.reload()
        }
    ) {
        webLayout.showError(message, retry)
    }

    private fun reportEvent(event: BitwebcEvent) {
        runCatching { eventReporter?.invoke(event) }
    }
}
