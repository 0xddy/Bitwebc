package cn.lmcw.bitwebc.download.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTaskStateTest {
    @Test
    fun allCompletedStatesAreTerminal() {
        assertTrue(DownloadTaskState.Success("success", "https://example.com", "file.bin", 1).isTerminal)
        assertTrue(
            DownloadTaskState.Failed(
                "failed",
                "https://example.com",
                "file.bin",
                IllegalStateException("failed")
            ).isTerminal
        )
        assertTrue(DownloadTaskState.Cancelled("cancelled", "https://example.com").isTerminal)
    }

    @Test
    fun inProgressStatesAreNotTerminal() {
        assertFalse(DownloadTaskState.Queued("queued", "https://example.com").isTerminal)
        assertFalse(DownloadTaskState.Paused("paused", "https://example.com").isTerminal)
        assertFalse(
            DownloadTaskState.Running(
                "running",
                "https://example.com",
                "file.bin",
                downloadedBytes = 1,
                totalBytes = 2
            ).isTerminal
        )
    }
}
