package net.gooshy.dashy

import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Dashy McDashface: full screen, screen kept on, reading the car while it's in
 * front. Nothing runs when it isn't.
 */
class MainActivity : ComponentActivity() {

    private lateinit var feed: LiveFeed

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Speed arrives only once CAR_SPEED is granted: reconnect to pick it up.
            feed.stop(); feed.start()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        feed = LiveFeed(applicationContext)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        requestRuntimePermissions()

        setContent { DashRoot(feed) }
    }

    override fun onStart() {
        super.onStart()
        feed.start()
    }

    override fun onStop() {
        feed.stop()
        super.onStop()
    }

    /** Only the runtime ("dangerous") ones; CAR_SPEED is the one that matters. */
    private fun requestRuntimePermissions() {
        val requested = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        val missing = requested.filter { p ->
            val info = runCatching { packageManager.getPermissionInfo(p, 0) }.getOrNull() ?: return@filter false
            info.protection == PermissionInfo.PROTECTION_DANGEROUS &&
                checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())
    }
}
