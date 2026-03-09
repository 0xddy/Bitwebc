package cn.lmcw.bitwebc.core.extensions

import android.Manifest
import android.webkit.PermissionRequest

internal fun String.toAndroidPermission(): String? = when (this) {
    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
    else -> null
}
