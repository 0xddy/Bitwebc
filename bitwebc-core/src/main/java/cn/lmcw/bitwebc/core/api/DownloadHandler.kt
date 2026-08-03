package cn.lmcw.bitwebc.core.api

import android.webkit.DownloadListener

interface DownloadHandler : DownloadListener {
    /** Called once after the owning Session has committed all runtime bindings. */
    fun onSessionReady() = Unit

    /** Detaches Session-owned callbacks and resources. Active app-owned work may continue. */
    fun release() = Unit
}
