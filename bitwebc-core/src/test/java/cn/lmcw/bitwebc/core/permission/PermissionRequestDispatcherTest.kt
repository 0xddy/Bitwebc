package cn.lmcw.bitwebc.core.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionRequestDispatcherTest {

    @Test
    fun `single permission requests should be processed in order`() {
        val launched = mutableListOf<String>()
        val callbackResults = mutableListOf<Boolean>()
        val dispatcher = PermissionRequestDispatcher(
            launchSinglePermission = { launched += it },
            launchMultiplePermissions = { error("should not launch multiple permissions") }
        )

        dispatcher.enqueueSingle("p1") { callbackResults += it }
        dispatcher.enqueueSingle("p2") { callbackResults += it }

        assertEquals(listOf("p1"), launched)

        dispatcher.onSinglePermissionResult(true)
        assertEquals(listOf("p1", "p2"), launched)

        dispatcher.onSinglePermissionResult(false)
        assertEquals(listOf(true, false), callbackResults)
    }

    @Test
    fun `multiple permission requests should be processed in order`() {
        val launched = mutableListOf<List<String>>()
        val callbackResults = mutableListOf<Map<String, Boolean>>()
        val dispatcher = PermissionRequestDispatcher(
            launchSinglePermission = { error("should not launch single permission") },
            launchMultiplePermissions = { launched += it.toList() }
        )

        dispatcher.enqueueMultiple(arrayOf("camera", "audio")) { callbackResults += it }
        dispatcher.enqueueMultiple(arrayOf("location")) { callbackResults += it }

        assertEquals(listOf(listOf("camera", "audio")), launched)

        dispatcher.onMultiplePermissionsResult(mapOf("camera" to true, "audio" to false))
        assertEquals(listOf(listOf("camera", "audio"), listOf("location")), launched)

        dispatcher.onMultiplePermissionsResult(mapOf("location" to true))
        assertEquals(2, callbackResults.size)
        assertEquals(mapOf("camera" to true, "audio" to false), callbackResults[0])
        assertEquals(mapOf("location" to true), callbackResults[1])
    }

    @Test
    fun `single and multiple permission requests should share one launch queue`() {
        val launched = mutableListOf<String>()
        val dispatcher = PermissionRequestDispatcher(
            launchSinglePermission = { launched += "single:$it" },
            launchMultiplePermissions = { launched += "multiple:${it.joinToString()}" }
        )

        dispatcher.enqueueSingle("location") {}
        dispatcher.enqueueMultiple(arrayOf("camera", "audio")) {}
        dispatcher.enqueueSingle("notifications") {}

        assertEquals(listOf("single:location"), launched)

        dispatcher.onSinglePermissionResult(true)
        assertEquals(listOf("single:location", "multiple:camera, audio"), launched)

        dispatcher.onMultiplePermissionsResult(mapOf("camera" to true, "audio" to true))
        assertEquals(
            listOf("single:location", "multiple:camera, audio", "single:notifications"),
            launched
        )
    }

    @Test
    fun `canceling active request should wait for stale result before launching next`() {
        val launched = mutableListOf<String>()
        val firstResults = mutableListOf<Boolean>()
        val secondResults = mutableListOf<Map<String, Boolean>>()
        val dispatcher = PermissionRequestDispatcher(
            launchSinglePermission = { launched += "single:$it" },
            launchMultiplePermissions = { launched += "multiple:${it.joinToString()}" }
        )

        val cancellation = dispatcher.enqueueSingle("location") { firstResults += it }
        dispatcher.enqueueMultiple(arrayOf("camera")) { secondResults += it }
        cancellation.cancel()

        assertEquals(listOf(false), firstResults)
        assertEquals(listOf("single:location"), launched)

        dispatcher.onSinglePermissionResult(true)
        assertEquals(listOf(false), firstResults)
        assertEquals(listOf("single:location", "multiple:camera"), launched)

        dispatcher.onMultiplePermissionsResult(mapOf("camera" to true))
        assertEquals(listOf(mapOf("camera" to true)), secondResults)
    }

    @Test
    fun `canceling queued request should deny it without launching it`() {
        val launched = mutableListOf<String>()
        var queuedResult = true
        val dispatcher = PermissionRequestDispatcher(
            launchSinglePermission = { launched += "single:$it" },
            launchMultiplePermissions = { launched += "multiple:${it.joinToString()}" }
        )

        dispatcher.enqueueSingle("location") {}
        val queuedCancellation = dispatcher.enqueueMultiple(arrayOf("camera")) {
            queuedResult = it["camera"] == true
        }
        queuedCancellation.cancel()
        dispatcher.onSinglePermissionResult(true)

        assertFalse(queuedResult)
        assertEquals(listOf("single:location"), launched)
    }

    @Test
    fun `launcher failure should deny request and leave dispatcher usable`() {
        val results = mutableListOf<Boolean>()
        var shouldFail = true
        val dispatcher = PermissionRequestDispatcher(
            launchSinglePermission = {
                if (shouldFail) throw IllegalStateException("launcher unavailable")
            },
            launchMultiplePermissions = { error("should not launch multiple permissions") }
        )

        dispatcher.enqueueSingle("first") { results += it }
        shouldFail = false
        dispatcher.enqueueSingle("second") { results += it }
        dispatcher.onSinglePermissionResult(true)

        assertEquals(listOf(false, true), results)
    }

    @Test
    fun `cancel should deny active and queued requests`() {
        val singleResults = mutableListOf<Boolean>()
        val multipleResults = mutableListOf<Map<String, Boolean>>()
        val dispatcher = PermissionRequestDispatcher(
            launchSinglePermission = {},
            launchMultiplePermissions = {}
        )

        dispatcher.enqueueSingle("camera") { singleResults += it }
        dispatcher.enqueueSingle("audio") { singleResults += it }
        dispatcher.enqueueMultiple(arrayOf("location")) { multipleResults += it }

        dispatcher.cancelAllPendingAsDenied()

        assertEquals(listOf(false, false), singleResults)
        assertEquals(1, multipleResults.size)
        assertEquals(mapOf("location" to false), multipleResults.first())
    }

    @Test
    fun `empty multiple permission request should return immediately`() {
        var called = false
        val dispatcher = PermissionRequestDispatcher(
            launchSinglePermission = { error("should not launch single permission") },
            launchMultiplePermissions = { error("should not launch multiple permissions") }
        )

        dispatcher.enqueueMultiple(emptyArray()) {
            called = true
            assertTrue(it.isEmpty())
        }

        assertTrue(called)
    }
}
