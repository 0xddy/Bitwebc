package cn.lmcw.bitwebc.filechooser

import androidx.fragment.app.FragmentActivity

/**
 * 文件选择模块入口。若在 Activity 已 STARTED 之后才动态创建 WebView（例如按钮点击后创建），
 * 必须在 Activity.onCreate 中调用 [install]，否则 DefaultFileChooserHandler 内直接注册
 * Activity Result 可能抛出 IllegalStateException。
 */
object BitwebcFileChooser {

    /**
     * 在 Activity.onCreate 中调用一次，确保用于注册 Activity Result 的 Headless Fragment 已添加。
     * 之后在任意时机创建 DefaultFileChooserHandler 均可安全使用 Fragment 的 Launcher。
     * 需要 [FragmentActivity]（如 AppCompatActivity）。
     */
    @JvmStatic
    fun install(activity: FragmentActivity): FileChooserResultFragment {
        return FileChooserResultFragment.ensureAdded(activity)
    }
}
