package cn.lmcw.bitwebc.download.config

import okhttp3.OkHttpClient
import cn.lmcw.bitwebc.download.storage.DefaultDownloadStorage
import cn.lmcw.bitwebc.download.storage.DownloadStorage

/**
 * 下载模块配置，用于 [BitwebcDownloadHandler] 与 [BitwebcDownloadFactory]。
 */
data class DownloadConfig(
    val okHttpClient: OkHttpClient = OkHttpClient(),
    val maxConcurrentDownloads: Int = 2,
    val bufferSizeBytes: Int = 8192,
    val foregroundPolicy: ForegroundPolicy = ForegroundPolicy(),
    val notificationChannelId: String = "bitwebc_download",
    val notificationChannelName: String = "Bitwebc 下载",
    val notificationChannelDescription: String = "Bitwebc 下载进度与结果通知",
    val storage: DownloadStorage? = null,
    /** 仅在使用默认 [DefaultDownloadStorage] 时生效：FileProvider authority */
    val fileProviderAuthority: String = "",
    /** 仅在使用默认 [DefaultDownloadStorage] 时生效：子目录名（可选） */
    val storageSubDirectory: String? = null
) {
    /**
     * 解析得到实际使用的 Storage：若未指定则用默认实现，需传入 [fileProviderAuthority]（通常为 packageName + ".bitwebc.fileprovider"）。
     */
    fun resolveStorage(context: android.content.Context): DownloadStorage {
        return storage ?: DefaultDownloadStorage(
            fileProviderAuthority = fileProviderAuthority.ifBlank {
                context.packageName + ".bitwebc.fileprovider"
            },
            subDirectory = storageSubDirectory
        )
    }
}
