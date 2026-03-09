package cn.lmcw.bitwebc.download.config

/** 大文件/前台策略 */
data class ForegroundPolicy(
    val largeFileThresholdBytes: Long = 30L * 1024 * 1024,
    val onLargeFileTask: ((taskId: String, fileName: String?) -> Unit)? = null
)
