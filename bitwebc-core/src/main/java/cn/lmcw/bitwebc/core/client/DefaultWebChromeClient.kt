package cn.lmcw.bitwebc.core.client

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cn.lmcw.bitwebc.core.api.IWebIndicator
import cn.lmcw.bitwebc.core.event.BitwebcEvent

class DefaultWebChromeClient(
    private val activity: ComponentActivity,
    private val indicator: IWebIndicator,
    private val dialogFactory: ((WebView) -> AlertDialog.Builder)? = null,
    private val eventReporter: ((BitwebcEvent) -> Unit)? = null,
    next: WebChromeClient? = null
) : MiddlewareWebChromeBase(next) {

    private var fullScreenContainer: FrameLayout? = null
    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null
    private var fullScreenBackCallback: OnBackPressedCallback? = null

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
        val builder = dialogFactory?.invoke(view) ?: AlertDialog.Builder(view.context)
        builder.setMessage(message ?: "")
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
            .setOnCancelListener { result.cancel() }
            .show()
        return true
    }

    override fun onJsConfirm(
        view: WebView,
        url: String?,
        message: String?,
        result: android.webkit.JsResult
    ): Boolean {
        val builder = dialogFactory?.invoke(view) ?: AlertDialog.Builder(view.context)
        builder.setMessage(message ?: "")
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }
            .show()
        return true
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (view == null || callback == null) {
            super.onShowCustomView(view, callback)
            return
        }
        if (customView != null) {
            // 已在全屏状态，直接回调隐藏新请求，避免多层叠加。
            callback.onCustomViewHidden()
            return
        }

        val decor = activity.findViewById<ViewGroup>(Window.ID_ANDROID_CONTENT)
        val container = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        decor.addView(container)

        customView = view
        customViewCallback = callback
        fullScreenContainer = container

        // 全屏播放时隐藏系统栏，提供沉浸式体验。
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
        // 退出全屏后恢复系统栏。
        WindowInsetsControllerCompat(activity.window, decor).show(WindowInsetsCompat.Type.systemBars())
        eventReporter?.invoke(BitwebcEvent.FullscreenChanged(fullscreen = false))
        super.onHideCustomView()
    }

    private fun registerFullScreenBackPress() {
        fullScreenBackCallback?.remove()
        fullScreenBackCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 全屏状态下优先消费返回键用于退出视频全屏。
                onHideCustomView()
            }
        }
        fullScreenBackCallback?.let { callback ->
            activity.onBackPressedDispatcher.addCallback(activity, callback)
        }
    }
}
