package cn.lmcw.bitwebc.download.model

/**
 * 单次下载请求参数（来自 WebView 或主动入队）。
 */
data class DownloadRequest(
    val url: String,
    val userAgent: String? = null,
    val contentDisposition: String? = null,
    val mimeType: String? = null,
    val contentLength: Long = -1L
)
