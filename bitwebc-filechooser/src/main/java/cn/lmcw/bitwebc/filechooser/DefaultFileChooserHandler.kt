package cn.lmcw.bitwebc.filechooser

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 默认文件选择实现：相册、相机、录像、录音、文档。
 */
open class DefaultFileChooserHandler(
    private val activity: ComponentActivity,
    private val eventReporter: ((BitwebcEvent) -> Unit)? = null,
    next: WebChromeClient? = null
) : MiddlewareWebChromeBase(next) {

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private var pendingCameraUri: Uri? = null

    private val pickVisualMediaLauncher = activity.registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        dispatchResult(uri?.let { arrayOf(it) })
    }

    private val openDocumentLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) {
            eventReporter?.invoke(BitwebcEvent.FileChooserCancelled("用户取消了文件选择"))
            dispatchResult(null)
        } else {
            dispatchResult(uris.toTypedArray())
        }
    }

    private val takePictureLauncher = activity.registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val result = if (success) pendingCameraUri?.let { arrayOf(it) } else null
        if (!success) {
            eventReporter?.invoke(BitwebcEvent.FileChooserCancelled("用户取消了相机拍照"))
        }
        pendingCameraUri = null
        dispatchResult(result)
    }

    private val captureVideoLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri == null) {
            eventReporter?.invoke(BitwebcEvent.FileChooserCancelled("用户取消了视频录制"))
            dispatchResult(null)
        } else {
            dispatchResult(arrayOf(uri))
        }
    }

    private val recordAudioLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri == null) {
            eventReporter?.invoke(BitwebcEvent.FileChooserCancelled("用户取消了音频录制"))
            dispatchResult(null)
        } else {
            dispatchResult(arrayOf(uri))
        }
    }

    private val requestPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingPermission?.complete(granted)
        pendingPermission = null
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

        activity.lifecycleScope.launch {
            runCatching {
                val accept = FileChooserAcceptResolver.normalizeAcceptTypes(fileChooserParams.acceptTypes)
                val mode = fileChooserParams.mode
                val captureEnabled = fileChooserParams.isCaptureEnabled
                val mediaType = FileChooserAcceptResolver.resolveMediaType(accept)

                when {
                    captureEnabled && mediaType == MediaType.IMAGE -> openCamera()
                    captureEnabled && mediaType == MediaType.VIDEO -> openVideoCapture()
                    captureEnabled && mediaType == MediaType.AUDIO -> openAudioCapture()
                    mode == FileChooserParams.MODE_OPEN_MULTIPLE -> {
                        openDocumentLauncher.launch(if (accept.isEmpty()) arrayOf("*/*") else accept.toTypedArray())
                    }
                    mediaType == MediaType.IMAGE -> {
                        pickVisualMediaLauncher.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }
                    mediaType == MediaType.VIDEO -> {
                        pickVisualMediaLauncher.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.VideoOnly
                            )
                        )
                    }
                    mediaType == MediaType.AUDIO -> {
                        openDocumentLauncher.launch(arrayOf("audio/*"))
                    }
                    else -> openDocumentLauncher.launch(if (accept.isEmpty()) arrayOf("*/*") else accept.toTypedArray())
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

    private suspend fun openCamera() {
        val granted = requestPermissionSuspend(Manifest.permission.CAMERA)
        if (!granted) {
            eventReporter?.invoke(BitwebcEvent.FileChooserPermissionDenied("CAMERA 权限被拒绝"))
            dispatchResult(null)
            return
        }
        val imageFile = createCameraTempFile()
        val authority = activity.packageName + ".bitwebc.fileprovider"
        pendingCameraUri = FileProvider.getUriForFile(activity, authority, imageFile)
        val cameraUri = pendingCameraUri
        if (cameraUri == null) {
            eventReporter?.invoke(BitwebcEvent.FileChooserFailed("相机文件 Uri 创建失败"))
            dispatchResult(null)
            return
        }
        takePictureLauncher.launch(cameraUri)
    }

    private fun openVideoCapture() {
        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        captureVideoLauncher.launch(intent)
    }

    private fun openAudioCapture() {
        val intent = Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
        recordAudioLauncher.launch(intent)
    }

    private suspend fun requestPermissionSuspend(permission: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingPermission = deferred
        requestPermissionLauncher.launch(permission)
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
