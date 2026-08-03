package cn.lmcw.bitwebc.core.bridge

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.net.URI
import java.util.Locale

/** WebMessagePort wrapper for postMessage/channel communication. */
object BitwebcWebMessagePort {

    /**
     * Creates and transfers a message channel when the page has a valid HTTP(S) origin.
     *
     * [setup] receives the native port followed by the port that will be transferred to JS.
     * No channel is created for opaque, malformed, or unsupported page URLs.
     */
    @JvmStatic
    @SuppressLint("RequiresFeature")
    fun setupOnPageFinished(
        webView: WebView,
        pageUrl: String?,
        setup: (nativePort: WebMessagePortCompat, jsPort: WebMessagePortCompat) -> Unit
    ) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE)
        ) {
            return
        }

        val targetOrigin = webMessageTargetOrigin(pageUrl)?.let(Uri::parse) ?: return
        val ports = runCatching { WebViewCompat.createWebMessageChannel(webView) }.getOrNull()
            ?: return
        if (ports.size < 2) {
            ports.forEach { it.closePortQuietly() }
            return
        }

        val nativePort = ports[0]
        val jsPort = ports[1]
        try {
            setup(nativePort, jsPort)
            WebViewCompat.postWebMessage(
                webView,
                WebMessageCompat(null, arrayOf(jsPort)),
                targetOrigin
            )
        } catch (_: Exception) {
            nativePort.closePortQuietly()
            jsPort.closePortQuietly()
        }
    }

    private fun WebMessagePortCompat.closePortQuietly() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_CLOSE)) {
            runCatching { close() }
        }
    }
}

internal fun webMessageTargetOrigin(pageUrl: String?): String? {
    if (pageUrl.isNullOrBlank()) return null
    val uri = runCatching { URI(pageUrl) }.getOrNull() ?: return null
    if (uri.isOpaque) return null

    val scheme = uri.scheme?.lowercase(Locale.ROOT)
        ?.takeIf { it == "http" || it == "https" }
        ?: return null
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    val port = uri.port
    if (port !in -1..65535) return null

    val originHost = when {
        host.startsWith("[") && host.endsWith("]") -> host
        ':' in host -> "[$host]"
        else -> host.lowercase(Locale.ROOT)
    }
    return buildString {
        append(scheme)
        append("://")
        append(originHost)
        if (port >= 0) {
            append(':')
            append(port)
        }
    }
}
