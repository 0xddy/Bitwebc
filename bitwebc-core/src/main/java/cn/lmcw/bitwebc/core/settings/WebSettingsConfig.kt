package cn.lmcw.bitwebc.core.settings

import android.content.res.Configuration
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import cn.lmcw.bitwebc.core.dsl.BitwebcDsl

enum class DarkMode {
    Off,
    On,
    Auto
}

/** Controls WebView's resource-loading cache policy. This does not clear shared WebView data. */
enum class WebResourceCachePolicy {
    Default,

    /** Uses a cached response when available, even when it may be stale, then falls back to network. */
    CacheFirst,

    NetworkOnly,
    CacheOnly
}

/** Type-safe mixed-content policy. Blocking mixed content is the secure default. */
enum class MixedContentPolicy {
    Block,
    Compatibility,
    Allow
}

/** Platform WebSettings only. View presentation and request routing live in separate scopes. */
@BitwebcDsl
class WebSettingsConfig internal constructor() {
    internal val scriptingConfig = ScriptingConfig()
    internal val storageConfig = StorageConfig()
    internal val cacheConfig = CacheConfig()
    internal val securityConfig = SecurityConfig()
    internal val mediaConfig = MediaConfig()
    internal val viewportConfig = ViewportConfig()
    internal val userAgentConfig = UserAgentConfig()

    fun scripting(block: ScriptingConfig.() -> Unit) {
        scriptingConfig.apply(block)
    }

    fun storage(block: StorageConfig.() -> Unit) {
        storageConfig.apply(block)
    }

    fun cache(block: CacheConfig.() -> Unit) {
        cacheConfig.apply(block)
    }

    fun security(block: SecurityConfig.() -> Unit) {
        securityConfig.apply(block)
    }

    fun media(block: MediaConfig.() -> Unit) {
        mediaConfig.apply(block)
    }

    fun viewport(block: ViewportConfig.() -> Unit) {
        viewportConfig.apply(block)
    }

    fun userAgent(block: UserAgentConfig.() -> Unit) {
        userAgentConfig.apply(block)
    }

    @JvmSynthetic
    internal fun snapshot(): WebSettingsSnapshot = WebSettingsSnapshot(
        javaScriptEnabled = scriptingConfig.enabled,
        javaScriptCanOpenWindowsAutomatically = scriptingConfig.canOpenWindows,
        domStorageEnabled = storageConfig.domEnabled,
        cachePolicy = cacheConfig.policy,
        fileAccessEnabled = securityConfig.fileAccessEnabled,
        contentAccessEnabled = securityConfig.contentAccessEnabled,
        mixedContentPolicy = securityConfig.mixedContent,
        mediaPlaybackRequiresUserGesture = mediaConfig.playbackRequiresUserGesture,
        loadWithOverviewMode = viewportConfig.overviewMode,
        useWideViewPort = viewportConfig.wide,
        zoomEnabled = viewportConfig.zoomConfig.enabled,
        builtInZoomControls = viewportConfig.zoomConfig.builtInControls,
        zoomControlsVisible = viewportConfig.zoomConfig.controlsVisible,
        userAgentSuffix = userAgentConfig.suffix,
        fullUserAgent = userAgentConfig.full
    )
}

@BitwebcDsl
class ScriptingConfig internal constructor() {
    var enabled: Boolean = true
    var canOpenWindows: Boolean = false
}

@BitwebcDsl
class StorageConfig internal constructor() {
    var domEnabled: Boolean = true
}

@BitwebcDsl
class CacheConfig internal constructor() {
    var policy: WebResourceCachePolicy = WebResourceCachePolicy.Default
}

@BitwebcDsl
class SecurityConfig internal constructor() {
    var fileAccessEnabled: Boolean = false
    var contentAccessEnabled: Boolean = false
    var mixedContent: MixedContentPolicy = MixedContentPolicy.Block
}

@BitwebcDsl
class MediaConfig internal constructor() {
    var playbackRequiresUserGesture: Boolean = true
}

@BitwebcDsl
class ViewportConfig internal constructor() {
    var overviewMode: Boolean = true
    var wide: Boolean = true
    internal val zoomConfig = ZoomConfig()

    fun zoom(block: ZoomConfig.() -> Unit) {
        zoomConfig.apply(block)
    }
}

@BitwebcDsl
class ZoomConfig internal constructor() {
    var enabled: Boolean = false
    var builtInControls: Boolean = false
    var controlsVisible: Boolean = false
}

@BitwebcDsl
class UserAgentConfig internal constructor() {
    internal var suffix: String = ""
        private set
    internal var full: String? = null
        private set

    /** Appends one token to the platform user agent. */
    fun append(value: String) {
        suffix = value.trim()
        require(suffix.isNotEmpty()) { "User-agent suffix must not be blank" }
        full = null
    }

    /** Replaces the complete user agent instead of appending to the platform value. */
    fun replaceWith(value: String) {
        full = value.trim().also {
            require(it.isNotEmpty()) { "Full user agent must not be blank" }
        }
        suffix = ""
    }

    /** Restores the unmodified platform user agent. */
    fun systemDefault() {
        suffix = ""
        full = null
    }
}

/** Presentation and native interaction options that are not Android WebSettings. */
@BitwebcDsl
class DisplayConfig internal constructor() {
    var darkMode: DarkMode = DarkMode.Auto
    var scrollBarsEnabled: Boolean = true
    var longPressSelectionEnabled: Boolean = true

    @JvmSynthetic
    internal fun snapshot(): DisplaySnapshot = DisplaySnapshot(
        darkMode = darkMode,
        scrollBarsEnabled = scrollBarsEnabled,
        longPressSelectionEnabled = longPressSelectionEnabled
    )
}

internal data class WebSettingsSnapshot(
    val javaScriptEnabled: Boolean,
    val javaScriptCanOpenWindowsAutomatically: Boolean,
    val domStorageEnabled: Boolean,
    val cachePolicy: WebResourceCachePolicy,
    val fileAccessEnabled: Boolean,
    val contentAccessEnabled: Boolean,
    val mixedContentPolicy: MixedContentPolicy,
    val mediaPlaybackRequiresUserGesture: Boolean,
    val loadWithOverviewMode: Boolean,
    val useWideViewPort: Boolean,
    val zoomEnabled: Boolean,
    val builtInZoomControls: Boolean,
    val zoomControlsVisible: Boolean,
    val userAgentSuffix: String,
    val fullUserAgent: String?
) {
    @Suppress("DEPRECATION")
    fun applyTo(webView: WebView) {
        val settings = webView.settings
        settings.javaScriptEnabled = javaScriptEnabled
        settings.javaScriptCanOpenWindowsAutomatically = javaScriptCanOpenWindowsAutomatically
        settings.domStorageEnabled = domStorageEnabled
        settings.cacheMode = when (cachePolicy) {
            WebResourceCachePolicy.Default -> WebSettings.LOAD_DEFAULT
            WebResourceCachePolicy.CacheFirst -> WebSettings.LOAD_CACHE_ELSE_NETWORK
            WebResourceCachePolicy.NetworkOnly -> WebSettings.LOAD_NO_CACHE
            WebResourceCachePolicy.CacheOnly -> WebSettings.LOAD_CACHE_ONLY
        }
        settings.allowFileAccess = fileAccessEnabled
        settings.allowContentAccess = contentAccessEnabled
        settings.mixedContentMode = when (mixedContentPolicy) {
            MixedContentPolicy.Block -> WebSettings.MIXED_CONTENT_NEVER_ALLOW
            MixedContentPolicy.Compatibility -> WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            MixedContentPolicy.Allow -> WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        settings.mediaPlaybackRequiresUserGesture = mediaPlaybackRequiresUserGesture
        settings.loadWithOverviewMode = loadWithOverviewMode
        settings.useWideViewPort = useWideViewPort
        settings.setSupportZoom(zoomEnabled)
        settings.builtInZoomControls = builtInZoomControls
        settings.displayZoomControls = zoomControlsVisible
        settings.setSupportMultipleWindows(false)

        val systemUserAgent = runCatching {
            WebSettings.getDefaultUserAgent(webView.context)
        }.getOrNull()
        settings.userAgentString = resolveUserAgent(
            systemUserAgent = systemUserAgent,
            currentUserAgent = settings.userAgentString.orEmpty(),
            suffix = userAgentSuffix,
            fullUserAgent = fullUserAgent
        )
    }
}

internal fun resolveUserAgent(
    systemUserAgent: String?,
    currentUserAgent: String,
    suffix: String,
    fullUserAgent: String?
): String {
    if (fullUserAgent != null) return fullUserAgent
    val suffixToken = suffix.takeIf(String::isNotEmpty)?.let { " $it" }.orEmpty()
    val fallbackBase = if (suffixToken.isNotEmpty() && currentUserAgent.endsWith(suffixToken)) {
        currentUserAgent.dropLast(suffixToken.length)
    } else {
        currentUserAgent
    }
    return systemUserAgent.orEmpty().ifEmpty { fallbackBase } + suffixToken
}

internal data class DisplaySnapshot(
    val darkMode: DarkMode,
    val scrollBarsEnabled: Boolean,
    val longPressSelectionEnabled: Boolean
) {
    fun applyTo(webView: WebView) {
        val allowDark = when (darkMode) {
            DarkMode.On -> true
            DarkMode.Off -> false
            DarkMode.Auto ->
                (webView.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, allowDark)
        }

        webView.isVerticalScrollBarEnabled = scrollBarsEnabled
        webView.isHorizontalScrollBarEnabled = scrollBarsEnabled
        webView.overScrollMode = if (scrollBarsEnabled) {
            WebView.OVER_SCROLL_IF_CONTENT_SCROLLS
        } else {
            WebView.OVER_SCROLL_NEVER
        }
        webView.isLongClickable = longPressSelectionEnabled
        if (longPressSelectionEnabled) {
            webView.setOnLongClickListener(null)
        } else {
            webView.setOnLongClickListener { true }
            webView.setOnCreateContextMenuListener(null)
        }
    }
}
