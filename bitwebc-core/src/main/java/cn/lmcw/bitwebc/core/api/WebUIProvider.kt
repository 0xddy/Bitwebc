package cn.lmcw.bitwebc.core.api

import android.content.Context
import android.net.http.SslError
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebView

/** WebView 的 UI 提供者，负责 JS 弹窗、SSL 错误等需用户交互的回调。 */
interface WebUIProvider {

    /** 显示 JS alert，用户选择后调用 [JsResult.confirm] 或 [JsResult.cancel]。 */
    fun showJsAlert(view: WebView, url: String?, message: String?, result: JsResult)

    /** 显示 JS confirm，用户选择后调用 [JsResult.confirm] 或 [JsResult.cancel]。 */
    fun showJsConfirm(view: WebView, url: String?, message: String?, result: JsResult)

    /** 显示 JS prompt，用户选择后调用 [JsPromptResult.confirm] 或 [JsPromptResult.cancel]。 */
    fun showJsPrompt(
        view: WebView,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult
    )

    /** 显示错误页并可重试，用户点重试时调用 [onRetry]。 */
    fun showErrorRetry(context: Context, message: String?, onRetry: () -> Unit)

    /**
     * 显示 SSL 错误，由 UI 决定是否继续。
     * 用户决定后调用 [onDecision]：true 表示继续（内部应调 handler.proceed()），
     * false 表示取消（内部应调 handler.cancel()）。默认用 [showErrorRetry] 并发 BitwebcEvent.SslError，
     * 可重写以自定义 UI。
     */
    fun showSslError(
        view: WebView,
        error: SslError,
        onDecision: (proceed: Boolean) -> Unit
    ) {
        showErrorRetry(view.context, "SSL 证书错误，是否重试？") {
            view.reload()
        }
        onDecision(false)
    }

    /** 显示权限说明，用户确认后调用 [onConfirm]；默认直接调用 [onConfirm]。 */
    fun showPermissionRationale(permission: String, onConfirm: () -> Unit) {
        onConfirm()
    }
}