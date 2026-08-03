package cn.lmcw.bitwebc.filechooser

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileChooserLauncherRegistryAndroidTest {

    @Test
    fun publicRegistryAttachesToPlainComponentActivity() {
        ActivityScenario.launch(FileChooserRegistryTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val registry = FileChooserLauncherRegistry(
                    activity = activity,
                    tag = "stable-activity-host"
                )
                assertFalse(registry.hasPendingResult())
                registry.release()
                assertCleared(registry, "lifecycleOwner", "bridge")

                val handler = DefaultFileChooserHandler(
                    activity = activity,
                    eventReporter = {}
                )
                handler.release()
                assertCleared(
                    handler,
                    "activity",
                    "lifecycleOwner",
                    "eventReporter",
                    "cameraTempFileStore"
                )

                val factoryHandler = BitwebcFileChooserFactory.createDefault(
                    activity = activity,
                    eventReporter = {}
                )
                factoryHandler.createWebChromeClient(null)
                factoryHandler.release()
                assertCleared(
                    factoryHandler,
                    "activity",
                    "lifecycleOwner",
                    "eventReporter",
                    "delegate"
                )
            }
        }
    }

    @Test
    fun releasedOwnerIsCancelledWhileOldPlatformResultDrainsIntoRebuiltBridge() {
        val persistedDrains = mutableMapOf<String, Boolean>()
        var persistedCameraUri: String? = null
        val registry = RecordingActivityResultRegistry()
        val firstOwner = Any()
        val firstResults = mutableListOf<Boolean>()
        val firstBridge = bridge(
            persistedDrains = persistedDrains,
            readCameraUri = { persistedCameraUri },
            writeCameraUri = { persistedCameraUri = it }
        )
        firstBridge.addOwner(firstOwner)
        firstBridge.attachForTesting(registry)

        firstBridge.requestPermission(firstOwner, "test.permission") { firstResults += it }
        assertTrue(firstBridge.hasPendingResult())
        assertTrue(persistedDrains.values.any { it })

        firstBridge.releaseOwner(firstOwner)
        assertEquals(listOf(false), firstResults)
        assertTrue(firstBridge.hasPendingResult())
        firstBridge.close()

        val replacementOwner = Any()
        val replacementBridge = bridge(
            persistedDrains = persistedDrains,
            readCameraUri = { persistedCameraUri },
            writeCameraUri = { persistedCameraUri = it }
        )
        replacementBridge.addOwner(replacementOwner)
        replacementBridge.attachForTesting(registry)

        assertLaunchRejected {
            replacementBridge.requestPermission(replacementOwner, "new.permission") {}
        }

        assertTrue(registry.dispatchLastBooleanResult(false))
        assertFalse(replacementBridge.hasPendingResult())
        assertFalse(persistedDrains.values.any { it })

        replacementBridge.requestPermission(replacementOwner, "new.permission") {}
        assertTrue(replacementBridge.hasPendingResult())
    }

    @Test
    fun rejectedPictureLaunchCannotOverwriteTheInflightCameraUri() {
        val persistedDrains = mutableMapOf<String, Boolean>()
        var persistedCameraUri: String? = null
        val registry = RecordingActivityResultRegistry()
        val bridge = HostFileChooserBridge(
            safeKey = stableFileChooserHostKey("stable-camera-host"),
            isDrainPending = { persistedDrains[it] == true },
            setDrainPending = { contract, pending ->
                if (pending) persistedDrains[contract] = true else persistedDrains.remove(contract)
            },
            readPendingCameraUri = { persistedCameraUri },
            setPendingCameraUri = { persistedCameraUri = it },
            onIdle = {}
        )
        val firstOwner = Any()
        val secondOwner = Any()
        val firstUri = Uri.parse("content://bitwebc.test/camera/first")
        val secondUri = Uri.parse("content://bitwebc.test/camera/second")
        var firstResult: Array<Uri>? = null

        bridge.addOwner(firstOwner)
        bridge.addOwner(secondOwner)
        bridge.attachForTesting(registry)
        bridge.launchTakePicture(firstOwner, firstUri) { firstResult = it }

        assertEquals(firstUri, bridge.pendingCameraUri)
        assertLaunchRejected {
            bridge.launchTakePicture(secondOwner, secondUri) {}
        }
        assertEquals(firstUri, bridge.pendingCameraUri)

        assertTrue(registry.dispatchLastBooleanResult(true))
        assertArrayEquals(arrayOf(firstUri), firstResult)
        assertNull(bridge.pendingCameraUri)
    }

    private fun bridge(
        persistedDrains: MutableMap<String, Boolean>,
        readCameraUri: () -> String?,
        writeCameraUri: (String?) -> Unit
    ): HostFileChooserBridge = HostFileChooserBridge(
        safeKey = stableFileChooserHostKey("stable-host"),
        isDrainPending = { persistedDrains[it] == true },
        setDrainPending = { contract, pending ->
            if (pending) persistedDrains[contract] = true else persistedDrains.remove(contract)
        },
        readPendingCameraUri = readCameraUri,
        setPendingCameraUri = writeCameraUri,
        onIdle = {}
    )

    private fun assertLaunchRejected(block: () -> Unit) {
        try {
            block()
            fail("Expected an in-flight picture result to reject a new launch")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("still in flight"))
        }
    }

    private fun assertCleared(target: Any, vararg fieldNames: String) {
        fieldNames.forEach { fieldName ->
            val field = target.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
            assertNull("$fieldName must be cleared after release", field.get(target))
        }
    }

    private class RecordingActivityResultRegistry : ActivityResultRegistry() {
        private var lastRequestCode: Int? = null

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            lastRequestCode = requestCode
        }

        fun dispatchLastBooleanResult(value: Boolean): Boolean =
            dispatchResult(checkNotNull(lastRequestCode), value)
    }
}

class FileChooserRegistryTestActivity : ComponentActivity()
