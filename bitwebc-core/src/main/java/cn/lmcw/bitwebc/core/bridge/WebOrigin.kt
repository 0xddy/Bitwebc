package cn.lmcw.bitwebc.core.bridge

import java.net.IDN
import java.net.URI
import java.util.Locale

/** Utilities for turning a URL into the exact HTTP(S) origin used by WebView policies. */
object WebOrigin {

    /** Returns a normalized origin for a full URL, or `null` when it has no safe HTTP(S) origin. */
    @JvmStatic
    fun fromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching { normalize(URI(url.trim()), requireOriginOnly = false) }.getOrNull()
    }

    /**
     * Normalizes an explicitly configured origin. Paths, credentials, queries and fragments are rejected.
     */
    @JvmStatic
    fun normalizeRule(origin: String?): String? {
        if (origin.isNullOrBlank()) return null
        return runCatching { normalize(URI(origin.trim()), requireOriginOnly = true) }.getOrNull()
    }

    @JvmStatic
    fun matches(url: String?, allowedOrigins: Set<String>): Boolean {
        val actual = fromUrl(url) ?: return false
        return actual in allowedOrigins.mapNotNull(::normalizeRule)
    }

    private fun normalize(uri: URI, requireOriginOnly: Boolean): String? {
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
        if (scheme != "https" && scheme != "http") return null
        if (uri.rawUserInfo != null) return null
        if (requireOriginOnly && (uri.rawQuery != null || uri.rawFragment != null)) return null
        if (requireOriginOnly && !uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null

        val rawHost = uri.host ?: return null
        val host = normalizeHost(rawHost) ?: return null
        val port = uri.port
        if (port !in -1..65535 || port == 0) return null
        val effectivePort = when {
            port == -1 && scheme == "https" -> 443
            port == -1 && scheme == "http" -> 80
            else -> port
        }
        val defaultPort = (scheme == "https" && effectivePort == 443) ||
            (scheme == "http" && effectivePort == 80)
        return buildString {
            append(scheme)
            append("://")
            if (host.contains(':')) append('[')
            append(host)
            if (host.contains(':')) append(']')
            if (!defaultPort) {
                append(':')
                append(effectivePort)
            }
        }
    }

    private fun normalizeHost(rawHost: String): String? {
        val host = rawHost.removePrefix("[").removeSuffix("]")
        if (host.isBlank()) return null
        return if (host.contains(':')) {
            host.lowercase(Locale.US)
        } else {
            runCatching { IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.US) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        }
    }
}
