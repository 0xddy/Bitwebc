package cn.lmcw.bitwebc.download.ui

import android.app.AlertDialog
import androidx.activity.ComponentActivity
import cn.lmcw.bitwebc.download.model.DownloadRequest
import cn.lmcw.bitwebc.download.ext.guessFileName

/** 下载前确认 UI */
fun interface DownloadConfirmUi {
    fun confirm(
        activity: ComponentActivity,
        request: DownloadRequest,
        onDecision: (Boolean) -> Unit
    )
}

/** 默认下载确认弹窗 */
class DefaultDownloadConfirmUi : DownloadConfirmUi {
    override fun confirm(
        activity: ComponentActivity,
        request: DownloadRequest,
        onDecision: (Boolean) -> Unit
    ) {
        val fileName = request.url.guessFileName(null, null)
        val sizeText = formatContentLength(request.contentLength)
        val message = if (sizeText != null) {
            "即将下载：$fileName（$sizeText）\n是否继续？"
        } else {
            "即将下载：$fileName\n是否继续？"
        }
        AlertDialog.Builder(activity)
            .setTitle("下载确认")
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onDecision(true) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onDecision(false) }
            .setOnCancelListener { onDecision(false) }
            .show()
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
