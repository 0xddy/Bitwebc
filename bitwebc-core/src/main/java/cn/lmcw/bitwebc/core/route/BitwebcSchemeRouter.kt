package cn.lmcw.bitwebc.core.route

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import cn.lmcw.bitwebc.core.extensions.handleIntentScheme

/** Scheme 路由：http(s) 放行，其它 scheme 用 Intent 打开 */
class BitwebcSchemeRouter {

    fun handle(webView: WebView, uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "http" || scheme == "https" || scheme == "about") {
            return false
        }

        return runCatching {
            when {
                scheme == "intent" -> webView.handleIntentScheme(uri.toString())
                else -> {
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    if (webView.context !is Activity) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    webView.context.startActivity(intent)
                    true
                }
            }
        }.getOrDefault(false)
    }

}
