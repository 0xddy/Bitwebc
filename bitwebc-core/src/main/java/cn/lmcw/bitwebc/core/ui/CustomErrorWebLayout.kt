package cn.lmcw.bitwebc.core.ui

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.core.view.isVisible
import cn.lmcw.bitwebc.core.api.WebLayout
import cn.lmcw.bitwebc.core.extensions.detachFromParent
import cn.lmcw.bitwebc.core.extensions.resolveIndicatorHeightPx

class CustomErrorWebLayout private constructor(
    private var errorView: View?,
    @param:LayoutRes private val layoutRes: Int,
    private val retryViewId: Int,
    private val errorMessageViewId: Int
) : WebLayout {
    private lateinit var rootView: FrameLayout
    private lateinit var webView: WebView
    private lateinit var resolvedErrorView: View

    constructor(
        errorView: View,
        retryViewId: Int = View.NO_ID,
        errorMessageViewId: Int = View.NO_ID
    ) : this(errorView, 0, retryViewId, errorMessageViewId)

    constructor(
        @LayoutRes layoutRes: Int,
        retryViewId: Int = View.NO_ID,
        errorMessageViewId: Int = View.NO_ID
    ) : this(null, layoutRes, retryViewId, errorMessageViewId)

    override fun createRoot(context: Context): ViewGroup {
        rootView = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        resolvedErrorView = errorView?.also { it.detachFromParent() }
            ?: LayoutInflater.from(context).inflate(layoutRes, rootView, false)
        errorView = null
        resolvedErrorView.visibility = View.GONE
        rootView.addView(
            resolvedErrorView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
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
                ViewGroup.LayoutParams.MATCH_PARENT,
                indicatorView.resolveIndicatorHeightPx(activity),
                Gravity.TOP
            )
        )
        indicatorView.bringToFront()
        showWebContent()
    }

    override fun replaceWebView(
        activity: Activity,
        oldWebView: WebView,
        newWebView: WebView,
        indicatorView: View
    ) {
        val wasShowingError = resolvedErrorView.isVisible
        rootView.removeView(oldWebView)
        this.webView = newWebView
        rootView.addView(
            newWebView,
            rootView.indexOfChild(indicatorView).coerceAtLeast(1),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        newWebView.visibility = if (wasShowingError) View.GONE else View.VISIBLE
        indicatorView.bringToFront()
    }

    override fun showWebContent() {
        webView.visibility = View.VISIBLE
        resolvedErrorView.visibility = View.GONE
    }

    override fun showError(message: String?, onRetry: () -> Unit) {
        webView.visibility = View.GONE
        resolvedErrorView.visibility = View.VISIBLE

        val messageView = resolvedErrorView.findViewById<View>(errorMessageViewId) as? TextView
        if (!message.isNullOrBlank() && messageView != null) {
            messageView.text = message
        }

        val retryTarget = resolvedErrorView.findViewById<View>(retryViewId) ?: resolvedErrorView
        retryTarget.setOnClickListener { onRetry.invoke() }
    }

    override fun root(): ViewGroup = rootView
}
