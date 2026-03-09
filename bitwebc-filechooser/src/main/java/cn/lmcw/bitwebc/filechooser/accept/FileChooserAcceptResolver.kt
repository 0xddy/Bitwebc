package cn.lmcw.bitwebc.filechooser.accept

import java.util.Locale

/** 解析 input[type=file] 的 accept/mode */
object FileChooserAcceptResolver {

    fun normalizeAcceptTypes(raw: Array<String>?): List<String> {
        return raw?.mapNotNull { value ->
            value.trim().takeIf { it.isNotBlank() }
        }?.ifEmpty { null } ?: listOf("*/*")
    }

    fun resolveMediaType(acceptTypes: List<String>): MediaType {
        val joined = acceptTypes.joinToString(",").lowercase(Locale.US)
        return when {
            joined.contains("image/") -> MediaType.IMAGE
            joined.contains("video/") -> MediaType.VIDEO
            joined.contains("audio/") -> MediaType.AUDIO
            else -> MediaType.UNKNOWN
        }
    }

    enum class MediaType {
        IMAGE, VIDEO, AUDIO, UNKNOWN
    }
}
