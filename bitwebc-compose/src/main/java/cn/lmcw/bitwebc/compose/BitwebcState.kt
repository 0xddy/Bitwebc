package cn.lmcw.bitwebc.compose

import android.os.Bundle
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import cn.lmcw.bitwebc.core.dsl.BitwebcSession
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import cn.lmcw.bitwebc.core.state.BitwebcPageState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

/** Controls whether WebView navigation history is included in Compose saved-instance state. */
enum class BitwebcSavePolicy {
    /** Do not persist WebView history. Recommended for sensitive or very large browsing sessions. */
    None,

    /** Persist navigation history and scroll position. DOM state is not preserved. */
    NavigationHistory
}

internal data class BindingClaim(
    val hostId: String,
    val token: Long,
    val previousHostId: String?,
    val previousToken: Long
)

/** A stable, renderer-independent state holder for [Bitwebc]. */
@Stable
class BitwebcState private constructor(
    initialUrl: String,
    @get:JvmSynthetic internal val instanceId: String,
    private val savePolicy: BitwebcSavePolicy,
    restoredNavigationState: Bundle? = null,
    restoredExternalUrl: String? = initialUrl,
    restoredSessionKey: String? = null
) {
    private val mutableEvents = MutableSharedFlow<BitwebcEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var session: BitwebcSession? by mutableStateOf(null)

    @get:JvmSynthetic
    internal val boundSession: BitwebcSession?
        get() = session
    private var savedNavigationState: Bundle? = restoredNavigationState?.let(::Bundle)
    private var savedForExternalUrl: String? = restoredExternalUrl
    private var lastExternalUrl: String? = restoredExternalUrl
    private var sessionKeyInitialized = restoredSessionKey != null
    private var lastSessionKey: String? = restoredSessionKey
    private var nextBindingToken = 0L
    private var requestedUrlRevision = 0L
    private var acknowledgedUrlRevision = 0L
    private var requestedFromUrl: String? = null
    private val supersededRequestedUrls = ArrayDeque<String>()
    @Volatile
    private var activeBindingToken = 0L
    private var activeHostId: String? = null

    @get:JvmSynthetic
    internal var hostGeneration: Int by mutableIntStateOf(0)
        private set

    @get:JvmSynthetic
    internal var requestedUrl: String by mutableStateOf(initialUrl)
        private set

    /** Current page/loading/navigation state, suitable for directly driving Compose UI. */
    var pageState: BitwebcPageState by mutableStateOf(BitwebcPageState(url = initialUrl))
        private set

    /** One-off events remain stable even when the native WebView Session is recreated. */
    val events: SharedFlow<BitwebcEvent> = mutableEvents.asSharedFlow()

    /** Loads [url] now, or queues it until this state is attached to a Bitwebc host. */
    @MainThread
    fun loadUrl(url: String) {
        require(url.isNotBlank()) { "url must not be blank" }
        beginUrlRequest(url)
        clearSavedNavigation()
        session?.takeUnless(BitwebcSession::isReleased)?.let { activeSession ->
            runCatching { activeSession.loadUrl(url) }
        }
    }

    @MainThread
    fun reload() {
        session?.takeUnless(BitwebcSession::isReleased)?.let { activeSession ->
            runCatching { activeSession.reload() }
        }
    }

    @MainThread
    fun retry(): Boolean {
        val activeSession = session?.takeUnless(BitwebcSession::isReleased) ?: return false
        return runCatching {
            activeSession.reload()
            true
        }.getOrDefault(false)
    }

    @MainThread
    fun stopLoading() {
        val activeSession = session?.takeUnless(BitwebcSession::isReleased)
        val cancelled = activeSession == null || runCatching {
            activeSession.stopLoading()
            true
        }.getOrDefault(false)
        if (cancelled && requestedUrlRevision != acknowledgedUrlRevision) {
            (requestedFromUrl ?: pageState.url)
                ?.takeIf(String::isNotBlank)
                ?.let { requestedUrl = it }
            acknowledgedUrlRevision = requestedUrlRevision
            requestedFromUrl = null
            supersededRequestedUrls.clear()
        }
    }

    @MainThread
    fun goBack(): Boolean = withActiveSession { it.goBack() } ?: false

    @MainThread
    fun goForward(): Boolean = withActiveSession { it.goForward() } ?: false

    /** Runs an advanced operation without encouraging callers to cache a renderer-bound WebView. */
    @MainThread
    fun withSession(block: (BitwebcSession) -> Unit): Boolean {
        val activeSession = session?.takeUnless(BitwebcSession::isReleased) ?: return false
        return runCatching {
            block(activeSession)
            true
        }.getOrDefault(false)
    }

    @JvmSynthetic
    internal fun prepareForSession(
        url: String,
        sessionKey: String,
        urlIsExternalInput: Boolean
    ): Bundle? {
        if (urlIsExternalInput && lastExternalUrl != url) {
            lastExternalUrl = url
            beginUrlRequest(url)
            clearSavedNavigation()
        }
        if (sessionKeyInitialized && lastSessionKey != sessionKey) {
            clearSavedNavigation()
        }
        lastSessionKey = sessionKey
        sessionKeyInitialized = true

        val canRestoreForUrl = !urlIsExternalInput || savedForExternalUrl == lastExternalUrl
        return if (savePolicy == BitwebcSavePolicy.NavigationHistory && canRestoreForUrl) {
            savedNavigationState?.let(::Bundle)
        } else {
            null
        }
    }

    @JvmSynthetic
    internal fun acceptExternalUrl(url: String) {
        if (lastExternalUrl == url) return
        lastExternalUrl = url
        loadUrl(url)
    }

    @JvmSynthetic
    internal fun beginBinding(hostId: String): BindingClaim {
        val currentHost = activeHostId
        check(currentHost == null || currentHost == hostId) {
            "A BitwebcState can only be attached to one Bitwebc host at a time"
        }
        val previousToken = activeBindingToken
        activeHostId = hostId
        val token = ++nextBindingToken
        activeBindingToken = token
        return BindingClaim(hostId, token, currentHost, previousToken)
    }

    @JvmSynthetic
    internal fun rollbackBinding(claim: BindingClaim) {
        if (activeHostId != claim.hostId || activeBindingToken != claim.token) return
        activeHostId = claim.previousHostId
        activeBindingToken = claim.previousToken
    }

    @JvmSynthetic
    internal fun isBindingActive(token: Long): Boolean = activeBindingToken == token

    @JvmSynthetic
    internal fun bind(newSession: BitwebcSession, token: Long) {
        if (!isBindingActive(token)) {
            newSession.release()
            return
        }
        session = newSession
        acknowledgedUrlRevision = requestedUrlRevision
        requestedFromUrl = null
        supersededRequestedUrls.clear()
        pageState = newSession.state.value
    }

    @JvmSynthetic
    internal fun unbind(expectedSession: BitwebcSession?, token: Long, hostId: String) {
        if (!isBindingActive(token) || activeHostId != hostId) return
        val currentSession = session
        val matchesExpectedSession = expectedSession != null && currentSession === expectedSession
        val currentSessionWasAlreadyReleased =
            currentSession == null && expectedSession?.isReleased == true
        if (matchesExpectedSession || currentSessionWasAlreadyReleased) {
            activeBindingToken = 0L
            activeHostId = null
            session = null
            pageState = pageState.copy(
                isLoading = false,
                canGoBack = false,
                canGoForward = false,
                isFullscreen = false,
                isReleased = true
            )
        }
    }

    @JvmSynthetic
    internal fun updatePageState(
        expectedSession: BitwebcSession,
        value: BitwebcPageState
    ): Boolean {
        if (session !== expectedSession) return false
        acknowledgeRequestedNavigation(value)
        pageState = value
        return value.isReleased
    }

    @JvmSynthetic
    internal fun recreateReleasedSession(expectedSession: BitwebcSession) {
        if (session !== expectedSession || !expectedSession.isReleased) return
        if (requestedUrlRevision == acknowledgedUrlRevision) {
            pageState.url?.takeIf(String::isNotBlank)?.let { requestedUrl = it }
        }
        clearSavedNavigation()
        session = null
        hostGeneration += 1
    }

    @JvmSynthetic
    internal fun emitEvent(token: Long, event: BitwebcEvent): Boolean {
        if (!isBindingActive(token)) return false
        mutableEvents.tryEmit(event)
        return true
    }

    @JvmSynthetic
    internal fun snapshot(activeSession: BitwebcSession?) {
        if (activeSession == null) return
        // A keyed replacement may be created before the old AndroidView receives onRelease.
        // Never let that obsolete Session overwrite the new Session's saved history.
        if (session != null && session !== activeSession) return
        if (requestedUrlRevision == acknowledgedUrlRevision) {
            pageState.url?.takeIf(String::isNotBlank)?.let { requestedUrl = it }
        }
        if (savePolicy != BitwebcSavePolicy.NavigationHistory) {
            clearSavedNavigation()
            return
        }
        val state = Bundle()
        if (activeSession.saveState(state)) {
            savedNavigationState = state
            savedForExternalUrl = lastExternalUrl
        } else {
            // A renderer replacement has no serializable history until it starts navigating.
            // Never retain an older WebView's Bundle: after rotation it would restore stale
            // history. Keep an explicitly queued loadUrl() request, otherwise resume from the
            // latest renderer-independent page URL.
            clearSavedNavigation()
        }
    }

    /** Marks a load command as consumed only after a trustworthy main-frame state transition. */
    @JvmSynthetic
    internal fun acknowledgeRequestedNavigation(value: BitwebcPageState): Boolean {
        if (requestedUrlRevision == acknowledgedUrlRevision) return false
        val matchesRequestedUrl = value.url == requestedUrl
        val matchesSupersededUrl = value.url != null && value.url in supersededRequestedUrls
        val startedRedirect = value.isLoading && !value.isReleased && value.error == null &&
            !value.url.isNullOrBlank() && value.url != requestedFromUrl && !matchesSupersededUrl
        if (!matchesRequestedUrl && !startedRedirect) return false
        acknowledgedUrlRevision = requestedUrlRevision
        requestedFromUrl = null
        supersededRequestedUrls.clear()
        return true
    }

    private fun beginUrlRequest(url: String) {
        if (requestedUrlRevision != acknowledgedUrlRevision) {
            if (supersededRequestedUrls.lastOrNull() != requestedUrl) {
                supersededRequestedUrls.addLast(requestedUrl)
                while (supersededRequestedUrls.size > MAX_SUPERSEDED_REQUESTED_URLS) {
                    supersededRequestedUrls.removeFirst()
                }
            }
        }
        requestedFromUrl = pageState.url
        requestedUrl = url
        requestedUrlRevision += 1
    }

    private fun clearSavedNavigation() {
        savedNavigationState = null
        savedForExternalUrl = null
    }

    private inline fun <T> withActiveSession(block: (BitwebcSession) -> T): T? {
        val activeSession = session?.takeUnless(BitwebcSession::isReleased) ?: return null
        return runCatching { block(activeSession) }.getOrNull()
    }

    internal companion object {
        private const val KEY_INSTANCE_ID = "instance_id"
        private const val KEY_REQUESTED_URL = "requested_url"
        private const val KEY_EXTERNAL_URL = "external_url"
        private const val KEY_NAVIGATION_STATE = "navigation_state"
        private const val KEY_SESSION_KEY = "session_key"
        private const val MAX_SUPERSEDED_REQUESTED_URLS = 16

        @JvmSynthetic
        internal fun create(
            initialUrl: String,
            instanceId: String,
            savePolicy: BitwebcSavePolicy,
            restoredNavigationState: Bundle? = null,
            restoredExternalUrl: String? = initialUrl,
            restoredSessionKey: String? = null
        ): BitwebcState = BitwebcState(
            initialUrl = initialUrl,
            instanceId = instanceId,
            savePolicy = savePolicy,
            restoredNavigationState = restoredNavigationState,
            restoredExternalUrl = restoredExternalUrl,
            restoredSessionKey = restoredSessionKey
        )

        @JvmSynthetic
        fun saver(
            savePolicy: BitwebcSavePolicy,
            currentInitialUrl: String
        ): Saver<BitwebcState, Bundle> = Saver(
            save = { state ->
                // onSaveInstanceState can run before ON_STOP, so capture the current WebView here
                // instead of relying solely on lifecycle/disposal callbacks.
                state.snapshot(state.boundSession)
                Bundle().apply {
                    putString(KEY_INSTANCE_ID, state.instanceId)
                    putString(KEY_REQUESTED_URL, state.requestedUrl)
                    putString(KEY_EXTERNAL_URL, state.lastExternalUrl)
                    putString(KEY_SESSION_KEY, state.lastSessionKey)
                    if (savePolicy == BitwebcSavePolicy.NavigationHistory) {
                        state.savedNavigationState?.let { putBundle(KEY_NAVIGATION_STATE, Bundle(it)) }
                    }
                }
            },
            restore = { bundle ->
                val savedExternalUrl = bundle.getString(KEY_EXTERNAL_URL)
                val initialUrlChanged = savedExternalUrl != null &&
                    savedExternalUrl != currentInitialUrl
                val requestedUrl = if (initialUrlChanged) {
                    currentInitialUrl
                } else {
                    bundle.getString(KEY_REQUESTED_URL).orEmpty()
                }
                if (requestedUrl.isBlank()) {
                    null
                } else {
                    create(
                        initialUrl = requestedUrl,
                        instanceId = bundle.getString(KEY_INSTANCE_ID)
                            ?: UUID.randomUUID().toString(),
                        savePolicy = savePolicy,
                        restoredNavigationState = if (initialUrlChanged) {
                            null
                        } else {
                            bundle.getBundle(KEY_NAVIGATION_STATE)
                        },
                        restoredExternalUrl = if (initialUrlChanged) {
                            currentInitialUrl
                        } else {
                            savedExternalUrl
                        },
                        restoredSessionKey = bundle.getString(KEY_SESSION_KEY)
                    )
                }
            }
        )
    }
}

/** Remembers a controller and restores its WebView history according to [savePolicy]. */
@Composable
fun rememberBitwebcState(
    initialUrl: String,
    savePolicy: BitwebcSavePolicy = BitwebcSavePolicy.NavigationHistory
): BitwebcState {
    require(initialUrl.isNotBlank()) { "initialUrl must not be blank" }
    val saver = BitwebcState.saver(savePolicy, initialUrl)
    val state = rememberSaveable(savePolicy, saver = saver) {
        BitwebcState.create(
            initialUrl = initialUrl,
            instanceId = UUID.randomUUID().toString(),
            savePolicy = savePolicy
        )
    }
    LaunchedEffect(state, initialUrl) {
        state.acceptExternalUrl(initialUrl)
    }
    return state
}
