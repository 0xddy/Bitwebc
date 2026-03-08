package cn.lmcw.bitwebc.core.permission

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 无 UI 的 Headless Fragment，在 [onCreate] 前完成 Activity Result 注册，
 * 用于地理定位、WebRTC 等权限申请，避免在 Activity 已 STARTED 后注册导致异常。
 */
class PermissionResultFragment : Fragment() {

    private var singlePermissionCallback: ((Boolean) -> Unit)? = null
    private var multiplePermissionsCallback: ((Map<String, Boolean>) -> Unit)? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        singlePermissionCallback?.invoke(granted)
        singlePermissionCallback = null
    }

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        multiplePermissionsCallback?.invoke(result)
        multiplePermissionsCallback = null
    }

    fun requestPermission(permission: String, onResult: (Boolean) -> Unit) {
        singlePermissionCallback = onResult
        requestPermissionLauncher.launch(permission)
    }

    fun requestPermissions(permissions: Array<String>, onResult: (Map<String, Boolean>) -> Unit) {
        if (permissions.isEmpty()) {
            onResult(emptyMap())
            return
        }
        multiplePermissionsCallback = onResult
        requestMultiplePermissionsLauncher.launch(permissions)
    }

    companion object {
        const val TAG = "BitwebcPermissionResultFragment"

        @JvmStatic
        fun ensureAdded(activity: FragmentActivity): PermissionResultFragment {
            val fm = activity.supportFragmentManager
            val existing = fm.findFragmentByTag(TAG) as? PermissionResultFragment
            if (existing != null) return existing
            val fragment = PermissionResultFragment()
            fm.beginTransaction().add(fragment, TAG).commitNow()
            return fragment
        }
    }
}
