package cn.lmcw.bitwebc.filechooser

import android.Manifest
import android.net.Uri
import android.os.Environment
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import cn.lmcw.bitwebc.core.client.MiddlewareWebChromeBase
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import cn.lmcw.bitwebc.filechooser.accept.FileChooserAcceptResolver
import cn.lmcw.bitwebc.filechooser.accept.FileChooserAcceptResolver.MediaType
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* *
 * 默认文件选择实现：相册、相机、录像、录音、文档。
 *
 * 依赖 [FileChooserLauncherRegistry] 来支持文件选择。
 * 只要宿主是 [ComponentActivity]，即可正常工作，无需强依赖 [androidx.fragment.app.FragmentActivity]。
 */
open class DefaultFileChooserHandler(
    private val activity: ComponentActivity,
    private val lifecycleOwner: androidx.lifecycle.LifecycleOwner = activity,
    private val tag: String = "default",
    private val eventReporter: ((BitwebcEvent) -> Unit)? = null,
    next: WebChromeClient? = null
) : MiddlewareWebChromeBase(next) {

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val registry by lazy { FileChooserLauncherRegistry(activity, lifecycleOwner, tag) }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        this.filePathCallback?.let { old ->
            old.onReceiveValue(null)
            eventReporter?.invoke(BitwebcEvent.FileChooserCancelled("旧回调被新请求覆盖，已主动释放"))
        }
        this.filePathCallback = filePathCallback

        val launcher = registry
        activity.lifecycleScope.launch {
            runCatching {
                val accept = FileChooserAcceptResolver.normalizeAcceptTypes(fileChooserParams.acceptTypes)
                val mode = fileChooserParams.mode
                val captureEnabled = fileChooserParams.isCaptureEnabled
                val mediaType = FileChooserAcceptResolver.resolveMediaType(accept)

                when {
                    captureEnabled && mediaType == MediaType.IMAGE -> openCamera(launcher)
                    captureEnabled && mediaType == MediaType.VIDEO -> openVideoCapture(launcher)
                    captureEnabled && mediaType == MediaType.AUDIO -> openAudioCapture(launcher)
                    mode == FileChooserParams.MODE_OPEN_MULTIPLE -> launchOpenDocuments(
                        launcher,
                        if (accept.isEmpty()) arrayOf("*/*") else accept.toTypedArray()
                    )
                    mediaType == MediaType.IMAGE -> launchPickImage(launcher)
                    mediaType == MediaType.VIDEO -> launchPickVideo(launcher)
                    mediaType == MediaType.AUDIO -> launchOpenDocuments(launcher, arrayOf("audio/*"))
                    else -> launchOpenDocuments(
                        launcher,
                        if (accept.isEmpty()) arrayOf("*/*") else accept.toTypedArray()
                    )
                }
            }.onFailure { throwable ->
                eventReporter?.invoke(
                    BitwebcEvent.FileChooserFailed(
                        "文件选择流程异常: ${throwable.message ?: "unknown"}"
                    )
                )
                dispatchResult(null)
            }
        }
        return true
    }

    private suspend fun openCamera(launcher: FileChooserLauncherRegistry) {
        val granted = requestPermissionSuspend(launcher, Manifest.permission.CAMERA)
        if (!granted) {
            eventReporter?.invoke(BitwebcEvent.FileChooserPermissionDenied("CAMERA 权限被拒绝"))
            dispatchResult(null)
            return
        }
        val imageFile = createCameraTempFile()
        val authority = activity.packageName + ".bitwebc.fileprovider"
        val cameraUri = FileProvider.getUriForFile(activity, authority, imageFile)
        if (cameraUri == null) {
            eventReporter?.invoke(BitwebcEvent.FileChooserFailed("相机文件 Uri 创建失败"))
            dispatchResult(null)
            return
        }
        launcher.launchTakePicture(cameraUri) { dispatchResult(it) }
    }

    private fun openVideoCapture(launcher: FileChooserLauncherRegistry) {
        launcher.launchCaptureVideo { uris ->
            if (uris == null || uris.isEmpty()) {
                eventReporter?.invoke(BitwebcEvent.FileChooserCancelled("用户取消了视频录制"))
                dispatchResult(null)
            } else {
                dispatchResult(uris)
            }
        }
    }

    private fun openAudioCapture(launcher: FileChooserLauncherRegistry) {
        launcher.launchRecordAudio { uris ->
            if (uris == null || uris.isEmpty()) {
                eventReporter?.invoke(BitwebcEvent.FileChooserCancelled("用户取消了音频录制"))
                dispatchResult(null)
            } else {
                dispatchResult(uris)
            }
        }
    }

    private fun launchPickImage(launcher: FileChooserLauncherRegistry) {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        launcher.launchPickVisualMedia(request) { dispatchResult(it) }
    }

    private fun launchPickVideo(launcher: FileChooserLauncherRegistry) {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
        launcher.launchPickVisualMedia(request) { dispatchResult(it) }
    }

    private fun launchOpenDocuments(launcher: FileChooserLauncherRegistry, mimeTypes: Array<String>) {
        launcher.launchOpenDocuments(mimeTypes) { uris ->
            if (uris == null || uris.isEmpty()) {
                eventReporter?.invoke(BitwebcEvent.FileChooserCancelled("用户取消了文件选择"))
                dispatchResult(null)
            } else {
                dispatchResult(uris)
            }
        }
    }

    private suspend fun requestPermissionSuspend(
        launcher: FileChooserLauncherRegistry,
        permission: String
    ): Boolean = suspendCancellableCoroutine { continuation ->
        launcher.requestPermission(permission) { granted ->
            if (continuation.isActive) {
                continuation.resume(granted)
            }
        }
    }

    private fun createCameraTempFile(): File {
        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: activity.cacheDir
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "bitwebc_camera_$timestamp.jpg")
    }

    private fun dispatchResult(result: Array<Uri>?) {
        filePathCallback?.onReceiveValue(result)
        filePathCallback = null
    }
}
