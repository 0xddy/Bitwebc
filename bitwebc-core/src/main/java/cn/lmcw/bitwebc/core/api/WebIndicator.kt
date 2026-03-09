package cn.lmcw.bitwebc.core.api

import android.content.Context
import android.view.View

interface WebIndicator {
    fun createView(context: Context): View
    fun onPageStarted()
    fun onProgressChanged(progress: Int)
    fun onPageFinished()
    fun reset()
}
