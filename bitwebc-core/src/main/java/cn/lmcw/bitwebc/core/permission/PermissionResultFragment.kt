package cn.lmcw.bitwebc.core.permission

import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity

/** 无 UI Fragment，用于权限申请（地理定位、WebRTC 等） */
class PermissionResultFragment : Fragment() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        dispatcher.onSinglePermissionResult(granted)
    }

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        dispatcher.onMultiplePermissionsResult(result)
    }

    private val dispatcher: PermissionRequestDispatcher by lazy(LazyThreadSafetyMode.NONE) {
        PermissionRequestDispatcher(
            launchSinglePermission = { permission -> requestPermissionLauncher.launch(permission) },
            launchMultiplePermissions = { permissions -> requestMultiplePermissionsLauncher.launch(permissions) }
        )
    }

    fun requestPermission(permission: String, onResult: (Boolean) -> Unit) {
        dispatcher.enqueueSingle(permission, onResult)
    }

    fun requestPermissions(permissions: Array<String>, onResult: (Map<String, Boolean>) -> Unit) {
        dispatcher.enqueueMultiple(permissions, onResult)
    }

    override fun onDestroy() {
        super.onDestroy()
        dispatcher.cancelAllPendingAsDenied()
    }

    companion object {
        const val TAG = "BitwebcPermissionResultFragment"

        @JvmStatic
        fun ensureAdded(activity: FragmentActivity): PermissionResultFragment {
            val fm = activity.supportFragmentManager
            val existing = fm.findFragmentByTag(TAG) as? PermissionResultFragment
            if (existing != null) return existing
            val fragment = PermissionResultFragment()
            try {
                fm.beginTransaction().add(fragment, TAG).commitNowAllowingStateLoss()
            } catch (e: Exception) {
                fm.beginTransaction().add(fragment, TAG).commitAllowingStateLoss()
            }
            return fragment
        }
    }
}
