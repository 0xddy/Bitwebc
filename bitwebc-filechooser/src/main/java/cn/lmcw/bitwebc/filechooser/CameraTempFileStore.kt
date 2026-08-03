package cn.lmcw.bitwebc.filechooser

import android.os.Environment
import androidx.activity.ComponentActivity
import java.io.File

/** App-owned camera output that remains readable while WebView uploads the selected image. */
internal class CameraTempFileStore(
    private val cacheDirectory: File,
    private val externalDirectory: File? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {

    fun createTempFile(): File {
        cleanupExpired()
        directories().forEach { directory ->
            val ready = runCatching {
                (directory.isDirectory || directory.mkdirs()) && directory.isDirectory
            }.getOrDefault(false)
            if (!ready) return@forEach
            runCatching {
                File.createTempFile(CAMERA_TEMP_FILE_PREFIX, CAMERA_TEMP_FILE_SUFFIX, directory)
            }.getOrNull()?.let { return it }
        }
        error("Unable to create Bitwebc camera directory")
    }

    fun cleanupExpired() {
        val cutoff = nowMillis() - CAMERA_TEMP_FILE_MAX_AGE_MILLIS
        directories().forEach { directory ->
            deleteExpiredCameraTempFiles(directory, cutoff)
        }
    }

    private fun directories(): List<File> = buildList {
        // Cache is intentionally first so one-off captures remain OS-evictable.
        add(cacheDirectory)
        externalDirectory?.takeUnless { it.absolutePath == cacheDirectory.absolutePath }?.let(::add)
    }

    companion object {
        fun from(activity: ComponentActivity): CameraTempFileStore {
            val cacheDirectory = File(activity.cacheDir, BITWEBC_CAMERA_CACHE_SUBDIRECTORY)
            val externalDirectory = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?.let { picturesDir -> File(picturesDir, BITWEBC_CAMERA_EXTERNAL_SUBDIRECTORY) }
            return CameraTempFileStore(cacheDirectory, externalDirectory)
        }
    }
}

/** Removes only Bitwebc-owned camera files; successful uploads get a generous consumption TTL. */
@JvmSynthetic
internal fun deleteExpiredCameraTempFiles(directory: File, cutoffEpochMillis: Long) {
    val candidates = runCatching { directory.listFiles() }.getOrNull() ?: return
    candidates.forEach { file ->
        if (
            file.isFile &&
            file.name.startsWith(CAMERA_TEMP_FILE_PREFIX) &&
            file.name.endsWith(CAMERA_TEMP_FILE_SUFFIX, ignoreCase = true) &&
            file.lastModified() <= cutoffEpochMillis
        ) {
            runCatching { file.delete() }
        }
    }
}

// Keep these paths aligned with res/xml/bitwebc_filechooser_paths.xml.
private const val BITWEBC_CAMERA_EXTERNAL_SUBDIRECTORY = "bitwebc/camera"
private const val BITWEBC_CAMERA_CACHE_SUBDIRECTORY = "bitwebc/camera"
private const val CAMERA_TEMP_FILE_PREFIX = "bitwebc_camera_"
private const val CAMERA_TEMP_FILE_SUFFIX = ".jpg"
internal const val CAMERA_TEMP_FILE_MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1_000L
