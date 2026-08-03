package cn.lmcw.bitwebc.core.permission

import android.Manifest
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import cn.lmcw.bitwebc.core.client.MiddlewareWebChromeBase
import cn.lmcw.bitwebc.core.extensions.toAndroidPermission
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Handles geolocation and WebRTC permission requests through an Activity-result bridge.
 *
 * Web content is denied by default. Applications must explicitly approve an origin through
 * [originAllowed] before an Android runtime permission is requested.
 */
internal class PermissionWebChromeMiddleware(
    private val activity: ComponentActivity,
    private val permissionRequester: PermissionRequester?,
    next: WebChromeClient? = null,
    private val originAllowed: (Uri) -> Boolean = { false }
) : MiddlewareWebChromeBase(next) {

    private class PendingWebPermission {
        val completed = AtomicBoolean(false)

        @Volatile
        var cancellation: PermissionRequestDispatcher.Cancellation? = null
    }

    private class PendingGeolocation {
        val completed = AtomicBoolean(false)

        @Volatile
        var cancellation: PermissionRequestDispatcher.Cancellation? = null

        fun cancel() {
            if (completed.compareAndSet(false, true)) {
                cancellation?.cancel()
            }
        }
    }

    private val pendingWebPermissions = Collections.synchronizedMap(
        IdentityHashMap<PermissionRequest, PendingWebPermission>()
    )
    private val pendingGeolocation = AtomicReference<PendingGeolocation?>(null)

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?
    ) {
        val requester = permissionRequester
        if (requester == null || callback == null) {
            super.onGeolocationPermissionsShowPrompt(origin, callback)
            return
        }

        pendingGeolocation.getAndSet(null)?.cancel()
        val parsedOrigin = parseAllowedOrigin(origin)
        if (parsedOrigin == null || activity.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            callback.invoke(origin, false, false)
            return
        }

        val pending = PendingGeolocation()
        pendingGeolocation.getAndSet(pending)?.cancel()
        val cancellation = requester.requestPermissionsCancellable(
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        ) { result ->
            if (pending.completed.compareAndSet(false, true)) {
                pendingGeolocation.compareAndSet(pending, null)
                callback.invoke(origin, isGeolocationPermissionGranted(result), false)
            }
        }
        pending.cancellation = cancellation
        if (pending.completed.get()) {
            cancellation.cancel()
        }
    }

    override fun onGeolocationPermissionsHidePrompt() {
        if (permissionRequester == null) {
            super.onGeolocationPermissionsHidePrompt()
            return
        }
        pendingGeolocation.getAndSet(null)?.cancel()
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        val requester = permissionRequester
        if (requester == null) {
            super.onPermissionRequest(request)
            return
        }

        val origin = runCatching { request.origin }.getOrNull()
        if (!isAllowedOrigin(origin) || activity.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            denySafely(request)
            return
        }

        val resources = runCatching { request.resources?.copyOf() ?: emptyArray() }
            .getOrDefault(emptyArray())
        val recognizedResources = recognizedWebResourcePermissions(resources)
        val androidPermissions = recognizedResources.map { it.second }.distinct().toTypedArray()
        if (androidPermissions.isEmpty()) {
            denySafely(request)
            return
        }

        val pending = PendingWebPermission()
        pendingWebPermissions[request] = pending
        val cancellation = requester.requestPermissionsCancellable(androidPermissions) { results ->
            if (!pending.completed.compareAndSet(false, true)) return@requestPermissionsCancellable
            pendingWebPermissions.remove(request)

            val approvedResources = approvedWebResources(recognizedResources, results)
            if (approvedResources.isEmpty()) {
                denySafely(request)
            } else {
                runCatching { request.grant(approvedResources) }
            }
        }
        pending.cancellation = cancellation

        // A synchronous launcher failure or cancellation may complete before the handle is stored.
        if (pending.completed.get()) {
            cancellation.cancel()
        }
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest) {
        if (permissionRequester == null) {
            super.onPermissionRequestCanceled(request)
            return
        }

        val pending = pendingWebPermissions.remove(request) ?: return
        if (pending.completed.compareAndSet(false, true)) {
            pending.cancellation?.cancel()
        }
    }

    /** Cancels requests owned by a WebView that is being replaced or released. */
    fun cancelPendingRequests() {
        pendingGeolocation.getAndSet(null)?.cancel()
        val pendingRequests = synchronized(pendingWebPermissions) {
            pendingWebPermissions.entries.map { it.key to it.value }.also {
                pendingWebPermissions.clear()
            }
        }
        pendingRequests.forEach { (request, pending) ->
            if (pending.completed.compareAndSet(false, true)) {
                pending.cancellation?.cancel()
                denySafely(request)
            }
        }
    }

    private fun parseAllowedOrigin(origin: String?): Uri? {
        if (origin.isNullOrBlank()) return null
        val parsed = runCatching { origin.toUri() }.getOrNull() ?: return null
        return parsed.takeIf(::isAllowedOrigin)
    }

    private fun isAllowedOrigin(origin: Uri?): Boolean {
        if (origin == null) return false
        val scheme = runCatching { origin.scheme?.lowercase() }.getOrNull()
        val host = runCatching { origin.host }.getOrNull()
        if (scheme !in SUPPORTED_ORIGIN_SCHEMES || host.isNullOrBlank()) return false
        return runCatching { originAllowed(origin) }.getOrDefault(false)
    }

    private fun denySafely(request: PermissionRequest) {
        runCatching { request.deny() }
    }

    private companion object {
        val SUPPORTED_ORIGIN_SCHEMES = setOf("http", "https")
    }
}

internal fun recognizedWebResourcePermissions(
    resources: Array<out String>
): List<Pair<String, String>> {
    return resources.mapNotNull { resource ->
        resource.toAndroidPermission()?.let { permission -> resource to permission }
    }.distinctBy { it.first }
}

internal fun approvedWebResources(
    recognizedResources: List<Pair<String, String>>,
    androidPermissionResults: Map<String, Boolean>
): Array<String> {
    return recognizedResources
        .filter { (_, permission) -> androidPermissionResults[permission] == true }
        .map { it.first }
        .toTypedArray()
}

internal fun isGeolocationPermissionGranted(result: Map<String, Boolean>): Boolean =
    result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
        result[Manifest.permission.ACCESS_FINE_LOCATION] == true
