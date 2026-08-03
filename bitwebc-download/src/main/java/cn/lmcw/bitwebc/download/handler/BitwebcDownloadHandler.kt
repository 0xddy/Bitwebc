package cn.lmcw.bitwebc.download.handler

import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import cn.lmcw.bitwebc.download.DownloadController
import cn.lmcw.bitwebc.download.config.DownloadConfig
import cn.lmcw.bitwebc.download.ext.DataUriDecoder
import cn.lmcw.bitwebc.download.ext.DataUriHeader
import cn.lmcw.bitwebc.download.ext.DataUriResult
import cn.lmcw.bitwebc.download.ext.extractFileNameFromContentDisposition
import cn.lmcw.bitwebc.download.ext.guessFileName
import cn.lmcw.bitwebc.download.ext.normalizeMimeType
import cn.lmcw.bitwebc.download.ext.resolveDownloadFileName
import cn.lmcw.bitwebc.download.model.DownloadRequest
import cn.lmcw.bitwebc.download.model.DownloadTaskState
import cn.lmcw.bitwebc.download.model.isTerminal
import cn.lmcw.bitwebc.download.notification.DownloadNotificationHelper
import cn.lmcw.bitwebc.download.storage.DownloadStorage
import cn.lmcw.bitwebc.download.storage.cleanupStaleBitwebcDownloadFiles
import cn.lmcw.bitwebc.download.ui.DefaultDownloadConfirmUi
import cn.lmcw.bitwebc.download.ui.DownloadConfirmUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.FilterOutputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class BitwebcDownloadHandler(
    activity: ComponentActivity,
    private val config: DownloadConfig = DownloadConfig(),
    eventReporter: ((BitwebcEvent) -> Unit)? = null
) : DownloadController {

    private data class DataUriPayload(
        val header: DataUriHeader,
        val mimeType: String
    )

    private data class PendingConfirmation(
        val request: DownloadRequest,
        val ui: DownloadConfirmUi
    )

    private val activityRef = WeakReference(activity)
    private val released = AtomicBoolean(false)
    private val eventReporterRef = AtomicReference(eventReporter)
    private val sessionReadyCallbackRef =
        AtomicReference<((DownloadController) -> Unit)?>(null)
    private val appContext = activity.applicationContext
    private val storage: DownloadStorage = config.resolveStorage(appContext)
    private val downloadClient = config.okHttpClient.newBuilder()
        .addNetworkInterceptor { chain ->
            val originalRequest = chain.request()
            val webViewCookie = runCatching {
                CookieManager.getInstance().getCookie(originalRequest.url.toString())
            }.getOrNull()
            val clientCookies = originalRequest.headers("Cookie")
                .joinToString("; ")
                .takeIf { it.isNotBlank() }
            val mergedCookie = mergeCookieHeaders(clientCookies, webViewCookie)
            val request = originalRequest.newBuilder()
                .removeHeader("Cookie")
                .apply {
                    mergedCookie?.let { header("Cookie", it) }
                }
                .build()
            chain.proceed(request).also { response ->
                val responseUrl = response.request.url.toString()
                val setCookies = response.headers("Set-Cookie")
                if (setCookies.isNotEmpty()) {
                    runCatching {
                        val cookieManager = CookieManager.getInstance()
                        setCookies.forEach { value -> cookieManager.setCookie(responseUrl, value) }
                        cookieManager.flush()
                    }.onFailure { Log.w(TAG, "Unable to persist response cookies to WebView", it) }
                }
            }
        }
        .build()
    private val notificationHelper = DownloadNotificationHelper(
        context = appContext,
        channelId = config.notificationChannelId,
        channelName = config.notificationChannelName,
        channelDescription = config.notificationChannelDescription
    )

    private val taskStateMap = MutableStateFlow<Map<String, DownloadTaskState>>(emptyMap())
    override val tasks: StateFlow<Map<String, DownloadTaskState>> = taskStateMap.asStateFlow()
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = ConcurrentHashMap<String, DownloadRequest>()
    private val dataUriPayloads = ConcurrentHashMap<String, DataUriPayload>()
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val runningCalls = ConcurrentHashMap<String, Call>()
    private val attempts = DownloadAttemptRegistry()
    private val semaphore = Semaphore(config.maxConcurrentDownloads)
    private val notificationLock = Any()
    private val suppressedNotificationTasks = mutableSetOf<String>()
    private val notificationIds = ConcurrentHashMap<String, Int>()
    private val lastReportedProgress = ConcurrentHashMap<String, Int>()
    private val lastReportedBytes = ConcurrentHashMap<String, Long>()

    private val confirmationLock = Any()
    private val pendingConfirmations = mutableMapOf<String, PendingConfirmation>()
    private val activityLifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) release()
    }

    init {
        downloadScope.launch {
            runCatching { cleanupStaleBitwebcDownloadFiles(appContext) }
                .onFailure { Log.w(TAG, "Unable to clean stale download files", it) }
        }
        runCatching { notificationHelper.ensureChannel() }
            .onFailure { Log.w(TAG, "Unable to create the download notification channel", it) }
        if (activity.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            release()
        } else {
            activity.lifecycle.addObserver(activityLifecycleObserver)
        }
    }

    override fun onDownloadStart(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long
    ) {
        if (released.get() || url.isNullOrBlank()) return
        // Notification permission is host-managed and never blocks the actual download. This
        // avoids tying durable work to an ActivityResult callback that cannot survive a host
        // recreation; without permission the task still runs and simply suppresses notifications.
        dispatchDownload(url, userAgent, contentDisposition, mimetype, contentLength)
    }

    private fun dispatchDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        when (val scheme = url.toUri().scheme?.lowercase()) {
            "http", "https" -> enqueue(
                DownloadRequest(
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    contentLength = contentLength
                )
            )
            "data" -> enqueueDataUri(url, mimeType, contentDisposition)
            else -> Log.w(TAG, "Unsupported download scheme=$scheme")
        }
    }

    private fun hasNotificationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, NOTIFICATION_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    override fun enqueue(request: DownloadRequest): String {
        check(!released.get()) { "BitwebcDownloadHandler has already been released" }
        val taskId = UUID.randomUUID().toString()
        if (config.confirmBeforeDownload) {
            registerPendingConfirmation(taskId, request) {
                prepareEnqueue(taskId, request)
            }
        } else {
            enqueueInternal(taskId, request)
        }
        return taskId
    }

    private fun enqueueInternal(taskId: String, request: DownloadRequest) {
        prepareEnqueue(taskId, request).invoke()
    }

    /** Performs only internal registration and returns the callback-producing start phase. */
    private fun prepareEnqueue(
        taskId: String,
        request: DownloadRequest,
        dataUriPayload: DataUriPayload? = null
    ): () -> Unit {
        requests[taskId] = request
        if (dataUriPayload != null) dataUriPayloads[taskId] = dataUriPayload
        handlersByTaskId[taskId] = this
        synchronized(notificationLock) { suppressedNotificationTasks.remove(taskId) }
        lastReportedProgress.remove(taskId)
        lastReportedBytes.remove(taskId)
        updateTask(taskId, DownloadTaskState.Queued(id = taskId, url = request.url))
        return start@{
            if (taskStateMap.value[taskId] !is DownloadTaskState.Queued) return@start
            reportEventSafely(BitwebcEvent.DownloadQueued(taskId, request.url))
            startTask(taskId)
        }
    }

    private fun enqueueDataUri(
        dataUrl: String,
        hintMimeType: String?,
        contentDisposition: String?
    ) {
        // Size calculation scans the encoded payload; keep large data URIs off the UI thread.
        downloadScope.launch {
            prepareDataUriDownload(dataUrl, hintMimeType, contentDisposition)
        }
    }

    private fun prepareDataUriDownload(
        dataUrl: String,
        hintMimeType: String?,
        contentDisposition: String?
    ) {
        if (released.get()) return
        val suggestedFileName = extractFileNameFromContentDisposition(contentDisposition)
        val header = when (
            val result = DataUriDecoder.parse(
                dataUrl,
                config.dataUriMaxBytes,
                suggestedFileName,
                hintMimeType
            )
        ) {
            is DataUriResult.Ready -> result.header
            is DataUriResult.TooLarge -> {
                val message = "data URI exceeds ${result.maxBytes} byte limit"
                reportEventSafely(BitwebcEvent.DownloadFailed("", message))
                return
            }
            is DataUriResult.InvalidFormat -> {
                reportEventSafely(BitwebcEvent.DownloadFailed("", "Invalid data URI"))
                return
            }
        }

        val taskId = UUID.randomUUID().toString()
        val mimeType = header.mimeType
        val request = DownloadRequest(
            url = "data:[${header.estimatedBytes} bytes]",
            contentDisposition = contentDisposition,
            mimeType = mimeType,
            contentLength = header.estimatedBytes
        )
        val prepareAction = {
            prepareEnqueue(taskId, request, DataUriPayload(header, mimeType))
        }
        if (config.confirmBeforeDownload) {
            registerPendingConfirmation(taskId, request, prepareAction)
        } else {
            prepareAction().invoke()
        }
    }

    private fun registerPendingConfirmation(
        taskId: String,
        request: DownloadRequest,
        prepareOnConfirm: () -> (() -> Unit)
    ) {
        if (released.get()) return
        val configuredUi = config.confirmUi
        val confirmUi = if (configuredUi == null || configuredUi is DefaultDownloadConfirmUi) {
            DefaultDownloadConfirmUi()
        } else {
            configuredUi
        }
        val pending = PendingConfirmation(request, confirmUi)
        synchronized(confirmationLock) { pendingConfirmations[taskId] = pending }
        showDownloadConfirmDialog(
            request = request,
            confirmUi = confirmUi,
            shouldShow = {
                synchronized(confirmationLock) { pendingConfirmations[taskId] === pending }
            }
        ) { confirmed ->
            val afterConfirmation = synchronized(confirmationLock) {
                if (pendingConfirmations[taskId] !== pending) return@synchronized null
                pendingConfirmations.remove(taskId)
                if (confirmed) prepareOnConfirm() else null
            }
            afterConfirmation?.invoke()
        }
    }

    private fun showDownloadConfirmDialog(
        request: DownloadRequest,
        confirmUi: DownloadConfirmUi,
        shouldShow: () -> Boolean,
        onDecision: (Boolean) -> Unit
    ) {
        val decisionMade = java.util.concurrent.atomic.AtomicBoolean(false)

        fun deliverDecision(confirmed: Boolean) {
            if (!decisionMade.compareAndSet(false, true)) return
            runCatching { onDecision(confirmed) }.onFailure { error ->
                Log.w(TAG, "Unable to apply download confirmation decision", error)
                reportEventSafely(
                    BitwebcEvent.DownloadFailed("", "Unable to start confirmed download")
                )
            }
        }

        val currentActivity = activityRef.get()
        if (currentActivity == null || currentActivity.isFinishing || currentActivity.isDestroyed) {
            reportEventSafely(
                BitwebcEvent.DownloadFailed("", "Unable to show download confirmation")
            )
            deliverDecision(false)
            return
        }
        val showDialog: () -> Unit = show@{
            if (!shouldShow()) {
                deliverDecision(false)
                return@show
            }
            runCatching {
                confirmUi.confirm(currentActivity, request, ::deliverDecision)
            }.onFailure { error ->
                if (!decisionMade.get()) {
                    reportEventSafely(
                        BitwebcEvent.DownloadFailed("", "Unable to show download confirmation")
                    )
                }
                deliverDecision(false)
                Log.w(TAG, "Download confirmation UI failed", error)
            }
        }
        runCatching { currentActivity.runOnUiThread(showDialog) }.onFailure { error ->
            if (!decisionMade.get()) {
                reportEventSafely(
                    BitwebcEvent.DownloadFailed("", "Unable to show download confirmation")
                )
            }
            deliverDecision(false)
            Log.w(TAG, "Unable to schedule download confirmation UI", error)
        }
    }

    override fun cancel(taskId: String) {
        val pendingConfirmation = synchronized(confirmationLock) {
            pendingConfirmations.remove(taskId)
        }
        if (pendingConfirmation != null) {
            dismissConfirmation(pendingConfirmation)
            return
        }

        val cancelled = attempts.invalidateIfAndRun(
            taskId,
            condition = {
                when (taskStateMap.value[taskId]) {
                    null, is DownloadTaskState.Success, is DownloadTaskState.Cancelled -> false
                    else -> true
                }
            }
        ) {
            val transitioned = transitionTask(
                taskId,
                predicate = { it !is DownloadTaskState.Success && it !is DownloadTaskState.Cancelled },
                updater = { state ->
                    DownloadTaskState.Cancelled(
                        state.id,
                        state.url,
                        state.fileName,
                        state.createdAtMillis
                    )
                }
            )
            if (!transitioned) return@invalidateIfAndRun false

            val current = taskStateMap.value[taskId] as DownloadTaskState.Cancelled
            val request = requests[taskId]
            runningCalls.remove(taskId)?.cancel()
            runningJobs.remove(taskId)?.cancel(CancellationException("Download cancelled"))
            synchronized(notificationLock) {
                suppressedNotificationTasks += taskId
                if (areNotificationsEnabled() && request != null) {
                    runCatching {
                        notificationHelper.showCancelled(
                            notificationId(taskId),
                            current.fileName ?: suggestedFileName(taskId, request)
                        )
                    }.onFailure { Log.w(TAG, "Unable to show cancelled notification", it) }
                }
            }
            handlersByTaskId.remove(taskId)
            lastReportedProgress.remove(taskId)
            lastReportedBytes.remove(taskId)
            if (dataUriPayloads.remove(taskId) != null) {
                // A cancelled data URI can otherwise retain its entire encoded payload indefinitely.
                requests.remove(taskId)
            }
            true
        } ?: false
        if (!cancelled) return
        trimRetainedTerminalTasks()
    }

    private fun dismissConfirmation(pending: PendingConfirmation) {
        val activity = activityRef.get() ?: return
        runCatching {
            activity.runOnUiThread {
                runCatching { pending.ui.cancel(pending.request) }
                    .onFailure { Log.w(TAG, "Unable to dismiss download confirmation", it) }
            }
        }.onFailure { Log.w(TAG, "Unable to schedule confirmation dismissal", it) }
    }

    /** Pause cancels the current attempt. Resume starts the payload from byte zero. */
    override fun pause(taskId: String) {
        val paused = attempts.invalidateIfAndRun(
            taskId,
            condition = {
                when (taskStateMap.value[taskId]) {
                    is DownloadTaskState.Queued, is DownloadTaskState.Running -> true
                    else -> false
                }
            }
        ) {
            val transitioned = transitionTask(
                taskId,
                predicate = {
                    it is DownloadTaskState.Queued || it is DownloadTaskState.Running
                },
                updater = { state ->
                    DownloadTaskState.Paused(
                        state.id,
                        state.url,
                        state.fileName,
                        state.createdAtMillis
                    )
                }
            )
            if (!transitioned) return@invalidateIfAndRun false

            runningCalls.remove(taskId)?.cancel()
            runningJobs.remove(taskId)?.cancel(CancellationException("Download paused"))
            synchronized(notificationLock) {
                suppressedNotificationTasks += taskId
                runCatching { notificationHelper.cancel(notificationId(taskId)) }
                    .onFailure { Log.w(TAG, "Unable to cancel paused notification", it) }
            }
            handlersByTaskId.remove(taskId)
            lastReportedProgress.remove(taskId)
            lastReportedBytes.remove(taskId)
            true
        } ?: false
        if (!paused) return
    }

    override fun resume(taskId: String) {
        if (requests[taskId] == null) return

        val resumed = attempts.invalidateIfAndRun(
            taskId,
            condition = { taskStateMap.value[taskId] is DownloadTaskState.Paused }
        ) {
            transitionTask(
                taskId,
                predicate = { it is DownloadTaskState.Paused },
                updater = { current ->
                    DownloadTaskState.Queued(
                        current.id,
                        current.url,
                        current.fileName,
                        current.createdAtMillis
                    )
                }
            ).also { transitioned ->
                if (transitioned) prepareRestart(taskId)
            }
        } ?: false
        if (!resumed) return
        startTask(taskId)
    }

    override fun retry(taskId: String) {
        if (requests[taskId] == null) return

        val retried = attempts.invalidateIfAndRun(
            taskId,
            condition = {
                requests[taskId] != null && when (taskStateMap.value[taskId]) {
                    is DownloadTaskState.Failed, is DownloadTaskState.Cancelled -> true
                    else -> false
                }
            }
        ) {
            transitionTask(
                taskId,
                predicate = {
                    it is DownloadTaskState.Failed || it is DownloadTaskState.Cancelled
                },
                updater = { current ->
                    DownloadTaskState.Queued(
                        current.id,
                        current.url,
                        null,
                        current.createdAtMillis
                    )
                }
            ).also { transitioned ->
                if (transitioned) prepareRestart(taskId)
            }
        } ?: false
        if (!retried) return
        startTask(taskId)
    }

    /** Releases a retained terminal task without affecting downloaded files. */
    override fun forget(taskId: String): Boolean {
        return attempts.invalidateIfAndRun(
            taskId,
            condition = {
                taskStateMap.value[taskId]?.isTerminal == true
            },
            action = {
                cleanupTaskMetadata(taskId, removeState = true)
                true
            }
        ) ?: false
    }

    private fun prepareRestart(taskId: String) {
        handlersByTaskId[taskId] = this
        synchronized(notificationLock) {
            suppressedNotificationTasks.remove(taskId)
            runCatching { notificationHelper.cancel(notificationId(taskId)) }
                .onFailure { Log.w(TAG, "Unable to clear previous notification", it) }
        }
        lastReportedProgress.remove(taskId)
        lastReportedBytes.remove(taskId)
    }

    private fun startTask(taskId: String) {
        val request = requests[taskId] ?: return
        val attemptId = attempts.beginIf(taskId) {
            taskStateMap.value[taskId] is DownloadTaskState.Queued
        } ?: return

        val job = downloadScope.launch(start = CoroutineStart.LAZY) {
            try {
                semaphore.withPermit {
                    currentCoroutineContext().ensureActive()
                    if (!attempts.isCurrent(taskId, attemptId)) {
                        throw CancellationException("Download attempt superseded")
                    }
                    val notificationsEnabled = areNotificationsEnabled()
                    if (!notificationsEnabled) {
                        reportEventSafely(BitwebcEvent.DownloadPermissionDenied(taskId))
                    }
                    val dataUriPayload = dataUriPayloads[taskId]
                    if (dataUriPayload != null) {
                        streamDataUriToStorage(
                            taskId,
                            attemptId,
                            request,
                            dataUriPayload,
                            notificationsEnabled
                        )
                    } else {
                        downloadFile(taskId, attemptId, request, notificationsEnabled)
                    }
                }
            } catch (error: Throwable) {
                handleAttemptFailure(taskId, attemptId, request, error)
            } finally {
                val currentJob = currentCoroutineContext()[Job]
                if (currentJob != null) runningJobs.remove(taskId, currentJob)
                attempts.finish(taskId, attemptId)
            }
        }
        val registered = attempts.runIfCurrent(taskId, attemptId) {
            runningJobs[taskId] = job
        }
        if (!registered) {
            job.cancel(CancellationException("Download attempt superseded before start"))
            return
        }
        job.start()
    }

    private fun handleAttemptFailure(
        taskId: String,
        attemptId: Long,
        request: DownloadRequest,
        error: Throwable
    ) {
        val message = error.message ?: "Download failed"
        var shouldReportFailure = false
        val currentAttempt = attempts.runIfCurrent(taskId, attemptId) {
            val failedStateWritten = transitionTask(
                taskId,
                predicate = {
                    it is DownloadTaskState.Queued || it is DownloadTaskState.Running
                },
                updater = { state ->
                    DownloadTaskState.Failed(
                        state.id,
                        state.url,
                        state.fileName,
                        error,
                        state.createdAtMillis
                    )
                }
            )
            if (!failedStateWritten) return@runIfCurrent

            handlersByTaskId.remove(taskId)
            lastReportedProgress.remove(taskId)
            lastReportedBytes.remove(taskId)
            synchronized(notificationLock) {
                suppressedNotificationTasks += taskId
                if (areNotificationsEnabled()) {
                    runCatching { notificationHelper.showFailed(notificationId(taskId), message) }
                        .onFailure { Log.w(TAG, "Unable to show failed notification", it) }
                }
            }
            if (dataUriPayloads.remove(taskId) != null) {
                // The encoded source can be many MiB. Failed data URI downloads are not retained.
                requests.remove(taskId, request)
            }
            shouldReportFailure = true
        }
        if (!currentAttempt || !shouldReportFailure) return

        Log.e(TAG, "Download failed: taskId=$taskId", error)
        if (attempts.isCurrent(taskId, attemptId)) {
            reportEventSafely(BitwebcEvent.DownloadFailed(taskId, message))
        }
        trimRetainedTerminalTasks()
    }

    private suspend fun downloadFile(
        taskId: String,
        attemptId: Long,
        request: DownloadRequest,
        notificationsEnabled: Boolean
    ) = withContext(Dispatchers.IO) {
        val httpRequest = Request.Builder().url(request.url).apply {
            request.userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
        }.build()
        val call = downloadClient.newCall(httpRequest)
        val registered = attempts.runIfCurrent(taskId, attemptId) {
            runningCalls[taskId] = call
        }
        if (!registered) {
            call.cancel()
            throw CancellationException("Download attempt superseded before HTTP execution")
        }
        try {
            ensureCurrentAttempt(taskId, attemptId)
            call.execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")

                val responseContentDisposition = response.header("Content-Disposition")
                    ?.takeIf { it.isNotBlank() }
                val resolvedUrl = response.request.url.toString()
                val resolvedMime = response.body.contentType()?.toString()
                    ?: request.mimeType
                    ?: "application/octet-stream"
                val fileName = resolveDownloadFileName(
                    originalUrl = request.url,
                    finalUrl = resolvedUrl,
                    contentDisposition = responseContentDisposition ?: request.contentDisposition,
                    mimeType = resolvedMime
                )
                val storageMimeType = resolvedMime.normalizeMimeType(fileName)
                val responseLength = response.body.contentLength()
                val totalBytes = responseLength.takeIf { it >= 0L }
                    ?: request.contentLength.takeIf { it > 0L }
                    ?: -1L
                val sink = storage.createSink(appContext, fileName, storageMimeType)
                    ?: throw IllegalStateException("Unable to create download destination")
                var committed = false
                try {
                    ensureCurrentAttempt(taskId, attemptId)
                    var enteredRunningState = false
                    val stillCurrent = attempts.runIfCurrent(taskId, attemptId) {
                        enteredRunningState = transitionTask(
                            taskId,
                            predicate = { it is DownloadTaskState.Queued },
                            updater = { current ->
                                DownloadTaskState.Running(
                                    current.id,
                                    current.url,
                                    fileName,
                                    0L,
                                    totalBytes,
                                    current.createdAtMillis
                                )
                            }
                        )
                    }
                    if (!stillCurrent || !enteredRunningState) {
                        throw CancellationException("Download is no longer queued")
                    }
                    val notificationId = notificationId(taskId)
                    synchronized(notificationLock) {
                        if (notificationsEnabled && taskId !in suppressedNotificationTasks) {
                            runCatching {
                                notificationHelper.showProgress(
                                    notificationId,
                                    taskId,
                                    fileName,
                                    0L,
                                    totalBytes
                                )
                            }.onFailure { Log.w(TAG, "Unable to show initial progress", it) }
                        }
                    }
                    lastReportedProgress[taskId] = 0
                    if (totalBytes >= config.foregroundPolicy.largeFileThresholdBytes &&
                        attempts.isCurrent(taskId, attemptId)
                    ) {
                        runCatching {
                            config.foregroundPolicy.onLargeFileTask?.invoke(taskId, fileName)
                        }.onFailure { Log.w(TAG, "Large-file callback failed", it) }
                    }

                    var downloadedBytes = 0L
                    val bufferedOutput = BufferedOutputStream(sink.outputStream, config.bufferSizeBytes)
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(config.bufferSizeBytes)
                        while (true) {
                            ensureCurrentAttempt(taskId, attemptId)
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            bufferedOutput.write(buffer, 0, read)
                            downloadedBytes += read
                            var runningStateUpdated = false
                            val updatedCurrentAttempt = attempts.runIfCurrent(taskId, attemptId) {
                                runningStateUpdated = transitionTask(
                                    taskId,
                                    predicate = { it is DownloadTaskState.Running },
                                    updater = { current ->
                                        (current as DownloadTaskState.Running).copy(
                                            downloadedBytes = downloadedBytes
                                        )
                                    }
                                )
                            }
                            if (!updatedCurrentAttempt || !runningStateUpdated) {
                                throw CancellationException("Download attempt superseded")
                            }
                            val progress = if (totalBytes > 0L) {
                                ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                -1
                            }
                            maybeReportProgress(
                                taskId,
                                notificationId,
                                fileName,
                                downloadedBytes,
                                totalBytes,
                                progress,
                                notificationsEnabled
                            )
                        }
                    }
                    bufferedOutput.flush()
                    ensureCurrentAttempt(taskId, attemptId)
                    var successStateWritten = false
                    val published = attempts.runIfCurrent(taskId, attemptId) {
                        sink.commit()
                        committed = true
                        successStateWritten = transitionTask(
                            taskId,
                            predicate = { it is DownloadTaskState.Running },
                            updater = { current ->
                                DownloadTaskState.Success(
                                    current.id,
                                    current.url,
                                    fileName,
                                    downloadedBytes,
                                    current.createdAtMillis
                                )
                            }
                        )
                    }
                    check(published && successStateWritten) {
                        "Download attempt changed while publishing the destination"
                    }
                    markSuccess(
                        taskId,
                        request,
                        fileName,
                        downloadedBytes,
                        sink.uri,
                        storageMimeType,
                        notificationsEnabled
                    )
                } finally {
                    if (!committed) {
                        runCatching { sink.abort() }
                            .onFailure { Log.w(TAG, "Unable to clean partial download", it) }
                    }
                }
            }
        } finally {
            runningCalls.remove(taskId, call)
        }
    }

    private suspend fun streamDataUriToStorage(
        taskId: String,
        attemptId: Long,
        request: DownloadRequest,
        payload: DataUriPayload,
        notificationsEnabled: Boolean
    ) = withContext(Dispatchers.IO) {
        val fileName = payload.header.fileName
        val storageMimeType = payload.mimeType.normalizeMimeType(fileName)
        val sink = storage.createSink(appContext, fileName, storageMimeType)
            ?: throw IllegalStateException("Unable to create download destination")
        var committed = false
        try {
            ensureCurrentAttempt(taskId, attemptId)
            var enteredRunningState = false
            val stillCurrent = attempts.runIfCurrent(taskId, attemptId) {
                enteredRunningState = transitionTask(
                    taskId,
                    predicate = { it is DownloadTaskState.Queued },
                    updater = { current ->
                        DownloadTaskState.Running(
                            current.id,
                            current.url,
                            fileName,
                            0L,
                            payload.header.estimatedBytes,
                            current.createdAtMillis
                        )
                    }
                )
            }
            if (!stillCurrent || !enteredRunningState) {
                throw CancellationException("Download is no longer queued")
            }
            val notificationId = notificationId(taskId)
            synchronized(notificationLock) {
                if (notificationsEnabled && taskId !in suppressedNotificationTasks) {
                    runCatching {
                        notificationHelper.showProgress(
                            notificationId,
                            taskId,
                            fileName,
                            0L,
                            payload.header.estimatedBytes
                        )
                    }.onFailure { Log.w(TAG, "Unable to show initial data URI progress", it) }
                }
            }
            lastReportedProgress[taskId] = 0
            if (payload.header.estimatedBytes >= config.foregroundPolicy.largeFileThresholdBytes &&
                attempts.isCurrent(taskId, attemptId)
            ) {
                runCatching {
                    config.foregroundPolicy.onLargeFileTask?.invoke(taskId, fileName)
                }.onFailure { Log.w(TAG, "Large-file callback failed", it) }
            }
            val decodingJob = currentCoroutineContext()[Job]
            val progressOutput = ProgressOutputStream(sink.outputStream) { writtenBytes ->
                var runningStateUpdated = false
                val updatedCurrentAttempt = attempts.runIfCurrent(taskId, attemptId) {
                    runningStateUpdated = transitionTask(
                        taskId,
                        predicate = { it is DownloadTaskState.Running },
                        updater = { current ->
                            (current as DownloadTaskState.Running).copy(
                                downloadedBytes = writtenBytes
                            )
                        }
                    )
                }
                if (!updatedCurrentAttempt || !runningStateUpdated) {
                    throw InterruptedIOException("Download attempt superseded")
                }
                val totalBytes = payload.header.estimatedBytes
                val progress = if (totalBytes > 0L) {
                    ((writtenBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                } else {
                    -1
                }
                maybeReportProgress(
                    taskId,
                    notificationId,
                    fileName,
                    writtenBytes,
                    totalBytes,
                    progress,
                    notificationsEnabled
                )
            }
            val bufferedOutput = BufferedOutputStream(progressOutput, config.bufferSizeBytes)
            val writtenBytes = DataUriDecoder.streamTo(payload.header, bufferedOutput) {
                decodingJob?.isActive == true && attempts.isCurrent(taskId, attemptId)
            }
            bufferedOutput.flush()
            ensureCurrentAttempt(taskId, attemptId)
            var successStateWritten = false
            val published = attempts.runIfCurrent(taskId, attemptId) {
                sink.commit()
                committed = true
                successStateWritten = transitionTask(
                    taskId,
                    predicate = { it is DownloadTaskState.Running },
                    updater = { current ->
                        DownloadTaskState.Success(
                            current.id,
                            current.url,
                            fileName,
                            writtenBytes,
                            current.createdAtMillis
                        )
                    }
                )
            }
            check(published && successStateWritten) {
                "Download attempt changed while publishing the destination"
            }
            markSuccess(
                taskId,
                request,
                fileName,
                writtenBytes,
                sink.uri,
                storageMimeType,
                notificationsEnabled
            )
        } finally {
            if (!committed) {
                runCatching { sink.abort() }
                    .onFailure { Log.w(TAG, "Unable to clean partial data URI download", it) }
            }
        }
    }

    private suspend fun ensureCurrentAttempt(taskId: String, attemptId: Long) {
        currentCoroutineContext().ensureActive()
        if (!attempts.isCurrent(taskId, attemptId)) {
            throw CancellationException("Download attempt superseded")
        }
    }

    private fun markSuccess(
        taskId: String,
        request: DownloadRequest,
        fileName: String,
        totalBytes: Long,
        uri: android.net.Uri,
        mimeType: String,
        notificationsEnabled: Boolean
    ) {
        if (taskStateMap.value[taskId] !is DownloadTaskState.Success) return

        val id = notificationId(taskId)
        handlersByTaskId.remove(taskId)
        lastReportedProgress.remove(taskId)
        lastReportedBytes.remove(taskId)
        requests.remove(taskId, request)
        dataUriPayloads.remove(taskId)
        synchronized(notificationLock) {
            suppressedNotificationTasks += taskId
            if (notificationsEnabled) {
                runCatching { notificationHelper.showSuccess(id, fileName, uri, mimeType) }
                    .onFailure { Log.w(TAG, "Unable to show download success notification", it) }
            }
        }
        reportEventSafely(BitwebcEvent.DownloadSuccess(taskId, fileName))
        cleanupTaskMetadata(taskId, removeState = false)
        trimRetainedTerminalTasks()
    }

    private fun maybeReportProgress(
        taskId: String,
        notificationId: Int,
        fileName: String,
        downloadedBytes: Long,
        totalBytes: Long,
        progress: Int,
        notificationsEnabled: Boolean
    ) {
        var shouldReportEvent = false
        synchronized(notificationLock) {
            if (taskId in suppressedNotificationTasks) return
            if (progress >= 0) {
                val previous = lastReportedProgress.put(taskId, progress)
                if (previous == progress) return
            } else {
                val previousBytes = lastReportedBytes[taskId] ?: 0L
                if (downloadedBytes - previousBytes < UNKNOWN_LENGTH_REPORT_STEP_BYTES) return
                lastReportedBytes[taskId] = downloadedBytes
            }
            if (notificationsEnabled) {
                runCatching {
                    notificationHelper.showProgress(
                        notificationId,
                        taskId,
                        fileName,
                        downloadedBytes,
                        totalBytes
                    )
                }.onFailure { Log.w(TAG, "Unable to update download notification", it) }
            }
            shouldReportEvent = true
        }
        if (shouldReportEvent) {
            reportEventSafely(BitwebcEvent.DownloadProgress(taskId, fileName, progress))
        }
    }

    private fun reportEventSafely(event: BitwebcEvent) {
        runCatching { eventReporterRef.get()?.invoke(event) }
            .onFailure { Log.w(TAG, "Download event listener failed", it) }
    }

    @JvmSynthetic
    internal fun whenSessionReady(callback: (DownloadController) -> Unit) {
        check(!released.get()) { "Download handler has already been released" }
        check(sessionReadyCallbackRef.compareAndSet(null, callback)) {
            "A Session-ready callback is already registered"
        }
    }

    override fun onSessionReady() {
        if (released.get()) return
        sessionReadyCallbackRef.getAndSet(null)?.invoke(this)
    }

    /**
     * Detaches Activity/Session-owned resources while allowing downloads that already started to
     * finish in the application-scoped IO pipeline.
     */
    override fun release() {
        if (!released.compareAndSet(false, true)) return

        // Stop retaining the Session (and its replaceable WebView) before asynchronous work emits
        // another event. An already-running callback may finish, but no later event can acquire it.
        eventReporterRef.set(null)
        sessionReadyCallbackRef.set(null)
        activityRef.get()?.lifecycle?.removeObserver(activityLifecycleObserver)
        val confirmations = synchronized(confirmationLock) {
            pendingConfirmations.values.toList().also { pendingConfirmations.clear() }
        }
        confirmations.forEach(::dismissConfirmation)
    }

    private fun cleanupTaskMetadata(taskId: String, removeState: Boolean) {
        handlersByTaskId.remove(taskId)
        requests.remove(taskId)
        dataUriPayloads.remove(taskId)
        lastReportedProgress.remove(taskId)
        lastReportedBytes.remove(taskId)
        notificationIds.remove(taskId)
        synchronized(notificationLock) { suppressedNotificationTasks.remove(taskId) }
        if (removeState) {
            taskStateMap.update { current -> current - taskId }
        }
    }

    private fun trimRetainedTerminalTasks() {
        val terminal = taskStateMap.value.values
            .filter { it.isTerminal }
        val overflow = (terminal.size - MAX_RETAINED_TERMINAL_TASKS).coerceAtLeast(0)
        terminal.take(overflow).forEach { state ->
            attempts.invalidateIfAndRun(
                state.id,
                condition = {
                    taskStateMap.value[state.id]?.isTerminal == true
                },
                action = { cleanupTaskMetadata(state.id, removeState = true) }
            )
        }
    }

    private fun suggestedFileName(taskId: String, request: DownloadRequest): String =
        dataUriPayloads[taskId]?.header?.fileName
            ?: request.url.guessFileName(request.contentDisposition, request.mimeType)

    private fun areNotificationsEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasNotificationPermission()

    private fun notificationId(taskId: String): Int = notificationIds.computeIfAbsent(taskId) {
        nextNotificationId.updateAndGet { current -> if (current == Int.MAX_VALUE) 1 else current + 1 }
    }

    private fun updateTask(taskId: String, value: DownloadTaskState) {
        taskStateMap.update { current -> current.toMutableMap().apply { put(taskId, value) } }
    }

    private fun updateTask(taskId: String, updater: (DownloadTaskState) -> DownloadTaskState) {
        taskStateMap.update { currentMap ->
            val current = currentMap[taskId] ?: return@update currentMap
            currentMap.toMutableMap().apply { put(taskId, updater(current)) }
        }
    }

    private fun transitionTask(
        taskId: String,
        predicate: (DownloadTaskState) -> Boolean,
        updater: (DownloadTaskState) -> DownloadTaskState
    ): Boolean {
        while (true) {
            val snapshot = taskStateMap.value
            val current = snapshot[taskId] ?: return false
            if (!predicate(current)) return false
            val updated = updater(current)
            val replacement = LinkedHashMap(snapshot)
            if (updated.isTerminal && !current.isTerminal) {
                // Move newly completed tasks to the end so terminal retention follows
                // completion order instead of the time a long-running task was created.
                replacement.remove(taskId)
            }
            replacement[taskId] = updated
            if (taskStateMap.compareAndSet(snapshot, replacement)) return true
        }
    }

    companion object {
        private const val TAG = "BitwebcDownload"
        private const val NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS"
        private const val UNKNOWN_LENGTH_REPORT_STEP_BYTES = 256L * 1024L
        private const val MAX_RETAINED_TERMINAL_TASKS = 64
        private val handlersByTaskId = ConcurrentHashMap<String, BitwebcDownloadHandler>()
        private val nextNotificationId = AtomicInteger(10_000)

        @JvmSynthetic
        internal fun cancelRegisteredTask(taskId: String) {
            handlersByTaskId[taskId]?.cancel(taskId)
        }
    }
}

private class ProgressOutputStream(
    output: OutputStream,
    private val onBytesWritten: (Long) -> Unit
) : FilterOutputStream(output) {
    private var writtenBytes = 0L

    override fun write(value: Int) {
        out.write(value)
        reportWritten(1)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        out.write(buffer, offset, length)
        reportWritten(length)
    }

    private fun reportWritten(count: Int) {
        if (count <= 0) return
        writtenBytes += count
        onBytesWritten(writtenBytes)
    }
}

internal fun mergeCookieHeaders(vararg headers: String?): String? {
    val cookies = mutableListOf<String>()
    val namesClaimedByEarlierSources = mutableSetOf<String>()
    headers.forEach { header ->
        val sourceNames = mutableSetOf<String>()
        header?.split(';')?.forEach cookieLoop@{ rawCookie ->
            val cookie = rawCookie.trim()
            val separator = cookie.indexOf('=')
            if (separator <= 0) return@cookieLoop
            val name = cookie.substring(0, separator).trim()
            if (name.isEmpty()) return@cookieLoop
            sourceNames += name
            // Preserve order and same-name cookies within one source (different Path cookies
            // are legal). Earlier sources have explicit precedence over later fallbacks.
            if (name !in namesClaimedByEarlierSources) cookies += cookie
        }
        namesClaimedByEarlierSources += sourceNames
    }
    return cookies.joinToString("; ").takeIf { it.isNotBlank() }
}
