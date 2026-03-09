package cn.lmcw.bitwebc.core.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/** 横向加载进度条 */
class CoolProgressIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barRect = RectF()
    private val edgeRect = RectF()
    private val shaderMatrix = Matrix()
    private var barColor: Int = 0xFF2F80ED.toInt()
    private var progress: Float = 0f
    private var progressAnimator: ValueAnimator? = null
    private var fadeAnimator: ValueAnimator? = null
    private var shimmerAnimator: ValueAnimator? = null
    private var shimmerGradient: LinearGradient? = null
    private var shimmerTranslateX: Float = 0f

    init {
        visibility = GONE
        alpha = 1f
        barPaint.style = Paint.Style.FILL
        barPaint.color = barColor
        shimmerPaint.style = Paint.Style.FILL
        edgeGlowPaint.style = Paint.Style.FILL
    }

    fun setBarColor(color: Int) {
        barColor = color
        barPaint.color = color
        edgeGlowPaint.color = lightenColor(color, 0.22f)
        updateShader()
        invalidate()
    }

    fun onPageStarted() {
        fadeAnimator?.cancel()
        progressAnimator?.cancel()
        progress = 1f
        alpha = 1f
        visibility = VISIBLE
        ensureShimmer()
        invalidate()
    }

    fun onProgressChanged(target: Int) {
        onProgressChanged(target, null)
    }

    private fun onProgressChanged(target: Int, endAction: (() -> Unit)?) {
        val to = target.coerceIn(0, 100).toFloat()
        if (to <= progress) {
            endAction?.invoke()
            return
        }
        progressAnimator?.cancel()
        val delta = (to - progress).coerceAtLeast(0f)
        progressAnimator = ValueAnimator.ofFloat(progress, to).apply {
            duration = (delta * 10f).toLong().coerceIn(90L, 420L)
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                progress = animator.animatedValue as Float
                invalidate()
            }
            if (endAction != null) {
                addListener(object : android.animation.Animator.AnimatorListener {
                    override fun onAnimationStart(animation: android.animation.Animator) = Unit
                    override fun onAnimationRepeat(animation: android.animation.Animator) = Unit
                    override fun onAnimationCancel(animation: android.animation.Animator) = Unit
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        endAction.invoke()
                    }
                })
            }
            start()
        }
    }

    fun onPageFinished() {
        onProgressChanged(100) {
            stopShimmer()
            fadeAnimator?.cancel()
            fadeAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 260L
                addUpdateListener { animator ->
                    alpha = animator.animatedValue as Float
                }
                addListener(object : android.animation.Animator.AnimatorListener {
                    override fun onAnimationStart(animation: android.animation.Animator) = Unit
                    override fun onAnimationRepeat(animation: android.animation.Animator) = Unit
                    override fun onAnimationCancel(animation: android.animation.Animator) = Unit
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        reset()
                    }
                })
                startDelay = 120L
                start()
            }
        }
    }

    fun reset() {
        progressAnimator?.cancel()
        fadeAnimator?.cancel()
        stopShimmer()
        progress = 0f
        alpha = 1f
        visibility = GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress <= 0f || width <= 0 || height <= 0) return
        val drawWidth = (width * (progress / 100f)).coerceAtLeast(1f)
        barRect.set(0f, 0f, drawWidth, height.toFloat())
        canvas.drawRect(barRect, barPaint)

        shimmerGradient?.let { gradient ->
            shaderMatrix.setTranslate(shimmerTranslateX, 0f)
            gradient.setLocalMatrix(shaderMatrix)
            shimmerPaint.shader = gradient
            canvas.drawRect(barRect, shimmerPaint)
        }

        val glowWidth = (height * 1.2f).coerceAtLeast(2f)
        edgeRect.set((drawWidth - glowWidth).coerceAtLeast(0f), 0f, drawWidth, height.toFloat())
        canvas.drawRect(edgeRect, edgeGlowPaint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShader()
    }

    override fun onDetachedFromWindow() {
        reset()
        super.onDetachedFromWindow()
    }

    private fun ensureShimmer() {
        if (shimmerAnimator?.isRunning == true) return
        updateShader()
        shimmerAnimator?.cancel()
        shimmerAnimator = ValueAnimator.ofFloat(-1f, 1f).apply {
            duration = 850L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                shimmerTranslateX = fraction * width
                invalidate()
            }
            start()
        }
    }

    private fun stopShimmer() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        shimmerTranslateX = 0f
    }

    private fun updateShader() {
        if (width <= 0) return
        val base = barColor
        val bright = lightenColor(base, 0.35f)
        shimmerGradient = LinearGradient(
            -width.toFloat(),
            0f,
            0f,
            0f,
            intArrayOf(
                withAlpha(bright, 0f),
                withAlpha(bright, 0.65f),
                withAlpha(bright, 0f)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun lightenColor(color: Int, factor: Float): Int {
        val f = factor.coerceIn(0f, 1f)
        val a = Color.alpha(color)
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val lr = (r + (255 - r) * f).toInt().coerceIn(0, 255)
        val lg = (g + (255 - g) * f).toInt().coerceIn(0, 255)
        val lb = (b + (255 - b) * f).toInt().coerceIn(0, 255)
        return Color.argb(a, lr, lg, lb)
    }

    private fun withAlpha(color: Int, alphaRatio: Float): Int {
        val a = (255f * alphaRatio.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }
}

