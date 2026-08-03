package cn.lmcw.bitwebc.core.client

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import cn.lmcw.bitwebc.core.extensions.mimeFromPath
import cn.lmcw.bitwebc.core.extensions.isWithinAssetsRoute
import cn.lmcw.bitwebc.core.extensions.toAssetsPath
import java.io.ByteArrayInputStream
/** URL 前缀映射到 assets，用于离线包/静态资源 */
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
            if (!url.isWithinAssetsRoute(urlPrefix)) continue
            val path = url.toAssetsPath(urlPrefix, assetsPath)
                ?: return localErrorResponse(400, "Invalid local asset path")
            return runCatching {
                val inputStream = context.assets.open(path)
                val mime = path.mimeFromPath()
                val encoding = if (mime.startsWith("text/") || mime in UTF8_MIME_TYPES) "UTF-8" else null
                WebResourceResponse(mime, encoding, inputStream)
            }.getOrElse {
                // A URL inside a local route must never fall through to the network.
                localErrorResponse(404, "Local asset not found")
            }
        }
        return nextClient()?.shouldInterceptRequest(view, request)
    }

    private fun localErrorResponse(statusCode: Int, reason: String): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            statusCode,
            reason,
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(reason.toByteArray(Charsets.UTF_8))
        )

    private companion object {
        val UTF8_MIME_TYPES = setOf(
            "application/javascript",
            "application/json",
            "application/xml",
            "image/svg+xml"
        )
    }
}
