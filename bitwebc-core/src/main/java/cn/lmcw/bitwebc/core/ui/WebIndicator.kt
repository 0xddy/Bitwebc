package cn.lmcw.bitwebc.core.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import cn.lmcw.bitwebc.core.api.IWebIndicator
import kotlin.math.max

class WebIndicator(
    private val heightDp: Int = 2,
    private val color: Int = Color.parseColor("#2F80ED")
) : IWebIndicator {

    private var indicatorView: View? = null
    private var currentProgress: Int = 0
    private var progressAnimator: ValueAnimator? = null
    private var fadeAnimator: ValueAnimator? = null

    override fun createView(context: Context): View {
        if (indicatorView != null) return indicatorView as View
        val heightPx = (context.resources.displayMetrics.density * heightDp).toInt().coerceAtLeast(1)
        return View(context).apply {
            layoutParams = FrameLayout.LayoutParams(0, heightPx)
            setBackgroundColor(color)
            alpha = 1f
            visibility = View.GONE
        }.also { indicatorView = it }
    }

    override fun onPageStarted() {
        currentProgress = 0
        val view = indicatorView ?: return
        view.visibility = View.VISIBLE
        view.alpha = 1f
        updateWidthByProgress(1)
    }

    override fun onProgressChanged(progress: Int) {
        val target = progress.coerceIn(0, 100)
        if (target <= currentProgress) return
        animateProgress(currentProgress, target)
    }

    override fun onPageFinished() {
        animateProgress(currentProgress, 100) {
            fadeOut()
        }
    }

    override fun reset() {
        progressAnimator?.cancel()
        fadeAnimator?.cancel()
        currentProgress = 0
        indicatorView?.apply {
            visibility = View.GONE
            alpha = 1f
            updateWidthByProgress(0)
        }
    }

    private fun animateProgress(from: Int, to: Int, endAction: (() -> Unit)? = null) {
        progressAnimator?.cancel()
        progressAnimator = ValueAnimator.ofInt(from, to).apply {
            duration = ((to - from) * 12L).coerceIn(80L, 450L)
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val value = animator.animatedValue as Int
                currentProgress = value
                updateWidthByProgress(value)
            }
            if (endAction != null) {
                doOnEnd { endAction.invoke() }
            }
            start()
        }
    }

    private fun fadeOut() {
        val view = indicatorView ?: return
        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 300L
            addUpdateListener {
                view.alpha = it.animatedValue as Float
            }
            doOnEnd {
                view.visibility = View.GONE
                view.alpha = 1f
                currentProgress = 0
                updateWidthByProgress(0)
            }
            start()
        }
    }

    private fun updateWidthByProgress(progress: Int) {
        val view = indicatorView ?: return
        val parent = view.parent as? ViewGroup ?: return
        val parentWidth = if (parent.width > 0) parent.width else parent.measuredWidth
        if (parentWidth <= 0) {
            view.post { updateWidthByProgress(progress) }
            return
        }
        val targetWidth = max((parentWidth * (progress / 100f)).toInt(), if (progress == 0) 0 else 1)
        view.layoutParams = (view.layoutParams as FrameLayout.LayoutParams).apply {
            width = targetWidth
        }
    }

    private fun ValueAnimator.doOnEnd(block: () -> Unit) {
        addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(animation: android.animation.Animator) = Unit
            override fun onAnimationEnd(animation: android.animation.Animator) = block()
            override fun onAnimationCancel(animation: android.animation.Animator) = Unit
            override fun onAnimationRepeat(animation: android.animation.Animator) = Unit
        })
    }
}
