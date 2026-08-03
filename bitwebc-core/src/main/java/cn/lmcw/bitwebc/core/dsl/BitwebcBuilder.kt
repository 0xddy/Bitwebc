package cn.lmcw.bitwebc.core.dsl

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ColorInt
import androidx.annotation.MainThread
import androidx.core.graphics.toColorInt
import androidx.lifecycle.LifecycleOwner
import cn.lmcw.bitwebc.core.api.WebLifecycle
import cn.lmcw.bitwebc.core.bridge.BitwebcJsBridge
import cn.lmcw.bitwebc.core.bridge.WebOrigin
import cn.lmcw.bitwebc.core.client.AssetsRouteInterceptor
import cn.lmcw.bitwebc.core.client.DefaultWebChromeClient
import cn.lmcw.bitwebc.core.client.DefaultWebViewClient
import cn.lmcw.bitwebc.core.client.MiddlewareWebChromeBase
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import cn.lmcw.bitwebc.core.event.BitwebcEventHub
import cn.lmcw.bitwebc.core.event.BitwebcEventListener
import cn.lmcw.bitwebc.core.lifecycle.BitwebcLifecycleObserver
import cn.lmcw.bitwebc.core.lifecycle.destroyAfterRendererGone
import cn.lmcw.bitwebc.core.permission.ActivityPermissionRequester
import cn.lmcw.bitwebc.core.permission.PermissionWebChromeMiddleware
import cn.lmcw.bitwebc.core.route.BitwebcSchemeRouter
import cn.lmcw.bitwebc.core.state.BitwebcPageStateStore
import cn.lmcw.bitwebc.core.settings.DisplayConfig
import cn.lmcw.bitwebc.core.settings.WebSettingsConfig
import java.util.UUID

private class MutableBitwebcConfig : BitwebcConfigScope {
    private val webSettingsConfig = WebSettingsConfig()
    private val displayConfig = DisplayConfig()
    private val resourcesConfig = ResourcesConfig()
    private val uiConfig = UiConfig()
    private val clientsConfig = ClientsConfig()
    private val bridgesConfig = BridgesConfig()
    private val permissionsConfig = WebPermissionsConfig()
    private val integrationsConfig = IntegrationsConfig()

    override fun webSettings(block: WebSettingsConfig.() -> Unit) =
        webSettingsConfig.apply(block).let { Unit }

    override fun display(block: DisplayConfig.() -> Unit) =
        displayConfig.apply(block).let { Unit }

    override fun resources(block: ResourcesConfig.() -> Unit) =
        resourcesConfig.apply(block).let { Unit }

    override fun ui(block: UiConfig.() -> Unit) = uiConfig.apply(block).let { Unit }

    override fun clients(block: ClientsConfig.() -> Unit) =
        clientsConfig.apply(block).let { Unit }

    override fun bridges(block: BridgesConfig.() -> Unit) =
        bridgesConfig.apply(block).let { Unit }

    override fun webPermissions(block: WebPermissionsConfig.() -> Unit) =
        permissionsConfig.apply(block).let { Unit }

    override fun integrations(block: IntegrationsConfig.() -> Unit) =
        integrationsConfig.apply(block).let { Unit }

    fun freeze(): FrozenBitwebcConfig = FrozenBitwebcConfig(
        webSettings = webSettingsConfig.snapshot(),
        display = displayConfig.snapshot(),
        resources = resourcesConfig.snapshot(),
        ui = uiConfig.snapshot(),
        clients = clientsConfig.snapshot(),
        bridges = bridgesConfig.snapshot(),
        permissions = permissionsConfig.snapshot(),
        integrations = integrationsConfig.snapshot()
    )
}

/** Selects whether Bitwebc or its host owns normal WebView history back handling. */
enum class BackPressMode {
    /** Bitwebc consumes back while the WebView has history, then delegates to the Activity. */
    Internal,

    /** The host (for example Compose [androidx.activity.OnBackPressedDispatcher]) handles back. */
    Host
}

@BitwebcDsl
class BitwebcBuilder private constructor(
    private val activity: ComponentActivity,
    private val lifecycleOwner: LifecycleOwner = activity,
    private val staticConfig: MutableBitwebcConfig = MutableBitwebcConfig()
) : BitwebcConfigScope by staticConfig {

    companion object {
        private const val TAG = "BitwebcBuilder"

        @JvmSynthetic
        internal fun create(
            activity: ComponentActivity,
            lifecycleOwner: LifecycleOwner = activity
        ): BitwebcBuilder = BitwebcBuilder(activity, lifecycleOwner)
    }

    private val navigationConfig = NavigationConfig()
    private val callbacksConfig = CallbacksConfig()
    private var explicitActivityResultKey: String? = null
    private var container: ViewGroup? = null
    private var launched = false

    fun navigation(block: NavigationConfig.() -> Unit) {
        navigationConfig.apply(block)
    }

    /** Stable Activity Result Registry key. Compose supplies this automatically. */
    fun activityResultKey(key: String) {
        val normalized = key.trim()
        require(normalized.isNotEmpty()) { "activityResultKey must not be blank" }
        explicitActivityResultKey = normalized
    }

    fun callbacks(block: CallbacksConfig.() -> Unit) {
        callbacksConfig.apply(block)
    }

    @JvmSynthetic
    internal fun attachTo(container: ViewGroup) = apply { this.container = container }

    @MainThread
    @JvmSynthetic
    internal fun launch(): BitwebcSession {
        check(!launched) { "A BitwebcBuilder can only launch one session" }
        check(lifecycleOwner.lifecycle.currentState != androidx.lifecycle.Lifecycle.State.DESTROYED) {
            "Cannot launch a Bitwebc session for a destroyed LifecycleOwner"
        }
        launched = true

        // Freeze the DSL before creating Android objects. A captured scope cannot mutate an
        // already-running Session or change the configuration used by renderer recovery.
        val config = staticConfig.freeze()
        val navigation = navigationConfig.snapshot()
        val callbacks = callbacksConfig.snapshot()
        val initialUrl = navigation.initialUrl
        val restoredNavigationState = navigation.restoredState
        val backPressMode = navigation.backHandling
        val settings = config.webSettings
        val display = config.display
        val layout = config.ui.layoutFactory.create(activity)
        val indicator = try {
            config.ui.indicatorFactory.create(activity)
        } catch (error: Throwable) {
            runCatching { layout.release() }
            throw error
        }
        val dialogProvider = try {
            config.ui.dialogProviderFactory?.create(activity)
        } catch (error: Throwable) {
            runCatching { indicator.release() }
            runCatching { layout.release() }
            throw error
        }
        fun releaseUiResources() {
            runCatching { dialogProvider?.release() }
            runCatching { indicator.release() }
            runCatching { layout.release() }
        }
        val nextWebViewClient = try {
            config.clients.webViewClientFactory?.create(activity)
        } catch (error: Throwable) {
            releaseUiResources()
            throw error
        }
        val nextWebChromeClient = try {
            config.clients.webChromeClientFactory?.create(activity)
        } catch (error: Throwable) {
            releaseUiResources()
            throw error
        }
        val sslErrorPolicy = config.clients.sslErrorHandler
        val errorPolicy = config.clients.errorPolicy
        val resourceInterceptors = config.resources.interceptors
        val messagePortRegistration = config.bridges.messagePorts
        val jsBridgeList = config.bridges.javascriptBridges
        val jsBridgeExecutor = config.bridges.executor
        val allowedWebPermissionOrigins = config.permissions
        val lifeCycle = callbacks.lifecycle

        val eventHub = BitwebcEventHub()
        val pageStateStore = BitwebcPageStateStore(initialUrl)
        var stateWebView: WebView? = null
        callbacks.eventListeners.forEach(eventHub::addListener)
        val reporter: (BitwebcEvent) -> Unit = { event ->
            pageStateStore.onEvent(event, stateWebView)
            eventHub.emit(event)
        }
        val sessionId = UUID.randomUUID().toString()
        val hostContainer = container ?: activity.findViewById(android.R.id.content)
        val activityResultKey = explicitActivityResultKey ?: if (hostContainer.id != View.NO_ID) {
            "host_${hostContainer.id}"
        } else {
            "session_$sessionId"
        }

        val validatedAssetRoutes = config.resources.assetRoutes
        val effectiveNextWebClient = if (validatedAssetRoutes.isEmpty()) {
            nextWebViewClient
        } else {
            AssetsRouteInterceptor(activity, validatedAssetRoutes, nextWebViewClient)
        }
        val fileChooser = try {
            config.integrations.fileChooserFactory?.create(
                FileChooserFactoryContext(
                    activity = activity,
                    lifecycleOwner = lifecycleOwner,
                    activityResultKey = activityResultKey,
                    reportEvent = reporter
                )
            )
        } catch (error: Throwable) {
            releaseUiResources()
            throw error
        }
        val resolvedNextChromeClient = try {
            fileChooser?.createWebChromeClient(nextWebChromeClient) ?: nextWebChromeClient
        } catch (error: Throwable) {
            runCatching { fileChooser?.release() }
            releaseUiResources()
            throw error
        }
        val permissionRequester = try {
            ActivityPermissionRequester(
                activity = activity,
                lifecycleOwner = lifecycleOwner,
                hostKey = activityResultKey
            )
        } catch (error: Throwable) {
            runCatching { fileChooser?.release() }
            releaseUiResources()
            throw error
        }
        val permissionMiddleware = PermissionWebChromeMiddleware(
            activity = activity,
            permissionRequester = permissionRequester,
            next = resolvedNextChromeClient,
            originAllowed = { origin ->
                WebOrigin.fromUrl(origin.toString()) in allowedWebPermissionOrigins
            }
        )
        fun releasePermissionBridge() {
            runCatching { permissionMiddleware.cancelPendingRequests() }
            runCatching { permissionRequester.release() }
        }
        val chromeBeforeState = permissionMiddleware
        val stateChromeClient = object : MiddlewareWebChromeBase(chromeBeforeState) {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                pageStateStore.onProgressChanged(view, newProgress)
                super.onProgressChanged(view, newProgress)
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                pageStateStore.onTitleChanged(view, title)
                super.onReceivedTitle(view, title)
            }
        }
        val defaultChromeClient = DefaultWebChromeClient(
            activity = activity,
            indicator = indicator,
            dialogProvider = dialogProvider,
            eventReporter = reporter,
            next = stateChromeClient
        )
        val resolvedDownloadHandler = try {
            config.integrations.downloadFactory?.create(
                DownloadFactoryContext(activity, reporter)
            )
        } catch (error: Throwable) {
            releasePermissionBridge()
            runCatching { fileChooser?.release() }
            releaseUiResources()
            throw error
        }
        // Delay Activity/View mutations until plugin factories and Fragment setup have succeeded.
        val root = try {
            layout.createRoot(activity)
        } catch (error: Throwable) {
            runCatching { defaultChromeClient.release() }
            releasePermissionBridge()
            runCatching { fileChooser?.release() }
            runCatching { resolvedDownloadHandler?.release() }
            releaseUiResources()
            throw error
        }
        val indicatorView = try {
            indicator.createView(activity)
        } catch (error: Throwable) {
            runCatching { defaultChromeClient.release() }
            releasePermissionBridge()
            runCatching { fileChooser?.release() }
            runCatching { resolvedDownloadHandler?.release() }
            releaseUiResources()
            throw error
        }
        val initialWebView = try {
            WebView(activity)
        } catch (error: Throwable) {
            runCatching { defaultChromeClient.release() }
            releasePermissionBridge()
            runCatching { fileChooser?.release() }
            runCatching { resolvedDownloadHandler?.release() }
            releaseUiResources()
            throw error
        }
        stateWebView = initialWebView
        try {
            settings.applyTo(initialWebView)
            display.applyTo(initialWebView)
            layout.attach(activity, initialWebView, indicatorView)
            hostContainer.addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        } catch (error: Throwable) {
            runCatching { defaultChromeClient.release() }
            releasePermissionBridge()
            runCatching { fileChooser?.release() }
            runCatching { resolvedDownloadHandler?.release() }
            releaseUiResources()
            runCatching { (root.parent as? ViewGroup)?.removeView(root) }
            runCatching { BitwebcJsBridge.removeSafely(initialWebView) }
            runCatching { initialWebView.destroy() }
            throw error
        }

        val messagePortOrigins = messagePortRegistration?.allowedOrigins.orEmpty()
        lateinit var lifecycleObserver: BitwebcLifecycleObserver
        lateinit var session: BitwebcSession
        lateinit var defaultWebClient: DefaultWebViewClient

        fun installRuntimeBindings(webView: WebView) {
            webView.webViewClient = defaultWebClient
            webView.webChromeClient = defaultChromeClient
            webView.setDownloadListener(resolvedDownloadHandler)
            jsBridgeList.forEach { registration ->
                val injected = BitwebcJsBridge.injectSafely(
                    webView,
                    registration.name,
                    registration.bridge,
                    registration.allowedOrigins,
                    jsBridgeExecutor
                )
                if (!injected) {
                    Log.w(
                        TAG,
                        "JS bridge '${registration.name}' was not injected; configure a valid trusted origin " +
                            "and ensure the installed WebView supports WebMessage listeners."
                    )
                }
            }
        }

        defaultWebClient = DefaultWebViewClient(
            webLayout = layout,
            indicator = indicator,
            schemeRouter = BitwebcSchemeRouter(),
            sslErrorPolicy = sslErrorPolicy,
            messagePortSetup = messagePortRegistration?.setup,
            messagePortAllowedOrigins = messagePortOrigins,
            eventReporter = reporter,
            resourceInterceptors = resourceInterceptors.toList(),
            errorPolicy = errorPolicy,
            fallbackMainFrameUrl = { pageStateStore.state.value.url ?: initialUrl },
            rendererRetry = { replacement ->
                runCatching {
                    if (session.currentWebViewForRecovery() === replacement) session.reload()
                }
            },
            rendererQuarantine = { failedView ->
                // No WebView API is safe after onRenderProcessGone; state reporting falls back to
                // the renderer-independent snapshot until a replacement commits successfully.
                stateWebView = null
                lifecycleObserver.markWebViewUnusable(failedView)
                session.markWebViewUnusable(failedView)
                BitwebcJsBridge.discardAfterRendererGone(failedView)
            },
            rendererRecoveryFailed = { session.release() },
            rendererRecovery = recovery@{ failedView, _ ->
                val activeWebView = session.currentWebViewForRecovery() ?: return@recovery null
                if (activeWebView !== failedView) return@recovery activeWebView
                runCatching { defaultChromeClient.release() }
                runCatching { permissionMiddleware.cancelPendingRequests() }
                runCatching { fileChooser?.cancelPending() }
                var replacementCandidate: WebView? = null
                val replacement = runCatching {
                    WebView(activity).also { candidate ->
                        replacementCandidate = candidate
                        settings.applyTo(candidate)
                        display.applyTo(candidate)
                    }
                }.getOrElse { error ->
                    replacementCandidate?.let { candidate ->
                        runCatching { BitwebcJsBridge.removeSafely(candidate) }
                        runCatching { (candidate.parent as? ViewGroup)?.removeView(candidate) }
                        runCatching { candidate.destroy() }
                    }
                    Log.e(TAG, "Unable to create a replacement WebView", error)
                    return@recovery null
                }
                val configured = runCatching { installRuntimeBindings(replacement) }.isSuccess
                if (!configured) {
                    runCatching { BitwebcJsBridge.removeSafely(replacement) }
                    runCatching { (replacement.parent as? ViewGroup)?.removeView(replacement) }
                    runCatching { replacement.destroy() }
                    return@recovery null
                }
                val replaced = runCatching {
                    layout.replaceWebView(activity, failedView, replacement, indicatorView)
                    check(session.replaceWebView(failedView, replacement))
                    stateWebView = replacement
                    check(
                        lifecycleObserver.replaceWebView(
                            owner = lifecycleOwner,
                            replacement = replacement,
                            notifyPreviousLifecycle = false
                        )
                    )
                    true
                }.getOrDefault(false)
                if (!replaced) {
                    stateWebView = null
                    session.markWebViewUnusable(replacement)
                    // Recovery mutates the layout, Session and lifecycle owner in order. User
                    // lifecycle hooks may throw after only some of those mutations have landed.
                    // Let the observer destroy the WebView it owns, then dispose the other one
                    // explicitly so neither the dead renderer nor the replacement can leak.
                    val observerOwnsFailedView = lifecycleObserver.ownsWebView(failedView)
                    val observerOwnsReplacement = lifecycleObserver.ownsWebView(replacement)
                    if (!observerOwnsReplacement) {
                        BitwebcJsBridge.removeSafely(replacement)
                        (replacement.parent as? ViewGroup)?.removeView(replacement)
                        runCatching { replacement.destroy() }
                    }
                    if (!observerOwnsFailedView) {
                        failedView.destroyAfterRendererGone()
                    }
                    return@recovery null
                }
                failedView.destroyAfterRendererGone()
                replacement
            },
            next = effectiveNextWebClient
        )
        defaultWebClient.visitedHistoryListener = pageStateStore::onVisitedHistoryChanged
        val backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (session.goBack()) return
                isEnabled = false
                try {
                    activity.onBackPressedDispatcher.onBackPressed()
                } finally {
                    isEnabled = true
                }
            }
        }
        lifecycleObserver = BitwebcLifecycleObserver(
            webView = initialWebView,
            lifeCycle = lifeCycle,
            onReleaseStarted = { session.beginReleaseFromLifecycle() },
            onDestroyed = { cleanupWebView ->
                runCatching { defaultChromeClient.release() }
                releasePermissionBridge()
                runCatching { fileChooser?.release() }
                runCatching { resolvedDownloadHandler?.release() }
                releaseUiResources()
                runCatching { backPressedCallback.remove() }
                if (lifecycleObserver.canUseWebView(cleanupWebView)) {
                    runCatching { BitwebcJsBridge.removeSafely(cleanupWebView) }
                }
                try {
                    runCatching { (root.parent as? ViewGroup)?.removeView(root) }
                } finally {
                    session.finishReleaseFromLifecycle()
                }
            }
        )
        session = BitwebcSession.create(
            id = sessionId,
            webView = initialWebView,
            lifecycleOwner = lifecycleOwner,
            lifecycleObserver = lifecycleObserver,
            backPressedCallback = backPressedCallback,
            eventHub = eventHub,
            pageStateStore = pageStateStore,
            chromeClient = defaultChromeClient
        )
        defaultWebClient.mainFrameNavigationListener = session::confirmMainFrameNavigation
        try {
            if (backPressMode == BackPressMode.Internal) {
                activity.onBackPressedDispatcher.addCallback(lifecycleOwner, backPressedCallback)
            }
            installRuntimeBindings(initialWebView)
            lifeCycle.onAttach(lifecycleOwner, initialWebView)
            check(lifecycleOwner.lifecycle.currentState != androidx.lifecycle.Lifecycle.State.DESTROYED) {
                "LifecycleOwner was destroyed while launching the Bitwebc session"
            }
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                initialWebView.onPause()
            }
            lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
            val restored = restoredNavigationState?.let { savedState ->
                runCatching { initialWebView.restoreState(Bundle(savedState)) != null }
                    .getOrDefault(false)
            } == true
            if (restored) {
                pageStateStore.onWebViewChanged(initialWebView)
            } else {
                initialUrl?.let(session::loadUrl)
            }
            runCatching { resolvedDownloadHandler?.onSessionReady() }
                .onFailure { Log.e(TAG, "Download integration ready callback failed", it) }
        } catch (error: Throwable) {
            session.release()
            throw error
        }
        return session
    }

}

@BitwebcDsl
class NavigationConfig internal constructor() {
    internal var initialUrl: String? = null
        private set
    internal var restoredState: Bundle? = null
        private set

    var backHandling: BackPressMode = BackPressMode.Internal

    /** Atomically sets the fallback URL and optional state produced by BitwebcSession.saveState. */
    fun initial(url: String, restoreFrom: Bundle? = null) {
        require(url.isNotBlank()) { "navigation.initial url must not be blank" }
        initialUrl = url
        restoredState = restoreFrom?.let(::Bundle)
    }

    @JvmSynthetic
    internal fun snapshot(): NavigationSnapshot = NavigationSnapshot(
        initialUrl = initialUrl,
        restoredState = restoredState?.let(::Bundle),
        backHandling = backHandling
    )
}

@BitwebcDsl
class CallbacksConfig internal constructor() {
    internal var lifecycle: WebLifecycle = object : WebLifecycle {}
        private set
    internal val eventListeners = mutableListOf<BitwebcEventListener>()

    fun lifecycle(hooks: WebLifecycle) {
        lifecycle = hooks
    }

    fun onEvent(listener: BitwebcEventListener) {
        eventListeners += listener
    }

    @JvmSynthetic
    internal fun snapshot(): CallbacksSnapshot = CallbacksSnapshot(
        lifecycle = lifecycle,
        eventListeners = eventListeners.toList()
    )
}

internal data class NavigationSnapshot(
    val initialUrl: String?,
    val restoredState: Bundle?,
    val backHandling: BackPressMode
)

internal data class CallbacksSnapshot(
    val lifecycle: WebLifecycle,
    val eventListeners: List<BitwebcEventListener>
)

@BitwebcDsl
class ProgressIndicatorOptions internal constructor() {
    @ColorInt
    var color: Int = 0xFF2F80ED.toInt()
        private set
    var heightDp: Int = 2
        private set

    fun color(@ColorInt colorInt: Int) = apply { color = colorInt }
    fun color(colorString: String) = apply { color = colorString.toColorInt() }
    fun heightDp(dp: Int) = apply { heightDp = dp.coerceAtLeast(1) }
}

@BitwebcDsl
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
