package cn.lmcw.bitwebc.filechooser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity

/**
 * 无 UI 的 Headless Fragment，在 [onCreate] 中注册所有 Activity Result，
 * 避免在 Activity 已 STARTED/RESUMED 后注册导致 IllegalStateException。
 * 宿主应在 Activity.onCreate 中调用 [ensureAdded] 以确保此 Fragment 已添加。
 *
 * Process Death：调起相机后若进程被杀，[onSaveInstanceState] 会保存 [pendingCameraUri]，
 * 重建后在 [onCreate] 中恢复。但 [ValueCallback] 无法序列化，重建后无法将拍照结果回传 WebView，
 * 仅做状态清理。若需最佳体验，建议避免在拍照过程中强杀进程或使用不回收的 WebView。
 */
class FileChooserResultFragment : Fragment() {

    private val stateKeyPendingCameraUri = "BitwebcFileChooser.pendingCameraUri"

    companion object {
        const val TAG = "BitwebcFileChooserResultFragment"

        /**
         * 在 Activity.onCreate 中调用，确保 Fragment 已添加，从而在 STARTED 之前完成 Launcher 注册。
         * 若在 STARTED 之后才创建 WebView/Handler，必须先调用此方法，否则 Handler 内直接注册可能抛异常。
         */
        @JvmStatic
        fun ensureAdded(activity: FragmentActivity): FileChooserResultFragment {
            val fm = activity.supportFragmentManager
            val existing = fm.findFragmentByTag(TAG) as? FileChooserResultFragment
            if (existing != null) return existing
            val fragment = FileChooserResultFragment()
            fm.beginTransaction().add(fragment, TAG).commitNow()
            return fragment
        }
    }

    // 各 Launcher 回调只注册一次，通过临时 callback 转发结果
    private var pickVisualMediaCallback: ((Array<Uri>?) -> Unit)? = null
    private var openDocumentsCallback: ((Array<Uri>?) -> Unit)? = null
    private var takePictureCallback: ((Array<Uri>?) -> Unit)? = null
    private var captureVideoCallback: ((Array<Uri>?) -> Unit)? = null
    private var recordAudioCallback: ((Array<Uri>?) -> Unit)? = null
    private var permissionCallback: ((Boolean) -> Unit)? = null

    /** 拍照时使用的 Uri；Process Death 时在 onSaveInstanceState 中保存并在 onCreate 中恢复 */
    var pendingCameraUri: Uri? = null
        private set

    private val pickVisualMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val cb = pickVisualMediaCallback
        pickVisualMediaCallback = null
        cb?.invoke(uri?.let { arrayOf(it) })
    }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val cb = openDocumentsCallback
        openDocumentsCallback = null
        if (uris.isEmpty()) cb?.invoke(null) else cb?.invoke(uris.toTypedArray())
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        val cb = takePictureCallback
        takePictureCallback = null
        // Process Death 后 cb 为 null，无法回传 WebView，仅做状态清理
        cb?.invoke(if (success && uri != null) arrayOf(uri) else null)
    }

    private val captureVideoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        val cb = captureVideoCallback
        captureVideoCallback = null
        cb?.invoke(uri?.let { arrayOf(it) })
    }

    private val recordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        val cb = recordAudioCallback
        recordAudioCallback = null
        cb?.invoke(uri?.let { arrayOf(it) })
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val cb = permissionCallback
        permissionCallback = null
        cb?.invoke(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 恢复 Process Death 前保存的拍照 Uri（ValueCallback 无法恢复，仅做状态一致）
        savedInstanceState?.getString(stateKeyPendingCameraUri)?.let {
            pendingCameraUri = Uri.parse(it)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingCameraUri?.let { outState.putString(stateKeyPendingCameraUri, it.toString()) }
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
