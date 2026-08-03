package cn.lmcw.bitwebc.download.config

import cn.lmcw.bitwebc.download.ext.DataUriDecoder
import cn.lmcw.bitwebc.download.storage.DefaultDownloadStorage
import cn.lmcw.bitwebc.download.storage.DownloadStorage
import cn.lmcw.bitwebc.download.ui.DownloadConfirmUi
import okhttp3.OkHttpClient

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
    val confirmUi: DownloadConfirmUi? = null,
    /** `data:` URI 解码允许的最大字节数，超过此值拒绝解码以防 OOM。 */
    val dataUriMaxBytes: Long = DataUriDecoder.DEFAULT_MAX_BYTES
) {
    init {
        require(maxConcurrentDownloads > 0) {
            "maxConcurrentDownloads must be greater than 0"
        }
        require(bufferSizeBytes in MIN_BUFFER_SIZE_BYTES..MAX_BUFFER_SIZE_BYTES) {
            "bufferSizeBytes must be between $MIN_BUFFER_SIZE_BYTES and $MAX_BUFFER_SIZE_BYTES"
        }
        require(notificationChannelId.isNotBlank()) {
            "notificationChannelId must not be blank"
        }
        require(notificationChannelName.isNotBlank()) {
            "notificationChannelName must not be blank"
        }
        require(dataUriMaxBytes > 0L) {
            "dataUriMaxBytes must be greater than 0"
        }
        require(foregroundPolicy.largeFileThresholdBytes >= 0L) {
            "largeFileThresholdBytes must not be negative"
        }
    }

    companion object {
        const val MIN_BUFFER_SIZE_BYTES: Int = 1
        const val MAX_BUFFER_SIZE_BYTES: Int = 16 * 1024 * 1024

        val DEFAULT_CLIENT: OkHttpClient by lazy { OkHttpClient() }
    }

    fun resolveStorage(context: android.content.Context): DownloadStorage {
        val customStorage = storage
        if (customStorage != null) {
            storageSubDirectory?.let(customStorage::setSubDirectory)
            return customStorage
        }
        return DefaultDownloadStorage(
            fileProviderAuthority = fileProviderAuthority.ifBlank {
                context.packageName + ".bitwebc.download.fileprovider"
            },
            subDirectory = storageSubDirectory
        )
    }
}
