package cn.lmcw.bitwebc.download.config

/**
 * 大文件/前台策略：超过阈值时可回调（如提示用户或启动前台服务）。
 */
data class ForegroundPolicy(
    /** 超过该字节数视为大文件，触发 [onLargeFileTask] */
    val largeFileThresholdBytes: Long = 30L * 1024 * 1024,
    /** 大文件任务开始时回调，可用于显示提示或绑定前台服务 */
    val onLargeFileTask: ((taskId: String, fileName: String?) -> Unit)? = null
)
