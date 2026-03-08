package cn.lmcw.bitwebc.core.utils

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cookie 与 WebView 缓存/存储的同步与清理工具。
 */
object BitwebcCookieManager {

    /**
     * 在 IO 线程为指定 URL 设置 Cookie 并执行 flush，保证写入完成。
     */
    suspend fun syncCookie(url: String, cookieStr: String) = withContext(Dispatchers.IO) {
        CookieManager.getInstance().setCookie(url, cookieStr)
        CookieManager.getInstance().flush()
    }

    /**
     * 清除 Cookie、DOM Storage 及（当 [webView] 非 null 时）该 WebView 的缓存/历史/表单/SSL 偏好。
     * 若仅传入 [context]，则只清除全局 Cookie 与 Web Storage，不包含单个 WebView 实例数据。
     */
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
