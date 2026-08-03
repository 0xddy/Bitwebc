package cn.lmcw.bitwebc.filechooser

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import org.junit.Assert.assertNull
import org.junit.Test

class BitwebcFileChooserFactoryTest {

    @Test
    fun `release clears factory host references before a delegate is created`() {
        val activity = unsafeAllocate(ComponentActivity::class.java)
        val lifecycleOwner = object : LifecycleOwner {
            override val lifecycle: Lifecycle = RecordingLifecycle()
        }
        val reporter: (BitwebcEvent) -> Unit = {}
        val handler = BitwebcFileChooserFactory.createDefault(
            activity = activity,
            lifecycleOwner = lifecycleOwner,
            eventReporter = reporter
        )

        handler.release()
        handler.release()

        listOf("activity", "lifecycleOwner", "eventReporter", "delegate").forEach { fieldName ->
            val field = handler.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
            assertNull("$fieldName must be cleared after release", field.get(handler))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> unsafeAllocate(clazz: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, clazz) as T
    }

    private class RecordingLifecycle : Lifecycle() {
        override fun addObserver(observer: LifecycleObserver) = Unit
        override fun removeObserver(observer: LifecycleObserver) = Unit
        override val currentState: State = State.STARTED
    }
}
