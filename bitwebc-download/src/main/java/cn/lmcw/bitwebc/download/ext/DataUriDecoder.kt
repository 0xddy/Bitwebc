package cn.lmcw.bitwebc.download.ext

import android.util.Base64
import android.webkit.MimeTypeMap
import java.io.OutputStream

/**
 * `data:` URI 解析后的元信息（不持有解码数据，零内存占用）。
 *
 * 格式规范: `data:[<mediatype>][;base64],<data>`
 */
class DataUriHeader internal constructor(
    val mimeType: String,
    val fileName: String,
    val estimatedBytes: Long,
    internal val rawData: String,
    internal val isBase64: Boolean
)

sealed class DataUriResult {
    data class Ready(val header: DataUriHeader) : DataUriResult()
    data class TooLarge(val estimatedBytes: Long, val maxBytes: Long) : DataUriResult()
    data object InvalidFormat : DataUriResult()
}

object DataUriDecoder {

    private const val SCHEME = "data:"
    private const val FALLBACK_MIME = "application/octet-stream"
    private const val FALLBACK_NAME = "download"

    /** 默认上限 50 MB，防止 Base64 解码导致 OOM。 */
    const val DEFAULT_MAX_BYTES: Long = 50L * 1024 * 1024

    /** 流式写入时每批解码的 Base64 字符数（必须为 4 的倍数）。 */
    private const val CHUNK_CHARS = 4 * 4096  // 16384 chars → 12288 bytes

    /**
     * 解析 `data:` URI 的元信息。
     *
     * 只做字符串切分和大小估算，不分配解码缓冲区；
     * 超过 [maxBytes] 立即返回 [DataUriResult.TooLarge]。
     */
    fun parse(url: String, maxBytes: Long = DEFAULT_MAX_BYTES): DataUriResult {
        if (!url.startsWith(SCHEME, ignoreCase = true)) return DataUriResult.InvalidFormat

        val body = url.substring(SCHEME.length)
        val commaIndex = body.indexOf(',')
        if (commaIndex < 0) return DataUriResult.InvalidFormat

        val meta = body.substring(0, commaIndex)
        val rawData = body.substring(commaIndex + 1)

        val isBase64 = meta.endsWith(";base64", ignoreCase = true)
        val mimeType = meta
            .removeSuffix(";base64")
            .removeSuffix(";BASE64")
            .trim()
            .ifEmpty { FALLBACK_MIME }

        val estimatedBytes = if (isBase64) estimateBase64Size(rawData) else rawData.length.toLong()
        if (maxBytes > 0 && estimatedBytes > maxBytes) {
            return DataUriResult.TooLarge(estimatedBytes, maxBytes)
        }

        val header = DataUriHeader(
            mimeType = mimeType,
            fileName = buildFileName(mimeType),
            estimatedBytes = estimatedBytes,
            rawData = rawData,
            isBase64 = isBase64
        )
        return DataUriResult.Ready(header)
    }

    /**
     * 将 [header] 中的数据**流式解码**写入 [output]。
     *
     * Base64 模式下按 [CHUNK_CHARS] 分片，每片独立解码后写入，
     * 峰值内存仅约 16 KB（一个分片的 char[] + 解码后的 byte[]）。
     *
     * @return 实际写入的字节数。
     */
    fun streamTo(header: DataUriHeader, output: OutputStream): Long {
        return if (header.isBase64) {
            streamBase64(header.rawData, output)
        } else {
            streamPlainText(header.rawData, output)
        }
    }

    private fun streamBase64(raw: String, output: OutputStream): Long {
        var offset = 0
        var written = 0L
        while (offset < raw.length) {
            val end = (offset + CHUNK_CHARS).coerceAtMost(raw.length)
            val chunk = raw.substring(offset, end)
            val decoded = Base64.decode(chunk, Base64.DEFAULT)
            output.write(decoded)
            written += decoded.size
            offset = end
        }
        output.flush()
        return written
    }

    private fun streamPlainText(raw: String, output: OutputStream): Long {
        val decoded = java.net.URLDecoder.decode(raw, "UTF-8")
        val bytes = decoded.toByteArray(Charsets.UTF_8)
        output.write(bytes)
        return bytes.size.toLong()
    }

    /** Base64: 每 4 字符 → 3 字节，末尾 padding '=' 不计入。 */
    private fun estimateBase64Size(raw: String): Long {
        val len = raw.length.toLong()
        val padding = when {
            raw.endsWith("==") -> 2L
            raw.endsWith("=") -> 1L
            else -> 0L
        }
        return (len * 3 / 4) - padding
    }

    private fun buildFileName(mimeType: String): String {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        return if (ext.isNullOrBlank()) FALLBACK_NAME else "$FALLBACK_NAME.$ext"
    }
}
