package cn.lmcw.bitwebc.filechooser

import android.Manifest
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import cn.lmcw.bitwebc.core.client.MiddlewareWebChromeBase
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import cn.lmcw.bitwebc.filechooser.accept.FileChooserAcceptResolver
import cn.lmcw.bitwebc.filechooser.accept.FileChooserAcceptResolver.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Default implementation for gallery, camera, video, audio, and document selection. */
open class DefaultFileChooserHandler(
    activity: ComponentActivity,
    lifecycleOwner: LifecycleOwner = activity,
    private val tag: String = "default",
    eventReporter: ((BitwebcEvent) -> Unit)? = null,
    next: WebChromeClient? = null
) : MiddlewareWebChromeBase(next) {

    private var activity: ComponentActivity? = activity
    private var lifecycleOwner: LifecycleOwner? = lifecycleOwner
    private var eventReporter: ((BitwebcEvent) -> Unit)? = eventReporter
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var activeJob: Job? = null
    private var currentRequestId = 0L
    private var released = false
    private var cameraTempFileStore: CameraTempFileStore? =
        runCatching { CameraTempFileStore.from(activity) }.getOrNull()

    private val registryDelegate = lazy(LazyThreadSafetyMode.NONE) {
        val currentActivity = checkNotNull(this.activity) {
            "File chooser handler has already been released"
        }
        FileChooserLauncherRegistry(
            activity = currentActivity,
            lifecycleOwner = this.lifecycleOwner ?: currentActivity,
            tag = tag
        )
    }
    private val registry: FileChooserLauncherRegistry by registryDelegate

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            release()
        }
    }

    init {
        cameraTempFileStore?.cleanupExpired()
        if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            release()
        } else {
            lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        }
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        val currentLifecycleOwner = lifecycleOwner
        if (
            released || currentLifecycleOwner == null ||
            currentLifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED
        ) {
            filePathCallback.onReceiveValue(null)
            reportEvent(BitwebcEvent.FileChooserFailed("文件选择器的生命周期已结束"))
            return true
        }

        if (registryDelegate.isInitialized() && registry.hasPendingResult()) {
            cancelCurrentRequest(
                "旧文件选择请求已被新请求替换",
                clearLauncherCallbacks = false
            )
            filePathCallback.onReceiveValue(null)
            reportEvent(BitwebcEvent.FileChooserCancelled("上一个系统选择器尚未返回，请稍后重试"))
            return true
        }

        cancelCurrentRequest("旧文件选择请求已被新请求替换")
        val requestId = ++currentRequestId
        this.filePathCallback = filePathCallback

        val launcher = try {
            registry
        } catch (throwable: Exception) {
            reportLaunchFailure(throwable)
            dispatchResult(requestId, null)
            return true
        }

        activeJob = currentLifecycleOwner.lifecycleScope.launch {
            try {
                val acceptTypes = FileChooserAcceptResolver.normalizeAcceptTypes(
                    fileChooserParams.acceptTypes
                )
                val mediaType = FileChooserAcceptResolver.resolveMediaType(acceptTypes)

                when {
                    fileChooserParams.isCaptureEnabled && mediaType == MediaType.IMAGE ->
                        openCamera(launcher, requestId)

                    fileChooserParams.isCaptureEnabled && mediaType == MediaType.VIDEO ->
                        openVideoCapture(launcher, requestId)

                    fileChooserParams.isCaptureEnabled && mediaType == MediaType.AUDIO ->
                        openAudioCapture(launcher, requestId)

                    fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE ->
                        launchOpenDocuments(launcher, requestId, acceptTypes.toTypedArray())

                    mediaType == MediaType.IMAGE && "image/*" in acceptTypes ->
                        launchPickImage(launcher, requestId)

                    mediaType == MediaType.VIDEO && "video/*" in acceptTypes ->
                        launchPickVideo(launcher, requestId)

                    else -> launchOpenDocument(launcher, requestId, acceptTypes.toTypedArray())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Exception) {
                reportLaunchFailure(throwable)
                dispatchResult(requestId, null)
            }
        }.also { job ->
            job.invokeOnCompletion { cause ->
                if (cause is CancellationException) dispatchResult(requestId, null)
            }
        }

        return true
    }

    private suspend fun openCamera(launcher: FileChooserLauncherRegistry, requestId: Long) {
        if (!requestPermissionSuspend(launcher, Manifest.permission.CAMERA)) {
            reportEvent(BitwebcEvent.FileChooserPermissionDenied("CAMERA 权限被拒绝"))
            dispatchResult(requestId, null)
            return
        }

        val currentActivity = activity ?: run {
            dispatchResult(requestId, null)
            return
        }
        val imageFile = checkNotNull(cameraTempFileStore) {
            "Camera temporary file storage is unavailable"
        }.createTempFile()
        try {
            val authority = currentActivity.packageName + ".bitwebc.filechooser.fileprovider"
            val cameraUri = FileProvider.getUriForFile(currentActivity, authority, imageFile)
            launcher.launchTakePicture(cameraUri) { uris ->
                if (requestId != currentRequestId || uris.isNullOrEmpty()) imageFile.delete()
                handlePickerResult(requestId, uris, "用户取消了拍照")
            }
        } catch (throwable: Exception) {
            imageFile.delete()
            throw throwable
        }
    }

    private suspend fun openVideoCapture(launcher: FileChooserLauncherRegistry, requestId: Long) {
        if (!requestPermissionSuspend(launcher, Manifest.permission.CAMERA)) {
            reportEvent(BitwebcEvent.FileChooserPermissionDenied("CAMERA 权限被拒绝"))
            dispatchResult(requestId, null)
            return
        }

        launcher.launchCaptureVideo { uris ->
            handlePickerResult(requestId, uris, "用户取消了视频录制")
        }
    }

    private suspend fun openAudioCapture(launcher: FileChooserLauncherRegistry, requestId: Long) {
        if (!requestPermissionSuspend(launcher, Manifest.permission.RECORD_AUDIO)) {
            reportEvent(BitwebcEvent.FileChooserPermissionDenied("RECORD_AUDIO 权限被拒绝"))
            dispatchResult(requestId, null)
            return
        }

        launcher.launchRecordAudio { uris ->
            handlePickerResult(requestId, uris, "用户取消了音频录制")
        }
    }

    private fun launchPickImage(launcher: FileChooserLauncherRegistry, requestId: Long) {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        launcher.launchPickVisualMedia(request) { uris ->
            handlePickerResult(requestId, uris, "用户取消了图片选择")
        }
    }

    private fun launchPickVideo(launcher: FileChooserLauncherRegistry, requestId: Long) {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
        launcher.launchPickVisualMedia(request) { uris ->
            handlePickerResult(requestId, uris, "用户取消了视频选择")
        }
    }

    private fun launchOpenDocument(
        launcher: FileChooserLauncherRegistry,
        requestId: Long,
        mimeTypes: Array<String>
    ) {
        launcher.launchOpenDocument(mimeTypes) { uris ->
            handlePickerResult(requestId, uris, "用户取消了文件选择")
        }
    }

    private fun launchOpenDocuments(
        launcher: FileChooserLauncherRegistry,
        requestId: Long,
        mimeTypes: Array<String>
    ) {
        launcher.launchOpenDocuments(mimeTypes) { uris ->
            handlePickerResult(requestId, uris, "用户取消了文件选择")
        }
    }

    private fun handlePickerResult(requestId: Long, uris: Array<Uri>?, cancelReason: String) {
        if (requestId != currentRequestId) return
        if (uris.isNullOrEmpty()) {
            reportEvent(BitwebcEvent.FileChooserCancelled(cancelReason))
            dispatchResult(requestId, null)
        } else {
            dispatchResult(requestId, uris)
        }
    }

    private suspend fun requestPermissionSuspend(
        launcher: FileChooserLauncherRegistry,
        permission: String
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val callback: (Boolean) -> Unit = { granted ->
            if (continuation.isActive) continuation.resume(granted)
        }
        continuation.invokeOnCancellation { launcher.cancelPermissionRequest(callback) }
        try {
            launcher.requestPermission(permission, callback)
        } catch (throwable: Exception) {
            launcher.cancelPermissionRequest(callback)
            if (continuation.isActive) continuation.resumeWithException(throwable)
        }
    }

    private fun reportLaunchFailure(throwable: Exception) {
        reportEvent(
            BitwebcEvent.FileChooserFailed(
                "无法启动文件选择或媒体采集: ${throwable.message ?: throwable.javaClass.simpleName}"
            )
        )
    }

    private fun reportEvent(event: BitwebcEvent) {
        runCatching { eventReporter?.invoke(event) }
    }

    private fun dispatchResult(requestId: Long, result: Array<Uri>?) {
        if (requestId != currentRequestId) return
        val callback = filePathCallback ?: return
        filePathCallback = null
        callback.onReceiveValue(result)
    }

    private fun cancelCurrentRequest(reason: String?, clearLauncherCallbacks: Boolean = true) {
        val oldCallback = filePathCallback
        filePathCallback = null
        currentRequestId++
        activeJob?.cancel()
        activeJob = null
        if (clearLauncherCallbacks && registryDelegate.isInitialized()) {
            registry.cancelPendingCallbacks()
        }
        oldCallback?.onReceiveValue(null)
        if (oldCallback != null && reason != null) {
            reportEvent(BitwebcEvent.FileChooserCancelled(reason))
        }
    }

    /** Cancels pending work and guarantees that WebView does not retain a dangling callback. */
    fun cancelPending() {
        if (!released) {
            val clearCallbacks = !registryDelegate.isInitialized() || !registry.hasPendingResult()
            cancelCurrentRequest(
                "WebView 已被替换，文件选择请求已取消",
                clearLauncherCallbacks = clearCallbacks
            )
        }
    }

    /** Cancels pending work and unregisters all launchers. */
    fun release() {
        if (released) return
        released = true
        val owner = lifecycleOwner
        val tempFileStore = cameraTempFileStore
        try {
            runCatching { owner?.lifecycle?.removeObserver(lifecycleObserver) }
            cancelCurrentRequest("文件选择器已随生命周期释放")
        } finally {
            if (registryDelegate.isInitialized()) runCatching { registry.release() }
            runCatching { tempFileStore?.cleanupExpired() }
            activity = null
            lifecycleOwner = null
            eventReporter = null
            cameraTempFileStore = null
        }
    }

}
