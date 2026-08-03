package cn.lmcw.bitwebc.core.utils

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.annotation.MainThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Explicit APIs for process-shared browsing data and state owned by one WebView. */
object BitwebcBrowsingData {

    /** Stores and flushes a cookie, returning only after WebView has accepted the operation. */
    suspend fun setCookie(url: String, value: String): Boolean =
        withContext(Dispatchers.Main.immediate) {
            require(url.isNotBlank()) { "url must not be blank" }
            val manager = CookieManager.getInstance()
            suspendCancellableCoroutine { continuation ->
                manager.setCookie(url, value) { accepted ->
                    manager.flush()
                    if (continuation.isActive) continuation.resume(accepted)
                }
            }
        }

    /**
     * Clears process-wide cookies and DOM storage. Completion means the asynchronous cookie
     * removal callback has run and the cookie store has been flushed.
     */
    suspend fun clearSharedStorage() = withContext(Dispatchers.Main.immediate) {
        val manager = CookieManager.getInstance()
        suspendCancellableCoroutine { continuation ->
            manager.removeAllCookies {
                manager.flush()
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        WebStorage.getInstance().deleteAllData()
    }

    /** Clears history and transient form/find state belonging to this WebView only. */
    @MainThread
    fun clearViewState(webView: WebView) {
        webView.clearHistory()
        webView.clearFormData()
        webView.clearMatches()
    }

    /**
     * Clears the application-wide WebView HTTP resource cache. Although Android exposes this on
     * a WebView instance, the cache is shared by every WebView in the application.
     */
    @MainThread
    fun clearSharedHttpCache(webView: WebView, includeDiskFiles: Boolean = true) {
        webView.clearCache(includeDiskFiles)
    }

    /** Clears application-wide remembered SSL certificate decisions. */
    @MainThread
    fun clearSharedSslPreferences(webView: WebView) {
        webView.clearSslPreferences()
    }
}
