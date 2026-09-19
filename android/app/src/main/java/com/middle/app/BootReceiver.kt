package com.middle.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.middle.app.ble.SyncForegroundService

/**
 * Restarts the sync service after a reboot. No UI is shown: when the runtime
 * permissions the service needs are already granted, the service comes back on
 * its own, otherwise the user has to open the app and be asked for them, which
 * is the only place the permission prompt can be shown.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!hasRequiredPermissions(context)) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, SyncForegroundService::class.java),
        )
    }

    /**
     * Mirrors [MainActivity.requestPermissionsAndStart]: MainActivity starts the
     * service only when none of the runtime permissions it checks are missing, so
     * the boot path does the same rather than starting a service that would fail
     * its BLE calls or post no visible notification.
     */
    private fun hasRequiredPermissions(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!context.isGranted(Manifest.permission.BLUETOOTH_SCAN)) return false
            if (!context.isGranted(Manifest.permission.BLUETOOTH_CONNECT)) return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!context.isGranted(Manifest.permission.POST_NOTIFICATIONS)) return false
        }
        return true
    }

    private fun Context.isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
}
