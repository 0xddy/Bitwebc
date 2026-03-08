package cn.lmcw.bitwebc.download.ext

import java.net.URLConnection
import java.util.Locale

private const val FALLBACK_MIME = "application/octet-stream"

/**
 * 规范化 MIME 类型：转为小写；若为空则根据 [fileName] 猜测或返回默认。
 */
fun String?.normalizeMimeType(fileName: String? = null): String {
    val raw = this?.trim()?.lowercase(Locale.US).orEmpty()
    if (raw.isNotBlank()) return raw
    if (!fileName.isNullOrBlank()) {
        val guessed = URLConnection.guessContentTypeFromName(fileName)
        if (!guessed.isNullOrBlank()) return guessed
    }
    return FALLBACK_MIME
}
