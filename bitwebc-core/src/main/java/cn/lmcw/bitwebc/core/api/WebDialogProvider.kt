package cn.lmcw.bitwebc.core.api

import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebView

/** Session-owned UI for JavaScript alert, confirm, and prompt dialogs. */
interface WebDialogProvider {

    fun showAlert(view: WebView, url: String?, message: String?, result: JsResult)

    fun showConfirm(view: WebView, url: String?, message: String?, result: JsResult)

    fun showPrompt(
        view: WebView,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult
    )

    /** Cancels dialogs/results bound to the current renderer while keeping the provider reusable. */
    fun cancelPending() = Unit

    /** Cancels pending work and releases Activity references. Must be idempotent. */
    fun release() {
        cancelPending()
    }
}
