package cn.lmcw.bitwebc.core.permission

import android.Manifest
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import cn.lmcw.bitwebc.core.client.MiddlewareWebChromeBase
import cn.lmcw.bitwebc.core.extensions.toAndroidPermission
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 拦截地理定位/PermissionRequest，通过 PermissionResultFragment 申请权限 */
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
        val permissions = resources.mapNotNull { it.toAndroidPermission() }.distinct().toTypedArray()
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

}
