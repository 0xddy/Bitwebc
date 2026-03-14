package cn.lmcw.bitwebc.core.dsl

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ColorInt
import androidx.core.graphics.toColorInt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import cn.lmcw.bitwebc.core.api.DownloadHandler
import cn.lmcw.bitwebc.core.api.FileChooserHandler
import cn.lmcw.bitwebc.core.api.WebLayout
import cn.lmcw.bitwebc.core.api.WebLifecycle
import cn.lmcw.bitwebc.core.api.WebResourceInterceptor
import cn.lmcw.bitwebc.core.api.WebUIProvider
import cn.lmcw.bitwebc.core.bridge.BitwebcJsBridge
import cn.lmcw.bitwebc.core.client.AssetsRouteInterceptor
import cn.lmcw.bitwebc.core.client.DefaultWebChromeClient
import cn.lmcw.bitwebc.core.client.DefaultWebViewClient
import cn.lmcw.bitwebc.core.event.BitwebcEventHub
import cn.lmcw.bitwebc.core.event.BitwebcEventListener
import cn.lmcw.bitwebc.core.lifecycle.BitwebcLifecycleObserver
import cn.lmcw.bitwebc.core.permission.PermissionResultFragment
import cn.lmcw.bitwebc.core.permission.PermissionWebChromeMiddleware
import cn.lmcw.bitwebc.core.pool.BitwebcWebViewPool
import cn.lmcw.bitwebc.core.pool.BitwebcWebViewPoolRecycler
import cn.lmcw.bitwebc.core.settings.BitwebcSettings
import cn.lmcw.bitwebc.core.ui.CustomErrorWebLayout
import cn.lmcw.bitwebc.core.ui.DefaultWebIndicator
import cn.lmcw.bitwebc.core.ui.DefaultWebLayout

class BitwebcBuilder internal constructor(
    private val activity: ComponentActivity,
    private val lifecycleOwner: LifecycleOwner = activity
) {
    companion object {
        private const val TAG = "BitwebcBuilder"
    }

    private var initialUrl: String? = null
    private var container: ViewGroup? = null
    private var layout: WebLayout = DefaultWebLayout()
    private var indicator: cn.lmcw.bitwebc.core.api.WebIndicator = DefaultWebIndicator()
    private var lifeCycle: WebLifecycle = object : WebLifecycle {}
    private var nextWebViewClient: WebViewClient? = null
    private var nextWebChromeClient: WebChromeClient? = null
    private var customFileChooserHandler: FileChooserHandler? = null
    private var customFileChooserFactory: ((ComponentActivity) -> FileChooserHandler)? = null
    private var customDownloadHandler: DownloadHandler? = null
    private var customDownloadFactory: ((ComponentActivity) -> DownloadHandler)? = null
    private var reuseWebViewFromPool: Boolean = false
    private val poolRecycleOptions = PoolRecycleOptions()
    private var nativeUiDelegate: WebUIProvider? = null
    private var sslErrorPolicy: ((android.net.Uri, android.net.http.SslError) -> Boolean)? = null
    private var messagePortSetup: ((WebView, androidx.webkit.WebMessagePortCompat, androidx.webkit.WebMessagePortCompat) -> Unit)? = null
    private val jsBridgeList = mutableListOf<Pair<String, Any>>()
    private val eventListeners = mutableListOf<BitwebcEventListener>()
    private val resourceInterceptors = mutableListOf<WebResourceInterceptor>()
    private val settings = BitwebcSettings()

    fun loadUrl(url: String) = apply { this.initialUrl = url }
    internal fun attachTo(container: ViewGroup) = apply { this.container = container }
    fun errorLayout(layout: WebLayout) = apply { this.layout = layout }
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
    fun errorPage(block: ErrorPageOptions.() -> Unit) = apply {
        val options = ErrorPageOptions().apply(block)
        this.layout = CustomErrorWebLayout(
            layoutRes = options.layoutRes,
            retryViewId = options.retryViewId,
            errorMessageViewId = options.errorMessageViewId
        )
    }
    fun customIndicator(indicator: cn.lmcw.bitwebc.core.api.WebIndicator) = apply { this.indicator = indicator }
    fun indicator(block: ProgressIndicatorOptions.() -> Unit) = apply {
        val options = ProgressIndicatorOptions().apply(block)
        indicator = DefaultWebIndicator(
            heightDp = options.heightDp,
            color = options.color
        )
    }
    fun lifeCycle(lifeCycle: WebLifecycle) = apply { this.lifeCycle = lifeCycle }
    fun webViewInterceptor(next: WebViewClient) = apply { this.nextWebViewClient = next }
    fun webChromeInterceptor(next: WebChromeClient) = apply { this.nextWebChromeClient = next }
    fun addResourceInterceptor(interceptor: WebResourceInterceptor) = apply { resourceInterceptors += interceptor }
    fun registerFileChooserHandler(handler: FileChooserHandler) = apply {
        this.customFileChooserHandler = handler
        this.customFileChooserFactory = null
    }
    /** 文件选择器工厂，未注册时使用默认实现 */
    fun registerFileChooserHandler(factory: (ComponentActivity) -> FileChooserHandler) = apply {
        this.customFileChooserFactory = factory
        this.customFileChooserHandler = null
    }
    fun registerDownloadHandler(handler: DownloadHandler) = apply {
        this.customDownloadHandler = handler
        this.customDownloadFactory = null
    }
    /** 下载处理器工厂，未注册时使用默认实现 */
    fun registerDownloadHandler(factory: (ComponentActivity) -> DownloadHandler) = apply {
        this.customDownloadFactory = factory
        this.customDownloadHandler = null
    }
    /** 注册 JSBridge，前端通过 window[name].methodName() 调用 */
    fun jsBridge(name: String, bridge: Any) = apply { jsBridgeList += name to bridge }
    fun eventListener(listener: BitwebcEventListener) = apply { eventListeners += listener }
    fun reuseWebViewFromPool(enable: Boolean = true) = apply { reuseWebViewFromPool = enable }
    fun poolRecycleOptions(block: PoolRecycleOptions.() -> Unit) = apply { poolRecycleOptions.apply(block) }

    fun settings(block: BitwebcSettings.() -> Unit) = apply {
        settings.block()
    }

    /** 自定义 Web 与 Native 交互 UI（如 JS 弹窗替代默认 AlertDialog） */
    fun nativeUiDelegate(delegate: WebUIProvider) = apply { nativeUiDelegate = delegate }

    /**
     * SSL 错误放行策略。返回 true 放行（proceed），返回 false 走默认拦截流程（错误页 + cancel）。
     *
     * 优先级高于 [nativeUiDelegate] 的 `showSslError`。
     *
     * ```
     * sslErrorPolicy { url, error ->
     *     url.host == "dev.example.com"
     * }
     * ```
     */
    fun sslErrorPolicy(policy: (url: android.net.Uri, error: android.net.http.SslError) -> Boolean) =
        apply { sslErrorPolicy = policy }

    /** 设置 WebMessagePort 回调，用于 postMessage / channel 通信 */
    fun messagePorts(block: (WebView, androidx.webkit.WebMessagePortCompat, androidx.webkit.WebMessagePortCompat) -> Unit) =
        apply { messagePortSetup = block }

    fun launch(): BitwebcSession {
        val eventHub = BitwebcEventHub()
        eventListeners.forEach { eventHub.addListener(it) }
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
            nativeUiDelegate = nativeUiDelegate,
            sslErrorPolicy = sslErrorPolicy,
            messagePortSetup = messagePortSetup,
            eventReporter = eventHub::emit,
            resourceInterceptors = resourceInterceptors.toList(),
            next = effectiveNextWebClient
        )
        val fileChooser: FileChooserHandler? = customFileChooserHandler
            ?: customFileChooserFactory?.invoke(activity)
            ?: BitwebcPlugins.defaultFileChooserFactory?.invoke(activity, lifecycleOwner, "default", eventHub::emit)
        if (fileChooser == null) {
            Log.w(
                TAG,
                "No FileChooserHandler resolved. Please register custom handler or include default filechooser module."
            )
        }
        val resolvedNextChromeClient = fileChooser?.createWebChromeClient(nextWebChromeClient) ?: nextWebChromeClient
        val permissionFragment = (activity as? FragmentActivity)?.let { PermissionResultFragment.ensureAdded(it) }
        val chromeBeforeDefault = if (permissionFragment != null) {
            PermissionWebChromeMiddleware(activity, permissionFragment, resolvedNextChromeClient)
        } else {
            resolvedNextChromeClient
        }
        val defaultChromeClient = DefaultWebChromeClient(
            activity = activity,
            indicator = indicator,
            nativeUiDelegate = nativeUiDelegate,
            eventReporter = eventHub::emit,
            next = chromeBeforeDefault
        )

        webView.webViewClient = defaultWebClient
        webView.webChromeClient = defaultChromeClient
        val resolvedDownloadHandler: DownloadHandler? = customDownloadHandler
            ?: customDownloadFactory?.invoke(activity)
            ?: BitwebcPlugins.defaultDownloadFactory?.invoke(activity, eventHub::emit)
        if (resolvedDownloadHandler == null) {
            Log.w(
                TAG,
                "No DownloadHandler resolved. Please register custom handler or include default download module."
            )
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
            recycler = if (reuseWebViewFromPool) BitwebcWebViewPoolRecycler(
                BitwebcWebViewPool.RecyclePolicy(
                    clearCacheOnRecycle = poolRecycleOptions.clearCacheOnRecycle,
                    clearDiskCacheOnRecycle = poolRecycleOptions.clearDiskCacheOnRecycle
                )
            ) else null
        ) {
            backPressedCallback.remove()
            (root.parent as? ViewGroup)?.removeView(root)
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        lifeCycle.onAttach(activity, webView)

        initialUrl?.let { webView.loadUrl(it) }
        return BitwebcSession(
            webView = webView,
            lifecycleOwner = lifecycleOwner,
            lifecycleObserver = lifecycleObserver,
            backPressedCallback = backPressedCallback,
            eventHub = eventHub,
            chromeClient = defaultChromeClient
        )
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

class ErrorPageOptions internal constructor() {
    @androidx.annotation.LayoutRes
    var layoutRes: Int = 0
        private set
    var retryViewId: Int = View.NO_ID
        private set
    var errorMessageViewId: Int = View.NO_ID
        private set

    fun layout(@androidx.annotation.LayoutRes resId: Int) = apply { layoutRes = resId }
    fun retryView(@androidx.annotation.IdRes id: Int) = apply { retryViewId = id }
    fun errorMessageView(@androidx.annotation.IdRes id: Int) = apply { errorMessageViewId = id }
}

class PoolRecycleOptions internal constructor() {
    internal var clearCacheOnRecycle: Boolean = false
        private set
    internal var clearDiskCacheOnRecycle: Boolean = false
        private set

    /** 回收时是否清理缓存；includeDisk 为 true 时同时清磁盘 */
    fun clearCacheOnRecycle(enable: Boolean = true, includeDisk: Boolean = false) = apply {
        clearCacheOnRecycle = enable
        clearDiskCacheOnRecycle = enable && includeDisk
    }
}
