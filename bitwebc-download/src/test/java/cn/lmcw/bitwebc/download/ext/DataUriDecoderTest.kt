package cn.lmcw.bitwebc.download.ext

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException

class DataUriDecoderTest {

    @Test
    fun `oversized whitespace base64 is rejected by encoded size limit`() {
        val result = DataUriDecoder.parse(
            "data:application/octet-stream;base64," + " ".repeat(10_000),
            maxBytes = 100
        )

        assertTrue(result is DataUriResult.TooLarge)
    }

    @Test
    fun percentDecodingPreservesPlusAndUsesSuggestedName() {
        val result = DataUriDecoder.parse(
            url = "data:text/plain,a+b%20c",
            suggestedFileName = "../unsafe?.txt"
        ) as DataUriResult.Ready
        val output = ByteArrayOutputStream()

        val written = DataUriDecoder.streamTo(result.header, output)

        assertEquals("a+b c", output.toString(Charsets.UTF_8.name()))
        assertEquals(5L, written)
        assertEquals(5L, result.header.estimatedBytes)
        assertEquals("unsafe_.txt", result.header.fileName)
    }

    @Test
    fun base64DecoderSupportsWhitespaceAndUnpaddedInput() {
        val result = DataUriDecoder.parse("data:text/plain;base64,SGVs\n bG8r") as DataUriResult.Ready
        val output = ByteArrayOutputStream()

        val written = DataUriDecoder.streamTo(result.header, output)

        assertEquals(6L, written)
        assertArrayEquals("Hello+".toByteArray(), output.toByteArray())
    }

    @Test
    fun appliesLimitToDecodedSize() {
        val result = DataUriDecoder.parse("data:text/plain,%41%42%43", maxBytes = 2)

        assertTrue(result is DataUriResult.TooLarge)
        assertEquals(3L, (result as DataUriResult.TooLarge).estimatedBytes)
    }

    @Test
    fun mimeHintProvidesExtensionWhenDataUriOmitsMediaType() {
        val result = DataUriDecoder.parse(
            url = "data:,payload",
            fallbackMimeType = "image/png"
        ) as DataUriResult.Ready

        assertEquals("image/png", result.header.mimeType)
        assertEquals("download.png", result.header.fileName)
    }

    @Test
    fun omittedMediaTypeUsesTheDataUriTextDefault() {
        val result = DataUriDecoder.parse("data:,payload") as DataUriResult.Ready

        assertEquals("text/plain", result.header.mimeType)
        assertEquals("download.txt", result.header.fileName)
    }

    @Test
    fun explicitMediaTypeTakesPrecedenceOverFallbackHint() {
        val result = DataUriDecoder.parse(
            url = "data:image/png,payload",
            fallbackMimeType = "text/plain"
        ) as DataUriResult.Ready

        assertEquals("image/png", result.header.mimeType)
        assertEquals("download.png", result.header.fileName)
    }

    @Test
    fun streamingCanBeCancelled() {
        val result = DataUriDecoder.parse(
            "data:text/plain," + "a".repeat(20_000)
        ) as DataUriResult.Ready
        var checks = 0

        assertThrows(InterruptedIOException::class.java) {
            DataUriDecoder.streamTo(result.header, ByteArrayOutputStream()) {
                ++checks < 2
            }
        }
    }

    @Test
    fun rejectsOversizedMetadataBeforeSplittingIt() {
        val result = DataUriDecoder.parse(
            "data:" + "x".repeat(16 * 1024 + 1) + ",payload"
        )

        assertTrue(result is DataUriResult.InvalidFormat)
    }

    @Test
    fun isolatedSurrogateEstimateMatchesStreamedReplacementByte() {
        val result = DataUriDecoder.parse("data:text/plain,\uD800") as DataUriResult.Ready
        val output = ByteArrayOutputStream()

        val written = DataUriDecoder.streamTo(result.header, output)

        assertEquals(1L, result.header.estimatedBytes)
        assertEquals(result.header.estimatedBytes, written)
        assertArrayEquals(byteArrayOf('?'.code.toByte()), output.toByteArray())
    }
}
