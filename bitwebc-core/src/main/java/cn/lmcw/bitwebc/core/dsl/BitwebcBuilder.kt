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
import cn.lmcw.bitwebc.core.api.IWebUIProvider
import cn.lmcw.bitwebc.core.bridge.BitwebcJsBridge
import androidx.fragment.app.FragmentActivity
import cn.lmcw.bitwebc.core.client.AssetsRouteInterceptor
import cn.lmcw.bitwebc.core.client.DefaultWebChromeClient
import cn.lmcw.bitwebc.core.client.DefaultWebViewClient
import cn.lmcw.bitwebc.core.permission.PermissionResultFragment
import cn.lmcw.bitwebc.core.permission.PermissionWebChromeMiddleware
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
    private var uiProvider: IWebUIProvider? = null
    private var messagePortSetup: ((WebView, androidx.webkit.WebMessagePortCompat, androidx.webkit.WebMessagePortCompat) -> Unit)? = null
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
    /**
     * 注入 JSBridge（基于 [@JavascriptInterface]）。**推荐方式**，与前端约定即可：
     * 前端直接调用 `window[name].methodName(args)`，无需监听 message 事件或处理 port，对前端无侵入。
     */
    fun jsBridge(name: String, bridge: Any) = apply { jsBridgeList += name to bridge }
    fun eventListener(listener: BitwebcEventListener) = apply { eventHub.addListener(listener) }
    fun reuseWebViewFromPool(enable: Boolean = true) = apply { reuseWebViewFromPool = enable }

    fun settings(block: BitwebcSettings.() -> Unit) = apply {
        settings.block()
    }

    /** 传入自定义 Web UI 提供方（JS 弹窗、错误重试等）；不传则使用默认 AlertDialog 行为。 */
    fun uiProvider(provider: IWebUIProvider) = apply { uiProvider = provider }

    /**
     * **可选**：配置 WebMessagePort 双向通信（需前端配合监听 message 事件、处理 port，对前端改动较大）。
     * 常规场景请使用 [jsBridge] + `window[name].method()`，与 [@JavascriptInterface] 兼容，前端写法简单。
     * 仅在需要大 payload、复杂异步双向通道时再选用本 API。不调用则不会创建 channel，对前端零影响。
     */
    fun messagePorts(block: (android.webkit.WebView, androidx.webkit.WebMessagePortCompat, androidx.webkit.WebMessagePortCompat) -> Unit) =
        apply { messagePortSetup = block }

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

        val effectiveNextWebClient = if (settings.assetRoutes.isEmpty()) {
            nextWebViewClient
        } else {
            AssetsRouteInterceptor(activity, settings.assetRoutes.toList(), nextWebViewClient)
        }
        val defaultWebClient = DefaultWebViewClient(
            webLayout = layout,
            indicator = indicator,
            uiProvider = uiProvider,
            messagePortSetup = messagePortSetup,
            eventReporter = eventHub::emit,
            next = effectiveNextWebClient
        )
        val fileChooser: IFileChooserHandler? = fileChooserHandler ?: if (autoDefaultFileChooser) {
            BitwebcPlugins.defaultFileChooserFactory?.invoke(activity, eventHub::emit)
        } else {
            null
        }
        val resolvedNextChromeClient = fileChooser?.createWebChromeClient(nextWebChromeClient) ?: nextWebChromeClient
        val permissionFragment = (activity as? FragmentActivity)?.let { PermissionResultFragment.ensureAdded(it) }
        val chromeBeforeDefault = if (permissionFragment != null && activity is FragmentActivity) {
            PermissionWebChromeMiddleware(activity, permissionFragment, resolvedNextChromeClient)
        } else {
            resolvedNextChromeClient
        }
        val defaultChromeClient = DefaultWebChromeClient(
            activity = activity,
            indicator = indicator,
            uiProvider = uiProvider,
            eventReporter = eventHub::emit,
            next = chromeBeforeDefault
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
        return BitwebcSession(webView, lifecycleObserver, backPressedCallback, eventHub)
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
