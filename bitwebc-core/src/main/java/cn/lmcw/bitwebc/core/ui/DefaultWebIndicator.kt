package cn.lmcw.bitwebc.core.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import cn.lmcw.bitwebc.core.api.WebIndicator

class DefaultWebIndicator(
    private val heightDp: Int = 2,
    private val color: Int = 0xFF2F80ED.toInt()
) : WebIndicator {

    private var indicatorView: CoolProgressIndicatorView? = null

    override fun createView(context: Context): View {
        if (indicatorView != null) return indicatorView as View
        val heightPx = (context.resources.displayMetrics.density * heightDp).toInt().coerceAtLeast(1)
        return CoolProgressIndicatorView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                heightPx
            )
            setBarColor(color)
        }.also { indicatorView = it }
    }

    override fun onPageStarted() {
        indicatorView?.onPageStarted()
    }

    override fun onProgressChanged(progress: Int) {
        indicatorView?.onProgressChanged(progress)
    }

    override fun onPageFinished() {
        indicatorView?.onPageFinished()
    }

    override fun reset() {
        indicatorView?.reset()
    }
}
