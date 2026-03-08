package cn.lmcw.bitwebc.core.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import cn.lmcw.bitwebc.core.api.IWebLayout

class DefaultWebLayout : IWebLayout {
    private lateinit var rootView: FrameLayout
    private lateinit var webView: WebView
    private lateinit var errorContainer: LinearLayout
    private lateinit var errorMessageView: TextView
    private lateinit var retryButton: Button

    override fun createRoot(context: Context): ViewGroup {
        rootView = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.WHITE)
        }
        buildErrorView(context)
        rootView.addView(errorContainer)
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
        errorContainer.visibility = View.GONE
    }

    override fun showError(message: String?, onRetry: () -> Unit) {
        webView.visibility = View.GONE
        errorContainer.visibility = View.VISIBLE
        errorMessageView.text = if (message.isNullOrBlank()) {
            "页面加载失败，请检查网络后重试"
        } else {
            message
        }
        retryButton.setOnClickListener { onRetry.invoke() }
    }

    override fun root(): ViewGroup = rootView

    private fun buildErrorView(context: Context) {
        errorMessageView = TextView(context).apply {
            setTextColor(Color.parseColor("#666666"))
            textSize = 14f
            gravity = Gravity.CENTER
            text = "页面加载失败，请稍后重试"
        }
        retryButton = Button(context).apply {
            text = "重新加载"
            isAllCaps = false
        }
        errorContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(
                errorMessageView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (context.resources.displayMetrics.density * 12).toInt()
                }
            )
            addView(
                retryButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun resolveIndicatorHeightPx(indicatorView: View, context: Context): Int {
        val fromView = indicatorView.layoutParams?.height ?: 0
        if (fromView > 0) return fromView
        return (context.resources.displayMetrics.density * 2f).toInt().coerceAtLeast(1)
    }
}
