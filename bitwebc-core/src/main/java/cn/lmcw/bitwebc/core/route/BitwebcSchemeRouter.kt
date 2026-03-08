package cn.lmcw.bitwebc.core.route

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.URLUtil
import android.webkit.WebView
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 统一 Scheme 路由：内部 http(s) 放行，外部 scheme 尝试 Intent 打开并提供降级。
 */
class BitwebcSchemeRouter {

    fun handle(webView: WebView, uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "http" || scheme == "https" || scheme == "about") {
            return false
        }

        return runCatching {
            when {
                scheme == "intent" -> handleIntentScheme(webView, uri.toString())
                else -> {
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    webView.context.startActivity(intent)
                    true
                }
            }
        }.recoverCatching {
            if (scheme == "intent") {
                val fallbackUrl = extractBrowserFallback(uri.toString())
                if (!fallbackUrl.isNullOrBlank() && URLUtil.isNetworkUrl(fallbackUrl)) {
                    webView.loadUrl(fallbackUrl)
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }.getOrDefault(false)
    }

    private fun handleIntentScheme(webView: WebView, raw: String): Boolean {
        val intent = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME)
        return try {
            webView.context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (!fallback.isNullOrBlank() && URLUtil.isNetworkUrl(fallback)) {
                webView.loadUrl(fallback)
                true
            } else {
                false
            }
        }
    }

    private fun extractBrowserFallback(raw: String): String? {
        val encoded = raw.substringAfter("browser_fallback_url=", "").substringBefore(";end")
        if (encoded.isBlank()) return null
        return runCatching {
            URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
        }.getOrDefault(encoded)
    }
}
