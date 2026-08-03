package cn.lmcw.bitwebc.download.storage

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import cn.lmcw.bitwebc.download.ext.sanitizeFileName
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 默认存储：Q+ 写入 [MediaStore.Downloads]，以下写入 [Context.getExternalFilesDir] 并用 FileProvider 提供 Uri。
 */
class DefaultDownloadStorage(
    private val fileProviderAuthority: String,
    private val subDirectory: String? = null
) : DownloadStorage {

    private var _subDir: String? = normalizeSubDirectory(subDirectory)

    override fun setSubDirectory(subDir: String?) {
        _subDir = normalizeSubDirectory(subDir)
    }

    override fun createSink(context: Context, fileName: String, mimeType: String): FileSink? {
        val safeName = fileName.sanitizeFileName()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createMediaStoreSink(context, safeName, mimeType)
        } else {
            createFileSink(context, safeName)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createMediaStoreSink(context: Context, fileName: String, mimeType: String): FileSink? {
        val relativePath = _subDir?.let { "${Environment.DIRECTORY_DOWNLOADS}/$it" }
            ?: Environment.DIRECTORY_DOWNLOADS
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
            put(MediaStore.Downloads.DATE_EXPIRES, (System.currentTimeMillis() / 1000L) + PENDING_EXPIRY_SECONDS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null
        val stream = runCatching { resolver.openOutputStream(uri) }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            return null
        } ?: run {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        return FileSink(
            uri = uri,
            outputStream = stream,
            onFinish = {
                val done = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                    putNull(MediaStore.Downloads.DATE_EXPIRES)
                }
                check(resolver.update(uri, done, null, null) > 0) {
                    "Unable to publish downloaded MediaStore item"
                }
            },
            onAbort = { runCatching { resolver.delete(uri, null, null) } }
        )
    }

    private fun createFileSink(context: Context, fileName: String): FileSink? {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.let { downloadsDir -> File(downloadsDir, BITWEBC_EXTERNAL_DOWNLOAD_SUBDIRECTORY) }
            ?: File(context.filesDir, BITWEBC_INTERNAL_DOWNLOAD_SUBDIRECTORY)
        val dir = _subDir?.let { File(baseDir, it) } ?: baseDir
        if (!dir.exists() && !dir.mkdirs()) return null
        if (!dir.isDirectory) return null
        cleanupStalePartialFiles(dir)

        val finalFile = createUniqueDownloadFile(dir, fileName) ?: return null
        val partialFile = File(
            dir,
            ".${finalFile.name}.bitwebc-${UUID.randomUUID()}.part"
        )
        val stream = runCatching { partialFile.outputStream() }.getOrElse {
            partialFile.delete()
            finalFile.delete()
            return null
        }
        activePartialPaths += partialFile.absolutePath
        val uri = runCatching {
            FileProvider.getUriForFile(context, fileProviderAuthority, finalFile)
        }.getOrElse {
            runCatching { stream.close() }
            activePartialPaths -= partialFile.absolutePath
            partialFile.delete()
            finalFile.delete()
            return null
        }
        return FileSink(
            uri = uri,
            outputStream = stream,
            onFinish = {
                try {
                    Os.rename(partialFile.absolutePath, finalFile.absolutePath)
                    check(finalFile.isFile && !partialFile.exists()) {
                        "Unable to publish downloaded file"
                    }
                } finally {
                    activePartialPaths -= partialFile.absolutePath
                }
            },
            onAbort = {
                try {
                    partialFile.delete()
                    finalFile.delete()
                } finally {
                    activePartialPaths -= partialFile.absolutePath
                }
            }
        )
    }

    private companion object {
        // Keep these paths aligned with res/xml/bitwebc_download_paths.xml.
        const val BITWEBC_EXTERNAL_DOWNLOAD_SUBDIRECTORY = "bitwebc/downloads"
        const val BITWEBC_INTERNAL_DOWNLOAD_SUBDIRECTORY = "bitwebc/downloads"
    }
}

internal fun normalizeSubDirectory(subDirectory: String?): String? {
    val value = subDirectory?.trim()?.trim('/')?.takeIf(String::isNotBlank) ?: return null
    val segments = value.split('/')
    require('\\' !in value && segments.none { it.isBlank() || it == "." || it == ".." }) {
        "Download subdirectory must stay inside the Downloads directory"
    }
    return segments.joinToString("/")
}

internal fun cleanupStalePartialFiles(
    directory: File,
    nowMillis: Long = System.currentTimeMillis()
) {
    val cutoff = nowMillis - PARTIAL_EXPIRY_MILLIS
    directory.listFiles()?.forEach { partial ->
        if (!partial.isFile || !partial.name.startsWith('.') ||
            !partial.name.endsWith(".part") || ".bitwebc-" !in partial.name ||
            partial.lastModified() > cutoff || partial.absolutePath in activePartialPaths
        ) {
            return@forEach
        }
        val finalName = partial.name
            .removePrefix(".")
            .substringBeforeLast(".bitwebc-", missingDelimiterValue = "")
        if (partial.delete() && finalName.isNotBlank()) {
            val reservation = File(directory, finalName)
            if (reservation.isFile && reservation.length() == 0L &&
                reservation.lastModified() <= cutoff
            ) {
                reservation.delete()
            }
        }
    }
}

/** Cleans stale process-death leftovers across every configured Bitwebc subdirectory. */
internal fun cleanupStalePartialFilesRecursively(
    rootDirectory: File,
    nowMillis: Long = System.currentTimeMillis()
) {
    if (!rootDirectory.isDirectory) return
    val pendingDirectories = ArrayDeque<Pair<File, Int>>()
    pendingDirectories.addLast(rootDirectory to 0)
    var visitedDirectories = 0
    while (pendingDirectories.isNotEmpty() && visitedDirectories < MAX_CLEANUP_DIRECTORIES) {
        val (directory, depth) = pendingDirectories.removeFirst()
        visitedDirectories += 1
        cleanupStalePartialFiles(directory, nowMillis)
        if (depth >= MAX_CLEANUP_DEPTH) continue
        directory.listFiles()?.forEach { child ->
            if (child.isDirectory) pendingDirectories.addLast(child to (depth + 1))
        }
    }
}

internal fun cleanupStaleBitwebcDownloadFiles(
    context: Context,
    nowMillis: Long = System.currentTimeMillis()
) {
    val roots = buildList {
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.let { add(File(it, "bitwebc/downloads")) }
        add(File(context.filesDir, "bitwebc/downloads"))
    }
    roots.distinctBy(File::getAbsolutePath).forEach { root ->
        cleanupStalePartialFilesRecursively(root, nowMillis)
    }
}

internal fun createUniqueDownloadFile(directory: File, fileName: String): File? {
    val extensionIndex = fileName.lastIndexOf('.').takeIf { it > 0 }
    val baseName = extensionIndex?.let { fileName.substring(0, it) } ?: fileName
    val extension = extensionIndex?.let { fileName.substring(it) }.orEmpty()

    for (suffix in 0..MAX_UNIQUE_FILE_ATTEMPTS) {
        val candidateName = if (suffix == 0) fileName else "$baseName ($suffix)$extension"
        val candidate = File(directory, candidateName)
        if (runCatching { candidate.createNewFile() }.getOrDefault(false)) return candidate
    }
    return null
}

private const val MAX_UNIQUE_FILE_ATTEMPTS = 10_000
private const val MAX_CLEANUP_DIRECTORIES = 512
private const val MAX_CLEANUP_DEPTH = 16
private const val PENDING_EXPIRY_SECONDS = 24L * 60L * 60L
private const val PARTIAL_EXPIRY_MILLIS = 24L * 60L * 60L * 1000L
private val activePartialPaths = ConcurrentHashMap.newKeySet<String>()
