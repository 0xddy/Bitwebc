package cn.lmcw.bitwebc.download.model

/**
 * 下载任务状态：sealed 有限状态机，非法状态在编译期不可达。
 */
sealed interface DownloadTaskState {
    val id: String
    val url: String
    val fileName: String?
    val createdAtMillis: Long

    data class Queued(
        override val id: String,
        override val url: String,
        override val fileName: String? = null,
        override val createdAtMillis: Long = System.currentTimeMillis()
    ) : DownloadTaskState

    data class Running(
        override val id: String,
        override val url: String,
        override val fileName: String,
        public val downloadedBytes: Long,
        public val totalBytes: Long,
        override val createdAtMillis: Long = System.currentTimeMillis()
    ) : DownloadTaskState {
        val progressPercent: Int
            get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else -1
    }

    data class Paused(
        override val id: String,
        override val url: String,
        override val fileName: String? = null,
        override val createdAtMillis: Long = System.currentTimeMillis()
    ) : DownloadTaskState

    data class Success(
        override val id: String,
        override val url: String,
        override val fileName: String,
        public val totalBytes: Long,
        override val createdAtMillis: Long = System.currentTimeMillis()
    ) : DownloadTaskState

    data class Failed(
        override val id: String,
        override val url: String,
        override val fileName: String?,
        public val error: Throwable,
        override val createdAtMillis: Long = System.currentTimeMillis()
    ) : DownloadTaskState

    data class Cancelled(
        override val id: String,
        override val url: String,
        override val fileName: String? = null,
        override val createdAtMillis: Long = System.currentTimeMillis()
    ) : DownloadTaskState
}

/** 是否为终态（成功/失败/取消）。 */
val DownloadTaskState.isTerminal: Boolean
    get() = when (this) {
        is DownloadTaskState.Success, is DownloadTaskState.Failed, is DownloadTaskState.Cancelled -> true
        else -> false
    }

/** 是否处于可进行中的状态（排队中或下载中）。 */
val DownloadTaskState.isActive: Boolean
    get() = when (this) {
        is DownloadTaskState.Queued, is DownloadTaskState.Running -> true
        else -> false
    }
