package cn.lmcw.bitwebc.core.extensions

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.webkit.URLUtil
import android.webkit.WebView
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal fun WebView.handleIntentScheme(raw: String): Boolean {
    val intent = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        component = null
        selector = null
    }
    val hostActivity = context.findActivity()
    val launchContext = hostActivity ?: context
    if (hostActivity == null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        launchContext.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        val fallback = intent.getStringExtra("browser_fallback_url")
        if (!fallback.isNullOrBlank() && URLUtil.isNetworkUrl(fallback)) {
            loadUrl(fallback)
            true
        } else {
            false
        }
    }
}

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    val visited = mutableSetOf<Context>()
    while (current != null && visited.add(current)) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

internal fun String.extractBrowserFallback(): String? {
    val encoded = substringAfter("browser_fallback_url=", "").substringBefore(";end")
    if (encoded.isBlank()) return null
    return runCatching {
        URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
    }.getOrDefault(encoded)
}
