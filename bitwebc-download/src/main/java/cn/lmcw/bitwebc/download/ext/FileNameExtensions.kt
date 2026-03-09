package cn.lmcw.bitwebc.download.ext

import android.net.Uri
import android.webkit.URLUtil

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
    val fromFinalUrl = finalUrl?.parseFileNameFromUrl()?.sanitizeFileName()
    val fromOriginalUrl = originalUrl.parseFileNameFromUrl()?.sanitizeFileName()

    if (!fromFinalUrl.isNullOrBlank()) return fromFinalUrl
    if (!fromOriginalUrl.isNullOrBlank()) return fromOriginalUrl

    val guessed = URLUtil.guessFileName(finalUrl ?: originalUrl, contentDisposition, mimeType ?: "application/octet-stream")
    return guessed.sanitizeFileName().ifBlank { FALLBACK_NAME }
}

private fun String.parseFileNameFromUrl(): String? {
    val segment = runCatching { Uri.parse(this).lastPathSegment }.getOrNull()
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
    return base.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { FALLBACK_NAME }
}
