package cn.lmcw.bitwebc.core.permission

import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
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

internal interface PermissionRequester {
    fun requestPermissionCancellable(
        permission: String,
        onResult: (Boolean) -> Unit
    ): PermissionRequestDispatcher.Cancellation

    fun requestPermissionsCancellable(
        permissions: Array<String>,
        onResult: (Map<String, Boolean>) -> Unit
    ): PermissionRequestDispatcher.Cancellation

    fun release()
}

private val permissionBridgeViewModelFactory = viewModelFactory {
    initializer { PermissionBridgeViewModel(createSavedStateHandle()) }
}

/** Runtime-permission bridge that also works with a plain ComponentActivity/Compose host. */
internal class ActivityPermissionRequester(
    activity: ComponentActivity,
    private val lifecycleOwner: LifecycleOwner,
    hostKey: String
) : PermissionRequester, DefaultLifecycleObserver {

    private val ownerToken = Any()
    private val bridge = ViewModelProvider(activity, permissionBridgeViewModelFactory)
        .get(PermissionBridgeViewModel::class.java)
        .bridge(activity, hostKey, ownerToken)
    private val dispatcher: PermissionRequestDispatcher
    private var released = false

    init {
        dispatcher = PermissionRequestDispatcher(
            launchSinglePermission = { permission ->
                bridge.launchSingle(ownerToken, permission, ::onSinglePermissionResult)
            },
            launchMultiplePermissions = { permissions ->
                bridge.launchMultiple(ownerToken, permissions, ::onMultiplePermissionsResult)
            }
        )

        if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            release()
        } else {
            lifecycleOwner.lifecycle.addObserver(this)
        }
    }

    override fun requestPermissionCancellable(
        permission: String,
        onResult: (Boolean) -> Unit
    ): PermissionRequestDispatcher.Cancellation {
        if (released) {
            onResult(false)
            return PermissionRequestDispatcher.Cancellation {}
        }
        return dispatcher.enqueueSingle(permission, onResult)
    }

    override fun requestPermissionsCancellable(
        permissions: Array<String>,
        onResult: (Map<String, Boolean>) -> Unit
    ): PermissionRequestDispatcher.Cancellation {
        if (released) {
            onResult(permissions.associateWith { false })
            return PermissionRequestDispatcher.Cancellation {}
        }
        return dispatcher.enqueueMultiple(permissions, onResult)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        release()
    }

    override fun release() {
        if (released) return
        released = true
        lifecycleOwner.lifecycle.removeObserver(this)
        dispatcher.cancelAllPendingAsDenied()
        bridge.releaseOwner(ownerToken)
    }

    private fun onSinglePermissionResult(granted: Boolean) {
        dispatcher.onSinglePermissionResult(granted)
    }

    private fun onMultiplePermissionsResult(result: Map<String, Boolean>) {
        dispatcher.onMultiplePermissionsResult(result)
    }
}

internal class PermissionBridgeViewModel(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val bridges = mutableMapOf<String, HostPermissionBridge>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun bridge(
        activity: ComponentActivity,
        hostKey: String,
        ownerToken: Any
    ): HostPermissionBridge {
        attachPendingDrains(activity)
        val safeKey = stablePermissionHostKey(hostKey)
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
        bridges.values.forEach(HostPermissionBridge::close)
        bridges.clear()
    }

    private fun drainStateKey(safeKey: String, contract: String): String =
        "bitwebc_permission_drain_${safeKey}_$contract"

    private fun createBridge(safeKey: String): HostPermissionBridge =
        HostPermissionBridge(
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
        val hasPendingResult =
            savedStateHandle.get<Boolean>(drainStateKey(safeKey, CONTRACT_SINGLE)) == true ||
                savedStateHandle.get<Boolean>(drainStateKey(safeKey, CONTRACT_MULTIPLE)) == true
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

    private companion object {
        const val KEY_PENDING_HOSTS = "bitwebc_permission_pending_hosts"
        const val CONTRACT_SINGLE = "single"
        const val CONTRACT_MULTIPLE = "multiple"
    }
}

internal class HostPermissionBridge(
    private val safeKey: String,
    isDrainPending: (String) -> Boolean,
    private val setDrainPending: (String, Boolean) -> Unit,
    private val onIdle: (HostPermissionBridge) -> Unit
) : DefaultLifecycleObserver {

    private sealed class ActiveRequest(open val ownerToken: Any) {
        data class Single(
            override val ownerToken: Any,
            var callback: ((Boolean) -> Unit)?
        ) : ActiveRequest(ownerToken)

        data class Multiple(
            override val ownerToken: Any,
            var callback: ((Map<String, Boolean>) -> Unit)?
        ) : ActiveRequest(ownerToken)
    }

    private val owners = mutableSetOf<Any>()
    private var activity: ComponentActivity? = null
    private var singleLauncher: ActivityResultLauncher<String>? = null
    private var multipleLauncher: ActivityResultLauncher<Array<String>>? = null
    private var launchersReady = false
    private var attaching = false
    private var activeRequest: ActiveRequest? = null
    private var singleDrainPending = isDrainPending(CONTRACT_SINGLE)
    private var multipleDrainPending = isDrainPending(CONTRACT_MULTIPLE)

    fun addOwner(ownerToken: Any) {
        owners += ownerToken
    }

    fun attach(target: ComponentActivity) {
        if (activity === target && launchersReady) return
        detachLaunchers()
        activity = target
        target.lifecycle.addObserver(this)
        launchersReady = false
        attaching = true

        try {
            val registry = target.activityResultRegistry
            singleLauncher = registry.register(
                "bitwebc-web-permission-$safeKey-single",
                ActivityResultContracts.RequestPermission(),
                ::onSingleResult
            )
            multipleLauncher = registry.register(
                "bitwebc-web-permission-$safeKey-multiple",
                ActivityResultContracts.RequestMultiplePermissions(),
                ::onMultipleResult
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

    fun launchSingle(ownerToken: Any, permission: String, callback: (Boolean) -> Unit) {
        checkCanLaunch(ownerToken)
        val request = ActiveRequest.Single(ownerToken, callback)
        activeRequest = request
        try {
            updateSingleDrainPending(true)
            checkNotNull(singleLauncher).launch(permission)
        } catch (error: Throwable) {
            if (activeRequest === request) activeRequest = null
            runCatching { updateSingleDrainPending(false) }
            notifyIfIdle()
            throw error
        }
    }

    fun launchMultiple(
        ownerToken: Any,
        permissions: Array<String>,
        callback: (Map<String, Boolean>) -> Unit
    ) {
        checkCanLaunch(ownerToken)
        val request = ActiveRequest.Multiple(ownerToken, callback)
        activeRequest = request
        try {
            updateMultipleDrainPending(true)
            checkNotNull(multipleLauncher).launch(permissions)
        } catch (error: Throwable) {
            if (activeRequest === request) activeRequest = null
            runCatching { updateMultipleDrainPending(false) }
            notifyIfIdle()
            throw error
        }
    }

    fun releaseOwner(ownerToken: Any) {
        owners.remove(ownerToken)
        when (val active = activeRequest) {
            is ActiveRequest.Single -> if (active.ownerToken === ownerToken) active.callback = null
            is ActiveRequest.Multiple -> if (active.ownerToken === ownerToken) active.callback = null
            null -> Unit
        }
        notifyIfIdle()
    }

    fun canRemove(): Boolean =
        owners.isEmpty() && activeRequest == null && !singleDrainPending && !multipleDrainPending

    override fun onDestroy(owner: LifecycleOwner) {
        if (owner === activity) detachLaunchers()
    }

    fun close() {
        detachLaunchers()
        owners.clear()
        activeRequest = null
    }

    private fun checkCanLaunch(ownerToken: Any) {
        check(ownerToken in owners) { "Permission result owner has already been released" }
        check(launchersReady) { "Permission result bridge is not attached" }
        check(activeRequest == null && !singleDrainPending && !multipleDrainPending) {
            "Another web permission request is still in flight"
        }
    }

    private fun onSingleResult(granted: Boolean) {
        val active = activeRequest as? ActiveRequest.Single
        if (active != null) activeRequest = null
        runCatching { updateSingleDrainPending(false) }
        runCatching { active?.callback?.invoke(granted) }
        notifyIfIdle()
    }

    private fun onMultipleResult(result: Map<String, Boolean>) {
        val active = activeRequest as? ActiveRequest.Multiple
        if (active != null) activeRequest = null
        runCatching { updateMultipleDrainPending(false) }
        runCatching { active?.callback?.invoke(result) }
        notifyIfIdle()
    }

    private fun updateSingleDrainPending(pending: Boolean) {
        setDrainPending(CONTRACT_SINGLE, pending)
        singleDrainPending = pending
    }

    private fun updateMultipleDrainPending(pending: Boolean) {
        setDrainPending(CONTRACT_MULTIPLE, pending)
        multipleDrainPending = pending
    }

    private fun notifyIfIdle() {
        if (!attaching && canRemove()) onIdle(this)
    }

    private fun detachLaunchers() {
        launchersReady = false
        activity?.lifecycle?.removeObserver(this)
        activity = null
        singleLauncher?.let { launcher -> runCatching { launcher.unregister() } }
        multipleLauncher?.let { launcher -> runCatching { launcher.unregister() } }
        singleLauncher = null
        multipleLauncher = null
    }

    private companion object {
        const val CONTRACT_SINGLE = "single"
        const val CONTRACT_MULTIPLE = "multiple"
    }
}

internal fun stablePermissionHostKey(hostKey: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest(hostKey.toByteArray(StandardCharsets.UTF_8))
    return bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
