package cn.lmcw.bitwebc.download.ext

import android.webkit.URLUtil
import androidx.core.net.toUri
import java.nio.charset.Charset

private const val FALLBACK_NAME = "download"

fun String.guessFileName(contentDisposition: String?, mimeType: String?): String {
    return resolveDownloadFileName(this, this, contentDisposition, mimeType)
}

fun resolveDownloadFileName(
    originalUrl: String,
    finalUrl: String?,
    contentDisposition: String?,
    mimeType: String?
): String {
    val fromContentDisposition = extractFileNameFromContentDisposition(contentDisposition)
        ?.sanitizeFileName()
    if (!fromContentDisposition.isNullOrBlank()) return fromContentDisposition

    val fromFinalUrl = finalUrl?.parseFileNameFromUrl()?.sanitizeFileName()
    val fromOriginalUrl = originalUrl.parseFileNameFromUrl()?.sanitizeFileName()

    if (!fromFinalUrl.isNullOrBlank()) return fromFinalUrl
    if (!fromOriginalUrl.isNullOrBlank()) return fromOriginalUrl

    val guessed = URLUtil.guessFileName(finalUrl ?: originalUrl, contentDisposition, mimeType ?: "application/octet-stream")
    return guessed.sanitizeFileName().ifBlank { FALLBACK_NAME }
}

internal fun extractFileNameFromContentDisposition(contentDisposition: String?): String? {
    if (contentDisposition.isNullOrBlank()) return null

    val parameters = splitContentDispositionParameters(contentDisposition)
    val extended = parameters.firstOrNull { it.first.equals("filename*", ignoreCase = true) }
        ?.second
        ?.let(::decodeExtendedFileName)
    if (!extended.isNullOrBlank()) return extended

    return parameters.firstOrNull { it.first.equals("filename", ignoreCase = true) }
        ?.second
        ?.trim()
        ?.removeSurrounding("\"")
        ?.replace(Regex("\\\\(.)"), "$1")
        ?.takeIf { it.isNotBlank() }
}

private fun splitContentDispositionParameters(header: String): List<Pair<String, String>> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var escaped = false
    header.forEach { char ->
        when {
            escaped -> {
                current.append(char)
                escaped = false
            }
            char == '\\' && quoted -> {
                current.append(char)
                escaped = true
            }
            char == '"' -> {
                current.append(char)
                quoted = !quoted
            }
            char == ';' && !quoted -> {
                parts += current.toString()
                current.clear()
            }
            else -> current.append(char)
        }
    }
    parts += current.toString()

    return parts.mapNotNull { part ->
        val equals = part.indexOf('=')
        if (equals <= 0) return@mapNotNull null
        part.substring(0, equals).trim() to part.substring(equals + 1).trim()
    }
}

private fun decodeExtendedFileName(rawValue: String): String? {
    val value = rawValue.trim().removeSurrounding("\"")
    val firstQuote = value.indexOf('\'')
    val secondQuote = value.indexOf('\'', firstQuote + 1)
    if (firstQuote <= 0 || secondQuote < 0) return null

    val charset = runCatching { Charset.forName(value.substring(0, firstQuote)) }
        .getOrDefault(Charsets.UTF_8)
    return runCatching {
        decodePercentEncoded(value.substring(secondQuote + 1), charset)
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

private fun String.parseFileNameFromUrl(): String? {
    val segment = runCatching { toUri().lastPathSegment }.getOrNull()
    val fileName = segment?.substringAfterLast('/')?.trim().orEmpty()
    if (fileName.isBlank()) return null
    if (!fileName.contains('.') || fileName.endsWith('.')) return null
    return fileName
}

/**
 * 移除路径成分和非法字符，避免写入非法路径。
 */
fun String.sanitizeFileName(): String {
    var base = this
    val lastSlash = base.lastIndexOf('/')
    if (lastSlash >= 0) base = base.substring(lastSlash + 1)
    val lastBack = base.lastIndexOf('\\')
    if (lastBack >= 0) base = base.substring(lastBack + 1)
    return base.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_").trim().ifBlank { FALLBACK_NAME }
}
