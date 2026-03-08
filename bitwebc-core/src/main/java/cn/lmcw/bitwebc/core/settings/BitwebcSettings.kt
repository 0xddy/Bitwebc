package cn.lmcw.bitwebc.core.settings

import android.content.res.Configuration
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat

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
    /** 缓存模式，见 [WebSettings.LOAD_DEFAULT] / LOAD_CACHE_ELSE_NETWORK / LOAD_NO_CACHE / LOAD_CACHE_ONLY */
    var cacheMode: Int = WebSettings.LOAD_DEFAULT

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
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, allowDark)
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
        // false：新窗口链接（target="_blank" / window.open）在当前 WebView 内打开，避免“同一 WebView 不能作为自己的弹窗”异常
        settings.setSupportMultipleWindows(false)
        val fullUa = customUserAgent?.takeIf { it.isNotBlank() }
        if (fullUa != null) {
            settings.userAgentString = fullUa
        } else if (userAgentSuffix.isNotBlank()) {
            settings.userAgentString = settings.userAgentString + " " + userAgentSuffix
        }
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    }

    fun useCustomUserAgent(fullUserAgent: String) {
        customUserAgent = fullUserAgent
    }

    fun clearCustomUserAgent() {
        customUserAgent = null
    }

    /** 设置缓存模式：LOAD_DEFAULT / LOAD_CACHE_ELSE_NETWORK / LOAD_NO_CACHE / LOAD_CACHE_ONLY */
    fun cacheMode(mode: Int) = apply { cacheMode = mode }
}

class InterceptorsConfig(private val routes: MutableList<Pair<String, String>>) {
    fun assetsRoute(urlPrefix: String, assetsPath: String) {
        routes.add(urlPrefix to assetsPath)
    }
}
