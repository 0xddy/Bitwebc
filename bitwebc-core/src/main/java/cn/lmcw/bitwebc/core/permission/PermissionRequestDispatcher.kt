package cn.lmcw.bitwebc.core.permission

import java.util.concurrent.atomic.AtomicInteger

internal class PermissionRequestDispatcher(
    private val launchSinglePermission: (String) -> Unit,
    private val launchMultiplePermissions: (Array<String>) -> Unit
) {
    private data class SinglePermissionRequest(
        val requestId: Int,
        val permission: String,
        val onResult: (Boolean) -> Unit
    )

    private data class MultiplePermissionsRequest(
        val requestId: Int,
        val permissions: Array<String>,
        val onResult: (Map<String, Boolean>) -> Unit
    )

    private val requestIdGenerator = AtomicInteger(0)
    private val singlePermissionQueue = ArrayDeque<SinglePermissionRequest>()
    private val multiplePermissionsQueue = ArrayDeque<MultiplePermissionsRequest>()
    private var activeSinglePermissionRequest: SinglePermissionRequest? = null
    private var activeMultiplePermissionsRequest: MultiplePermissionsRequest? = null

    fun enqueueSingle(permission: String, onResult: (Boolean) -> Unit) {
        val requestId = requestIdGenerator.incrementAndGet()
        singlePermissionQueue.addLast(
            SinglePermissionRequest(
                requestId = requestId,
                permission = permission,
                onResult = onResult
            )
        )
        launchNextSinglePermissionRequest()
    }

    fun enqueueMultiple(permissions: Array<String>, onResult: (Map<String, Boolean>) -> Unit) {
        if (permissions.isEmpty()) {
            onResult(emptyMap())
            return
        }
        val requestId = requestIdGenerator.incrementAndGet()
        multiplePermissionsQueue.addLast(
            MultiplePermissionsRequest(
                requestId = requestId,
                permissions = permissions,
                onResult = onResult
            )
        )
        launchNextMultiplePermissionsRequest()
    }

    fun onSinglePermissionResult(granted: Boolean) {
        val request = activeSinglePermissionRequest ?: return
        activeSinglePermissionRequest = null
        request.onResult(granted)
        launchNextSinglePermissionRequest()
    }

    fun onMultiplePermissionsResult(result: Map<String, Boolean>) {
        val request = activeMultiplePermissionsRequest ?: return
        activeMultiplePermissionsRequest = null
        request.onResult(result)
        launchNextMultiplePermissionsRequest()
    }

    fun cancelAllPendingAsDenied() {
        activeSinglePermissionRequest?.onResult(false)
        activeSinglePermissionRequest = null
        while (singlePermissionQueue.isNotEmpty()) {
            singlePermissionQueue.removeFirst().onResult(false)
        }

        activeMultiplePermissionsRequest?.let { request ->
            request.onResult(request.permissions.associateWith { false })
        }
        activeMultiplePermissionsRequest = null
        while (multiplePermissionsQueue.isNotEmpty()) {
            val request = multiplePermissionsQueue.removeFirst()
            request.onResult(request.permissions.associateWith { false })
        }
    }

    private fun launchNextSinglePermissionRequest() {
        if (activeSinglePermissionRequest != null) return
        val next = singlePermissionQueue.removeFirstOrNull() ?: return
        activeSinglePermissionRequest = next
        launchSinglePermission(next.permission)
    }

    private fun launchNextMultiplePermissionsRequest() {
        if (activeMultiplePermissionsRequest != null) return
        val next = multiplePermissionsQueue.removeFirstOrNull() ?: return
        activeMultiplePermissionsRequest = next
        launchMultiplePermissions(next.permissions)
    }
}
