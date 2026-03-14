package cn.lmcw.bitwebc.core.event

sealed class BitwebcEvent {
    data class PageStarted(val url: String?) : BitwebcEvent()
    data class PageFinished(val url: String?) : BitwebcEvent()
    data class PageError(val url: String?, val message: String?) : BitwebcEvent()
    data class HttpError(val url: String?, val statusCode: Int) : BitwebcEvent()
    data class SslError(val url: String?, val message: String?) : BitwebcEvent()
    data class SchemeFallback(val rawUrl: String?, val reason: String) : BitwebcEvent()
    data class RenderProcessGone(val didCrash: Boolean, val priorityAtExit: Int) : BitwebcEvent()
    data class FullscreenChanged(val fullscreen: Boolean) : BitwebcEvent()

    data class DownloadQueued(val taskId: String, val url: String) : BitwebcEvent()
    data class DownloadProgress(val taskId: String, val fileName: String, val progress: Int) : BitwebcEvent()
    data class DownloadSuccess(val taskId: String, val fileName: String) : BitwebcEvent()
    data class DownloadFailed(val taskId: String, val reason: String) : BitwebcEvent()
    data class DownloadPermissionDenied(val taskId: String?) : BitwebcEvent()

    data class FileChooserPermissionDenied(val reason: String) : BitwebcEvent()
    data class FileChooserCancelled(val reason: String) : BitwebcEvent()
    data class FileChooserFailed(val reason: String) : BitwebcEvent()
}

fun interface BitwebcEventListener {
    fun onEvent(event: BitwebcEvent)
}
