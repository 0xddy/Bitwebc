package cn.lmcw.bitwebc.core.ui

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import androidx.lifecycle.LifecycleOwner
import cn.lmcw.bitwebc.core.dsl.BitwebcBuilder
import cn.lmcw.bitwebc.core.dsl.BitwebcSession

/** 自定义 View 容器，用于承载 WebView */
class BitwebcView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var session: BitwebcSession? = null

    /**
     * When true this View owns the Session and releases it on detach. Hosts with an explicit
     * disposal callback, such as Compose, set it to false and call [release] themselves.
     */
    var releaseOnDetach: Boolean = true

    @MainThread
    @JvmOverloads
    fun setup(
        activity: ComponentActivity,
        lifecycleOwner: LifecycleOwner = activity,
        block: BitwebcBuilder.() -> Unit
    ): BitwebcSession {
        release()
        removeAllViews()

        val builder = BitwebcBuilder.create(activity, lifecycleOwner)
        builder.attachTo(this)
        builder.block()
        val newSession = builder.launch()
        session = newSession
        return newSession
    }

    @MainThread
    fun setup(
        fragment: androidx.fragment.app.Fragment,
        block: BitwebcBuilder.() -> Unit
    ): BitwebcSession {
        val activity = fragment.requireActivity() as? ComponentActivity
            ?: error("BitwebcView requires Fragment hosted by ComponentActivity")
        return setup(activity, fragment.viewLifecycleOwner, block)
    }

    fun getSession(): BitwebcSession? = session?.takeUnless(BitwebcSession::isReleased)

    /** Saves WebView navigation history and scroll position into [outState]. */
    @MainThread
    fun saveState(outState: Bundle): Boolean = getSession()?.saveState(outState) == true

    /** Releases the active session. Safe to call more than once. */
    @MainThread
    fun release() {
        session?.release()
        session = null
    }

    override fun onDetachedFromWindow() {
        if (releaseOnDetach) release()
        super.onDetachedFromWindow()
    }
}
