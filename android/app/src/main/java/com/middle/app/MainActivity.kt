package com.middle.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.middle.app.ble.SyncForegroundService
import com.middle.app.ui.RecordingsScreen
import com.middle.app.ui.SettingsScreen
import com.middle.app.ui.theme.MiddleTheme
import com.middle.app.viewmodel.RecordingsViewModel
import com.middle.app.viewmodel.SettingsViewModel

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startSyncService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        requestPermissionsAndStart()

        setContent {
            MiddleTheme {
                MiddleNavigation()
            }
        }
    }

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            startSyncService()
        }
    }

    private fun startSyncService() {
        val intent = Intent(this, SyncForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
}

@Composable
fun MiddleNavigation() {
    val navController = rememberNavController()
    val recordingsViewModel: RecordingsViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()

    NavHost(navController = navController, startDestination = "recordings") {
        composable("recordings") {
            RecordingsScreen(
                viewModel = recordingsViewModel,
                onNavigateToSettings = { navController.navigate("settings") },
            )
        }
        composable("settings") {
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
