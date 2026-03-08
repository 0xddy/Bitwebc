package cn.lmcw.bitwebc.download.model

/**
 * 下载任务状态。
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
