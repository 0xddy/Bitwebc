package cn.lmcw.bitwebc.download.model

/**
 * 下载任务状态枚举。推荐使用 [DownloadTaskState] sealed 类型做 when 分支，本枚举保留用于 UI/日志兼容。
 */
enum class DownloadTaskStatus {
    /** 已加入队列，等待执行 */
    QUEUED,
    /** 正在下载 */
    RUNNING,
    /** 已暂停 */
    PAUSED,
    /** 已取消 */
    CANCELLED,
    /** 下载成功 */
    SUCCESS,
    /** 下载失败 */
    FAILED
}

/** 将 [DownloadTaskState] 映射为 [DownloadTaskStatus]，便于与依赖枚举的 UI/日志 兼容。 */
fun DownloadTaskState.toStatus(): DownloadTaskStatus = when (this) {
    is DownloadTaskState.Queued -> DownloadTaskStatus.QUEUED
    is DownloadTaskState.Running -> DownloadTaskStatus.RUNNING
    is DownloadTaskState.Paused -> DownloadTaskStatus.PAUSED
    is DownloadTaskState.Success -> DownloadTaskStatus.SUCCESS
    is DownloadTaskState.Failed -> DownloadTaskStatus.FAILED
    is DownloadTaskState.Cancelled -> DownloadTaskStatus.CANCELLED
}
