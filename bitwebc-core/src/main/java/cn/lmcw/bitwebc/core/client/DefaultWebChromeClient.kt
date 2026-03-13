package cn.lmcw.bitwebc.core.client

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cn.lmcw.bitwebc.core.api.WebIndicator
import cn.lmcw.bitwebc.core.api.WebUIProvider
import cn.lmcw.bitwebc.core.event.BitwebcEvent

class DefaultWebChromeClient(
    private val activity: ComponentActivity,
    private val indicator: WebIndicator,
    private val nativeUiDelegate: WebUIProvider? = null,
    private val eventReporter: ((BitwebcEvent) -> Unit)? = null,
    next: WebChromeClient? = null
) : MiddlewareWebChromeBase(next) {

    private var fullScreenContainer: FrameLayout? = null
    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null
    private var fullScreenBackCallback: OnBackPressedCallback? = null
    /** ?????????????????????????????? */
    private var systemBarsVisibleBeforeFullscreen: Boolean = true
    /** ???????????????? */
    private var requestedOrientationBeforeFullscreen: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    /** ???????????? FLAG_KEEP_SCREEN_ON?????????? */
    private var keepScreenOnAddedByUs: Boolean = false

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        indicator.onProgressChanged(newProgress)
        super.onProgressChanged(view, newProgress)
    }

    override fun onJsAlert(
        view: WebView,
        url: String?,
        message: String?,
        result: android.webkit.JsResult
    ): Boolean {
        if (nativeUiDelegate != null) {
            nativeUiDelegate.showJsAlert(view, url, message, result)
        } else {
            AlertDialog.Builder(view.context)
                .setMessage(message ?: "")
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
        }
        return true
    }

    override fun onJsConfirm(
        view: WebView,
        url: String?,
        message: String?,
        result: android.webkit.JsResult
    ): Boolean {
        if (nativeUiDelegate != null) {
            nativeUiDelegate.showJsConfirm(view, url, message, result)
        } else {
            AlertDialog.Builder(view.context)
                .setMessage(message ?: "")
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
        }
        return true
    }

    override fun onJsPrompt(
        view: WebView,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: android.webkit.JsPromptResult
    ): Boolean {
        if (nativeUiDelegate != null) {
            nativeUiDelegate.showJsPrompt(view, url, message, defaultValue, result)
        } else {
            val editText = android.widget.EditText(view.context).apply {
                setText(defaultValue ?: "")
                setPadding(48, 32, 48, 32)
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
        return true
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (view == null || callback == null) {
            super.onShowCustomView(view, callback)
            return
        }
        if (customView != null) {
            // ?????????????????????????            callback.onCustomViewHidden()
            return
        }

        requestedOrientationBeforeFullscreen = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        keepScreenOnAddedByUs = true
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val decor = activity.findViewById<ViewGroup>(Window.ID_ANDROID_CONTENT)
        val container = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.BLACK)
            isFocusable = true
            addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        decor.addView(container)
        container.requestFocus()

        customView = view
        customViewCallback = callback
        fullScreenContainer = container

        val insets = ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.systemBars())
        systemBarsVisibleBeforeFullscreen = (insets?.top ?: 0) > 0 || (insets?.bottom ?: 0) > 0
        WindowInsetsControllerCompat(activity.window, decor).hide(WindowInsetsCompat.Type.systemBars())
        eventReporter?.invoke(BitwebcEvent.FullscreenChanged(fullscreen = true))
        registerFullScreenBackPress()
    }

    override fun onHideCustomView() {
        val decor = activity.findViewById<ViewGroup>(Window.ID_ANDROID_CONTENT)
        fullScreenContainer?.let { container ->
            decor.removeView(container)
        }
        fullScreenContainer = null
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        fullScreenBackCallback?.remove()
        fullScreenBackCallback = null

        activity.requestedOrientation = requestedOrientationBeforeFullscreen
        if (keepScreenOnAddedByUs) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            keepScreenOnAddedByUs = false
        }

        val controller = WindowInsetsControllerCompat(activity.window, decor)
        if (systemBarsVisibleBeforeFullscreen) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        eventReporter?.invoke(BitwebcEvent.FullscreenChanged(fullscreen = false))
        super.onHideCustomView()
    }

    fun release() {
        if (customView != null) {
            onHideCustomView()
        }
        fullScreenBackCallback?.remove()
        fullScreenBackCallback = null
    }

    private fun registerFullScreenBackPress() {
        fullScreenBackCallback?.remove()
        fullScreenBackCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                onHideCustomView()
            }
        }
        fullScreenBackCallback?.let { callback ->
            activity.onBackPressedDispatcher.addCallback(activity, callback)
        }
    }
}
