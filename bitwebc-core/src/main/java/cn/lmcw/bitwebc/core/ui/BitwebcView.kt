package cn.lmcw.bitwebc.core.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
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

    fun setup(
        activity: ComponentActivity,
        lifecycleOwner: LifecycleOwner = activity,
        block: BitwebcBuilder.() -> Unit
    ): BitwebcSession {
        session?.release()
        removeAllViews()

        val builder = BitwebcBuilder(activity, lifecycleOwner)
        builder.attachTo(this)
        builder.block()
        val newSession = builder.launch()
        session = newSession
        return newSession
    }

    fun setup(
        fragment: androidx.fragment.app.Fragment,
        block: BitwebcBuilder.() -> Unit
    ): BitwebcSession {
        val activity = fragment.requireActivity() as? ComponentActivity
            ?: error("BitwebcView requires Fragment hosted by ComponentActivity")
        return setup(activity, fragment.viewLifecycleOwner, block)
    }

    fun getSession(): BitwebcSession? = session

    fun getWebView(): android.webkit.WebView? = session?.webView

    override fun onDetachedFromWindow() {
        session?.release()
        session = null
        super.onDetachedFromWindow()
    }
}
