package cn.lmcw.bitwebc.download.config

import okhttp3.OkHttpClient
import cn.lmcw.bitwebc.download.storage.DefaultDownloadStorage
import cn.lmcw.bitwebc.download.storage.DownloadStorage
import cn.lmcw.bitwebc.download.ui.DownloadConfirmUi

/** 下载模块配置 */
data class DownloadConfig(
    val okHttpClient: OkHttpClient = DEFAULT_CLIENT,
    val maxConcurrentDownloads: Int = 2,
    val bufferSizeBytes: Int = 8192,
    val foregroundPolicy: ForegroundPolicy = ForegroundPolicy(),
    val notificationChannelId: String = "bitwebc_download",
    val notificationChannelName: String = "Bitwebc 下载",
    val notificationChannelDescription: String = "Bitwebc 下载进度与结果通知",
    val storage: DownloadStorage? = null,
    val fileProviderAuthority: String = "",
    val storageSubDirectory: String? = null,
    val confirmBeforeDownload: Boolean = false,
    val confirmUi: DownloadConfirmUi? = null
) {
    companion object {
        val DEFAULT_CLIENT: OkHttpClient by lazy { OkHttpClient() }
    }

    fun resolveStorage(context: android.content.Context): DownloadStorage {
        return storage ?: DefaultDownloadStorage(
            fileProviderAuthority = fileProviderAuthority.ifBlank {
                context.packageName + ".bitwebc.fileprovider"
            },
            subDirectory = storageSubDirectory
        )
    }
}
