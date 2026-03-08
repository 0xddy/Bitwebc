package cn.lmcw.bitwebc.download.model

/**
 * 下载任务当前状态快照，用于 UI 或事件订阅。
 */
data class DownloadTaskState(
    val id: String,
    val url: String,
    val fileName: String? = null,
    val status: DownloadTaskStatus = DownloadTaskStatus.QUEUED,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = -1,
    val error: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    /** 进度百分比 [0, 100]，未知总大小时为 -1 */
    val progressPercent: Int
        get() = if (totalBytes > 0) {
            ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
        } else -1

    val isTerminal: Boolean
        get() = status == DownloadTaskStatus.SUCCESS ||
            status == DownloadTaskStatus.FAILED ||
            status == DownloadTaskStatus.CANCELLED

    val isActive: Boolean
        get() = status == DownloadTaskStatus.QUEUED || status == DownloadTaskStatus.RUNNING
}
