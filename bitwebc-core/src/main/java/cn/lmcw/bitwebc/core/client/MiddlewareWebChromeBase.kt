package cn.lmcw.bitwebc.core.client

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

open class MiddlewareWebChromeBase(
    private val next: WebChromeClient? = null
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        next?.onProgressChanged(view, newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        next?.onReceivedTitle(view, title)
    }

    override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
        next?.onReceivedIcon(view, icon)
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        return next?.onConsoleMessage(consoleMessage) ?: false
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?
    ) {
        next?.onGeolocationPermissionsShowPrompt(origin, callback)
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        next?.onPermissionRequest(request)
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        next?.onShowCustomView(view, callback)
    }

    override fun onHideCustomView() {
        next?.onHideCustomView()
    }

    override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        return next?.onJsAlert(view, url, message, result) ?: false
    }

    override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        return next?.onJsConfirm(view, url, message, result) ?: false
    }

    override fun onJsPrompt(
        view: WebView,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult
    ): Boolean {
        return next?.onJsPrompt(view, url, message, defaultValue, result) ?: false
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        return next?.onShowFileChooser(webView, filePathCallback, fileChooserParams) ?: false
    }

    open fun nextChromeClient(): WebChromeClient? = next
}
