package cn.lmcw.bitwebc.core.permission

import android.Manifest
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import cn.lmcw.bitwebc.core.client.MiddlewareWebChromeBase
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 在 WebChromeClient 链中拦截地理定位与 [PermissionRequest]（如 WebRTC），
 * 通过 [PermissionResultFragment] 向系统申请权限后回调 H5。
 */
class PermissionWebChromeMiddleware(
    private val activity: FragmentActivity,
    private val permissionFragment: PermissionResultFragment?,
    next: WebChromeClient? = null
) : MiddlewareWebChromeBase(next) {

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?
    ) {
        if (permissionFragment == null || callback == null) {
            super.onGeolocationPermissionsShowPrompt(origin, callback)
            return
        }
        activity.lifecycleScope.launch {
            val granted = suspendCancellableCoroutine { cont ->
                permissionFragment.requestPermission(Manifest.permission.ACCESS_FINE_LOCATION) {
                    cont.resume(it)
                }
            }
            callback.invoke(origin, granted, false)
        }
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        if (permissionFragment == null) {
            super.onPermissionRequest(request)
            return
        }
        val resources = request.resources ?: emptyArray()
        val permissions = resources.mapNotNull { resourceToAndroidPermission(it) }.distinct().toTypedArray()
        if (permissions.isEmpty()) {
            request.deny()
            return
        }
        activity.lifecycleScope.launch {
            val results = suspendCancellableCoroutine<Map<String, Boolean>> { cont ->
                permissionFragment.requestPermissions(permissions) { cont.resume(it) }
            }
            val allGranted = permissions.all { results[it] == true }
            if (allGranted) {
                request.grant(request.resources)
            } else {
                request.deny()
            }
        }
    }

    private fun resourceToAndroidPermission(resource: String): String? = when (resource) {
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
        else -> null
    }
}
