package cn.lmcw.bitwebc.filechooser.accept

import java.util.Locale

/** Resolves the HTML `accept` attribute into MIME types understood by Android pickers. */
object FileChooserAcceptResolver {

    private val mimeTypePattern = Regex(
        "^(?:\\*/\\*|[a-z0-9][a-z0-9!#$&^_.+-]*/(?:\\*|[a-z0-9][a-z0-9!#$&^_.+-]*))$"
    )

    /**
     * WebView implementations may return one entry per type or put a comma-separated list in a
     * single entry. Invalid tokens are ignored, just like invalid HTML `accept` specifiers.
     * Extensions are converted to MIME types because Android's document contracts only accept
     * MIME types. An extension unknown to this resolver falls back to Android's any-file MIME
     * wildcard so the requested file does not become impossible to select.
     */
    fun normalizeAcceptTypes(raw: Array<String>?): List<String> {
        val normalized = raw.orEmpty()
            .asSequence()
            .flatMap { it.splitToSequence(',') }
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotEmpty() }
            .mapNotNull(::normalizeToken)
            .distinct()
            .toList()

        if (normalized.isEmpty() || "*/*" in normalized) return listOf("*/*")
        return normalized
    }

    /** Returns a media family only when every accepted type belongs to that same family. */
    fun resolveMediaType(acceptTypes: List<String>): MediaType {
        if (acceptTypes.isEmpty()) return MediaType.UNKNOWN

        val families = acceptTypes.map { mimeType ->
            when {
                mimeType.startsWith("image/") -> MediaType.IMAGE
                mimeType.startsWith("video/") -> MediaType.VIDEO
                mimeType.startsWith("audio/") -> MediaType.AUDIO
                else -> MediaType.UNKNOWN
            }
        }.distinct()

        return families.singleOrNull()?.takeUnless { it == MediaType.UNKNOWN } ?: MediaType.UNKNOWN
    }

    private fun normalizeToken(token: String): String? {
        if (token.startsWith('.')) {
            val extension = token.drop(1).trim()
            if (extension.isEmpty() || extension.any { !it.isLetterOrDigit() }) return null
            return extensionMimeTypes[extension] ?: "*/*"
        }

        // MIME parameters are not valid accept specifiers, but stripping them is a safe and useful
        // compatibility measure for pages that send values such as "image/jpeg; quality=90".
        val mimeType = token.substringBefore(';').trim()
        return mimeType.takeIf(mimeTypePattern::matches)
    }

    private val extensionMimeTypes = mapOf(
        // Images
        "avif" to "image/avif",
        "bmp" to "image/bmp",
        "gif" to "image/gif",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "ico" to "image/x-icon",
        "jpeg" to "image/jpeg",
        "jpg" to "image/jpeg",
        "png" to "image/png",
        "svg" to "image/svg+xml",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        "webp" to "image/webp",

        // Video
        "3gp" to "video/3gpp",
        "3gpp" to "video/3gpp",
        "avi" to "video/x-msvideo",
        "m4v" to "video/x-m4v",
        "mkv" to "video/x-matroska",
        "mov" to "video/quicktime",
        "mp4" to "video/mp4",
        "mpeg" to "video/mpeg",
        "mpg" to "video/mpeg",
        "ts" to "video/mp2t",
        "webm" to "video/webm",

        // Audio
        "aac" to "audio/aac",
        "amr" to "audio/amr",
        "flac" to "audio/flac",
        "m4a" to "audio/mp4",
        "mid" to "audio/midi",
        "midi" to "audio/midi",
        "mp3" to "audio/mpeg",
        "oga" to "audio/ogg",
        "ogg" to "audio/ogg",
        "opus" to "audio/opus",
        "wav" to "audio/wav",

        // Documents and archives
        "7z" to "application/x-7z-compressed",
        "apk" to "application/vnd.android.package-archive",
        "csv" to "text/csv",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "gz" to "application/gzip",
        "htm" to "text/html",
        "html" to "text/html",
        "json" to "application/json",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odt" to "application/vnd.oasis.opendocument.text",
        "pdf" to "application/pdf",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "rar" to "application/vnd.rar",
        "rtf" to "application/rtf",
        "tar" to "application/x-tar",
        "txt" to "text/plain",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "xml" to "application/xml",
        "zip" to "application/zip"
    )

    enum class MediaType {
        IMAGE,
        VIDEO,
        AUDIO,
        UNKNOWN
    }
}
