package cn.lmcw.bitwebc.core.state

import android.webkit.WebView
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A stable snapshot of the page state exposed by a [cn.lmcw.bitwebc.core.dsl.BitwebcSession]. */
data class BitwebcPageState(
    val url: String? = null,
    val title: String? = null,
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isFullscreen: Boolean = false,
    val error: BitwebcPageError? = null,
    val isReleased: Boolean = false
)

/** The last main-frame error, if the current page failed to load. */
data class BitwebcPageError(
    val url: String?,
    val message: String?
)

internal class BitwebcPageStateStore(initialUrl: String?) {
    private val mutableState = MutableStateFlow(BitwebcPageState(url = initialUrl))

    val state: StateFlow<BitwebcPageState> = mutableState.asStateFlow()

    fun onEvent(event: BitwebcEvent, webView: WebView?) {
        when (event) {
            is BitwebcEvent.PageStarted -> updateFrom(webView, refreshTitle = false) { current ->
                current.copy(
                    url = event.url ?: current.url,
                    title = null,
                    isLoading = true,
                    progress = 0,
                    error = null
                )
            }

            is BitwebcEvent.PageFinished -> updateFrom(webView) { current ->
                current.copy(
                    url = event.url ?: current.url,
                    isLoading = false,
                    progress = 100,
                    error = current.error
                )
            }

            is BitwebcEvent.PageError -> updateFrom(webView) { current ->
                current.copy(
                    url = event.url ?: current.url,
                    isLoading = false,
                    error = BitwebcPageError(event.url, event.message)
                )
            }

            is BitwebcEvent.RenderProcessGone -> updateFrom(webView) { current ->
                current.copy(
                    isLoading = false,
                    error = BitwebcPageError(
                        url = current.url,
                        message = "The WebView renderer exited"
                    )
                )
            }

            is BitwebcEvent.FullscreenChanged -> mutableState.update { current ->
                if (current.isReleased) current else current.copy(isFullscreen = event.fullscreen)
            }

            else -> Unit
        }
    }

    fun onProgressChanged(webView: WebView, progress: Int) {
        updateFrom(webView) { current ->
            current.copy(progress = progress.coerceIn(0, 100))
        }
    }

    fun onTitleChanged(webView: WebView, title: String?) {
        updateFrom(webView) { current -> current.copy(title = title) }
    }

    fun onWebViewChanged(webView: WebView) {
        updateFrom(webView) { current ->
            current.copy(
                url = readSafely(current.url) { webView.url },
                title = readSafely(current.title) { webView.title },
                isReleased = false
            )
        }
    }

    fun syncNavigation(webView: WebView?) {
        if (webView == null) return
        updateFrom(webView) { it }
    }

    fun onVisitedHistoryChanged(webView: WebView?, url: String?) {
        updateFrom(webView) { current -> current.copy(url = url ?: current.url) }
    }

    fun markLoadingStopped() {
        mutableState.update { current ->
            if (current.isReleased) current else current.copy(isLoading = false)
        }
    }

    fun markReleased() {
        mutableState.update { current ->
            current.copy(
                isLoading = false,
                canGoBack = false,
                canGoForward = false,
                isFullscreen = false,
                isReleased = true
            )
        }
    }

    private fun updateFrom(
        webView: WebView?,
        refreshTitle: Boolean = true,
        transform: (BitwebcPageState) -> BitwebcPageState
    ) {
        mutableState.update { current ->
            if (current.isReleased) return@update current
            val transformed = transform(current)
            if (webView == null) {
                transformed
            } else {
                transformed.copy(
                    title = if (refreshTitle) {
                        readSafely(transformed.title) { webView.title }
                    } else {
                        transformed.title
                    },
                    canGoBack = readSafely(transformed.canGoBack) { webView.canGoBack() },
                    canGoForward = readSafely(transformed.canGoForward) { webView.canGoForward() },
                    isReleased = false
                )
            }
        }
    }

    private inline fun <T> readSafely(fallback: T, block: () -> T?): T =
        runCatching(block).getOrNull() ?: fallback
}
