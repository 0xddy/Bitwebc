package cn.lmcw.bitwebc.download.ext

import java.net.URLConnection
import java.util.Locale

private const val FALLBACK_MIME = "application/octet-stream"

/** 规范化 MIME 类型，优先按 fileName 猜测 */
fun String?.normalizeMimeType(fileName: String? = null): String {
    if (!fileName.isNullOrBlank()) {
        val guessed = URLConnection.guessContentTypeFromName(fileName)
        if (!guessed.isNullOrBlank()) return guessed
    }
    val raw = this?.trim()?.lowercase(Locale.US).orEmpty()
    if (raw.isNotBlank()) return raw
    return FALLBACK_MIME
}
