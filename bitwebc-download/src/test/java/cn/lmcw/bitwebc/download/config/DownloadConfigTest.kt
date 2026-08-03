package cn.lmcw.bitwebc.download.config

import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadConfigTest {

    @Test
    fun rejectsInvalidConcurrencyAndBufferBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            DownloadConfig(maxConcurrentDownloads = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DownloadConfig(bufferSizeBytes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DownloadConfig(bufferSizeBytes = DownloadConfig.MAX_BUFFER_SIZE_BYTES + 1)
        }
    }

    @Test
    fun rejectsInvalidNotificationAndSizeConfiguration() {
        assertThrows(IllegalArgumentException::class.java) {
            DownloadConfig(notificationChannelId = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DownloadConfig(notificationChannelName = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DownloadConfig(dataUriMaxBytes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DownloadConfig(foregroundPolicy = ForegroundPolicy(largeFileThresholdBytes = -1))
        }
    }
}
