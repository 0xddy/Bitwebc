package cn.lmcw.bitwebc.core.extensions

import android.webkit.MimeTypeMap

internal fun String.toAssetsPath(urlPrefix: String, assetsPath: String): String? {
    val suffix = removePrefix(urlPrefix).trimStart('/')
    return (assetsPath.trimEnd('/') + "/" + suffix).trimStart('/').takeIf { it != "/" }
}

internal fun String.mimeFromPath(): String {
    val ext = substringAfterLast('.', "")
    return MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(ext.lowercase())
        ?: "application/octet-stream"
}
