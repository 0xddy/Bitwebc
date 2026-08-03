package cn.lmcw.bitwebc.download.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class UniqueDownloadFileTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun suffixesAnExistingNameWithoutChangingExtension() {
        val directory = temporaryFolder.newFolder("downloads")
        assertTrue(directory.resolve("report.pdf").createNewFile())

        val allocated = createUniqueDownloadFile(directory, "report.pdf")

        assertNotNull(allocated)
        assertEquals("report (1).pdf", allocated?.name)
    }

    @Test
    fun concurrentAllocationsNeverReturnTheSameFile() {
        val directory = temporaryFolder.newFolder("parallel")
        val workers = 16
        val executor = Executors.newFixedThreadPool(workers)
        val start = CountDownLatch(1)
        val names = Collections.synchronizedSet(mutableSetOf<String>())
        try {
            val futures = (1..workers).map {
                executor.submit(Callable<String?> {
                    start.await()
                    createUniqueDownloadFile(directory, "archive.zip")?.name
                })
            }
            start.countDown()
            futures.mapNotNullTo(names) { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(workers, names.size)
    }

    @Test
    fun stalePartialCleanupAlsoRemovesItsEmptyReservation() {
        val directory = temporaryFolder.newFolder("stale")
        val reservation = directory.resolve("report.pdf").apply { createNewFile() }
        val partial = directory.resolve(".report.pdf.bitwebc-id.part").apply {
            writeText("partial")
        }
        val oldTime = 1_000L
        assertTrue(reservation.setLastModified(oldTime))
        assertTrue(partial.setLastModified(oldTime))

        cleanupStalePartialFiles(directory, nowMillis = oldTime + 25L * 60L * 60L * 1000L)

        assertTrue(!partial.exists())
        assertTrue(!reservation.exists())
    }

    @Test
    fun recursiveStartupCleanupCoversChangedSubdirectories() {
        val root = temporaryFolder.newFolder("startup-cleanup")
        val oldSubdirectory = root.resolve("old/account").apply { mkdirs() }
        val reservation = oldSubdirectory.resolve("archive.zip").apply { createNewFile() }
        val partial = oldSubdirectory.resolve(".archive.zip.bitwebc-old.part").apply {
            writeText("partial")
        }
        val oldTime = 1_000L
        assertTrue(reservation.setLastModified(oldTime))
        assertTrue(partial.setLastModified(oldTime))

        cleanupStalePartialFilesRecursively(
            root,
            nowMillis = oldTime + 25L * 60L * 60L * 1000L
        )

        assertTrue(!partial.exists())
        assertTrue(!reservation.exists())
    }

    @Test
    fun subdirectoryNormalizationRejectsTraversal() {
        assertEquals("reports/2026", normalizeSubDirectory("/reports/2026/"))
        assertThrows(IllegalArgumentException::class.java) {
            normalizeSubDirectory("reports/../private")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeSubDirectory("reports\\private")
        }
    }
}
