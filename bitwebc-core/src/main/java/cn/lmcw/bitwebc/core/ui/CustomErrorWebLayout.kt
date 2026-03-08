package cn.lmcw.bitwebc.core.ui

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import cn.lmcw.bitwebc.core.api.IWebLayout

/**
 * 宿主自定义错误页布局：
 * - errorView: 你的错误页面根布局
 * - retryViewId: 点击重试的控件 id（不传则点击整个错误页重试）
 * - errorMessageViewId: 错误文案控件 id（可选，若是 TextView 会自动写入错误信息）
 */
class CustomErrorWebLayout(
    private val errorView: View,
    private val retryViewId: Int = View.NO_ID,
    private val errorMessageViewId: Int = View.NO_ID
) : IWebLayout {
    private lateinit var rootView: FrameLayout
    private lateinit var webView: WebView

    override fun createRoot(context: Context): ViewGroup {
        rootView = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        detachIfAttached(errorView)
        errorView.visibility = View.GONE
        rootView.addView(
            errorView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        return rootView
    }

    override fun attach(activity: Activity, webView: WebView, indicatorView: View) {
        this.webView = webView
        rootView.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        rootView.addView(
            indicatorView,
            FrameLayout.LayoutParams(
                0,
                resolveIndicatorHeightPx(indicatorView, activity),
                Gravity.TOP
            )
        )
        indicatorView.bringToFront()
        showWebContent()
    }

    override fun showWebContent() {
        webView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
    }

    override fun showError(message: String?, onRetry: () -> Unit) {
        webView.visibility = View.GONE
        errorView.visibility = View.VISIBLE

        val messageView = errorView.findViewById<View>(errorMessageViewId) as? TextView
        if (!message.isNullOrBlank() && messageView != null) {
            messageView.text = message
        }

        val retryTarget = errorView.findViewById<View>(retryViewId) ?: errorView
        retryTarget.setOnClickListener { onRetry.invoke() }
    }

    override fun root(): ViewGroup = rootView

    private fun detachIfAttached(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun resolveIndicatorHeightPx(indicatorView: View, context: Context): Int {
        val fromView = indicatorView.layoutParams?.height ?: 0
        if (fromView > 0) return fromView
        return (context.resources.displayMetrics.density * 2f).toInt().coerceAtLeast(1)
    }
}
