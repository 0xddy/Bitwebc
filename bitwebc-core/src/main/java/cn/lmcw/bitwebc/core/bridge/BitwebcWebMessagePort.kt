package cn.lmcw.bitwebc.core.bridge

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** WebMessagePort 封装，用于 postMessage / channel 通信 */
object BitwebcWebMessagePort {

    /**
     * 内核支持时创建 Message Channel 并调用 setup。
     *
     * @param setup 回调参数：
     *   - `nativePort`（ports[0]）：Native 端持有，用于收发消息
     *   - `jsPort`（ports[1]）：将被传给 JS 端，Native 端不应再直接使用
     */
    @JvmStatic
    fun setupOnPageFinished(
        webView: WebView,
        pageUrl: String?,
        setup: (nativePort: WebMessagePortCompat, jsPort: WebMessagePortCompat) -> Unit
    ) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL)) return
        val ports = WebViewCompat.createWebMessageChannel(webView) ?: return
        if (ports.size < 2) return
        val nativePort = ports[0]
        val jsPort = ports[1]
        setup(nativePort, jsPort)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE)) return
        val origin = pageUrl?.let { toOrigin(it) } ?: Uri.parse("https://localhost")
        WebViewCompat.postWebMessage(webView, WebMessageCompat(null, arrayOf(jsPort)), origin)
    }

    private fun toOrigin(url: String): Uri {
        return runCatching {
            val u = Uri.parse(url)
            Uri.Builder()
                .scheme(u.scheme ?: "https")
                .authority(u.host ?: "localhost")
                .path("")
                .build()
        }.getOrElse { Uri.parse("https://localhost") }
    }
}
