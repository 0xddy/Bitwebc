package cn.lmcw.bitwebc.core.api

import android.webkit.WebView
import androidx.lifecycle.LifecycleOwner

interface ILifeCycle {
    fun onAttach(owner: LifecycleOwner, webView: WebView) = Unit
    fun onResume(webView: WebView) = Unit
    fun onPause(webView: WebView) = Unit
    fun onDestroy(webView: WebView) = Unit
}
