package cn.lmcw.bitwebc.download

import cn.lmcw.bitwebc.core.api.DownloadHandler
import cn.lmcw.bitwebc.download.model.DownloadRequest
import cn.lmcw.bitwebc.download.model.DownloadTaskState
import kotlinx.coroutines.flow.StateFlow

/**
 * Controls downloads started by Bitwebc and exposes observable task snapshots.
 *
 * The [tasks] flow is read-only. Each command is safe to call with an unknown task id;
 * unsupported state transitions are ignored, while [forget] reports whether it removed a task.
 */
interface DownloadController : DownloadHandler {
    val tasks: StateFlow<Map<String, DownloadTaskState>>

    fun enqueue(request: DownloadRequest): String

    fun cancel(taskId: String)

    fun pause(taskId: String)

    fun resume(taskId: String)

    fun retry(taskId: String)

    /** Removes a retained terminal task without deleting its downloaded file. */
    fun forget(taskId: String): Boolean
}
