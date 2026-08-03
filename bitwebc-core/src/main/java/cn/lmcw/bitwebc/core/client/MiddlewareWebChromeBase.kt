package cn.lmcw.bitwebc.core.client

import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebStorage
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

    override fun onReceivedTouchIconUrl(view: WebView, url: String?, precomposed: Boolean) {
        val nextClient = next
        if (nextClient != null) {
            nextClient.onReceivedTouchIconUrl(view, url, precomposed)
        } else {
            super.onReceivedTouchIconUrl(view, url, precomposed)
        }
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        return next?.onConsoleMessage(consoleMessage) ?: false
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onConsoleMessage(message: String?, lineNumber: Int, sourceID: String?) {
        val nextClient = next
        if (nextClient != null) {
            nextClient.onConsoleMessage(message, lineNumber, sourceID)
        } else {
            super.onConsoleMessage(message, lineNumber, sourceID)
        }
    }

    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message
    ): Boolean {
        val nextClient = next
        return if (nextClient != null) {
            nextClient.onCreateWindow(view, isDialog, isUserGesture, resultMsg)
        } else {
            super.onCreateWindow(view, isDialog, isUserGesture, resultMsg)
        }
    }

    override fun onCloseWindow(window: WebView) {
        val nextClient = next
        if (nextClient != null) {
            nextClient.onCloseWindow(window)
        } else {
            super.onCloseWindow(window)
        }
    }

    override fun onRequestFocus(view: WebView) {
        val nextClient = next
        if (nextClient != null) {
            nextClient.onRequestFocus(view)
        } else {
            super.onRequestFocus(view)
        }
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?
    ) {
        val nextClient = next
        if (nextClient != null) {
            nextClient.onGeolocationPermissionsShowPrompt(origin, callback)
        } else {
            callback?.invoke(origin, false, false)
        }
    }

    override fun onGeolocationPermissionsHidePrompt() {
        next?.onGeolocationPermissionsHidePrompt()
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        val nextClient = next
        if (nextClient != null) {
            nextClient.onPermissionRequest(request)
        } else {
            request.deny()
        }
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest) {
        next?.onPermissionRequestCanceled(request)
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        next?.onShowCustomView(view, callback)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onShowCustomView(
        view: View?,
        requestedOrientation: Int,
        callback: CustomViewCallback?
    ) {
        val nextClient = next
        if (nextClient != null) {
            nextClient.onShowCustomView(view, requestedOrientation, callback)
        } else {
            super.onShowCustomView(view, requestedOrientation, callback)
        }
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

    override fun onJsBeforeUnload(
        view: WebView,
        url: String?,
        message: String?,
        result: JsResult
    ): Boolean {
        val nextClient = next
        return if (nextClient != null) {
            nextClient.onJsBeforeUnload(view, url, message, result)
        } else {
            super.onJsBeforeUnload(view, url, message, result)
        }
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

    override fun getDefaultVideoPoster(): Bitmap? {
        val nextClient = next
        return if (nextClient != null) {
            nextClient.defaultVideoPoster
        } else {
            super.getDefaultVideoPoster()
        }
    }

    override fun getVideoLoadingProgressView(): View? {
        val nextClient = next
        return if (nextClient != null) {
            nextClient.videoLoadingProgressView
        } else {
            super.getVideoLoadingProgressView()
        }
    }

    override fun getVisitedHistory(callback: ValueCallback<Array<String>>?) {
        val nextClient = next
        if (nextClient != null) {
            nextClient.getVisitedHistory(callback)
        } else {
            super.getVisitedHistory(callback)
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onExceededDatabaseQuota(
        url: String?,
        databaseIdentifier: String?,
        quota: Long,
        estimatedDatabaseSize: Long,
        totalQuota: Long,
        quotaUpdater: WebStorage.QuotaUpdater?
    ) {
        val nextClient = next
        if (nextClient != null) {
            nextClient.onExceededDatabaseQuota(
                url,
                databaseIdentifier,
                quota,
                estimatedDatabaseSize,
                totalQuota,
                quotaUpdater
            )
        } else {
            super.onExceededDatabaseQuota(
                url,
                databaseIdentifier,
                quota,
                estimatedDatabaseSize,
                totalQuota,
                quotaUpdater
            )
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onJsTimeout(): Boolean {
        val nextClient = next
        return if (nextClient != null) {
            nextClient.onJsTimeout()
        } else {
            super.onJsTimeout()
        }
    }

    open fun nextChromeClient(): WebChromeClient? = next
}
