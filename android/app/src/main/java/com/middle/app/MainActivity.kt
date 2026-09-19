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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.middle.app.ble.SyncForegroundService
import com.middle.app.ui.LogScreen
import com.middle.app.ui.RecordingsScreen
import com.middle.app.ui.SettingsScreen
import com.middle.app.ui.theme.MiddleTheme
import com.middle.app.viewmodel.RecordingsViewModel
import com.middle.app.viewmodel.SettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Set from an intent extra when a notification asks to open a specific
    // screen. The NavHost lives inside composition, so the request is parked
    // here until MiddleNavigation can consume it. The extra is a boolean rather
    // than a route name because this activity is exported: an arbitrary route
    // from an untrusted caller would crash navigate().
    private val openRecordingsRequested = MutableStateFlow(false)

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

        handleNavigationIntent(intent)
        requestPermissionsAndStart()

        setContent {
            MiddleTheme {
                MiddleNavigation(openRecordingsRequested)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: Intent?) {
        if (intent == null) return
        if (!intent.getBooleanExtra(EXTRA_OPEN_RECORDINGS, false)) return
        // Consume the extra so a later recreation of this activity from the same
        // intent (for example a rotation) does not navigate again.
        intent.removeExtra(EXTRA_OPEN_RECORDINGS)
        openRecordingsRequested.value = true
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.setForeground(true)
    }

    override fun onStop() {
        AppVisibility.setForeground(false)
        super.onStop()
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

    companion object {
        const val EXTRA_OPEN_RECORDINGS = "com.middle.app.extra.OPEN_RECORDINGS"
        const val ROUTE_RECORDINGS = "recordings"
    }
}

@Composable
fun MiddleNavigation(openRecordingsRequested: MutableStateFlow<Boolean>) {
    val navController = rememberNavController()
    val recordingsViewModel: RecordingsViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val openRecordings by openRecordingsRequested.collectAsState()

    // A notification tap asks for the recordings screen, which is already the
    // start destination (so a cold launch needs no navigation). Consume the
    // request so a later tap still triggers a fresh emission.
    LaunchedEffect(openRecordings) {
        if (!openRecordings) return@LaunchedEffect
        navController.navigate(MainActivity.ROUTE_RECORDINGS) {
            popUpTo(MainActivity.ROUTE_RECORDINGS) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        openRecordingsRequested.value = false
    }

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Recordings") },
                    selected = currentRoute == "recordings",
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            navController.navigate("recordings") {
                                popUpTo("recordings") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text("Log") },
                    selected = currentRoute == "log",
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            navController.navigate("log") {
                                popUpTo("recordings") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                    selected = currentRoute == "settings",
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            navController.navigate("settings") {
                                popUpTo("recordings") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                )
            }
        },
    ) {
        NavHost(navController = navController, startDestination = "recordings") {
            composable("recordings") {
                RecordingsScreen(
                    viewModel = recordingsViewModel,
                    onOpenDrawer = openDrawer,
                )
            }
            composable("log") {
                LogScreen(onOpenDrawer = openDrawer)
            }
            composable("settings") {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onOpenDrawer = openDrawer,
                )
            }
        }
    }
}
