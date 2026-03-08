package cn.lmcw.bitwebc.core.dsl

import android.view.View
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import cn.lmcw.bitwebc.core.event.BitwebcEventHub
import cn.lmcw.bitwebc.core.event.BitwebcEventListener
import cn.lmcw.bitwebc.core.lifecycle.BitwebcLifecycleObserver
import kotlinx.coroutines.flow.SharedFlow

class BitwebcSession internal constructor(
    val webView: WebView,
    val rootView: View,
    private val lifecycleObserver: BitwebcLifecycleObserver,
    private val backPressedCallback: OnBackPressedCallback,
    private val eventHub: BitwebcEventHub
) {
    val events: SharedFlow<BitwebcEvent> = eventHub.events

    fun loadUrl(url: String) {
        webView.loadUrl(url)
    }

    fun addEventListener(listener: BitwebcEventListener) {
        eventHub.addListener(listener)
    }

    fun removeEventListener(listener: BitwebcEventListener) {
        eventHub.removeListener(listener)
    }

    fun release() {
        backPressedCallback.remove()
        lifecycleObserver.release()
    }
}
