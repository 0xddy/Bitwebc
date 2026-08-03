package cn.lmcw.bitwebc.download.ui

import android.app.AlertDialog
import androidx.activity.ComponentActivity
import cn.lmcw.bitwebc.download.ext.guessFileName
import cn.lmcw.bitwebc.download.model.DownloadRequest
import java.util.IdentityHashMap

/** 下载前确认 UI */
fun interface DownloadConfirmUi {
    fun confirm(
        activity: ComponentActivity,
        request: DownloadRequest,
        onDecision: (Boolean) -> Unit
    )

    /** Dismisses an outstanding confirmation when supported. Existing custom UIs may ignore it. */
    fun cancel(request: DownloadRequest) = Unit
}

/** 默认下载确认弹窗 */
class DefaultDownloadConfirmUi : DownloadConfirmUi {
    private val activeDialogs = IdentityHashMap<DownloadRequest, AlertDialog>()

    override fun confirm(
        activity: ComponentActivity,
        request: DownloadRequest,
        onDecision: (Boolean) -> Unit
    ) {
        val fileName = request.url.guessFileName(request.contentDisposition, request.mimeType)
        val sizeText = formatContentLength(request.contentLength)
        val message = if (sizeText != null) {
            "即将下载：$fileName（$sizeText）\n是否继续？"
        } else {
            "即将下载：$fileName\n是否继续？"
        }
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(activity)
            .setTitle("下载确认")
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                complete(request, dialog) { onDecision(true) }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                complete(request, dialog) { onDecision(false) }
            }
            .setOnCancelListener { complete(request, dialog) { onDecision(false) } }
            .create()
        dialog.setOnDismissListener {
            complete(request, dialog) { onDecision(false) }
        }
        activeDialogs.put(request, dialog)?.let { previous -> runCatching { previous.dismiss() } }
        runCatching { dialog.show() }.onFailure {
            complete(request, dialog) { onDecision(false) }
        }
    }

    override fun cancel(request: DownloadRequest) {
        activeDialogs.remove(request)?.let { dialog -> runCatching { dialog.dismiss() } }
    }

    private fun complete(
        request: DownloadRequest,
        dialog: AlertDialog,
        onComplete: () -> Unit
    ) {
        if (activeDialogs[request] !== dialog) return
        activeDialogs.remove(request)
        onComplete()
    }

    private fun formatContentLength(bytes: Long): String? {
        if (bytes <= 0) return null
        return when {
            bytes < 1024 -> "${bytes} B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }
}
