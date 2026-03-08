package cn.lmcw.bitwebc.core.bridge

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * **可选** WebMessagePort 双向通信封装，与 [jsBridge]/[@JavascriptInterface] 并存。
 * 默认推荐使用 [jsBridge]：前端 `window[name].method()` 即可，无需改前端交互。
 * 本类仅在宿主导航栏配置 [messagePorts] 时启用；前端需监听 `message` 事件并处理 port，改动较大，仅适合大 payload、复杂双向等进阶场景。
 *
 * 在 [onPageFinished] 后通过 [WebViewCompat] 创建 channel，经 [WebViewFeature] 检测内核能力后安全建立通道。
 */
object BitwebcWebMessagePort {

    /**
     * 在 WebView 内核支持时创建 Message Channel 并调用 [setup]。
     * 使用 [WebViewCompat] + [WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL] 检测，兼容 Android 7 及国产老旧/魔改 ROM。
     * [receivePort]：Native 端设置 [WebMessagePortCompat.setWebMessageCallback] 接收前端消息。
     * [sendToJsPort]：已通过 [WebViewCompat.postWebMessage] 传给当前页，前端通过 message 事件 e.ports[0] 获取；Native 也可持有并 [WebMessagePortCompat.postMessage] 发往前端。
     */
    @JvmStatic
    fun setupOnPageFinished(
        webView: WebView,
        pageUrl: String?,
        setup: (receivePort: WebMessagePortCompat, sendToJsPort: WebMessagePortCompat) -> Unit
    ) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL)) return
        val ports = WebViewCompat.createWebMessageChannel(webView) ?: return
        if (ports.size < 2) return
        val receivePort = ports[0]
        val sendToJsPort = ports[1]
        setup(receivePort, sendToJsPort)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE)) return
        val origin = pageUrl?.let { toOrigin(it) } ?: Uri.parse("https://localhost")
        WebViewCompat.postWebMessage(webView, WebMessageCompat(null, arrayOf(sendToJsPort)), origin)
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
