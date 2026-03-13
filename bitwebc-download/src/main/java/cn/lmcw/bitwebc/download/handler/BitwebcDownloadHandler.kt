package cn.lmcw.bitwebc.download.handler

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import cn.lmcw.bitwebc.core.api.DownloadHandler
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import cn.lmcw.bitwebc.download.config.DownloadConfig
import cn.lmcw.bitwebc.download.ext.DataUriDecoder
import cn.lmcw.bitwebc.download.ext.DataUriHeader
import cn.lmcw.bitwebc.download.ext.DataUriResult
import cn.lmcw.bitwebc.download.ext.guessFileName
import cn.lmcw.bitwebc.download.ext.normalizeMimeType
import cn.lmcw.bitwebc.download.ext.resolveDownloadFileName
import cn.lmcw.bitwebc.download.model.DownloadRequest
import cn.lmcw.bitwebc.download.model.DownloadTaskState
import cn.lmcw.bitwebc.download.notification.DownloadNotificationHelper
import cn.lmcw.bitwebc.download.storage.DownloadStorage
import cn.lmcw.bitwebc.download.ui.DefaultDownloadConfirmUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import androidx.core.net.toUri

class BitwebcDownloadHandler(
    activity: ComponentActivity,
    private val config: DownloadConfig = DownloadConfig(),
    private val eventReporter: ((BitwebcEvent) -> Unit)? = null
) : DownloadHandler {

    private val activityRef = WeakReference(activity)
    private val appContext = activity.applicationContext

    private val storage: DownloadStorage = config.resolveStorage(appContext)

    private var pendingAfterPermission: (() -> Unit)? = null
    private val notificationPermissionLauncher: ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, "notificationPermission result: granted=$granted")
            pendingAfterPermission?.invoke()
            pendingAfterPermission = null
        }

    companion object {
        private const val TAG = "BitwebcDownload"
        private val handlersByTaskId = ConcurrentHashMap<String, BitwebcDownloadHandler>()

        internal fun cancelRegisteredTask(taskId: String) {
            handlersByTaskId[taskId]?.cancel(taskId)
        }
    }
    private val notificationHelper = DownloadNotificationHelper(
        context = appContext,
        channelId = config.notificationChannelId,
        channelName = config.notificationChannelName,
        channelDescription = config.notificationChannelDescription
    )

    private val taskStateMap = MutableStateFlow<Map<String, DownloadTaskState>>(emptyMap())

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val requests = ConcurrentHashMap<String, DownloadRequest>()
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val runningCalls = ConcurrentHashMap<String, Call>()
    private val semaphore = Semaphore(config.maxConcurrentDownloads.coerceAtLeast(1))

    private val completedNotificationIds = CopyOnWriteArraySet<Int>()
    private val cancelledTaskIds = CopyOnWriteArraySet<String>()
    private val lastReportedProgress = ConcurrentHashMap<String, Int>()

    init {
        Log.d(TAG, "init: channel=$config.notificationChannelId")
        notificationHelper.ensureChannel()
    }

    override fun onDownloadStart(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long
    ) {
        Log.d(TAG, "onDownloadStart: url=${url?.take(80)} contentLength=$contentLength")
        if (url.isNullOrBlank()) return
        ensureNotificationPermission {
            dispatchDownload(url, userAgent, contentDisposition, mimetype, contentLength)
        }
    }

    private fun dispatchDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long
    ) {
        when (val scheme = url.toUri().scheme?.lowercase()) {
            "http", "https" -> enqueue(
                DownloadRequest(
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimetype,
                    contentLength = contentLength
                )
            )
            "data" -> enqueueDataUri(url, mimetype)
            else -> Log.w(TAG, "onDownloadStart: unsupported scheme=$scheme, ignored")
        }
    }

    private fun ensureNotificationPermission(onReady: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onReady()
            return
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS)
            == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            onReady()
            return
        }
        val activity = activityRef.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "ensureNotificationPermission: activity unavailable, proceed without permission")
            onReady()
            return
        }
        Log.d(TAG, "ensureNotificationPermission: requesting POST_NOTIFICATIONS")
        pendingAfterPermission = onReady
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun showDownloadConfirmDialog(
        request: DownloadRequest,
        onConfirm: () -> Unit
    ) {
        val confirmUi = config.confirmUi ?: DefaultDownloadConfirmUi()
        activityRef.get()?.let { act ->
            if (act.isFinishing || act.isDestroyed) return@let
            act.runOnUiThread {
                confirmUi.confirm(act, request) { confirmed ->
                    if (confirmed) onConfirm()
                }
            }
        } ?: run {
            Log.w(TAG, "showDownloadConfirmDialog: Activity is dead, fallback to direct enqueue")
            onConfirm()
        }
    }

    private fun enqueueInternal(taskId: String, request: DownloadRequest) {
        requests[taskId] = request
        handlersByTaskId[taskId] = this
        cancelledTaskIds.remove(taskId)
        lastReportedProgress.remove(taskId)
        updateTask(taskId, DownloadTaskState.Queued(id = taskId, url = request.url))
        Log.d(TAG, "enqueue: taskId=$taskId url=${request.url.take(80)}")
        eventReporter?.invoke(BitwebcEvent.DownloadQueued(taskId, request.url))
        startTask(taskId)
    }

    fun enqueue(request: DownloadRequest): String {
        val taskId = UUID.randomUUID().toString()
        if (config.confirmBeforeDownload) {
            showDownloadConfirmDialog(request) {
                enqueueInternal(taskId, request)
            }
        } else {
            enqueueInternal(taskId, request)
        }
        return taskId
    }

    fun cancel(taskId: String) {
        Log.d(TAG, "cancel: taskId=$taskId")
        cancelledTaskIds.add(taskId)
        val notificationsEnabled = areNotificationsEnabled()
        requests[taskId]?.let { request ->
            val notificationId = (request.url + taskId).hashCode()
            completedNotificationIds.add(notificationId)
            if (notificationsEnabled) {
                notificationHelper.showCancelled(notificationId, taskStateMap.value[taskId]?.fileName ?: request.url.guessFileName(request.contentDisposition, request.mimeType))
            }
        }
        updateTask(taskId) { current ->
            DownloadTaskState.Cancelled(current.id, current.url, current.fileName, current.createdAtMillis)
        }
        runningCalls[taskId]?.cancel()
        runningJobs[taskId]?.cancel()
        handlersByTaskId.remove(taskId)
        lastReportedProgress.remove(taskId)
        runningCalls.remove(taskId)
        runningJobs.remove(taskId)
        requests.remove(taskId)
    }

    /**
     * 暂停下载任务。内部会取消当前的 HTTP 请求和协程 Job。
     * 不支持断点续传——调用 [resume] 后将从头重新下载。
     */
    fun pause(taskId: String) {
        Log.d(TAG, "pause: taskId=$taskId")
        runningCalls[taskId]?.cancel()
        runningJobs[taskId]?.cancel()
        updateTask(taskId) { current ->
            DownloadTaskState.Paused(current.id, current.url, current.fileName, current.createdAtMillis)
        }
    }

    /**
     * 恢复已暂停的下载任务。注意：不支持断点续传，resume 会从头重新下载整个文件。
     * 如需断点续传能力，请使用系统 DownloadManager 或其他支持 Range 请求的下载实现。
     */
    fun resume(taskId: String) {
        val current = taskStateMap.value[taskId] ?: return
        if (current !is DownloadTaskState.Paused) return
        Log.d(TAG, "resume: taskId=$taskId")
        updateTask(taskId, DownloadTaskState.Queued(current.id, current.url, current.fileName, current.createdAtMillis))
        startTask(taskId)
    }

    fun retry(taskId: String) {
        val current = taskStateMap.value[taskId] ?: return
        when (current) {
            is DownloadTaskState.Failed, is DownloadTaskState.Cancelled -> {
                Log.d(TAG, "retry: taskId=$taskId")
                updateTask(taskId, DownloadTaskState.Queued(current.id, current.url, null, current.createdAtMillis))
                startTask(taskId)
            }
            else -> return
        }
    }

    private fun startTask(taskId: String) {
        val request = requests[taskId] ?: return
        Log.d(TAG, "startTask: taskId=$taskId url=${request.url.take(60)}")
        val job = downloadScope.launch {
            val notificationsEnabled = areNotificationsEnabled()
            if (!notificationsEnabled) {
                Log.w(TAG, "startTask: taskId=$taskId notifications disabled, download silently")
                eventReporter?.invoke(BitwebcEvent.DownloadPermissionDenied(taskId))
            }
            semaphore.withPermit {
                runCatching {
                    downloadFile(taskId, request, notificationsEnabled)
                }.onFailure { e ->
                    val current = taskStateMap.value[taskId]
                    if (taskId in cancelledTaskIds || current is DownloadTaskState.Cancelled || current is DownloadTaskState.Paused) {
                        Log.d(TAG, "startTask: taskId=$taskId ignored failure (cancelled/paused)")
                        handlersByTaskId.remove(taskId)
                        lastReportedProgress.remove(taskId)
                        requests.remove(taskId)
                        return@onFailure
                    }
                    val msg = e.message ?: "下载失败"
                    Log.e(TAG, "startTask: taskId=$taskId failed msg=$msg", e)
                    updateTask(taskId) { s ->
                        DownloadTaskState.Failed(s.id, s.url, s.fileName, e, s.createdAtMillis)
                    }
                    val notificationId = (request.url + taskId).hashCode()
                    completedNotificationIds.add(notificationId)
                    handlersByTaskId.remove(taskId)
                    cancelledTaskIds.remove(taskId)
                    lastReportedProgress.remove(taskId)
                    requests.remove(taskId)
                    if (notificationsEnabled) {
                        notificationHelper.showFailed(notificationId, msg)
                    }
                    eventReporter?.invoke(BitwebcEvent.DownloadFailed(taskId, msg))
                }.also {
                    runningJobs.remove(taskId)
                    runningCalls.remove(taskId)
                }
            }
        }
        runningJobs[taskId] = job
    }

    private suspend fun downloadFile(taskId: String, request: DownloadRequest, notificationsEnabled: Boolean) = withContext(Dispatchers.IO) {
        Log.d(TAG, "downloadFile: taskId=$taskId start url=${request.url.take(60)}")
        val httpRequest = Request.Builder().url(request.url).apply {
            request.userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
        }.build()
        val call = config.okHttpClient.newCall(httpRequest)
        runningCalls[taskId] = call
        call.execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "downloadFile: taskId=$taskId HTTP ${response.code}")
                throw IllegalStateException("HTTP ${response.code}")
            }
            val responseContentDisposition = response.header("Content-Disposition")
            val resolvedUrl = response.request.url.toString()
            val resolvedMime = response.body.contentType()?.toString()
                ?: request.mimeType
                ?: "application/octet-stream"
            val fileName = resolveDownloadFileName(
                request.url,
                resolvedUrl,
                responseContentDisposition ?: request.contentDisposition,
                resolvedMime
            )
            val storageMimeType = resolvedMime.normalizeMimeType(fileName)
            val total = if (request.contentLength > 0) request.contentLength
                else response.body.contentLength()
            val notificationId = (request.url + taskId).hashCode()
            Log.d(TAG, "downloadFile: taskId=$taskId fileName=$fileName total=$total")

            val sink = storage.createSink(appContext, fileName, storageMimeType)
                ?: throw IllegalStateException("无法创建下载目标")

            updateTask(taskId) { current ->
                DownloadTaskState.Running(current.id, current.url, fileName, 0L, total, current.createdAtMillis)
            }
            withContext(Dispatchers.Main.immediate) {
                if (notificationsEnabled) {
                    notificationHelper.showProgress(notificationId, taskId, fileName, 0L, total)
                }
            }
            lastReportedProgress[taskId] = 0
            if (total >= config.foregroundPolicy.largeFileThresholdBytes) {
                Log.d(TAG, "downloadFile: taskId=$taskId largeFile callback")
                config.foregroundPolicy.onLargeFileTask?.invoke(taskId, fileName)
            }

            var lastLogPercent = -1
            response.body.byteStream().use { input ->
                sink.outputStream.use { output ->
                    val buffer = ByteArray(config.bufferSizeBytes)
                    var downloaded = 0L
                    var read = input.read(buffer)
                    while (read >= 0) {
                        if (taskId in cancelledTaskIds) return@withContext
                        output.write(buffer, 0, read)
                        downloaded += read
                        updateTask(taskId) { current ->
                            when (current) {
                                is DownloadTaskState.Running -> DownloadTaskState.Running(current.id, current.url, current.fileName, downloaded, total, current.createdAtMillis)
                                else -> current
                            }
                        }
                        val progress = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else -1
                        maybeReportProgress(taskId, notificationId, fileName, downloaded, total, progress, notificationsEnabled)
                        if (progress in 0..100 && (progress == 0 || progress == 100 || progress / 10 > lastLogPercent / 10)) {
                            lastLogPercent = progress
                            Log.d(TAG, "downloadFile: taskId=$taskId progress=$progress% ($downloaded/$total)")
                        }
                        read = input.read(buffer)
                    }
                    output.flush()
                }
            }

            sink.close()
            updateTask(taskId) { current ->
                when (current) {
                    is DownloadTaskState.Running -> DownloadTaskState.Success(current.id, current.url, current.fileName, current.totalBytes, current.createdAtMillis)
                    else -> current
                }
            }
            Log.d(TAG, "downloadFile: taskId=$taskId success fileName=$fileName uri=${sink.uri}")
            completedNotificationIds.add(notificationId)
            handlersByTaskId.remove(taskId)
            cancelledTaskIds.remove(taskId)
            lastReportedProgress.remove(taskId)
            requests.remove(taskId)
            if (notificationsEnabled) {
                notificationHelper.showSuccess(notificationId, fileName, sink.uri, storageMimeType)
            }
            eventReporter?.invoke(BitwebcEvent.DownloadSuccess(taskId, fileName))
        }
    }

    private fun enqueueDataUri(dataUrl: String, hintMime: String?) {
        val header = when (val result = DataUriDecoder.parse(dataUrl, config.dataUriMaxBytes)) {
            is DataUriResult.Ready -> result.header
            is DataUriResult.TooLarge -> {
                val mb = result.estimatedBytes / 1024 / 1024
                Log.w(TAG, "enqueueDataUri: data: URI too large (${mb}MB > ${result.maxBytes / 1024 / 1024}MB limit)")
                eventReporter?.invoke(BitwebcEvent.DownloadFailed("", "data: URI 数据过大 (${mb}MB)，已拒绝"))
                return
            }
            is DataUriResult.InvalidFormat -> {
                Log.w(TAG, "enqueueDataUri: failed to parse data: URI")
                return
            }
        }
        val taskId = UUID.randomUUID().toString()
        val mimeType = hintMime?.takeIf { it.isNotBlank() } ?: header.mimeType
        val request = DownloadRequest(
            url = "data:[${header.estimatedBytes} bytes]",
            mimeType = mimeType,
            contentLength = header.estimatedBytes
        )
        if (config.confirmBeforeDownload) {
            showDownloadConfirmDialog(request) {
                launchDataUriSave(taskId, request, header, mimeType)
            }
        } else {
            launchDataUriSave(taskId, request, header, mimeType)
        }
    }

    private fun launchDataUriSave(
        taskId: String,
        request: DownloadRequest,
        header: DataUriHeader,
        mimeType: String
    ) {
        requests[taskId] = request
        handlersByTaskId[taskId] = this
        updateTask(taskId, DownloadTaskState.Queued(id = taskId, url = request.url))
        Log.d(TAG, "enqueueDataUri: taskId=$taskId fileName=${header.fileName} estimated=${header.estimatedBytes}")
        eventReporter?.invoke(BitwebcEvent.DownloadQueued(taskId, request.url))

        val job = downloadScope.launch {
            runCatching {
                streamDataUriToStorage(taskId, header, mimeType)
            }.onFailure { e ->
                val msg = e.message ?: "data: URI 保存失败"
                Log.e(TAG, "streamDataUri: taskId=$taskId failed msg=$msg", e)
                updateTask(taskId) { s ->
                    DownloadTaskState.Failed(s.id, s.url, s.fileName, e, s.createdAtMillis)
                }
                val notificationId = taskId.hashCode()
                if (areNotificationsEnabled()) {
                    notificationHelper.showFailed(notificationId, msg)
                }
                eventReporter?.invoke(BitwebcEvent.DownloadFailed(taskId, msg))
            }.also {
                handlersByTaskId.remove(taskId)
                requests.remove(taskId)
                runningJobs.remove(taskId)
            }
        }
        runningJobs[taskId] = job
    }

    private suspend fun streamDataUriToStorage(
        taskId: String,
        header: DataUriHeader,
        mimeType: String
    ) = withContext(Dispatchers.IO) {
        val storageMime = mimeType.normalizeMimeType(header.fileName)
        val sink = storage.createSink(appContext, header.fileName, storageMime)
            ?: throw IllegalStateException("无法创建下载目标")

        val total = header.estimatedBytes
        updateTask(taskId) { current ->
            DownloadTaskState.Running(current.id, current.url, header.fileName, 0L, total, current.createdAtMillis)
        }

        val written = sink.outputStream.use { out ->
            DataUriDecoder.streamTo(header, out)
        }
        sink.close()

        updateTask(taskId) { current ->
            when (current) {
                is DownloadTaskState.Running -> DownloadTaskState.Success(current.id, current.url, current.fileName, written, current.createdAtMillis)
                else -> current
            }
        }
        Log.d(TAG, "streamDataUri: taskId=$taskId success fileName=${header.fileName} written=$written")
        val notificationId = taskId.hashCode()
        if (areNotificationsEnabled()) {
            notificationHelper.showSuccess(notificationId, header.fileName, sink.uri, storageMime)
        }
        eventReporter?.invoke(BitwebcEvent.DownloadSuccess(taskId, header.fileName))
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
        if (notificationId in completedNotificationIds) return
        if (progress < 0) {
            if (notificationsEnabled) {
                notificationHelper.showProgress(notificationId, taskId, fileName, downloadedBytes, totalBytes)
            }
            eventReporter?.invoke(BitwebcEvent.DownloadProgress(taskId, fileName, progress))
            return
        }
        val previous = lastReportedProgress.put(taskId, progress)
        if (previous == progress) return
        if (notificationsEnabled) {
            notificationHelper.showProgress(notificationId, taskId, fileName, downloadedBytes, totalBytes)
        }
        eventReporter?.invoke(BitwebcEvent.DownloadProgress(taskId, fileName, progress))
    }

    private fun areNotificationsEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.d(TAG, "areNotificationsEnabled: SDK < 33, granted")
            return true
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "areNotificationsEnabled: already granted")
            return true
        }
        Log.d(TAG, "areNotificationsEnabled: denied")
        return false
    }

    private fun updateTask(taskId: String, value: DownloadTaskState) {
        taskStateMap.update { currentMap ->
            currentMap.toMutableMap().apply { put(taskId, value) }
        }
    }

    private fun updateTask(taskId: String, updater: (DownloadTaskState) -> DownloadTaskState) {
        taskStateMap.update { currentMap ->
            val current = currentMap[taskId] ?: return@update currentMap
            currentMap.toMutableMap().apply { put(taskId, updater(current)) }
        }
    }

}
