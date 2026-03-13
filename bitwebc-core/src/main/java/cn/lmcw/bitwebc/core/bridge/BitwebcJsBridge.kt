package cn.lmcw.bitwebc.core.bridge

import android.annotation.SuppressLint
import android.webkit.WebView
import java.util.WeakHashMap

private val BRIDGE_NAME_REGEX = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")

/** JSBridge 注入，前端通过 window[name].method() 调用 */
object BitwebcJsBridge {

    private val injectedBridges = WeakHashMap<WebView, MutableSet<String>>()

    @SuppressLint("JavascriptInterface")
    fun injectSafely(webView: WebView, bridgeName: String, bridge: Any): Boolean {
        if (!BRIDGE_NAME_REGEX.matches(bridgeName)) return false

        webView.removeJavascriptInterface("searchBoxJavaBridge_")
        webView.removeJavascriptInterface("accessibility")
        webView.removeJavascriptInterface("accessibilityTraversal")
        webView.addJavascriptInterface(bridge, bridgeName)
        
        injectedBridges.getOrPut(webView) { mutableSetOf() }.add(bridgeName)
        return true
    }

    fun removeSafely(webView: WebView) {
        val names = injectedBridges.remove(webView)
        if (names != null) {
            for (name in names) {
                webView.removeJavascriptInterface(name)
            }
        }
    }
}

/** 执行 JS，旧系统降级 loadUrl */
fun WebView.evaluateJavascriptSafe(
    script: String,
    onResult: ((String?) -> Unit)? = null
) {
    post {
        evaluateJavascript(script) { value ->
            onResult?.invoke(value)
        }
    }
}
