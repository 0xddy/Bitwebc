package cn.lmcw.bitwebc.filechooser

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * 生命周期安全的文件选择 Launcher 容器，绑定在 [ComponentActivity] 的 activityResultRegistry。
 *
 * 使用固定 key 前缀 + tag，确保 Activity 重建后 pending result 能正确恢复。
 * 通过 [lifecycleOwner] 的 ON_DESTROY 自动反注册所有 Launcher。
 */
class FileChooserLauncherRegistry(
    activity: ComponentActivity,
    lifecycleOwner: LifecycleOwner = activity,
    tag: String = "default"
) {

    private val registry = activity.activityResultRegistry
    private val keyPrefix = "BitwebcFileChooser_${tag}_"

    private var pickVisualMediaCallback: ((Array<Uri>?) -> Unit)? = null
    private var openDocumentsCallback: ((Array<Uri>?) -> Unit)? = null
    private var takePictureCallback: ((Array<Uri>?) -> Unit)? = null
    private var captureVideoCallback: ((Array<Uri>?) -> Unit)? = null
    private var recordAudioCallback: ((Array<Uri>?) -> Unit)? = null
    private var permissionCallback: ((Boolean) -> Unit)? = null

    var pendingCameraUri: Uri? = null
        private set

    private val pickVisualMediaLauncher: ActivityResultLauncher<PickVisualMediaRequest> = registry.register(
        keyPrefix + "pickVisualMedia",
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val cb = pickVisualMediaCallback
        pickVisualMediaCallback = null
        cb?.invoke(uri?.let { arrayOf(it) })
    }

    private val openDocumentLauncher: ActivityResultLauncher<Array<String>> = registry.register(
        keyPrefix + "openDocument",
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val cb = openDocumentsCallback
        openDocumentsCallback = null
        if (uris.isEmpty()) cb?.invoke(null) else cb?.invoke(uris.toTypedArray())
    }

    private val takePictureLauncher: ActivityResultLauncher<Uri> = registry.register(
        keyPrefix + "takePicture",
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        val cb = takePictureCallback
        takePictureCallback = null
        cb?.invoke(if (success && uri != null) arrayOf(uri) else null)
    }

    private val captureVideoLauncher: ActivityResultLauncher<Intent> = registry.register(
        keyPrefix + "captureVideo",
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        val cb = captureVideoCallback
        captureVideoCallback = null
        cb?.invoke(uri?.let { arrayOf(it) })
    }

    private val recordAudioLauncher: ActivityResultLauncher<Intent> = registry.register(
        keyPrefix + "recordAudio",
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        val cb = recordAudioCallback
        recordAudioCallback = null
        cb?.invoke(uri?.let { arrayOf(it) })
    }

    private val requestPermissionLauncher: ActivityResultLauncher<String> = registry.register(
        keyPrefix + "requestPermission",
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val cb = permissionCallback
        permissionCallback = null
        cb?.invoke(granted)
    }

    private val allLaunchers: List<ActivityResultLauncher<*>> = listOf(
        pickVisualMediaLauncher,
        openDocumentLauncher,
        takePictureLauncher,
        captureVideoLauncher,
        recordAudioLauncher,
        requestPermissionLauncher
    )

    init {
        lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                allLaunchers.forEach { it.unregister() }
                owner.lifecycle.removeObserver(this)
            }
        })
    }

    fun launchPickVisualMedia(request: PickVisualMediaRequest, onResult: (Array<Uri>?) -> Unit) {
        pickVisualMediaCallback = onResult
        pickVisualMediaLauncher.launch(request)
    }

    fun launchOpenDocuments(mimeTypes: Array<String>, onResult: (Array<Uri>?) -> Unit) {
        openDocumentsCallback = onResult
        openDocumentLauncher.launch(mimeTypes)
    }

    fun launchTakePicture(cameraUri: Uri, onResult: (Array<Uri>?) -> Unit) {
        pendingCameraUri = cameraUri
        takePictureCallback = onResult
        takePictureLauncher.launch(cameraUri)
    }

    fun launchCaptureVideo(onResult: (Array<Uri>?) -> Unit) {
        captureVideoCallback = onResult
        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        captureVideoLauncher.launch(intent)
    }

    fun launchRecordAudio(onResult: (Array<Uri>?) -> Unit) {
        recordAudioCallback = onResult
        val intent = Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
        recordAudioLauncher.launch(intent)
    }

    fun requestPermission(permission: String, onResult: (Boolean) -> Unit) {
        permissionCallback = onResult
        requestPermissionLauncher.launch(permission)
    }
}
