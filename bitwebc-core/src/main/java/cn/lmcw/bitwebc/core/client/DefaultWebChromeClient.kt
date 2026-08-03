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
import cn.lmcw.bitwebc.core.api.WebDialogProvider
import cn.lmcw.bitwebc.core.event.BitwebcEvent

internal class DefaultWebChromeClient(
    private val activity: ComponentActivity,
    private val indicator: WebIndicator,
    private val dialogProvider: WebDialogProvider? = null,
    private val eventReporter: ((BitwebcEvent) -> Unit)? = null,
    next: WebChromeClient? = null
) : MiddlewareWebChromeBase(next) {

    private var fullScreenContainer: FrameLayout? = null
    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null
    private var fullScreenBackCallback: OnBackPressedCallback? = null
    private var activeJsDialog: AlertDialog? = null
    private var cancelActiveJsResult: (() -> Unit)? = null
    /** 进入全屏前系统栏是否可见。 */
    private var systemBarsVisibleBeforeFullscreen: Boolean = true
    /** 进入全屏前的屏幕方向。 */
    private var requestedOrientationBeforeFullscreen: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    /** FLAG_KEEP_SCREEN_ON 是否由本类添加。 */
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
        if (dialogProvider != null) {
            dialogProvider.showAlert(view, url, message, result)
        } else {
            dismissActiveJsDialog()
            lateinit var dialog: AlertDialog
            dialog = AlertDialog.Builder(view.context)
                .setMessage(message ?: "")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    completeJsDialog(dialog) { result.confirm() }
                }
                .setOnCancelListener { completeJsDialog(dialog) { result.cancel() } }
                .create()
            showTrackedJsDialog(dialog) { result.cancel() }
        }
        return true
    }

    override fun onJsConfirm(
        view: WebView,
        url: String?,
        message: String?,
        result: android.webkit.JsResult
    ): Boolean {
        if (dialogProvider != null) {
            dialogProvider.showConfirm(view, url, message, result)
        } else {
            dismissActiveJsDialog()
            lateinit var dialog: AlertDialog
            dialog = AlertDialog.Builder(view.context)
                .setMessage(message ?: "")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    completeJsDialog(dialog) { result.confirm() }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    completeJsDialog(dialog) { result.cancel() }
                }
                .setOnCancelListener { completeJsDialog(dialog) { result.cancel() } }
                .create()
            showTrackedJsDialog(dialog) { result.cancel() }
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
        if (dialogProvider != null) {
            dialogProvider.showPrompt(view, url, message, defaultValue, result)
        } else {
            dismissActiveJsDialog()
            val editText = android.widget.EditText(view.context).apply {
                setText(defaultValue ?: "")
                setPadding(48, 32, 48, 32)
            }
            lateinit var dialog: AlertDialog
            dialog = AlertDialog.Builder(view.context)
                .setMessage(message ?: "")
                .setView(editText)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    completeJsDialog(dialog) {
                        result.confirm(editText.text?.toString().orEmpty())
                    }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    completeJsDialog(dialog) { result.cancel() }
                }
                .setOnCancelListener { completeJsDialog(dialog) { result.cancel() } }
                .create()
            showTrackedJsDialog(dialog) { result.cancel() }
        }
        return true
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (view == null || callback == null) {
            super.onShowCustomView(view, callback)
            return
        }
        if (customView != null) {
            runCatching { callback.onCustomViewHidden() }
            return
        }

        customView = view
        customViewCallback = callback
        try {
            requestedOrientationBeforeFullscreen = activity.requestedOrientation
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

            val alreadyKeepingScreenOn =
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            keepScreenOnAddedByUs = !alreadyKeepingScreenOn
            if (keepScreenOnAddedByUs) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

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
            fullScreenContainer = container
            decor.addView(container)
            container.requestFocus()

            val insets = ViewCompat.getRootWindowInsets(decor)
                ?.getInsets(WindowInsetsCompat.Type.systemBars())
            systemBarsVisibleBeforeFullscreen =
                (insets?.top ?: 0) > 0 || (insets?.bottom ?: 0) > 0
            WindowInsetsControllerCompat(activity.window, decor)
                .hide(WindowInsetsCompat.Type.systemBars())
            registerFullScreenBackPress()
            runCatching { eventReporter?.invoke(BitwebcEvent.FullscreenChanged(fullscreen = true)) }
        } catch (_: Exception) {
            onHideCustomView()
        }
    }

    override fun onHideCustomView() {
        if (customView == null && fullScreenContainer == null) {
            super.onHideCustomView()
            return
        }
        val decor = activity.findViewById<ViewGroup>(Window.ID_ANDROID_CONTENT)
        val container = fullScreenContainer
        val callback = customViewCallback
        val orientationToRestore = requestedOrientationBeforeFullscreen
        val clearKeepScreenOn = keepScreenOnAddedByUs
        val restoreSystemBars = systemBarsVisibleBeforeFullscreen
        fullScreenContainer = null
        customView = null
        customViewCallback = null
        fullScreenBackCallback?.remove()
        fullScreenBackCallback = null
        keepScreenOnAddedByUs = false
        requestedOrientationBeforeFullscreen = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        runCatching {
            container?.let {
                decor.removeView(it)
                it.removeAllViews()
            }
        }
        runCatching { activity.requestedOrientation = orientationToRestore }
        if (clearKeepScreenOn) {
            runCatching { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        runCatching {
            val controller = WindowInsetsControllerCompat(activity.window, decor)
            if (restoreSystemBars) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        runCatching { eventReporter?.invoke(BitwebcEvent.FullscreenChanged(fullscreen = false)) }
        runCatching { callback?.onCustomViewHidden() }
    }

    fun release() {
        dismissActiveJsDialog()
        runCatching { dialogProvider?.cancelPending() }
        if (customView != null || fullScreenContainer != null) {
            onHideCustomView()
        }
        fullScreenBackCallback?.remove()
        fullScreenBackCallback = null
    }

    private fun showTrackedJsDialog(dialog: AlertDialog, cancelResult: () -> Unit) {
        activeJsDialog = dialog
        cancelActiveJsResult = cancelResult
        dialog.setOnDismissListener {
            if (activeJsDialog === dialog) {
                val pendingCancel = cancelActiveJsResult
                activeJsDialog = null
                cancelActiveJsResult = null
                runCatching { pendingCancel?.invoke() }
            }
        }
        runCatching { dialog.show() }.onFailure {
            if (activeJsDialog === dialog) {
                activeJsDialog = null
                cancelActiveJsResult = null
                runCatching(cancelResult)
            }
            runCatching { dialog.dismiss() }
        }
    }

    private fun completeJsDialog(dialog: AlertDialog, completeResult: () -> Unit) {
        if (activeJsDialog === dialog) {
            activeJsDialog = null
            cancelActiveJsResult = null
        }
        runCatching(completeResult)
    }

    private fun dismissActiveJsDialog() {
        val dialog = activeJsDialog
        val cancelResult = cancelActiveJsResult
        activeJsDialog = null
        cancelActiveJsResult = null
        runCatching { cancelResult?.invoke() }
        runCatching { dialog?.dismiss() }
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
