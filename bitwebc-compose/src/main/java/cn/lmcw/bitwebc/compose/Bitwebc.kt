package cn.lmcw.bitwebc.compose

import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.lmcw.bitwebc.core.dsl.BackPressMode
import cn.lmcw.bitwebc.core.dsl.BitwebcConfigScope
import cn.lmcw.bitwebc.core.dsl.BitwebcSession
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import cn.lmcw.bitwebc.core.ui.BitwebcView
import kotlinx.coroutines.flow.collectLatest
import java.util.UUID

/** Determines whether this composable consumes back presses for WebView history. */
enum class BitwebcBackHandling {
    /** Navigate through WebView history; let the outer host handle back when no history exists. */
    WebHistory,

    /** Never install a Compose history back handler. */
    Disabled
}

private data class NativeBinding(
    val token: Long,
    val session: BitwebcSession
)

/**
 * Hosts a Bitwebc WebView in Compose. [configure] is an initialization callback: changes to the
 * lambda do not implicitly recreate the WebView. Use a stable [sessionKey]; changing it creates a
 * new Session and prevents restored history from crossing account/configuration boundaries.
 */
@Composable
fun Bitwebc(
    state: BitwebcState,
    modifier: Modifier = Modifier,
    sessionKey: String = "default",
    backHandling: BitwebcBackHandling = BitwebcBackHandling.WebHistory,
    onEvent: (BitwebcEvent) -> Unit = {},
    configure: BitwebcConfigScope.() -> Unit = {}
) {
    BitwebcHost(
        url = state.requestedUrl,
        urlIsExternalInput = false,
        state = state,
        modifier = modifier,
        sessionKey = sessionKey,
        backHandling = backHandling,
        onEvent = onEvent,
        configure = configure
    )
}

/** Convenience overload that owns its state and applies [url] changes without recreating WebView. */
@Composable
fun Bitwebc(
    url: String,
    modifier: Modifier = Modifier,
    sessionKey: String = "default",
    backHandling: BitwebcBackHandling = BitwebcBackHandling.WebHistory,
    onEvent: (BitwebcEvent) -> Unit = {},
    configure: BitwebcConfigScope.() -> Unit = {}
) {
    require(url.isNotBlank()) { "url must not be blank" }
    val state = rememberBitwebcState(url)
    BitwebcHost(
        url = url,
        urlIsExternalInput = true,
        state = state,
        modifier = modifier,
        sessionKey = sessionKey,
        backHandling = backHandling,
        onEvent = onEvent,
        configure = configure
    )
}

@Composable
private fun BitwebcHost(
    url: String,
    urlIsExternalInput: Boolean,
    state: BitwebcState,
    modifier: Modifier,
    sessionKey: String,
    backHandling: BitwebcBackHandling,
    onEvent: (BitwebcEvent) -> Unit,
    configure: BitwebcConfigScope.() -> Unit
) {
    require(sessionKey.isNotBlank()) { "sessionKey must not be blank" }
    val activity = LocalActivity.current as? ComponentActivity
        ?: error("Bitwebc must be hosted by a ComponentActivity")
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentConfiguration by rememberUpdatedState(configure)
    val currentOnEvent by rememberUpdatedState(onEvent)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val hostId = remember(state) { UUID.randomUUID().toString() }

    LaunchedEffect(state, url, urlIsExternalInput) {
        if (urlIsExternalInput) state.acceptExternalUrl(url)
    }

    key(activity, lifecycleOwner, sessionKey, state.instanceId, state.hostGeneration) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                val claim = state.beginBinding(hostId)
                var createdHost: BitwebcView? = null
                try {
                    val restoredState = state.prepareForSession(
                        url = url,
                        sessionKey = sessionKey,
                        urlIsExternalInput = urlIsExternalInput
                    )
                    val launchUrl = state.requestedUrl
                    val host = BitwebcView(context).apply {
                        releaseOnDetach = false
                    }
                    createdHost = host
                    val session = host.setup(activity, lifecycleOwner) {
                        currentConfiguration()
                        activityResultKey("${state.instanceId}:$sessionKey")
                        navigation {
                            this.backHandling = BackPressMode.Host
                            initial(launchUrl, restoreFrom = restoredState)
                        }
                        callbacks {
                            onEvent { event ->
                                if (state.emitEvent(claim.token, event)) {
                                    val notifyHost = Runnable {
                                        if (state.isBindingActive(claim.token)) {
                                            runCatching { currentOnEvent(event) }
                                        }
                                    }
                                    if (Looper.myLooper() == Looper.getMainLooper()) {
                                        notifyHost.run()
                                    } else {
                                        mainHandler.post(notifyHost)
                                    }
                                }
                            }
                        }
                    }
                    host.tag = NativeBinding(claim.token, session)
                    state.bind(session, claim.token)
                    host
                } catch (error: Throwable) {
                    runCatching { createdHost?.release() }
                    state.rollbackBinding(claim)
                    throw error
                }
            },
            onRelease = { host ->
                val binding = host.tag as? NativeBinding
                host.tag = null
                binding?.let { released ->
                    state.snapshot(released.session)
                    state.unbind(released.session, released.token, hostId)
                }
                host.release()
            }
        )
    }

    val activeSession = state.boundSession
    LaunchedEffect(state, activeSession) {
        activeSession?.state?.collectLatest { pageState ->
            val released = state.updatePageState(activeSession, pageState)
            if (
                released &&
                lifecycleOwner.lifecycle.currentState != Lifecycle.State.DESTROYED
            ) {
                state.recreateReleasedSession(activeSession)
            }
        }
    }

    DisposableEffect(lifecycleOwner, state, activeSession) {
        if (activeSession == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) state.snapshot(activeSession)
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }

    BackHandler(
        enabled = backHandling == BitwebcBackHandling.WebHistory &&
            state.pageState.canGoBack
    ) {
        state.goBack()
    }
}
