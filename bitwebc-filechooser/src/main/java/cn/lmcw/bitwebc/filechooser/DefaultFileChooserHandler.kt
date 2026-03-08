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
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import cn.lmcw.bitwebc.core.client.MiddlewareWebChromeBase
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import cn.lmcw.bitwebc.filechooser.accept.FileChooserAcceptResolver
import cn.lmcw.bitwebc.filechooser.accept.FileChooserAcceptResolver.MediaType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 默认文件选择实现：相册、相机、录像、录音、文档。
 *
 * 始终通过 [FileChooserResultFragment] 注册 Activity Result，避免在 Activity 已 STARTED/RESUMED
 * 后注册导致 IllegalStateException。构造时会自动 ensureAdded Fragment（仅 [FragmentActivity]）。
 * 非 FragmentActivity 的宿主不支持文件选择，会回传 null 并上报事件。
 */
open class DefaultFileChooserHandler(
    private val activity: ComponentActivity,
    private val eventReporter: ((BitwebcEvent) -> Unit)? = null,
    next: WebChromeClient? = null
) : MiddlewareWebChromeBase(next) {

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /** 仅 FragmentActivity 时有值；构造时自动 ensureAdded，保证 Launcher 在 Fragment 上安全注册。 */
    private val resultFragment: FileChooserResultFragment? =
        (activity as? FragmentActivity)?.let { fa ->
            FileChooserResultFragment.ensureAdded(fa)
        }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        this.filePathCallback?.onReceiveValue(null)
        this.filePathCallback = null
        eventReporter?.invoke(BitwebcEvent.FileChooserCancelled("旧回调被新请求覆盖，已主动释放"))
        this.filePathCallback = filePathCallback

        if (resultFragment == null) {
            eventReporter?.invoke(
                BitwebcEvent.FileChooserFailed("文件选择需要 FragmentActivity，当前宿主不支持")
            )
            dispatchResult(null)
            return true
        }

        val fragment = resultFragment
        activity.lifecycleScope.launch {
            runCatching {
                val accept = FileChooserAcceptResolver.normalizeAcceptTypes(fileChooserParams.acceptTypes)
                val mode = fileChooserParams.mode
                val captureEnabled = fileChooserParams.isCaptureEnabled
                val mediaType = FileChooserAcceptResolver.resolveMediaType(accept)

                when {
                    captureEnabled && mediaType == MediaType.IMAGE -> openCamera(fragment)
                    captureEnabled && mediaType == MediaType.VIDEO -> openVideoCapture(fragment)
                    captureEnabled && mediaType == MediaType.AUDIO -> openAudioCapture(fragment)
                    mode == FileChooserParams.MODE_OPEN_MULTIPLE -> launchOpenDocuments(
                        fragment,
                        if (accept.isEmpty()) arrayOf("*/*") else accept.toTypedArray()
                    )
                    mediaType == MediaType.IMAGE -> launchPickImage(fragment)
                    mediaType == MediaType.VIDEO -> launchPickVideo(fragment)
                    mediaType == MediaType.AUDIO -> launchOpenDocuments(fragment, arrayOf("audio/*"))
                    else -> launchOpenDocuments(
                        fragment,
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

    private suspend fun openCamera(fragment: FileChooserResultFragment) {
        val granted = requestPermissionSuspend(fragment, Manifest.permission.CAMERA)
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
        fragment.launchTakePicture(cameraUri) { dispatchResult(it) }
    }

    private fun openVideoCapture(fragment: FileChooserResultFragment) {
        fragment.launchCaptureVideo { uris ->
            if (uris == null || uris.isEmpty()) {
                eventReporter?.invoke(BitwebcEvent.FileChooserCancelled("用户取消了视频录制"))
                dispatchResult(null)
            } else {
                dispatchResult(uris)
            }
        }
    }

    private fun openAudioCapture(fragment: FileChooserResultFragment) {
        fragment.launchRecordAudio { uris ->
            if (uris == null || uris.isEmpty()) {
                eventReporter?.invoke(BitwebcEvent.FileChooserCancelled("用户取消了音频录制"))
                dispatchResult(null)
            } else {
                dispatchResult(uris)
            }
        }
    }

    private fun launchPickImage(fragment: FileChooserResultFragment) {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        fragment.launchPickVisualMedia(request) { dispatchResult(it) }
    }

    private fun launchPickVideo(fragment: FileChooserResultFragment) {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
        fragment.launchPickVisualMedia(request) { dispatchResult(it) }
    }

    private fun launchOpenDocuments(fragment: FileChooserResultFragment, mimeTypes: Array<String>) {
        fragment.launchOpenDocuments(mimeTypes) { uris ->
            if (uris == null || uris.isEmpty()) {
                eventReporter?.invoke(BitwebcEvent.FileChooserCancelled("用户取消了文件选择"))
                dispatchResult(null)
            } else {
                dispatchResult(uris)
            }
        }
    }

    private suspend fun requestPermissionSuspend(
        fragment: FileChooserResultFragment,
        permission: String
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        fragment.requestPermission(permission) { deferred.complete(it) }
        return deferred.await()
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
