package cn.lmcw.bitwebc.core.api

import android.content.Context
import android.view.View

interface WebIndicator {
    /** Releases listeners and other resources owned by this Session's indicator. */
    fun release() = Unit

    fun createView(context: Context): View
    fun onPageStarted()
    fun onProgressChanged(progress: Int)
    fun onPageFinished()
    fun reset()
}
