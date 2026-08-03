package cn.lmcw.bitwebc.core.dsl

import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.lifecycle.LifecycleOwner
import androidx.webkit.WebMessagePortCompat
import cn.lmcw.bitwebc.core.api.DownloadHandler
import cn.lmcw.bitwebc.core.api.ErrorPolicy
import cn.lmcw.bitwebc.core.api.FileChooserHandler
import cn.lmcw.bitwebc.core.api.WebIndicator
import cn.lmcw.bitwebc.core.api.WebLayout
import cn.lmcw.bitwebc.core.api.WebDialogProvider
import cn.lmcw.bitwebc.core.api.WebResourceInterceptor
import cn.lmcw.bitwebc.core.bridge.WebOrigin
import cn.lmcw.bitwebc.core.client.ErrorPolicies
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import cn.lmcw.bitwebc.core.settings.DisplayConfig
import cn.lmcw.bitwebc.core.settings.DisplaySnapshot
import cn.lmcw.bitwebc.core.settings.WebSettingsConfig
import cn.lmcw.bitwebc.core.settings.WebSettingsSnapshot
import cn.lmcw.bitwebc.core.ui.CustomErrorWebLayout
import cn.lmcw.bitwebc.core.ui.DefaultWebIndicator
import cn.lmcw.bitwebc.core.ui.DefaultWebLayout
import java.net.URI
import java.util.concurrent.Executor

/** Static configuration shared by the View and Compose entry points. */
@BitwebcDsl
interface BitwebcConfigScope {
    fun webSettings(block: WebSettingsConfig.() -> Unit)
    fun display(block: DisplayConfig.() -> Unit)
    fun resources(block: ResourcesConfig.() -> Unit)
    fun ui(block: UiConfig.() -> Unit)
    fun clients(block: ClientsConfig.() -> Unit)
    fun bridges(block: BridgesConfig.() -> Unit)
    fun webPermissions(block: WebPermissionsConfig.() -> Unit)
    fun integrations(block: IntegrationsConfig.() -> Unit)
}

@BitwebcDsl
class ResourcesConfig internal constructor() {
    private val assetsConfig = AssetResourcesConfig()
    private val interceptors = mutableListOf<WebResourceInterceptor>()

    fun assets(block: AssetResourcesConfig.() -> Unit) {
        assetsConfig.apply(block)
    }

    fun interceptor(interceptor: WebResourceInterceptor) {
        interceptors += interceptor
    }

    @JvmSynthetic
    internal fun snapshot(): ResourcesSnapshot = ResourcesSnapshot(
        assetRoutes = assetsConfig.snapshot(),
        interceptors = interceptors.toList()
    )
}

@BitwebcDsl
class AssetResourcesConfig internal constructor() {
    private val routes = mutableListOf<Pair<String, String>>()

    /** Maps a validated HTTP(S) URL prefix to a directory inside the app's assets. */
    fun route(urlPrefix: String, assetsPath: String) {
        val prefix = runCatching { URI(urlPrefix) }.getOrNull()
        require(
            prefix != null && !prefix.isOpaque &&
                prefix.scheme?.lowercase() in setOf("http", "https") &&
                !prefix.host.isNullOrBlank() && prefix.rawUserInfo == null &&
                prefix.rawQuery == null && prefix.rawFragment == null &&
                prefix.port in -1..65535 && prefix.port != 0
        ) { "assets.route requires a valid HTTP(S) URL prefix without credentials, query, or fragment" }
        require(prefix.path.orEmpty().split('/').none { it == "." || it == ".." || '\\' in it }) {
            "assets.route URL prefix must not contain traversal segments"
        }
        val assetSegments = assetsPath.trim('/').split('/').filter(String::isNotBlank)
        require(assetSegments.none { it == "." || it == ".." || '\\' in it }) {
            "assets.route path must stay inside the assets directory"
        }
        val routeKey = prefix.assetRouteKey()
        require(routes.none { (existing, _) -> URI(existing).assetRouteKey() == routeKey }) {
            "assets.route already contains the same URL prefix"
        }
        routes += urlPrefix to assetsPath
    }

    @JvmSynthetic
    internal fun snapshot(): List<Pair<String, String>> = routes
        .sortedByDescending { (urlPrefix, _) ->
            URI(urlPrefix).path.orEmpty().trimEnd('/').length
        }
}

private fun URI.assetRouteKey(): String {
    val effectivePort = when {
        port >= 0 -> port
        scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }
    val normalizedPath = path.orEmpty().trimEnd('/').ifEmpty { "/" }
    return "${scheme.lowercase()}://${host.lowercase()}:$effectivePort$normalizedPath"
}

@BitwebcDsl
class UiConfig internal constructor() {
    private var layoutFactory: WebLayoutFactory = WebLayoutFactory { DefaultWebLayout() }
    private var indicatorFactory: WebIndicatorFactory = WebIndicatorFactory { DefaultWebIndicator() }
    private var dialogProviderFactory: WebDialogProviderFactory? = null
    private var layoutConfigured = false
    private var indicatorConfigured = false
    private var dialogsConfigured = false

    /** Creates a fresh layout for each Session. */
    fun layout(factory: WebLayoutFactory) {
        check(!layoutConfigured) { "Choose either ui.layout or ui.errorPage once" }
        layoutConfigured = true
        layoutFactory = factory
    }

    fun errorPage(block: ErrorPageOptions.() -> Unit) {
        check(!layoutConfigured) { "Choose either ui.layout or ui.errorPage once" }
        layoutConfigured = true
        val options = ErrorPageOptions().apply(block)
        require(options.layoutRes != 0) { "ui.errorPage requires a non-zero layout resource" }
        layoutFactory = WebLayoutFactory {
            CustomErrorWebLayout(
                layoutRes = options.layoutRes,
                retryViewId = options.retryViewId,
                errorMessageViewId = options.errorMessageViewId
            )
        }
    }

    fun indicator(block: ProgressIndicatorOptions.() -> Unit) {
        check(!indicatorConfigured) {
            "Choose either ui.indicator or ui.customIndicator once"
        }
        indicatorConfigured = true
        val options = ProgressIndicatorOptions().apply(block)
        indicatorFactory = WebIndicatorFactory {
            DefaultWebIndicator(options.heightDp, options.color)
        }
    }

    /** Creates a fresh custom indicator for each Session. */
    fun customIndicator(factory: WebIndicatorFactory) {
        check(!indicatorConfigured) {
            "Choose either ui.indicator or ui.customIndicator once"
        }
        indicatorConfigured = true
        indicatorFactory = factory
    }

    /** Creates a fresh JavaScript dialog provider for each Session. */
    fun dialogs(factory: WebDialogProviderFactory) {
        check(!dialogsConfigured) { "ui.dialogs can only be configured once" }
        dialogsConfigured = true
        dialogProviderFactory = factory
    }

    @JvmSynthetic
    internal fun snapshot(): UiSnapshot = UiSnapshot(
        layoutFactory = layoutFactory,
        indicatorFactory = indicatorFactory,
        dialogProviderFactory = dialogProviderFactory
    )
}

fun interface WebLayoutFactory {
    fun create(activity: ComponentActivity): WebLayout
}

fun interface WebIndicatorFactory {
    fun create(activity: ComponentActivity): WebIndicator
}

fun interface WebDialogProviderFactory {
    fun create(activity: ComponentActivity): WebDialogProvider
}

@BitwebcDsl
class ClientsConfig internal constructor() {
    private var webViewClientFactory: WebViewClientFactory? = null
    private var webChromeClientFactory: WebChromeClientFactory? = null
    private var errorPolicy: ErrorPolicy = ErrorPolicies.standard
    private var sslErrorHandler: ((Uri, android.net.http.SslError) -> Boolean)? = null

    /** Creates a fresh downstream WebViewClient for each Session. */
    fun webViewClient(factory: WebViewClientFactory) {
        webViewClientFactory = factory
    }

    /** Creates a fresh downstream WebChromeClient for each Session. */
    fun webChromeClient(factory: WebChromeClientFactory) {
        webChromeClientFactory = factory
    }

    fun errorPolicy(policy: ErrorPolicy) {
        errorPolicy = policy
    }

    /** Return true only when the host intentionally accepts this invalid certificate. */
    fun onSslError(handler: (url: Uri, error: android.net.http.SslError) -> Boolean) {
        sslErrorHandler = handler
    }

    @JvmSynthetic
    internal fun snapshot(): ClientsSnapshot = ClientsSnapshot(
        webViewClientFactory = webViewClientFactory,
        webChromeClientFactory = webChromeClientFactory,
        errorPolicy = errorPolicy,
        sslErrorHandler = sslErrorHandler
    )
}

fun interface WebViewClientFactory {
    fun create(activity: ComponentActivity): WebViewClient
}

fun interface WebChromeClientFactory {
    fun create(activity: ComponentActivity): WebChromeClient
}

@BitwebcDsl
class BridgesConfig internal constructor() {
    private val javascriptBridges = mutableListOf<JsBridgeRegistration>()
    private var executor: Executor? = null
    private var messagePorts: MessagePortRegistration? = null

    /** Registers a bridge for an explicit set of exact trusted HTTP(S) origins. */
    fun javascript(name: String, bridge: Any, allowedOrigins: Set<String>) {
        require(name.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*$"))) {
            "Invalid JavaScript bridge name: $name"
        }
        val origins = normalizeOriginSet(allowedOrigins)
        javascriptBridges.removeAll { it.name == name }
        javascriptBridges += JsBridgeRegistration(name, bridge, origins)
    }

    /** Overrides the default per-bridge serial background executor. */
    fun executor(executor: Executor) {
        this.executor = executor
    }

    fun messagePorts(
        allowedOrigins: Set<String>,
        block: (WebView, WebMessagePortCompat, WebMessagePortCompat) -> Unit
    ) {
        messagePorts = MessagePortRegistration(normalizeOriginSet(allowedOrigins), block)
    }

    @JvmSynthetic
    internal fun snapshot(): BridgesSnapshot = BridgesSnapshot(
        javascriptBridges = javascriptBridges.toList(),
        executor = executor,
        messagePorts = messagePorts
    )
}

@BitwebcDsl
class WebPermissionsConfig internal constructor() {
    private val allowedOrigins = linkedSetOf<String>()

    /** Allows geolocation/WebRTC prompts for exact trusted HTTP(S) origins. Default is deny-all. */
    fun allowFrom(vararg origins: String) {
        allowedOrigins += normalizeOriginSet(origins.toSet())
    }

    @JvmSynthetic
    internal fun snapshot(): Set<String> = allowedOrigins.toSet()
}

class FileChooserFactoryContext internal constructor(
    val activity: ComponentActivity,
    val lifecycleOwner: LifecycleOwner,
    val activityResultKey: String,
    val reportEvent: (BitwebcEvent) -> Unit
)

fun interface FileChooserFactory {
    fun create(context: FileChooserFactoryContext): FileChooserHandler
}

class DownloadFactoryContext internal constructor(
    val activity: ComponentActivity,
    val reportEvent: (BitwebcEvent) -> Unit
)

fun interface DownloadFactory {
    fun create(context: DownloadFactoryContext): DownloadHandler
}

@BitwebcDsl
class IntegrationsConfig internal constructor() {
    private var fileChooserFactory: FileChooserFactory? = null
    private var downloadFactory: DownloadFactory? = null

    fun fileChooser(factory: FileChooserFactory) {
        fileChooserFactory = factory
    }

    fun downloads(factory: DownloadFactory) {
        downloadFactory = factory
    }

    @JvmSynthetic
    internal fun snapshot(): IntegrationsSnapshot = IntegrationsSnapshot(
        fileChooserFactory = fileChooserFactory,
        downloadFactory = downloadFactory
    )
}

private fun normalizeOriginSet(origins: Set<String>): Set<String> {
    require(origins.isNotEmpty()) { "At least one trusted origin is required" }
    return origins.map { origin ->
        requireNotNull(WebOrigin.normalizeRule(origin)) {
            "Invalid HTTP(S) origin '$origin'; provide only scheme, host, and optional port"
        }
    }.toSet()
}

internal data class JsBridgeRegistration(
    val name: String,
    val bridge: Any,
    val allowedOrigins: Set<String>
)

internal data class MessagePortRegistration(
    val allowedOrigins: Set<String>,
    val setup: (WebView, WebMessagePortCompat, WebMessagePortCompat) -> Unit
)

internal data class ResourcesSnapshot(
    val assetRoutes: List<Pair<String, String>>,
    val interceptors: List<WebResourceInterceptor>
)

internal data class UiSnapshot(
    val layoutFactory: WebLayoutFactory,
    val indicatorFactory: WebIndicatorFactory,
    val dialogProviderFactory: WebDialogProviderFactory?
)

internal data class ClientsSnapshot(
    val webViewClientFactory: WebViewClientFactory?,
    val webChromeClientFactory: WebChromeClientFactory?,
    val errorPolicy: ErrorPolicy,
    val sslErrorHandler: ((Uri, android.net.http.SslError) -> Boolean)?
)

internal data class BridgesSnapshot(
    val javascriptBridges: List<JsBridgeRegistration>,
    val executor: Executor?,
    val messagePorts: MessagePortRegistration?
)

internal data class IntegrationsSnapshot(
    val fileChooserFactory: FileChooserFactory?,
    val downloadFactory: DownloadFactory?
)

internal data class FrozenBitwebcConfig(
    val webSettings: WebSettingsSnapshot,
    val display: DisplaySnapshot,
    val resources: ResourcesSnapshot,
    val ui: UiSnapshot,
    val clients: ClientsSnapshot,
    val bridges: BridgesSnapshot,
    val permissions: Set<String>,
    val integrations: IntegrationsSnapshot
)
