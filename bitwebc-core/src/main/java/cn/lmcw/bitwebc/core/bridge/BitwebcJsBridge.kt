package cn.lmcw.bitwebc.core.bridge

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.WebView

private val BRIDGE_NAME_REGEX = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")

/**
 * JSBridge 安全注入与脚本执行扩展。
 */
object BitwebcJsBridge {

    /**
     * 仅在 API 17+ 注入，且限制 bridge 名称格式，避免非法对象名导致脚本异常。
     */
    @SuppressLint("JavascriptInterface")
    fun injectSafely(webView: WebView, bridgeName: String, bridge: Any): Boolean {
        if (!BRIDGE_NAME_REGEX.matches(bridgeName)) return false

        webView.removeJavascriptInterface("searchBoxJavaBridge_")
        webView.removeJavascriptInterface("accessibility")
        webView.removeJavascriptInterface("accessibilityTraversal")
        webView.addJavascriptInterface(bridge, bridgeName)
        return true
    }
}

/**
 * 安全执行 JS：新系统走 evaluateJavascript，旧系统降级 loadUrl。
 */
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
