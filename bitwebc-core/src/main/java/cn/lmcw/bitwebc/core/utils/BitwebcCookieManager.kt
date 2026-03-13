package cn.lmcw.bitwebc.core.utils

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cookie 与 WebView 缓存同步/清理 */
object BitwebcCookieManager {

    suspend fun syncCookie(url: String, cookieStr: String) = withContext(Dispatchers.IO) {
        CookieManager.getInstance().setCookie(url, cookieStr)
        CookieManager.getInstance().flush()
    }

    @JvmStatic
    fun clearAllData(context: Context, webView: WebView? = null) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies { cookieManager.flush() }
        WebStorage.getInstance().deleteAllData()

        webView?.let { wv ->
            wv.clearCache(true)
            wv.clearFormData()
            wv.clearHistory()
            wv.clearSslPreferences()
            wv.clearMatches()
        }
    }
}
