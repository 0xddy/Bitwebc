package cn.lmcw.bitwebc.core.ui

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import cn.lmcw.bitwebc.core.api.WebLayout
import cn.lmcw.bitwebc.core.extensions.detachFromParent
import cn.lmcw.bitwebc.core.extensions.resolveIndicatorHeightPx

/** ?????????errorView ????retryViewId ???? id?errorMessageViewId ???? TextView id */
class CustomErrorWebLayout(
    private val errorView: View,
    private val retryViewId: Int = View.NO_ID,
    private val errorMessageViewId: Int = View.NO_ID
) : WebLayout {
    private lateinit var rootView: FrameLayout
    private lateinit var webView: WebView

    override fun createRoot(context: Context): ViewGroup {
        rootView = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        errorView.detachFromParent()
        errorView.visibility = View.GONE
        rootView.addView(
            errorView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
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
                indicatorView.resolveIndicatorHeightPx(activity),
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
}
