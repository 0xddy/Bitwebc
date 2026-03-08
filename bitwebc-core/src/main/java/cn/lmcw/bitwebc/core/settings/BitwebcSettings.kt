package cn.lmcw.bitwebc.core.settings

import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView

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

    fun applyTo(webView: WebView) {
        val settings = webView.settings
        settings.javaScriptEnabled = javaScriptEnabled
        settings.domStorageEnabled = domStorageEnabled
        settings.databaseEnabled = databaseEnabled
        settings.setSupportZoom(supportZoom)
        settings.builtInZoomControls = builtInZoomControls
        settings.displayZoomControls = displayZoomControls
        settings.allowFileAccess = allowFileAccess
        settings.loadWithOverviewMode = loadWithOverviewMode
        settings.useWideViewPort = useWideViewPort
        settings.cacheMode = WebSettings.LOAD_DEFAULT
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
}
