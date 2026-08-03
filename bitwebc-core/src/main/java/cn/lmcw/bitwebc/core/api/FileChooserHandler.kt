package cn.lmcw.bitwebc.core.api

import android.webkit.WebChromeClient
interface FileChooserHandler {
    fun createWebChromeClient(next: WebChromeClient?): WebChromeClient
    fun cancelPending() = Unit
    fun release() = Unit
}
