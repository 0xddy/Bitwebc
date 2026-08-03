package cn.lmcw.bitwebc.core.route

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import cn.lmcw.bitwebc.core.extensions.handleIntentScheme
import cn.lmcw.bitwebc.core.extensions.findActivity

/** Scheme 路由：http(s) 放行，其它 scheme 用 Intent 打开 */
class BitwebcSchemeRouter {

    enum class Result(val consumesNavigation: Boolean) {
        /** Let WebView or the next WebViewClient handle the URL. */
        PASS_THROUGH(false),

        /** An external activity was opened or an intent fallback URL was loaded. */
        HANDLED(true),

        /** The custom scheme could not be opened and is deliberately consumed. */
        CONSUMED(true)
    }

    fun handle(webView: WebView, uri: Uri): Boolean {
        return route(webView, uri).consumesNavigation
    }

    fun route(webView: WebView, uri: Uri): Result {
        val scheme = uri.scheme?.lowercase() ?: return Result.PASS_THROUGH
        if (scheme == "http" || scheme == "https" || scheme == "about") {
            return Result.PASS_THROUGH
        }

        return runCatching {
            when {
                scheme == "intent" -> {
                    if (webView.handleIntentScheme(uri.toString())) {
                        Result.HANDLED
                    } else {
                        Result.CONSUMED
                    }
                }
                else -> {
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE)
                        component = null
                        selector = null
                    }
                    val hostActivity = webView.context.findActivity()
                    val launchContext = hostActivity ?: webView.context
                    if (hostActivity == null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    launchContext.startActivity(intent)
                    Result.HANDLED
                }
            }
        }.getOrDefault(Result.CONSUMED)
    }

}
