package cn.lmcw.bitwebc.core.client

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import cn.lmcw.bitwebc.core.extensions.mimeFromPath
import cn.lmcw.bitwebc.core.extensions.toAssetsPath
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
            if (!url.startsWith(urlPrefix)) continue
            val path = url.toAssetsPath(urlPrefix, assetsPath) ?: continue
            runCatching {
                val inputStream = context.assets.open(path)
                val mime = path.mimeFromPath()
                return WebResourceResponse(mime, null, inputStream)
            }
            break
        }
        return nextClient()?.shouldInterceptRequest(view, request)
    }
}
