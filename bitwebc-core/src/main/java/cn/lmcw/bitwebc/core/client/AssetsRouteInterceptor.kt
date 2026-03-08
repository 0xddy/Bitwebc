package cn.lmcw.bitwebc.core.client

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * 根据 URL 前缀将请求映射到 assets 目录，用于离线包或静态资源拦截。
 */
class AssetsRouteInterceptor(
    private val context: Context,
    private val routes: List<Pair<String, String>>,
    next: WebViewClient? = null
) : MiddlewareWebClientBase(next) {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val url = request.url?.toString() ?: return nextClient()?.shouldInterceptRequest(view, request)
        for ((urlPrefix, assetsPath) in routes) {
            if (!url.startsWith(urlPrefix)) continue
            val path = toAssetsPath(urlPrefix, assetsPath, url) ?: continue
            runCatching {
                val bytes = context.assets.open(path).use { it.readBytes() }
                val mime = mimeFromPath(path)
                return WebResourceResponse(mime, null, ByteArrayInputStream(bytes))
            }
            break
        }
        return nextClient()?.shouldInterceptRequest(view, request)
    }

    @Deprecated("Deprecated in Java")
    override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
        for ((urlPrefix, assetsPath) in routes) {
            if (!url.startsWith(urlPrefix)) continue
            val path = toAssetsPath(urlPrefix, assetsPath, url) ?: continue
            runCatching {
                val bytes = context.assets.open(path).use { it.readBytes() }
                val mime = mimeFromPath(path)
                return WebResourceResponse(mime, null, ByteArrayInputStream(bytes))
            }
            break
        }
        return nextClient()?.shouldInterceptRequest(view, url)
    }

    private fun toAssetsPath(urlPrefix: String, assetsPath: String, url: String): String? {
        val suffix = url.removePrefix(urlPrefix).trimStart('/')
        return (assetsPath.trimEnd('/') + "/" + suffix).trimStart('/').takeIf { it != "/" }
    }

    private fun mimeFromPath(path: String): String {
        val ext = path.substringAfterLast('.', "")
        return when (ext.lowercase()) {
            "js" -> "application/javascript"
            "css" -> "text/css"
            "html", "htm" -> "text/html"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            else -> "application/octet-stream"
        }
    }
}
