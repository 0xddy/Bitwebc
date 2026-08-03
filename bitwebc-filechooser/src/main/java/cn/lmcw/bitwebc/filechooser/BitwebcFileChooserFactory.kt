package cn.lmcw.bitwebc.filechooser

import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.lifecycle.LifecycleOwner
import cn.lmcw.bitwebc.core.api.FileChooserHandler
import cn.lmcw.bitwebc.core.event.BitwebcEvent

/** Creates the default [FileChooserHandler] used for HTML `input type="file"`. */
object BitwebcFileChooserFactory {

    @JvmStatic
    fun createDefault(
        activity: ComponentActivity,
        lifecycleOwner: LifecycleOwner = activity,
        tag: String = "default",
        eventReporter: ((BitwebcEvent) -> Unit)? = null
    ): FileChooserHandler = DefaultFileChooserFactoryHandler(
        activity = activity,
        lifecycleOwner = lifecycleOwner,
        tag = tag,
        eventReporter = eventReporter
    )
}

private class DefaultFileChooserFactoryHandler(
    activity: ComponentActivity,
    lifecycleOwner: LifecycleOwner,
    private val tag: String,
    eventReporter: ((BitwebcEvent) -> Unit)?
) : FileChooserHandler {
    private var activity: ComponentActivity? = activity
    private var lifecycleOwner: LifecycleOwner? = lifecycleOwner
    private var eventReporter: ((BitwebcEvent) -> Unit)? = eventReporter
    private var delegate: DefaultFileChooserHandler? = null
    private var released = false

    override fun createWebChromeClient(next: WebChromeClient?): WebChromeClient {
        check(!released) { "File chooser handler has already been released" }
        val currentActivity = checkNotNull(activity)
        val currentLifecycleOwner = checkNotNull(lifecycleOwner)
        val previous = delegate
        delegate = null
        previous?.release()
        return DefaultFileChooserHandler(
            activity = currentActivity,
            lifecycleOwner = currentLifecycleOwner,
            tag = tag,
            eventReporter = eventReporter,
            next = next
        ).also { delegate = it }
    }

    override fun release() {
        if (released) return
        released = true
        val currentDelegate = delegate
        delegate = null
        activity = null
        lifecycleOwner = null
        eventReporter = null
        currentDelegate?.release()
    }

    override fun cancelPending() {
        if (!released) delegate?.cancelPending()
    }
}
