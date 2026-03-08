package cn.lmcw.bitwebc.core.ui

import android.content.Context
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebView
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import cn.lmcw.bitwebc.core.api.IWebUIProvider

/**
 * 基于 [AlertDialog] 的默认 Web UI 实现，用于 JS 弹窗与错误重试提示。
 */
class DefaultWebUIProvider : IWebUIProvider {

    override fun showJsAlert(view: WebView, url: String?, message: String?, result: JsResult) {
        AlertDialog.Builder(view.context)
            .setMessage(message ?: "")
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
            .setOnCancelListener { result.cancel() }
            .show()
    }

    override fun showJsConfirm(view: WebView, url: String?, message: String?, result: JsResult) {
        AlertDialog.Builder(view.context)
            .setMessage(message ?: "")
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }
            .show()
    }

    override fun showJsPrompt(
        view: WebView,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult
    ) {
        val editText = EditText(view.context).apply {
            setText(defaultValue ?: "")
            setPadding(
                (context.resources.displayMetrics.density * 24).toInt(),
                (context.resources.displayMetrics.density * 16).toInt(),
                (context.resources.displayMetrics.density * 24).toInt(),
                (context.resources.displayMetrics.density * 16).toInt()
            )
        }
        AlertDialog.Builder(view.context)
            .setMessage(message ?: "")
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                result.confirm(editText.text?.toString().orEmpty())
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }
            .show()
    }

    override fun showErrorRetry(context: Context, message: String?, onRetry: () -> Unit) {
        AlertDialog.Builder(context)
            .setMessage(message ?: "加载异常")
            .setPositiveButton(android.R.string.ok) { _, _ -> onRetry() }
            .setCancelable(false)
            .show()
    }
}
