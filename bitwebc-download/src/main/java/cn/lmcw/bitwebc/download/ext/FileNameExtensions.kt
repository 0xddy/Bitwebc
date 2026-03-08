package cn.lmcw.bitwebc.download.ext

import android.webkit.URLUtil
import java.util.regex.Pattern

private const val FALLBACK_NAME = "download"
private val FILENAME_STAR = Pattern.compile("filename\\*?=(?:UTF-8''|\\\")?([^;\\\"]+)")

/**
 * 以当前 URL 为基准，结合 Content-Disposition 与 MIME 解析出安全可用的文件名。
 * 优先从 Content-Disposition 解析 filename/filename*，否则使用系统 [URLUtil.guessFileName]。
 */
fun String.guessFileName(contentDisposition: String?, mimeType: String?): String {
    val fromCd = contentDisposition.parseFilenameFromContentDisposition()
    if (!fromCd.isNullOrBlank()) return fromCd.sanitizeFileName()
    val guessed = URLUtil.guessFileName(this, contentDisposition, mimeType ?: "application/octet-stream")
    return guessed.sanitizeFileName().ifBlank { FALLBACK_NAME }
}

/**
 * 从 Content-Disposition 头中解析 filename 或 filename* (RFC 5987)。
 */
fun String?.parseFilenameFromContentDisposition(): String? {
    if (this.isNullOrBlank()) return null
    val matcher = FILENAME_STAR.matcher(this)
    return if (matcher.find()) matcher.group(1)?.trim() else null
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
