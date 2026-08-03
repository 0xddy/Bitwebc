package cn.lmcw.bitwebc.download.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import cn.lmcw.bitwebc.download.R
import cn.lmcw.bitwebc.download.ext.normalizeMimeType

/** 下载进度/结果通知 */
class DownloadNotificationHelper(
    private val context: Context,
    private val channelId: String,
    private val channelName: String,
    private val channelDescription: String = "下载进度与结果通知"
) {
    companion object {
        internal const val EXTRA_TASK_ID = "bitwebc.extra.TASK_ID"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = channelDescription
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun showProgress(notificationId: Int, taskId: String, fileName: String, downloadedBytes: Long, totalBytes: Long) {
        val cancelIntent = Intent(context, DownloadNotificationReceiver::class.java).apply {
            action = DownloadNotificationReceiver.ACTION_CANCEL_DOWNLOAD
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "取消",
            cancelPendingIntent
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_DELETE)
            .build()
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle("正在下载")
            .setContentText(fileName)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(cancelAction)
        if (totalBytes > 0) {
            val progress = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        notificationManager.notify(notificationId, builder.build())
    }

    fun showCancelled(notificationId: Int, fileName: String) {
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle("已取消下载")
            .setContentText(fileName)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(notificationId, notification)
    }

    fun showSuccess(notificationId: Int, fileName: String, uri: Uri, mimeType: String) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.normalizeMimeType(fileName))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle("下载完成")
            .setContentText(fileName)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        notificationManager.notify(notificationId, notification)
    }

    fun showFailed(notificationId: Int, reason: String) {
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle("下载失败")
            .setContentText(reason)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(notificationId, notification)
    }

    fun cancel(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }
}
