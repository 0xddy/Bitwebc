package cn.lmcw.bitwebc.core.settings

import android.content.res.Configuration
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

enum class DarkMode {
    OFF,
    ON,
    AUTO
}

class BitwebcSettings {
    var javaScriptEnabled: Boolean = true
    var domStorageEnabled: Boolean = true
    var databaseEnabled: Boolean = true
    var supportZoom: Boolean = false
    var builtInZoomControls: Boolean = false
    var displayZoomControls: Boolean = false
    var allowFileAccess: Boolean = false
    var loadWithOverviewMode: Boolean = true
    var useWideViewPort: Boolean = true
    var userAgentSuffix: String = "Bitwebc/1.0"
    var customUserAgent: String? = null
    var darkMode: DarkMode = DarkMode.AUTO
    var cacheMode: Int = WebSettings.LOAD_DEFAULT
    var disableScrollBars: Boolean = false
    var disableLongPressSelection: Boolean = false

    val assetRoutes = mutableListOf<Pair<String, String>>()

    fun interceptors(block: InterceptorsConfig.() -> Unit) {
        block(InterceptorsConfig(assetRoutes))
    }

    fun applyTo(webView: WebView) {
        val settings = webView.settings
        val allowDark = when (darkMode) {
            DarkMode.ON -> true
            DarkMode.OFF -> false
            DarkMode.AUTO -> (webView.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, allowDark)
        }
        settings.javaScriptEnabled = javaScriptEnabled
        settings.domStorageEnabled = domStorageEnabled
        settings.databaseEnabled = databaseEnabled
        settings.setSupportZoom(supportZoom)
        settings.builtInZoomControls = builtInZoomControls
        settings.displayZoomControls = displayZoomControls
        settings.allowFileAccess = allowFileAccess
        settings.loadWithOverviewMode = loadWithOverviewMode
        settings.useWideViewPort = useWideViewPort
        settings.cacheMode = cacheMode
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportMultipleWindows(false)
        val fullUa = customUserAgent?.takeIf { it.isNotBlank() }
        if (fullUa != null) {
            settings.userAgentString = fullUa
        } else if (userAgentSuffix.isNotBlank()) {
            settings.userAgentString = settings.userAgentString + " " + userAgentSuffix
        }
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        if (disableScrollBars) {
            webView.isVerticalScrollBarEnabled = false
            webView.isHorizontalScrollBarEnabled = false
            webView.overScrollMode = WebView.OVER_SCROLL_NEVER
        }
        if (disableLongPressSelection) {
            webView.isLongClickable = false
            webView.setOnLongClickListener { true }
            webView.setOnCreateContextMenuListener(null)
        }
    }

    fun useCustomUserAgent(fullUserAgent: String) {
        customUserAgent = fullUserAgent
    }

    fun clearCustomUserAgent() {
        customUserAgent = null
    }

    fun cacheMode(mode: Int) = apply { cacheMode = mode }
    fun disableScrollBars(disable: Boolean = true) = apply { disableScrollBars = disable }
    fun disableLongPressSelection(disable: Boolean = true) = apply { disableLongPressSelection = disable }
}

class InterceptorsConfig(private val routes: MutableList<Pair<String, String>>) {
    fun assetsRoute(urlPrefix: String, assetsPath: String) {
        routes.add(urlPrefix to assetsPath)
    }
}
