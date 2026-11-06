package com.skcingenieria.facturas

import android.Manifest
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.skcingenieria.facturas.ui.AppRoot
import com.skcingenieria.facturas.ui.AppViewModel
import com.skcingenieria.facturas.ui.Screen
import com.skcingenieria.facturas.ui.theme.SkcTheme
import com.skcingenieria.facturas.worker.ReminderScheduler

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<AppViewModel>()
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReminderScheduler.ensureScheduled(this)
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleIntent(intent)
        setContent {
            SkcTheme {
                AppRoot(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("open_messages", false) == true) {
            viewModel.navigate(Screen.MESSAGES)
            intent.removeExtra("open_messages")
        }
    }
}

