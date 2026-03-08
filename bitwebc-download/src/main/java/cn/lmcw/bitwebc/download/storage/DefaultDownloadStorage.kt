package cn.lmcw.bitwebc.download.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import cn.lmcw.bitwebc.download.ext.sanitizeFileName
import java.io.File

/**
 * 默认存储：Q+ 写入 [MediaStore.Downloads]，以下写入 [Context.getExternalFilesDir] 并用 FileProvider 提供 Uri。
 */
class DefaultDownloadStorage(
    private val fileProviderAuthority: String,
    private val subDirectory: String? = null
) : DownloadStorage {

    private var _subDir: String? = subDirectory

    override fun setSubDirectory(subDir: String?) {
        _subDir = subDir
    }

    override fun createSink(context: Context, fileName: String, mimeType: String): FileSink? {
        val safeName = fileName.sanitizeFileName()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createMediaStoreSink(context, safeName, mimeType)
        } else {
            createFileSink(context, safeName, mimeType)
        }
    }

    private fun createMediaStoreSink(context: Context, fileName: String, mimeType: String): FileSink? {
        val relativePath = _subDir?.let { "${Environment.DIRECTORY_DOWNLOADS}/$it" }
            ?: Environment.DIRECTORY_DOWNLOADS
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null
        val stream = resolver.openOutputStream(uri) ?: run {
            resolver.delete(uri, null, null)
            return null
        }
        return FileSink(uri, stream) {
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
    }

    private fun createFileSink(context: Context, fileName: String, mimeType: String): FileSink? {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val dir = _subDir?.let { File(baseDir, it) } ?: baseDir
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        val stream = file.outputStream()
        val uri = FileProvider.getUriForFile(context, fileProviderAuthority, file)
        return FileSink(uri, stream) {}
    }
}
