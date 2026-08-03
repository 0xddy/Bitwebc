package cn.lmcw.bitwebc.filechooser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CameraTempFileCleanupTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `cleanup removes only expired Bitwebc camera images`() {
        val directory = temporaryFolder.newFolder("camera")
        val oldCameraFile = temporaryFolder.newFile("camera/bitwebc_camera_old.jpg")
        val recentCameraFile = temporaryFolder.newFile("camera/bitwebc_camera_recent.jpg")
        val unrelatedFile = temporaryFolder.newFile("camera/user_photo.jpg")
        val now = System.currentTimeMillis()
        assertTrue(oldCameraFile.setLastModified(now - 20_000L))
        assertTrue(recentCameraFile.setLastModified(now - 1_000L))
        assertTrue(unrelatedFile.setLastModified(now - 20_000L))

        deleteExpiredCameraTempFiles(directory, cutoffEpochMillis = now - 10_000L)

        assertFalse(oldCameraFile.exists())
        assertTrue(recentCameraFile.exists())
        assertTrue(unrelatedFile.exists())
    }

    @Test
    fun `store prefers cache and cleans expired files in every owned directory`() {
        val cacheDirectory = temporaryFolder.newFolder("cache-camera")
        val externalDirectory = temporaryFolder.newFolder("external-camera")
        val now = 20_000L + CAMERA_TEMP_FILE_MAX_AGE_MILLIS
        val oldCacheFile = cacheDirectory.resolve("bitwebc_camera_old.jpg").apply {
            writeText("old-cache")
            assertTrue(setLastModified(10_000L))
        }
        val oldExternalFile = externalDirectory.resolve("bitwebc_camera_old.jpg").apply {
            writeText("old-external")
            assertTrue(setLastModified(10_000L))
        }
        val recentSuccessfulCapture = cacheDirectory.resolve("bitwebc_camera_recent.jpg").apply {
            writeText("still-uploading")
            assertTrue(setLastModified(now))
        }
        val store = CameraTempFileStore(
            cacheDirectory = cacheDirectory,
            externalDirectory = externalDirectory,
            nowMillis = { now }
        )

        val created = store.createTempFile()

        assertEquals(cacheDirectory.canonicalFile, created.parentFile?.canonicalFile)
        assertFalse(oldCacheFile.exists())
        assertFalse(oldExternalFile.exists())
        assertTrue(recentSuccessfulCapture.exists())
        assertTrue(created.exists())
    }

    @Test
    fun `store falls back to external directory when cache path is unavailable`() {
        val cachePath = temporaryFolder.newFile("cache-is-a-file")
        val externalDirectory = temporaryFolder.newFolder("external-fallback")
        val store = CameraTempFileStore(
            cacheDirectory = cachePath,
            externalDirectory = externalDirectory
        )

        val created = store.createTempFile()

        assertEquals(externalDirectory.canonicalFile, created.parentFile?.canonicalFile)
    }
}
