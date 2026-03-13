package cn.lmcw.bitwebc.core.pool

import android.webkit.WebView
import cn.lmcw.bitwebc.core.api.WebViewRecycler

/** [WebViewRecycler] ????????? [BitwebcWebViewPool.recycle]? */
internal class BitwebcWebViewPoolRecycler(
    private val policy: BitwebcWebViewPool.RecyclePolicy = BitwebcWebViewPool.RecyclePolicy()
) : WebViewRecycler {
    override fun recycle(webView: WebView) {
        BitwebcWebViewPool.recycle(webView, policy)
    }
}
