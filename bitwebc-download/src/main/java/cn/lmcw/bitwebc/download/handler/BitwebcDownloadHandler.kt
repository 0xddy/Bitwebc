package cn.lmcw.bitwebc.download.handler

import android.Manifest
import androidx.activity.ComponentActivity
import cn.lmcw.bitwebc.core.api.IDownloadHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import cn.lmcw.bitwebc.download.config.DownloadConfig
import cn.lmcw.bitwebc.download.model.DownloadRequest
import cn.lmcw.bitwebc.download.model.DownloadTaskState
import cn.lmcw.bitwebc.download.model.DownloadTaskStatus
import cn.lmcw.bitwebc.download.notification.DownloadNotificationHelper
import cn.lmcw.bitwebc.download.storage.DownloadStorage
import cn.lmcw.bitwebc.download.ext.guessFileName
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@OptIn(FlowPreview::class)
class BitwebcDownloadHandler(
    private val activity: ComponentActivity,
    private val config: DownloadConfig = DownloadConfig(),
    private val eventReporter: ((BitwebcEvent) -> Unit)? = null
) : IDownloadHandler {

    private val storage: DownloadStorage = config.resolveStorage(activity)
    private val notificationHelper = DownloadNotificationHelper(
        context = activity,
        channelId = config.notificationChannelId,
        channelName = config.notificationChannelName,
        channelDescription = config.notificationChannelDescription
    )

    private val taskStateMap = MutableStateFlow<Map<String, DownloadTaskState>>(emptyMap())

    private val requests = ConcurrentHashMap<String, DownloadRequest>()
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val runningCalls = ConcurrentHashMap<String, Call>()
    private val semaphore = Semaphore(config.maxConcurrentDownloads.coerceAtLeast(1))

    private val progressFlow = MutableSharedFlow<ProgressPayload>(replay = 1, extraBufferCapacity = 64)
    private var pendingPermission: CompletableDeferred<Boolean>? = null

    private val requestPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingPermission?.complete(granted)
        pendingPermission = null
    }

    init {
        notificationHelper.ensureChannel()
        activity.lifecycleScope.launch {
            progressFlow
                .debounce(500)
                .distinctUntilChanged()
                .collect { payload ->
                    notificationHelper.showProgress(
                        payload.notificationId,
                        payload.fileName,
                        payload.downloadedBytes,
                        payload.totalBytes
                    )
                }
        }
    }

    override fun onDownloadStart(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long
    ) {
        if (url.isNullOrBlank()) return
        enqueue(
            DownloadRequest(
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimetype,
                contentLength = contentLength
            )
        )
    }

    fun enqueue(request: DownloadRequest): String {
        val taskId = UUID.randomUUID().toString()
        requests[taskId] = request
        updateTask(
            taskId,
            DownloadTaskState(
                id = taskId,
                url = request.url,
                status = DownloadTaskStatus.QUEUED
            )
        )
        eventReporter?.invoke(BitwebcEvent.DownloadQueued(taskId, request.url))
        startTask(taskId)
        return taskId
    }

    fun cancel(taskId: String) {
        runningCalls[taskId]?.cancel()
        runningJobs[taskId]?.cancel()
        updateTask(taskId) { it.copy(status = DownloadTaskStatus.CANCELLED, error = "用户取消下载") }
    }

    fun pause(taskId: String) {
        runningCalls[taskId]?.cancel()
        runningJobs[taskId]?.cancel()
        updateTask(taskId) { it.copy(status = DownloadTaskStatus.PAUSED, error = "任务已暂停") }
    }

    fun resume(taskId: String) {
        val current = taskStateMap.value[taskId] ?: return
        if (current.status != DownloadTaskStatus.PAUSED) return
        updateTask(taskId) { it.copy(status = DownloadTaskStatus.QUEUED, error = null) }
        startTask(taskId)
    }

    fun retry(taskId: String) {
        val current = taskStateMap.value[taskId] ?: return
        if (current.status != DownloadTaskStatus.FAILED && current.status != DownloadTaskStatus.CANCELLED) return
        updateTask(taskId) { it.copy(status = DownloadTaskStatus.QUEUED, error = null, downloadedBytes = 0) }
        startTask(taskId)
    }

    private fun startTask(taskId: String) {
        val request = requests[taskId] ?: return
        val job = activity.lifecycleScope.launch {
            if (!ensureNotificationPermission()) {
                updateTask(taskId) { it.copy(status = DownloadTaskStatus.FAILED, error = "通知权限被拒绝") }
                eventReporter?.invoke(BitwebcEvent.DownloadPermissionDenied(taskId))
                return@launch
            }
            semaphore.withPermit {
                runCatching {
                    downloadFile(taskId, request)
                }.onFailure { e ->
                    val status = taskStateMap.value[taskId]?.status
                    if (status == DownloadTaskStatus.CANCELLED || status == DownloadTaskStatus.PAUSED) return@onFailure
                    val msg = e.message ?: "下载失败"
                    updateTask(taskId) { it.copy(status = DownloadTaskStatus.FAILED, error = msg) }
                    notificationHelper.showFailed((request.url + taskId).hashCode(), msg)
                    eventReporter?.invoke(BitwebcEvent.DownloadFailed(taskId, msg))
                }.also {
                    runningJobs.remove(taskId)
                    runningCalls.remove(taskId)
                }
            }
        }
        runningJobs[taskId] = job
    }

    private suspend fun downloadFile(taskId: String, request: DownloadRequest) = withContext(Dispatchers.IO) {
        val httpRequest = Request.Builder().url(request.url).apply {
            request.userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
        }.build()
        val call = config.okHttpClient.newCall(httpRequest)
        runningCalls[taskId] = call
        call.execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val resolvedMime = response.body?.contentType()?.toString()
                ?: request.mimeType
                ?: "application/octet-stream"
            val fileName = request.url.guessFileName(request.contentDisposition, resolvedMime)
            val total = if (request.contentLength > 0) request.contentLength
                else response.body?.contentLength() ?: -1L
            val notificationId = (request.url + fileName).hashCode()

            val sink = storage.createSink(activity, fileName, resolvedMime)
                ?: throw IllegalStateException("无法创建下载目标")

            updateTask(taskId) {
                it.copy(fileName = fileName, status = DownloadTaskStatus.RUNNING, totalBytes = total, error = null)
            }
            if (total >= config.foregroundPolicy.largeFileThresholdBytes) {
                config.foregroundPolicy.onLargeFileTask?.invoke(taskId, fileName)
            }

            response.body?.byteStream()?.use { input ->
                sink.outputStream.use { output ->
                    val buffer = ByteArray(config.bufferSizeBytes)
                    var downloaded = 0L
                    var read = input.read(buffer)
                    while (read >= 0) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        updateTask(taskId) { it.copy(downloadedBytes = downloaded, totalBytes = total) }
                        progressFlow.tryEmit(ProgressPayload(notificationId, taskId, fileName, downloaded, total))
                        val progress = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else -1
                        eventReporter?.invoke(BitwebcEvent.DownloadProgress(taskId, fileName, progress))
                        read = input.read(buffer)
                    }
                    output.flush()
                }
            } ?: throw IllegalStateException("响应体为空")

            sink.close()
            updateTask(taskId) { it.copy(status = DownloadTaskStatus.SUCCESS, downloadedBytes = total.coerceAtLeast(0)) }
            notificationHelper.showSuccess(notificationId, fileName, sink.uri, resolvedMime)
            eventReporter?.invoke(BitwebcEvent.DownloadSuccess(taskId, fileName))
        }
    }

    private suspend fun ensureNotificationPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return true
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return true
        val deferred = CompletableDeferred<Boolean>()
        pendingPermission = deferred
        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        return deferred.await()
    }

    private fun updateTask(taskId: String, value: DownloadTaskState) {
        taskStateMap.value = taskStateMap.value.toMutableMap().apply { put(taskId, value) }
    }

    private fun updateTask(taskId: String, updater: (DownloadTaskState) -> DownloadTaskState) {
        val current = taskStateMap.value[taskId] ?: return
        updateTask(taskId, updater(current))
    }

    private data class ProgressPayload(
        val notificationId: Int,
        val taskId: String,
        val fileName: String,
        val downloadedBytes: Long,
        val totalBytes: Long
    )
}
