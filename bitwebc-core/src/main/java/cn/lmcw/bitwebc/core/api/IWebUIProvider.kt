package cn.lmcw.bitwebc.core.api

import android.content.Context
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebView

/**
 * WebView 相关 UI 交互的抽象：JS 弹窗、错误重试提示、权限解释等，便于宿主定制。
 */
interface IWebUIProvider {

    /**
     * 展示 JS alert；完成后须调用 [JsResult.confirm] 或 [JsResult.cancel]。
     */
    fun showJsAlert(view: WebView, url: String?, message: String?, result: JsResult)

    /**
     * 展示 JS confirm；完成后须调用 [JsResult.confirm] 或 [JsResult.cancel]。
     */
    fun showJsConfirm(view: WebView, url: String?, message: String?, result: JsResult)

    /**
     * 展示 JS prompt；完成后须调用 [JsPromptResult.confirm] 或 [JsPromptResult.cancel]。
     */
    fun showJsPrompt(
        view: WebView,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult
    )

    /**
     * 展示错误重试提示（如网络错误、SSL、渲染进程退出）；用户确认后执行 [onRetry]。
     */
    fun showErrorRetry(context: Context, message: String?, onRetry: () -> Unit)

    /**
     * 可选：权限解释/说明弹窗，用户确认后执行 [onConfirm]。
     * 默认直接调用 [onConfirm]。
     */
    fun showPermissionRationale(permission: String, onConfirm: () -> Unit) {
        onConfirm()
    }
}
