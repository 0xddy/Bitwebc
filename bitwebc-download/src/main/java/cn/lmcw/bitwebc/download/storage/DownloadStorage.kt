package cn.lmcw.bitwebc.download.storage

import android.content.Context

/**
 * Creates transactional [FileSink] instances. Implementations should keep a
 * destination private/incomplete until commit and remove partial data on abort.
 */
interface DownloadStorage {
    fun createSink(context: Context, fileName: String, mimeType: String): FileSink?
    fun setSubDirectory(subDir: String?) {}
}
