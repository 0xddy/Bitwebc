package cn.lmcw.bitwebc.filechooser

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private object FileChooserContract {
    const val PICK_VISUAL_MEDIA = "pickVisualMedia"
    const val OPEN_DOCUMENT = "openDocument"
    const val OPEN_DOCUMENTS = "openDocuments"
    const val TAKE_PICTURE = "takePicture"
    const val CAPTURE_VIDEO = "captureVideo"
    const val RECORD_AUDIO = "recordAudio"
    const val REQUEST_PERMISSION = "requestPermission"

    val all = listOf(
        PICK_VISUAL_MEDIA,
        OPEN_DOCUMENT,
        OPEN_DOCUMENTS,
        TAKE_PICTURE,
        CAPTURE_VIDEO,
        RECORD_AUDIO,
        REQUEST_PERMISSION
    )
}

private val fileChooserBridgeViewModelFactory = viewModelFactory {
    initializer { FileChooserBridgeViewModel(createSavedStateHandle()) }
}

/**
 * Lifecycle-aware launchers used by a single file chooser handler.
 *
 * [tag] identifies the logical host and is hashed without truncation. Platform launchers live in
 * an Activity-scoped bridge so an obsolete owner can release its callbacks while an in-flight
 * result remains registered and is safely drained across Activity or process recreation.
 */
class FileChooserLauncherRegistry(
    activity: ComponentActivity,
    lifecycleOwner: LifecycleOwner = activity,
    tag: String = "default"
) {

    private val ownerToken = Any()
    private var lifecycleOwner: LifecycleOwner? = lifecycleOwner
    private var bridge: HostFileChooserBridge? = ViewModelProvider(activity, fileChooserBridgeViewModelFactory)
        .get(FileChooserBridgeViewModel::class.java)
        .bridge(activity, tag, ownerToken)
    private var released = false

    var pendingCameraUri: Uri?
        get() = bridge?.pendingCameraUri
        private set(_) = Unit

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            release()
        }
    }

    init {
        if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            release()
        } else {
            lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        }
    }

    fun launchPickVisualMedia(
        request: PickVisualMediaRequest,
        onResult: (Array<Uri>?) -> Unit
    ) {
        checkNotReleased()
        requireBridge().launchPickVisualMedia(ownerToken, request, onResult)
    }

    fun launchOpenDocument(mimeTypes: Array<String>, onResult: (Array<Uri>?) -> Unit) {
        checkNotReleased()
        requireBridge().launchOpenDocument(ownerToken, mimeTypes, onResult)
    }

    fun launchOpenDocuments(mimeTypes: Array<String>, onResult: (Array<Uri>?) -> Unit) {
        checkNotReleased()
        requireBridge().launchOpenDocuments(ownerToken, mimeTypes, onResult)
    }

    fun launchTakePicture(cameraUri: Uri, onResult: (Array<Uri>?) -> Unit) {
        checkNotReleased()
        requireBridge().launchTakePicture(ownerToken, cameraUri, onResult)
    }

    fun launchCaptureVideo(onResult: (Array<Uri>?) -> Unit) {
        checkNotReleased()
        requireBridge().launchCaptureVideo(ownerToken, onResult)
    }

    fun launchRecordAudio(onResult: (Array<Uri>?) -> Unit) {
        checkNotReleased()
        requireBridge().launchRecordAudio(ownerToken, onResult)
    }

    fun requestPermission(permission: String, onResult: (Boolean) -> Unit) {
        checkNotReleased()
        requireBridge().requestPermission(ownerToken, permission, onResult)
    }

    internal fun cancelPermissionRequest(callback: (Boolean) -> Unit) {
        bridge?.cancelPermissionRequest(ownerToken, callback)
    }

    /** A platform picker/permission UI cannot be relaunched safely until its old result is drained. */
    internal fun hasPendingResult(): Boolean = bridge?.hasPendingResult() == true

    /** Cancels this owner's business callback while retaining the launcher needed to drain a result. */
    internal fun cancelPendingCallbacks() {
        bridge?.cancelOwnerCallbacks(ownerToken)
    }

    fun release() {
        if (released) return
        released = true
        val owner = lifecycleOwner
        val currentBridge = bridge
        lifecycleOwner = null
        bridge = null
        try {
            owner?.lifecycle?.removeObserver(lifecycleObserver)
        } finally {
            currentBridge?.releaseOwner(ownerToken)
        }
    }

    private fun checkNotReleased() {
        check(!released) { "File chooser launcher registry has already been released" }
    }

    private fun requireBridge(): HostFileChooserBridge = checkNotNull(bridge) {
        "File chooser launcher registry has already been released"
    }
}

internal class FileChooserBridgeViewModel(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val bridges = mutableMapOf<String, HostFileChooserBridge>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun bridge(
        activity: ComponentActivity,
        hostKey: String,
        ownerToken: Any
    ): HostFileChooserBridge {
        attachPendingDrains(activity)
        val safeKey = stableFileChooserHostKey(hostKey.trim().ifEmpty { "default" })
        val bridge = bridges.getOrPut(safeKey) { createBridge(safeKey) }
        bridge.addOwner(ownerToken)
        try {
            bridge.attach(activity)
        } catch (error: Throwable) {
            bridge.releaseOwner(ownerToken)
            throw error
        }
        return bridge
    }

    override fun onCleared() {
        bridges.values.forEach(HostFileChooserBridge::close)
        bridges.clear()
    }

    private fun createBridge(safeKey: String): HostFileChooserBridge =
        HostFileChooserBridge(
            safeKey = safeKey,
            isDrainPending = { contract ->
                savedStateHandle.get<Boolean>(drainStateKey(safeKey, contract)) == true
            },
            setDrainPending = { contract, pending ->
                val stateKey = drainStateKey(safeKey, contract)
                if (pending) {
                    savedStateHandle[stateKey] = true
                } else {
                    savedStateHandle.remove<Boolean>(stateKey)
                }
                updatePendingHost(safeKey)
            },
            readPendingCameraUri = {
                savedStateHandle.get<String>(cameraUriStateKey(safeKey))
            },
            setPendingCameraUri = { value ->
                val stateKey = cameraUriStateKey(safeKey)
                if (value == null) {
                    savedStateHandle.remove<String>(stateKey)
                } else {
                    savedStateHandle[stateKey] = value
                }
            },
            onIdle = { idleBridge ->
                mainHandler.post {
                    if (bridges[safeKey] === idleBridge && idleBridge.canRemove()) {
                        bridges.remove(safeKey)
                        idleBridge.close()
                    }
                }
            }
        )

    private fun attachPendingDrains(activity: ComponentActivity) {
        val pendingHosts = savedStateHandle.get<ArrayList<String>>(KEY_PENDING_HOSTS)
            ?.toList()
            .orEmpty()
        pendingHosts.forEach { safeKey ->
            bridges.getOrPut(safeKey) { createBridge(safeKey) }.attach(activity)
        }
    }

    private fun updatePendingHost(safeKey: String) {
        val hasPendingResult = FileChooserContract.all.any { contract ->
            savedStateHandle.get<Boolean>(drainStateKey(safeKey, contract)) == true
        }
        val pendingHosts = savedStateHandle.get<ArrayList<String>>(KEY_PENDING_HOSTS)
            ?.toMutableSet()
            ?: linkedSetOf()
        if (hasPendingResult) pendingHosts += safeKey else pendingHosts -= safeKey
        if (pendingHosts.isEmpty()) {
            savedStateHandle.remove<ArrayList<String>>(KEY_PENDING_HOSTS)
        } else {
            savedStateHandle[KEY_PENDING_HOSTS] = ArrayList(pendingHosts)
        }
    }

    private fun drainStateKey(safeKey: String, contract: String): String =
        "bitwebc_filechooser_drain_${safeKey}_$contract"

    private fun cameraUriStateKey(safeKey: String): String =
        "bitwebc_filechooser_camera_uri_$safeKey"

    private companion object {
        const val KEY_PENDING_HOSTS = "bitwebc_filechooser_pending_hosts"
    }
}

internal class HostFileChooserBridge(
    private val safeKey: String,
    isDrainPending: (String) -> Boolean,
    private val setDrainPending: (String, Boolean) -> Unit,
    readPendingCameraUri: () -> String?,
    private val setPendingCameraUri: (String?) -> Unit,
    private val onIdle: (HostFileChooserBridge) -> Unit
) : DefaultLifecycleObserver {

    private sealed class ActiveRequest(
        open val ownerToken: Any,
        val contract: String
    ) {
        data class PickVisualMedia(
            override val ownerToken: Any,
            var callback: ((Array<Uri>?) -> Unit)?
        ) : ActiveRequest(ownerToken, FileChooserContract.PICK_VISUAL_MEDIA)

        data class OpenDocument(
            override val ownerToken: Any,
            var callback: ((Array<Uri>?) -> Unit)?
        ) : ActiveRequest(ownerToken, FileChooserContract.OPEN_DOCUMENT)

        data class OpenDocuments(
            override val ownerToken: Any,
            var callback: ((Array<Uri>?) -> Unit)?
        ) : ActiveRequest(ownerToken, FileChooserContract.OPEN_DOCUMENTS)

        data class TakePicture(
            override val ownerToken: Any,
            var callback: ((Array<Uri>?) -> Unit)?
        ) : ActiveRequest(ownerToken, FileChooserContract.TAKE_PICTURE)

        data class CaptureVideo(
            override val ownerToken: Any,
            var callback: ((Array<Uri>?) -> Unit)?
        ) : ActiveRequest(ownerToken, FileChooserContract.CAPTURE_VIDEO)

        data class RecordAudio(
            override val ownerToken: Any,
            var callback: ((Array<Uri>?) -> Unit)?
        ) : ActiveRequest(ownerToken, FileChooserContract.RECORD_AUDIO)

        data class Permission(
            override val ownerToken: Any,
            var callback: ((Boolean) -> Unit)?
        ) : ActiveRequest(ownerToken, FileChooserContract.REQUEST_PERMISSION)
    }

    private val owners = mutableSetOf<Any>()
    private val drainPending = FileChooserContract.all.associateWith(isDrainPending).toMutableMap()
    private var pendingCameraUriValue: String? = readPendingCameraUri()
    private var activity: ComponentActivity? = null
    private var attachedRegistry: ActivityResultRegistry? = null
    private var launchersReady = false
    private var attaching = false
    private var activeRequest: ActiveRequest? = null

    private var pickVisualMediaLauncher: ActivityResultLauncher<PickVisualMediaRequest>? = null
    private var openDocumentLauncher: ActivityResultLauncher<Array<String>>? = null
    private var openDocumentsLauncher: ActivityResultLauncher<Array<String>>? = null
    private var takePictureLauncher: ActivityResultLauncher<Uri>? = null
    private var captureVideoLauncher: ActivityResultLauncher<Intent>? = null
    private var recordAudioLauncher: ActivityResultLauncher<Intent>? = null
    private var requestPermissionLauncher: ActivityResultLauncher<String>? = null

    val pendingCameraUri: Uri?
        get() = pendingCameraUriValue?.let(Uri::parse)

    fun addOwner(ownerToken: Any) {
        owners += ownerToken
    }

    fun attach(target: ComponentActivity) {
        attachRegistry(target.activityResultRegistry, target)
    }

    internal fun attachForTesting(registry: ActivityResultRegistry) {
        attachRegistry(registry, null)
    }

    fun launchPickVisualMedia(
        ownerToken: Any,
        request: PickVisualMediaRequest,
        callback: (Array<Uri>?) -> Unit
    ) {
        launchRequest(
            ActiveRequest.PickVisualMedia(ownerToken, callback),
            pickVisualMediaLauncher,
            request
        )
    }

    fun launchOpenDocument(
        ownerToken: Any,
        mimeTypes: Array<String>,
        callback: (Array<Uri>?) -> Unit
    ) {
        launchRequest(
            ActiveRequest.OpenDocument(ownerToken, callback),
            openDocumentLauncher,
            mimeTypes
        )
    }

    fun launchOpenDocuments(
        ownerToken: Any,
        mimeTypes: Array<String>,
        callback: (Array<Uri>?) -> Unit
    ) {
        launchRequest(
            ActiveRequest.OpenDocuments(ownerToken, callback),
            openDocumentsLauncher,
            mimeTypes
        )
    }

    fun launchTakePicture(ownerToken: Any, cameraUri: Uri, callback: (Array<Uri>?) -> Unit) {
        launchRequest(
            request = ActiveRequest.TakePicture(ownerToken, callback),
            launcher = takePictureLauncher,
            input = cameraUri,
            beforePlatformLaunch = { updatePendingCameraUri(cameraUri.toString()) },
            onLaunchFailure = { updatePendingCameraUri(null) }
        )
    }

    fun launchCaptureVideo(ownerToken: Any, callback: (Array<Uri>?) -> Unit) {
        launchRequest(
            ActiveRequest.CaptureVideo(ownerToken, callback),
            captureVideoLauncher,
            Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        )
    }

    fun launchRecordAudio(ownerToken: Any, callback: (Array<Uri>?) -> Unit) {
        launchRequest(
            ActiveRequest.RecordAudio(ownerToken, callback),
            recordAudioLauncher,
            Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
        )
    }

    fun requestPermission(ownerToken: Any, permission: String, callback: (Boolean) -> Unit) {
        launchRequest(
            ActiveRequest.Permission(ownerToken, callback),
            requestPermissionLauncher,
            permission
        )
    }

    fun cancelPermissionRequest(ownerToken: Any, callback: (Boolean) -> Unit) {
        val active = activeRequest as? ActiveRequest.Permission ?: return
        if (active.ownerToken === ownerToken && active.callback === callback) active.callback = null
    }

    fun cancelOwnerCallbacks(ownerToken: Any) {
        val active = activeRequest?.takeIf { it.ownerToken === ownerToken } ?: return
        val cancellation = clearCallback(active)
        if (active is ActiveRequest.TakePicture) runCatching { updatePendingCameraUri(null) }
        runCatching(cancellation)
    }

    fun releaseOwner(ownerToken: Any) {
        cancelOwnerCallbacks(ownerToken)
        owners.remove(ownerToken)
        notifyIfIdle()
    }

    fun hasPendingResult(): Boolean =
        activeRequest != null || drainPending.values.any { it }

    fun canRemove(): Boolean = owners.isEmpty() && !hasPendingResult()

    override fun onDestroy(owner: LifecycleOwner) {
        if (owner === activity) detachLaunchers()
    }

    fun close() {
        detachLaunchers()
        activeRequest?.let { active -> runCatching(clearCallback(active)) }
        if (pendingCameraUriValue != null) runCatching { updatePendingCameraUri(null) }
        activeRequest = null
        owners.clear()
    }

    private fun attachRegistry(
        registry: ActivityResultRegistry,
        targetActivity: ComponentActivity?
    ) {
        if (attachedRegistry === registry && activity === targetActivity && launchersReady) return
        detachLaunchers()
        attachedRegistry = registry
        activity = targetActivity
        targetActivity?.lifecycle?.addObserver(this)
        attaching = true
        try {
            pickVisualMediaLauncher = registry.register(
                launcherKey(FileChooserContract.PICK_VISUAL_MEDIA),
                ActivityResultContracts.PickVisualMedia(),
                ::onPickVisualMediaResult
            )
            openDocumentLauncher = registry.register(
                launcherKey(FileChooserContract.OPEN_DOCUMENT),
                ActivityResultContracts.OpenDocument(),
                ::onOpenDocumentResult
            )
            openDocumentsLauncher = registry.register(
                launcherKey(FileChooserContract.OPEN_DOCUMENTS),
                ActivityResultContracts.OpenMultipleDocuments(),
                ::onOpenDocumentsResult
            )
            takePictureLauncher = registry.register(
                launcherKey(FileChooserContract.TAKE_PICTURE),
                ActivityResultContracts.TakePicture(),
                ::onTakePictureResult
            )
            captureVideoLauncher = registry.register(
                launcherKey(FileChooserContract.CAPTURE_VIDEO),
                ActivityResultContracts.StartActivityForResult(),
                ::onCaptureVideoResult
            )
            recordAudioLauncher = registry.register(
                launcherKey(FileChooserContract.RECORD_AUDIO),
                ActivityResultContracts.StartActivityForResult(),
                ::onRecordAudioResult
            )
            requestPermissionLauncher = registry.register(
                launcherKey(FileChooserContract.REQUEST_PERMISSION),
                ActivityResultContracts.RequestPermission(),
                ::onPermissionResult
            )
            launchersReady = true
        } catch (error: Throwable) {
            detachLaunchers()
            throw error
        } finally {
            attaching = false
        }
        notifyIfIdle()
    }

    private fun <I> launchRequest(
        request: ActiveRequest,
        launcher: ActivityResultLauncher<I>?,
        input: I,
        beforePlatformLaunch: () -> Unit = {},
        onLaunchFailure: () -> Unit = {}
    ) {
        checkCanLaunch(request.ownerToken)
        activeRequest = request
        try {
            updateDrainPending(request.contract, true)
            beforePlatformLaunch()
            checkNotNull(launcher).launch(input)
        } catch (error: Throwable) {
            if (activeRequest === request) activeRequest = null
            runCatching(onLaunchFailure)
            runCatching { updateDrainPending(request.contract, false) }
            notifyIfIdle()
            throw error
        }
    }

    private fun checkCanLaunch(ownerToken: Any) {
        check(ownerToken in owners) { "File chooser result owner has already been released" }
        check(launchersReady) { "File chooser result bridge is not attached" }
        check(!hasPendingResult()) { "Another file chooser result is still in flight" }
    }

    private fun onPickVisualMediaResult(uri: Uri?) {
        completeUriRequest(
            FileChooserContract.PICK_VISUAL_MEDIA,
            activeRequest as? ActiveRequest.PickVisualMedia,
            uri?.let { arrayOf(it) }
        )
    }

    private fun onOpenDocumentResult(uri: Uri?) {
        completeUriRequest(
            FileChooserContract.OPEN_DOCUMENT,
            activeRequest as? ActiveRequest.OpenDocument,
            uri?.let { arrayOf(it) }
        )
    }

    private fun onOpenDocumentsResult(uris: List<Uri>) {
        completeUriRequest(
            FileChooserContract.OPEN_DOCUMENTS,
            activeRequest as? ActiveRequest.OpenDocuments,
            uris.takeUnless(List<Uri>::isEmpty)?.toTypedArray()
        )
    }

    private fun onTakePictureResult(success: Boolean) {
        val active = activeRequest as? ActiveRequest.TakePicture
        val uri = pendingCameraUri
        runCatching { updatePendingCameraUri(null) }
        completeUriRequest(
            FileChooserContract.TAKE_PICTURE,
            active,
            if (success && uri != null) arrayOf(uri) else null
        )
    }

    private fun onCaptureVideoResult(result: ActivityResult) {
        val uri = result.data?.data.takeIf { result.resultCode == Activity.RESULT_OK }
        completeUriRequest(
            FileChooserContract.CAPTURE_VIDEO,
            activeRequest as? ActiveRequest.CaptureVideo,
            uri?.let { arrayOf(it) }
        )
    }

    private fun onRecordAudioResult(result: ActivityResult) {
        val uri = result.data?.data.takeIf { result.resultCode == Activity.RESULT_OK }
        completeUriRequest(
            FileChooserContract.RECORD_AUDIO,
            activeRequest as? ActiveRequest.RecordAudio,
            uri?.let { arrayOf(it) }
        )
    }

    private fun onPermissionResult(granted: Boolean) {
        val active = activeRequest as? ActiveRequest.Permission
        if (active != null) activeRequest = null
        runCatching { updateDrainPending(FileChooserContract.REQUEST_PERMISSION, false) }
        runCatching { active?.callback?.invoke(granted) }
        notifyIfIdle()
    }

    private fun completeUriRequest(
        contract: String,
        active: ActiveRequest?,
        result: Array<Uri>?
    ) {
        if (active != null) activeRequest = null
        runCatching { updateDrainPending(contract, false) }
        val callback = when (active) {
            is ActiveRequest.PickVisualMedia -> active.callback
            is ActiveRequest.OpenDocument -> active.callback
            is ActiveRequest.OpenDocuments -> active.callback
            is ActiveRequest.TakePicture -> active.callback
            is ActiveRequest.CaptureVideo -> active.callback
            is ActiveRequest.RecordAudio -> active.callback
            else -> null
        }
        runCatching { callback?.invoke(result) }
        notifyIfIdle()
    }

    private fun clearCallback(active: ActiveRequest): () -> Unit = when (active) {
        is ActiveRequest.PickVisualMedia -> {
            val callback = active.callback
            active.callback = null
            { callback?.invoke(null) }
        }
        is ActiveRequest.OpenDocument -> {
            val callback = active.callback
            active.callback = null
            { callback?.invoke(null) }
        }
        is ActiveRequest.OpenDocuments -> {
            val callback = active.callback
            active.callback = null
            { callback?.invoke(null) }
        }
        is ActiveRequest.TakePicture -> {
            val callback = active.callback
            active.callback = null
            { callback?.invoke(null) }
        }
        is ActiveRequest.CaptureVideo -> {
            val callback = active.callback
            active.callback = null
            { callback?.invoke(null) }
        }
        is ActiveRequest.RecordAudio -> {
            val callback = active.callback
            active.callback = null
            { callback?.invoke(null) }
        }
        is ActiveRequest.Permission -> {
            val callback = active.callback
            active.callback = null
            { callback?.invoke(false) }
        }
    }

    private fun updateDrainPending(contract: String, pending: Boolean) {
        setDrainPending(contract, pending)
        drainPending[contract] = pending
    }

    private fun updatePendingCameraUri(value: String?) {
        setPendingCameraUri(value)
        pendingCameraUriValue = value
    }

    private fun notifyIfIdle() {
        if (!attaching && canRemove()) onIdle(this)
    }

    private fun launcherKey(contract: String): String =
        "bitwebc-filechooser-$safeKey-$contract"

    private fun detachLaunchers() {
        launchersReady = false
        activity?.lifecycle?.removeObserver(this)
        activity = null
        attachedRegistry = null
        allLaunchers().forEach { launcher -> runCatching { launcher.unregister() } }
        pickVisualMediaLauncher = null
        openDocumentLauncher = null
        openDocumentsLauncher = null
        takePictureLauncher = null
        captureVideoLauncher = null
        recordAudioLauncher = null
        requestPermissionLauncher = null
    }

    private fun allLaunchers(): List<ActivityResultLauncher<*>> = listOfNotNull(
        pickVisualMediaLauncher,
        openDocumentLauncher,
        openDocumentsLauncher,
        takePictureLauncher,
        captureVideoLauncher,
        recordAudioLauncher,
        requestPermissionLauncher
    )
}

internal fun stableFileChooserHostKey(hostKey: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest(hostKey.toByteArray(StandardCharsets.UTF_8))
    return bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
