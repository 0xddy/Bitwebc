package cn.lmcw.bitwebc.core.extensions

import android.content.Context
import android.view.View
import android.view.ViewGroup

internal fun View.detachFromParent() {
    (parent as? ViewGroup)?.removeView(this)
}

internal fun View.resolveIndicatorHeightPx(context: Context): Int {
    val fromView = layoutParams?.height ?: 0
    if (fromView > 0) return fromView
    return (context.resources.displayMetrics.density * 2f).toInt().coerceAtLeast(1)
}
