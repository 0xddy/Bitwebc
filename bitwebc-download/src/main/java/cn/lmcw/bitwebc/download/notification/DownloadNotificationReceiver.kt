package cn.lmcw.bitwebc.download.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.lmcw.bitwebc.download.handler.BitwebcDownloadHandler

internal class DownloadNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_CANCEL_DOWNLOAD) return
        val taskId = intent.getStringExtra(DownloadNotificationHelper.EXTRA_TASK_ID).orEmpty()
        if (taskId.isBlank()) return
        BitwebcDownloadHandler.cancelRegisteredTask(taskId)
    }

    companion object {
        internal const val ACTION_CANCEL_DOWNLOAD = "cn.lmcw.bitwebc.download.action.CANCEL"
    }
}
