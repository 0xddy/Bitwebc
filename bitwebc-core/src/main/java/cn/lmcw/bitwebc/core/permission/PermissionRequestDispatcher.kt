package cn.lmcw.bitwebc.core.permission

import java.util.concurrent.atomic.AtomicInteger

internal class PermissionRequestDispatcher(
    private val launchSinglePermission: (String) -> Unit,
    private val launchMultiplePermissions: (Array<String>) -> Unit
) {
    internal fun interface Cancellation {
        fun cancel()
    }

    private sealed class PendingRequest {
        abstract val requestId: Int
        var completed: Boolean = false

        abstract fun deniedCallback(): () -> Unit
        abstract fun launch(
            launchSinglePermission: (String) -> Unit,
            launchMultiplePermissions: (Array<String>) -> Unit
        )
    }

    private data class SinglePermissionRequest(
        override val requestId: Int,
        val permission: String,
        val onResult: (Boolean) -> Unit
    ) : PendingRequest() {
        override fun deniedCallback(): () -> Unit = { onResult(false) }

        override fun launch(
            launchSinglePermission: (String) -> Unit,
            launchMultiplePermissions: (Array<String>) -> Unit
        ) {
            launchSinglePermission(permission)
        }
    }

    private data class MultiplePermissionsRequest(
        override val requestId: Int,
        val permissions: Array<String>,
        val onResult: (Map<String, Boolean>) -> Unit
    ) : PendingRequest() {
        override fun deniedCallback(): () -> Unit = {
            onResult(permissions.associateWith { false })
        }

        override fun launch(
            launchSinglePermission: (String) -> Unit,
            launchMultiplePermissions: (Array<String>) -> Unit
        ) {
            launchMultiplePermissions(permissions)
        }
    }

    private val requestIdGenerator = AtomicInteger(0)
    private val lock = Any()
    private val requestQueue = ArrayDeque<PendingRequest>()
    private var activeRequest: PendingRequest? = null

    fun enqueueSingle(permission: String, onResult: (Boolean) -> Unit): Cancellation {
        val requestId = requestIdGenerator.incrementAndGet()
        val request = SinglePermissionRequest(
            requestId = requestId,
            permission = permission,
            onResult = onResult
        )
        synchronized(lock) {
            requestQueue.addLast(request)
        }
        launchNextRequest()
        return Cancellation { cancel(requestId) }
    }

    fun enqueueMultiple(
        permissions: Array<String>,
        onResult: (Map<String, Boolean>) -> Unit
    ): Cancellation {
        if (permissions.isEmpty()) {
            onResult(emptyMap())
            return Cancellation {}
        }
        val requestId = requestIdGenerator.incrementAndGet()
        val request = MultiplePermissionsRequest(
            requestId = requestId,
            permissions = permissions.copyOf(),
            onResult = onResult
        )
        synchronized(lock) {
            requestQueue.addLast(request)
        }
        launchNextRequest()
        return Cancellation { cancel(requestId) }
    }

    fun onSinglePermissionResult(granted: Boolean) {
        val callback = synchronized(lock) {
            val request = activeRequest as? SinglePermissionRequest
                ?: return@synchronized null
            activeRequest = null
            if (request.completed) {
                null
            } else {
                request.completed = true
                { request.onResult(granted) }
            }
        }
        invokeCallbackThenLaunchNext(callback)
    }

    fun onMultiplePermissionsResult(result: Map<String, Boolean>) {
        val callback = synchronized(lock) {
            val request = activeRequest as? MultiplePermissionsRequest
                ?: return@synchronized null
            activeRequest = null
            if (request.completed) {
                null
            } else {
                request.completed = true
                { request.onResult(result) }
            }
        }
        invokeCallbackThenLaunchNext(callback)
    }

    fun cancelAllPendingAsDenied() {
        val callbacks = synchronized(lock) {
            buildList {
                activeRequest?.let { request ->
                    if (!request.completed) {
                        request.completed = true
                        add(request.deniedCallback())
                    }
                }
                while (requestQueue.isNotEmpty()) {
                    val request = requestQueue.removeFirst()
                    if (!request.completed) {
                        request.completed = true
                        add(request.deniedCallback())
                    }
                }
            }
        }
        callbacks.forEach { callback ->
            runCatching { callback() }
        }
    }

    private fun cancel(requestId: Int) {
        val callback = synchronized(lock) {
            val request = when {
                activeRequest?.requestId == requestId -> activeRequest
                else -> requestQueue.firstOrNull { it.requestId == requestId }?.also {
                    requestQueue.remove(it)
                }
            } ?: return@synchronized null

            if (request.completed) {
                null
            } else {
                request.completed = true
                request.deniedCallback()
            }
        }
        if (callback != null) {
            runCatching { callback() }
        }
        launchNextRequest()
    }

    private fun launchNextRequest() {
        val next = synchronized(lock) {
            if (activeRequest != null) return
            requestQueue.removeFirstOrNull()?.also { activeRequest = it }
        } ?: return

        try {
            next.launch(launchSinglePermission, launchMultiplePermissions)
        } catch (_: Exception) {
            onLaunchFailed(next.requestId)
        }
    }

    private fun onLaunchFailed(requestId: Int) {
        val callback = synchronized(lock) {
            val request = activeRequest?.takeIf { it.requestId == requestId }
                ?: return@synchronized null
            activeRequest = null
            if (request.completed) {
                null
            } else {
                request.completed = true
                request.deniedCallback()
            }
        }
        invokeCallbackThenLaunchNext(callback)
    }

    private fun invokeCallbackThenLaunchNext(callback: (() -> Unit)?) {
        try {
            callback?.invoke()
        } finally {
            launchNextRequest()
        }
    }
}
