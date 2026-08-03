package cn.lmcw.bitwebc.download.ext

import java.io.InterruptedIOException
import java.io.OutputStream

/** Parsed metadata for a data URI. The encoded payload is streamed on demand. */
class DataUriHeader internal constructor(
    val mimeType: String,
    val fileName: String,
    val estimatedBytes: Long,
    internal val rawData: CharSequence,
    internal val isBase64: Boolean
)

sealed class DataUriResult {
    data class Ready(val header: DataUriHeader) : DataUriResult()
    data class TooLarge(val estimatedBytes: Long, val maxBytes: Long) : DataUriResult()
    data object InvalidFormat : DataUriResult()
}

object DataUriDecoder {

    private const val SCHEME = "data:"
    private const val FALLBACK_MIME = "text/plain"
    private const val FALLBACK_NAME = "download"
    private const val DECODED_BUFFER_BYTES = 12 * 1024
    private const val MAX_METADATA_CHARS = 16 * 1024
    private const val CANCELLATION_CHECK_INTERVAL_CHARS = 8 * 1024

    const val DEFAULT_MAX_BYTES: Long = 16L * 1024 * 1024

    @JvmOverloads
    fun parse(
        url: String,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        suggestedFileName: String? = null,
        fallbackMimeType: String? = null
    ): DataUriResult {
        require(maxBytes > 0L) { "maxBytes must be greater than 0" }
        if (!url.startsWith(SCHEME, ignoreCase = true)) return DataUriResult.InvalidFormat

        val commaIndex = url.indexOf(',', startIndex = SCHEME.length)
        if (commaIndex < 0) return DataUriResult.InvalidFormat
        if (commaIndex - SCHEME.length > MAX_METADATA_CHARS) {
            return DataUriResult.InvalidFormat
        }

        val meta = url.substring(SCHEME.length, commaIndex)
        val metadata = meta.split(';')
        val isBase64 = metadata.drop(1).any { it.trim().equals("base64", ignoreCase = true) }
        val encodedLength = url.length - commaIndex - 1
        val maxEncodedChars = encodedCharacterLimit(maxBytes, isBase64)
        if (encodedLength.toLong() > maxEncodedChars) {
            return DataUriResult.TooLarge(maxBytes + 1L, maxBytes)
        }
        // Keep a view over the caller's String instead of allocating a second, potentially huge copy.
        val rawData: CharSequence = StringRegion(url, commaIndex + 1)
        val mimeType = metadata.firstOrNull()
            ?.trim()
            ?.takeIf { '/' in it }
            ?: fallbackMimeType
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { '/' in it }
            ?: FALLBACK_MIME

        val estimatedBytes = if (isBase64) {
            estimateBase64Size(rawData)
        } else {
            estimatePercentDecodedSize(rawData)
        }
        if (estimatedBytes > maxBytes) {
            return DataUriResult.TooLarge(estimatedBytes, maxBytes)
        }

        return DataUriResult.Ready(
            DataUriHeader(
                mimeType = mimeType,
                fileName = suggestedFileName?.sanitizeFileName()?.takeIf { it.isNotBlank() }
                    ?: buildFileName(mimeType),
                estimatedBytes = estimatedBytes,
                rawData = rawData,
                isBase64 = isBase64
            )
        )
    }

    @JvmOverloads
    fun streamTo(
        header: DataUriHeader,
        output: OutputStream,
        shouldContinue: () -> Boolean = { true }
    ): Long = if (header.isBase64) {
        streamBase64(header.rawData, output, shouldContinue)
    } else {
        streamPercentDecoded(header.rawData, output, shouldContinue)
    }

    private fun streamBase64(
        raw: CharSequence,
        output: OutputStream,
        shouldContinue: () -> Boolean
    ): Long {
        val decodedBuffer = ByteArray(DECODED_BUFFER_BYTES)
        var decodedBufferSize = 0
        var written = 0L

        fun append(value: Int) {
            decodedBuffer[decodedBufferSize++] = value.toByte()
            if (decodedBufferSize == decodedBuffer.size) {
                output.write(decodedBuffer)
                written += decodedBufferSize
                decodedBufferSize = 0
            }
        }

        var accumulator = 0
        var sextets = 0
        var padding = 0
        var index = 0
        var nextCancellationCheck = 0
        while (index < raw.length) {
            if (index >= nextCancellationCheck) {
                if (!shouldContinue()) throw InterruptedIOException("data URI decoding cancelled")
                nextCancellationCheck = index + CANCELLATION_CHECK_INTERVAL_CHARS
            }
            val char = raw[index++]
            if (char.isWhitespace()) continue
            if (char == '=') {
                padding++
                if (padding > 2) throw IllegalArgumentException("Invalid Base64 padding")
                continue
            }
            if (padding > 0) throw IllegalArgumentException("Invalid Base64 padding")

            val value = decodeBase64Char(char)
            if (value < 0) throw IllegalArgumentException("Invalid Base64 character")
            accumulator = (accumulator shl 6) or value
            sextets++
            if (sextets == 4) {
                append(accumulator shr 16)
                append(accumulator shr 8)
                append(accumulator)
                accumulator = 0
                sextets = 0
            }
        }
        if (!shouldContinue()) throw InterruptedIOException("data URI decoding cancelled")

        when {
            padding == 0 && sextets == 0 -> Unit
            padding == 0 && sextets == 2 -> append(accumulator shr 4)
            padding == 0 && sextets == 3 -> {
                append(accumulator shr 10)
                append(accumulator shr 2)
            }
            padding == 1 && sextets == 3 -> {
                append(accumulator shr 10)
                append(accumulator shr 2)
            }
            padding == 2 && sextets == 2 -> append(accumulator shr 4)
            else -> throw IllegalArgumentException("Invalid Base64 length")
        }

        if (decodedBufferSize > 0) {
            output.write(decodedBuffer, 0, decodedBufferSize)
            written += decodedBufferSize
        }
        output.flush()
        return written
    }

    private fun decodeBase64Char(char: Char): Int = when (char) {
        in 'A'..'Z' -> char - 'A'
        in 'a'..'z' -> char - 'a' + 26
        in '0'..'9' -> char - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> -1
    }

    private fun estimateBase64Size(raw: CharSequence): Long {
        val sextets = raw.count { !it.isWhitespace() && it != '=' }.toLong()
        return (sextets * 6L) / 8L
    }

    private fun encodedCharacterLimit(maxBytes: Long, isBase64: Boolean): Long {
        val multiplier = if (isBase64) 4L else 3L
        val divisor = if (isBase64) 3L else 1L
        val allowance = if (isBase64) {
            minOf(64L * 1024L, maxOf(1024L, maxBytes / 100L))
        } else {
            4096L
        }
        return if (maxBytes > (Long.MAX_VALUE - allowance) / multiplier) {
            Long.MAX_VALUE
        } else {
            (maxBytes * multiplier) / divisor + allowance
        }
    }

    private fun buildFileName(mimeType: String): String {
        val subtype = mimeType.substringAfter('/', "").substringBefore('+').lowercase()
        val extension = when (subtype) {
            "plain" -> "txt"
            "jpeg" -> "jpg"
            "svg" -> "svg"
            "octet-stream" -> "bin"
            else -> subtype.takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
        }
        return if (extension.isNullOrBlank()) FALLBACK_NAME else "$FALLBACK_NAME.$extension"
    }
}

private class StringRegion(
    private val source: String,
    private val startIndex: Int,
    private val endIndex: Int = source.length
) : CharSequence {
    init {
        require(startIndex in 0..endIndex && endIndex <= source.length)
    }

    override val length: Int get() = endIndex - startIndex

    override fun get(index: Int): Char {
        require(index in indices)
        return source[startIndex + index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
        require(startIndex in 0..endIndex && endIndex <= length)
        return StringRegion(source, this.startIndex + startIndex, this.startIndex + endIndex)
    }

    override fun toString(): String = source.substring(startIndex, endIndex)
}
