package cn.lmcw.bitwebc.download.ext

import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.charset.Charset

/** Percent-decodes without treating '+' as a space. */
internal fun decodePercentEncoded(
    value: String,
    charset: Charset = Charsets.UTF_8
): String {
    val output = ByteArrayOutputStream(value.length)
    streamPercentDecoded(value, output)
    return output.toByteArray().toString(charset)
}

internal fun streamPercentDecoded(
    value: CharSequence,
    output: OutputStream,
    shouldContinue: () -> Boolean = { true }
): Long {
    val decodedBuffer = ByteArray(DECODED_BUFFER_BYTES)
    var decodedBufferSize = 0
    var index = 0
    var written = 0L

    fun append(value: Int) {
        decodedBuffer[decodedBufferSize++] = value.toByte()
        if (decodedBufferSize == decodedBuffer.size) {
            output.write(decodedBuffer)
            written += decodedBufferSize
            decodedBufferSize = 0
        }
    }

    fun appendUtf8(codePoint: Int) {
        when {
            codePoint <= 0x7f -> append(codePoint)
            codePoint <= 0x7ff -> {
                append(0xc0 or (codePoint shr 6))
                append(0x80 or (codePoint and 0x3f))
            }
            codePoint in 0xd800..0xdfff -> append('?'.code)
            codePoint <= 0xffff -> {
                append(0xe0 or (codePoint shr 12))
                append(0x80 or ((codePoint shr 6) and 0x3f))
                append(0x80 or (codePoint and 0x3f))
            }
            else -> {
                append(0xf0 or (codePoint shr 18))
                append(0x80 or ((codePoint shr 12) and 0x3f))
                append(0x80 or ((codePoint shr 6) and 0x3f))
                append(0x80 or (codePoint and 0x3f))
            }
        }
    }

    var nextCancellationCheck = 0
    while (index < value.length) {
        if (index >= nextCancellationCheck) {
            if (!shouldContinue()) throw InterruptedIOException("data URI decoding cancelled")
            nextCancellationCheck = index + CANCELLATION_CHECK_INTERVAL_CHARS
        }

        val char = value[index]
        if (char == '%' && index + 2 < value.length) {
            val high = value[index + 1].digitToIntOrNull(16)
            val low = value[index + 2].digitToIntOrNull(16)
            if (high != null && low != null) {
                append((high shl 4) or low)
                index += 3
                continue
            }
        }

        val codePoint = Character.codePointAt(value, index)
        appendUtf8(codePoint)
        index += Character.charCount(codePoint)
    }
    if (!shouldContinue()) throw InterruptedIOException("data URI decoding cancelled")
    if (decodedBufferSize > 0) {
        output.write(decodedBuffer, 0, decodedBufferSize)
        written += decodedBufferSize
    }
    output.flush()
    return written
}

internal fun estimatePercentDecodedSize(value: CharSequence): Long {
    var index = 0
    var size = 0L
    while (index < value.length) {
        if (value[index] == '%' && index + 2 < value.length &&
            value[index + 1].digitToIntOrNull(16) != null &&
            value[index + 2].digitToIntOrNull(16) != null
        ) {
            size++
            index += 3
            continue
        }

        val codePoint = Character.codePointAt(value, index)
        size += when {
            codePoint <= 0x7f -> 1
            codePoint <= 0x7ff -> 2
            codePoint in 0xd800..0xdfff -> 1
            codePoint <= 0xffff -> 3
            else -> 4
        }
        index += Character.charCount(codePoint)
    }
    return size
}

private const val DECODED_BUFFER_BYTES = 12 * 1024
private const val CANCELLATION_CHECK_INTERVAL_CHARS = 8 * 1024
