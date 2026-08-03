package cn.lmcw.bitwebc.download.ext

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNameExtensionsTest {

    @Test
    fun contentDispositionTakesPriorityOverRedirectUrl() {
        val result = resolveDownloadFileName(
            originalUrl = "https://example.test/original.zip",
            finalUrl = "https://cdn.example.test/redirected.zip",
            contentDisposition = "attachment; filename=server-name.pdf",
            mimeType = "application/pdf"
        )

        assertEquals("server-name.pdf", result)
    }

    @Test
    fun extendedFilenameSupportsUtf8AndPreservesPlus() {
        val result = extractFileNameFromContentDisposition(
            "attachment; filename=fallback.txt; filename*=UTF-8''%E6%B5%8B%E8%AF%95+v1.txt"
        )

        assertEquals("测试+v1.txt", result)
    }

    @Test
    fun quotedFilenameMayContainSemicolon() {
        val result = extractFileNameFromContentDisposition(
            "attachment; filename=\"report; final.pdf\""
        )

        assertEquals("report; final.pdf", result)
    }
}
