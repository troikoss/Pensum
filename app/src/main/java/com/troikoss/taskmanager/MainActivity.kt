package com.troikoss.taskmanager

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.troikoss.taskmanager.ui.TaskManager
import com.troikoss.taskmanager.ui.theme.TaskManagerTheme
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    companion object {
        private const val REQUEST_CODE = 1001
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkShizukuPermission()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        // Shizuku was killed — update UI state here if needed
    }

    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted
                } else {
                    // Permission denied
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register listeners BEFORE checking anything
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)

        setContent {
            TaskManagerTheme {
                TaskManager()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Always clean up listeners
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
    }

    private fun checkShizukuPermission() {
        when {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                // Ready to use Shizuku
            }
            Shizuku.shouldShowRequestPermissionRationale() -> {
                // User denied before — show explanation UI
            }
            else -> {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        }
    }
}