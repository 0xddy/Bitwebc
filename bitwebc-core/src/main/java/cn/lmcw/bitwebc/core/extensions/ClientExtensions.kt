package cn.lmcw.bitwebc.core.extensions

import android.webkit.MimeTypeMap
import java.net.URI
import java.util.Locale

internal fun String.toAssetsPath(urlPrefix: String, assetsPath: String): String? {
    val request = runCatching { URI(this) }.getOrNull() ?: return null
    val prefix = runCatching { URI(urlPrefix) }.getOrNull() ?: return null
    if (request.isOpaque || prefix.isOpaque) return null
    if (request.scheme?.lowercase(Locale.ROOT) != prefix.scheme?.lowercase(Locale.ROOT)) return null
    if (!request.host.equals(prefix.host, ignoreCase = true)) return null
    if (effectivePort(request) != effectivePort(prefix)) return null
    if (prefix.rawQuery != null || prefix.rawFragment != null || prefix.rawUserInfo != null) return null

    val requestPath = request.path ?: return null
    val prefixPath = prefix.path.orEmpty().trimEnd('/')
    if (requestPath != prefixPath && !requestPath.startsWith("$prefixPath/")) return null
    val suffix = requestPath.removePrefix(prefixPath).trimStart('/')
    if (suffix.isBlank()) return null

    val baseSegments = assetsPath.trim('/').split('/').filter(String::isNotBlank)
    val suffixSegments = suffix.split('/').filter(String::isNotBlank)
    val allSegments = baseSegments + suffixSegments
    if (allSegments.isEmpty() || allSegments.any { it == "." || it == ".." || '\\' in it }) return null
    return allSegments.joinToString("/")
}

internal fun String.isWithinAssetsRoute(urlPrefix: String): Boolean {
    val request = runCatching { URI(this) }.getOrNull() ?: return false
    val prefix = runCatching { URI(urlPrefix) }.getOrNull() ?: return false
    if (request.isOpaque || prefix.isOpaque) return false
    if (request.scheme?.lowercase(Locale.ROOT) != prefix.scheme?.lowercase(Locale.ROOT)) return false
    if (!request.host.equals(prefix.host, ignoreCase = true)) return false
    if (effectivePort(request) != effectivePort(prefix)) return false
    if (prefix.rawQuery != null || prefix.rawFragment != null || prefix.rawUserInfo != null) return false

    val requestPath = request.path ?: return false
    val prefixPath = prefix.path.orEmpty().trimEnd('/')
    return requestPath == prefixPath || requestPath.startsWith("$prefixPath/")
}

internal fun String.mimeFromPath(): String {
    val extension = substringAfterLast('.', "")
    return MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(extension.lowercase(Locale.ROOT))
        ?: "application/octet-stream"
}

private fun effectivePort(uri: URI): Int = when {
    uri.port >= 0 -> uri.port
    uri.scheme.equals("https", ignoreCase = true) -> 443
    uri.scheme.equals("http", ignoreCase = true) -> 80
    else -> -1
}
