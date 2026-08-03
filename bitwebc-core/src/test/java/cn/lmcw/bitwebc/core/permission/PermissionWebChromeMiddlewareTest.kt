package cn.lmcw.bitwebc.core.permission

import android.Manifest
import android.webkit.PermissionRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionWebChromeMiddlewareTest {

    @Test
    fun `unknown web resources should never be recognized or granted`() {
        val recognized = recognizedWebResourcePermissions(
            arrayOf(
                PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                "android.webkit.resource.FUTURE_RESOURCE"
            )
        )

        assertEquals(
            listOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE to Manifest.permission.CAMERA),
            recognized
        )
        assertArrayEquals(
            arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE),
            approvedWebResources(
                recognized,
                mapOf(
                    Manifest.permission.CAMERA to true,
                    "android.permission.FUTURE" to true
                )
            )
        )
    }

    @Test
    fun `only resources backed by granted Android permissions should be approved`() {
        val recognized = recognizedWebResourcePermissions(
            arrayOf(
                PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                PermissionRequest.RESOURCE_AUDIO_CAPTURE
            )
        )

        val approved = approvedWebResources(
            recognized,
            mapOf(
                Manifest.permission.CAMERA to true,
                Manifest.permission.RECORD_AUDIO to false
            )
        )

        assertArrayEquals(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE), approved)
    }

    @Test
    fun `geolocation accepts approximate or precise permission`() {
        assertTrue(
            isGeolocationPermissionGranted(
                mapOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION to true,
                    Manifest.permission.ACCESS_FINE_LOCATION to false
                )
            )
        )
        assertTrue(
            isGeolocationPermissionGranted(
                mapOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION to false,
                    Manifest.permission.ACCESS_FINE_LOCATION to true
                )
            )
        )
        assertFalse(
            isGeolocationPermissionGranted(
                mapOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION to false,
                    Manifest.permission.ACCESS_FINE_LOCATION to false
                )
            )
        )
    }
}
