package cn.lmcw.bitwebc.core.dsl

import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ColorInt
import androidx.core.graphics.toColorInt
import cn.lmcw.bitwebc.core.api.IFileChooserHandler
import cn.lmcw.bitwebc.core.api.IDownloadHandler
import cn.lmcw.bitwebc.core.api.ILifeCycle
import cn.lmcw.bitwebc.core.api.IWebIndicator
import cn.lmcw.bitwebc.core.api.IWebLayout
import cn.lmcw.bitwebc.core.bridge.BitwebcJsBridge
import cn.lmcw.bitwebc.core.client.DefaultWebChromeClient
import cn.lmcw.bitwebc.core.client.DefaultWebViewClient
import cn.lmcw.bitwebc.core.event.BitwebcEventCenter
import cn.lmcw.bitwebc.core.event.BitwebcEventHub
import cn.lmcw.bitwebc.core.event.BitwebcEventListener
import cn.lmcw.bitwebc.core.lifecycle.BitwebcLifecycleObserver
import cn.lmcw.bitwebc.core.pool.BitwebcWebViewPool
import cn.lmcw.bitwebc.core.settings.BitwebcSettings
import cn.lmcw.bitwebc.core.ui.CustomErrorWebLayout
import cn.lmcw.bitwebc.core.ui.DefaultWebLayout
import cn.lmcw.bitwebc.core.ui.WebIndicator

class BitwebcBuilder internal constructor(
    private val activity: ComponentActivity
) {
    private var initialUrl: String? = null
    private var container: ViewGroup? = null
    private var layout: IWebLayout = DefaultWebLayout()
    private var indicator: IWebIndicator = WebIndicator()
    private var lifeCycle: ILifeCycle = object : ILifeCycle {}
    private var nextWebViewClient: WebViewClient? = null
    private var nextWebChromeClient: WebChromeClient? = null
    private var fileChooserHandler: IFileChooserHandler? = null
    private var downloadHandler: IDownloadHandler? = null
    private var autoDefaultFileChooser: Boolean = true
    private var autoDefaultDownload: Boolean = true
    private var reuseWebViewFromPool: Boolean = false
    private val jsBridgeList = mutableListOf<Pair<String, Any>>()
    private val eventHub: BitwebcEventHub = BitwebcEventCenter.hub(activity)
    private val settings = BitwebcSettings()

    fun loadUrl(url: String) = apply { this.initialUrl = url }
    fun attachTo(container: ViewGroup) = apply { this.container = container }
    fun errorLayout(layout: IWebLayout) = apply { this.layout = layout }
    fun errorPage(
        errorView: View,
        retryViewId: Int = View.NO_ID,
        errorMessageViewId: Int = View.NO_ID
    ) = apply {
        this.layout = CustomErrorWebLayout(
            errorView = errorView,
            retryViewId = retryViewId,
            errorMessageViewId = errorMessageViewId
        )
    }
    fun customIndicator(indicator: IWebIndicator) = apply { this.indicator = indicator }
    fun indicator(block: ProgressIndicatorOptions.() -> Unit) = apply {
        val options = ProgressIndicatorOptions().apply(block)
        indicator = WebIndicator(
            heightDp = options.heightDp,
            color = options.color
        )
    }
    fun lifeCycle(lifeCycle: ILifeCycle) = apply { this.lifeCycle = lifeCycle }
    fun webViewInterceptor(next: WebViewClient) = apply { this.nextWebViewClient = next }
    fun webChromeInterceptor(next: WebChromeClient) = apply { this.nextWebChromeClient = next }
    /** 传入自定义文件选择实现；不传且 [autoFileChooserHandler] 为 true 时使用模块注册的默认实现。 */
    fun fileChooserHandler(handler: IFileChooserHandler) = apply { this.fileChooserHandler = handler }
    fun autoFileChooserHandler(enable: Boolean = true) = apply { autoDefaultFileChooser = enable }
    fun autoDownload(enable: Boolean = true) = apply { autoDefaultDownload = enable }
    /** 传入自定义下载实现；不传且 [autoDownload] 为 true 时使用模块注册的默认实现。 */
    fun downloadHandler(handler: IDownloadHandler) = apply { this.downloadHandler = handler }
    fun jsBridge(name: String, bridge: Any) = apply { jsBridgeList += name to bridge }
    fun eventListener(listener: BitwebcEventListener) = apply { eventHub.addListener(listener) }
    fun reuseWebViewFromPool(enable: Boolean = true) = apply { reuseWebViewFromPool = enable }

    fun settings(block: BitwebcSettings.() -> Unit) = apply {
        settings.block()
    }

    fun launch(): BitwebcSession {
        val hostContainer = container ?: activity.findViewById(android.R.id.content)
        val webView = if (reuseWebViewFromPool) {
            BitwebcWebViewPool.acquire(activity)
        } else {
            WebView(activity)
        }
        settings.applyTo(webView)

        val root = layout.createRoot(activity)
        layout.attach(activity, webView, indicator.createView(activity))

        hostContainer.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val defaultWebClient = DefaultWebViewClient(
            webLayout = layout,
            indicator = indicator,
            eventReporter = eventHub::emit,
            next = nextWebViewClient
        )
        val fileChooser: IFileChooserHandler? = fileChooserHandler ?: if (autoDefaultFileChooser) {
            BitwebcPlugins.defaultFileChooserFactory?.invoke(activity, eventHub::emit)
        } else {
            null
        }
        val resolvedNextChromeClient = fileChooser?.createWebChromeClient(nextWebChromeClient) ?: nextWebChromeClient
        val defaultChromeClient = DefaultWebChromeClient(
            activity = activity,
            indicator = indicator,
            eventReporter = eventHub::emit,
            next = resolvedNextChromeClient
        )

        webView.webViewClient = defaultWebClient
        webView.webChromeClient = defaultChromeClient
        val resolvedDownloadHandler: IDownloadHandler? = downloadHandler ?: if (autoDefaultDownload) {
            BitwebcPlugins.defaultDownloadFactory?.invoke(activity, eventHub::emit)
        } else {
            null
        }
        resolvedDownloadHandler?.let { webView.setDownloadListener(it) }
        jsBridgeList.forEach { (name, bridge) ->
            BitwebcJsBridge.injectSafely(webView, name, bridge)
        }

        val backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                    return
                }
                isEnabled = false
                activity.onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
        activity.onBackPressedDispatcher.addCallback(activity, backPressedCallback)

        val lifecycleObserver = BitwebcLifecycleObserver(
            webView = webView,
            lifeCycle = lifeCycle,
            recycleToPool = reuseWebViewFromPool
        ) {
            backPressedCallback.remove()
            (root.parent as? ViewGroup)?.removeView(root)
        }
        activity.lifecycle.addObserver(lifecycleObserver)
        lifeCycle.onAttach(activity, webView)

        initialUrl?.let { webView.loadUrl(it) }
        return BitwebcSession(webView, root, lifecycleObserver, backPressedCallback, eventHub)
    }

}

class ProgressIndicatorOptions internal constructor() {
    @ColorInt
    var color: Int = "#2F80ED".toColorInt()
        private set
    var heightDp: Int = 2
        private set

    fun color(@ColorInt colorInt: Int) = apply {
        color = colorInt
    }

    fun color(colorString: String) = apply {
        color = colorString.toColorInt()
    }

    fun heightDp(dp: Int) = apply {
        heightDp = dp.coerceAtLeast(1)
    }
}
