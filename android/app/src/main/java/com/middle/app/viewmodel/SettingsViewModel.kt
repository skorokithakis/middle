package com.middle.app.viewmodel

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.middle.app.data.BackupParseError
import com.middle.app.data.BackupParseResult
import com.middle.app.data.Settings
import com.middle.app.data.SettingsBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BondedDevice(val name: String?, val address: String)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = Settings(application)

    private val _openAiApiKey = MutableStateFlow(settings.openAiApiKey)
    val openAiApiKey: StateFlow<String> = _openAiApiKey

    private val _elevenLabsApiKey = MutableStateFlow(settings.elevenLabsApiKey)
    val elevenLabsApiKey: StateFlow<String> = _elevenLabsApiKey

    private val _transcriptionProvider = MutableStateFlow(settings.transcriptionProvider)
    val transcriptionProvider: StateFlow<String> = _transcriptionProvider

    private val _deviceType = MutableStateFlow(settings.deviceType)
    val deviceType: StateFlow<String> = _deviceType

    private val _ringDeviceAddress = MutableStateFlow(settings.ringDeviceAddress)
    val ringDeviceAddress: StateFlow<String> = _ringDeviceAddress

    private val _bondedDevices = MutableStateFlow<List<BondedDevice>>(emptyList())
    val bondedDevices: StateFlow<List<BondedDevice>> = _bondedDevices

    // True when [bondedDevices] is the unfiltered bond list because no device
    // was recognised as a ring by name. The picker says so, otherwise the user
    // is left guessing why unrelated devices are offered.
    private val _showingUnrecognisedDevices = MutableStateFlow(false)
    val showingUnrecognisedDevices: StateFlow<Boolean> = _showingUnrecognisedDevices

    private val _bluetoothConnectGranted = MutableStateFlow(false)
    val bluetoothConnectGranted: StateFlow<Boolean> = _bluetoothConnectGranted

    private val _backgroundSyncEnabled = MutableStateFlow(settings.backgroundSyncEnabled)
    val backgroundSyncEnabled: StateFlow<Boolean> = _backgroundSyncEnabled

    private val _transcriptionEnabled = MutableStateFlow(settings.transcriptionEnabled)
    val transcriptionEnabled: StateFlow<Boolean> = _transcriptionEnabled

    private val _webhookEnabled = MutableStateFlow(settings.webhookEnabled)
    val webhookEnabled: StateFlow<Boolean> = _webhookEnabled

    private val _webhookUrl = MutableStateFlow(settings.webhookUrl)
    val webhookUrl: StateFlow<String> = _webhookUrl

    private val _webhookBodyTemplate = MutableStateFlow(settings.webhookBodyTemplate)
    val webhookBodyTemplate: StateFlow<String> = _webhookBodyTemplate

    private val _isPaired = MutableStateFlow(settings.isPaired)
    val isPaired: StateFlow<Boolean> = _isPaired

    private val _pairingToken = MutableStateFlow(settings.pairingToken)
    val pairingToken: StateFlow<String> = _pairingToken

    // A validated backup waiting for the user to confirm the import. Null when
    // there is no confirmation dialog to show.
    private val _pendingImport = MutableStateFlow<SettingsBackup?>(null)
    val pendingImport: StateFlow<SettingsBackup?> = _pendingImport

    fun setOpenAiApiKey(key: String) {
        settings.openAiApiKey = key
        _openAiApiKey.value = key
    }

    fun setElevenLabsApiKey(key: String) {
        settings.elevenLabsApiKey = key
        _elevenLabsApiKey.value = key
    }

    fun setTranscriptionProvider(provider: String) {
        settings.transcriptionProvider = provider
        _transcriptionProvider.value = provider
    }

    fun setDeviceType(type: String) {
        settings.deviceType = type
        _deviceType.value = type
    }

    fun setRingDeviceAddress(address: String) {
        settings.ringDeviceAddress = address
        _ringDeviceAddress.value = address
    }

    /**
     * Reads the system's bonded device list, which is shared across apps, so
     * the ring is present without the app ever asking to become a companion.
     *
     * The list is only read once the picker is shown, rather than at
     * construction, because [android.bluetooth.BluetoothAdapter.getBondedDevices]
     * throws when the required permission has not been granted yet and the
     * permission result arrives after the activity creates its view models.
     */
    fun refreshBondedDevices() {
        val application = getApplication<Application>()
        // BLUETOOTH_CONNECT only exists from API 31, so on older releases the
        // old normal BLUETOOTH permission (declared with maxSdkVersion="30")
        // is the one that guards the bonded list. Checking BLUETOOTH_CONNECT
        // unconditionally would report denied on API 26 to 30 and the picker
        // would never list anything.
        val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        val granted = ContextCompat.checkSelfPermission(
            application,
            requiredPermission,
        ) == PackageManager.PERMISSION_GRANTED
        _bluetoothConnectGranted.value = granted
        if (!granted) {
            _bondedDevices.value = emptyList()
            return
        }
        val adapter = application.getSystemService(BluetoothManager::class.java).adapter
        val storedAddress = settings.ringDeviceAddress
        val allDevices = adapter.bondedDevices.map { BondedDevice(it.name, it.address) }
        val recognised = allDevices.filter {
            it.name?.startsWith(RING_NAME_PREFIX, ignoreCase = true) == true
        }
        // The stored ring stays in the list even when its bond name does not
        // match, because the name comes from the bond record and can be null or
        // change with firmware. Dropping it would hide a selection that is still
        // in effect, and the user could not see which ring is in use.
        val selectable = recognised + allDevices.filter {
            it.address == storedAddress && it !in recognised
        }
        // Falling back to the whole bond list keeps the picker usable when no
        // name matches, where filtering strictly would leave the user with an
        // empty list and no way to proceed.
        _showingUnrecognisedDevices.value = selectable.isEmpty()
        _bondedDevices.value = selectable.ifEmpty { allDevices }
        // Only auto-select a device the name filter recognised. Auto-selecting
        // the single entry of the fallback list could silently store a pair of
        // headphones as the ring. Storing an address resets the collection
        // index, so this must not overwrite a ring the user already chose.
        if (recognised.size == 1 && storedAddress.isEmpty()) {
            setRingDeviceAddress(recognised.first().address)
        }
    }

    fun setBackgroundSync(enabled: Boolean) {
        settings.backgroundSyncEnabled = enabled
        _backgroundSyncEnabled.value = enabled
    }

    fun setTranscription(enabled: Boolean) {
        settings.transcriptionEnabled = enabled
        _transcriptionEnabled.value = enabled
    }

    fun setWebhookEnabled(enabled: Boolean) {
        settings.webhookEnabled = enabled
        _webhookEnabled.value = enabled
    }

    fun setWebhookUrl(url: String) {
        settings.webhookUrl = url
        _webhookUrl.value = url
    }

    fun setWebhookBodyTemplate(template: String) {
        settings.webhookBodyTemplate = template
        _webhookBodyTemplate.value = template
    }

    fun unpairPendant() {
        settings.clearPairing()
        _isPaired.value = false
        _pairingToken.value = ""
    }

    /**
     * Writes the current settings to [uri] as a JSON backup. The write runs off
     * the main thread because a document provider can be a network service.
     */
    fun exportSettings(uri: Uri) {
        val application = getApplication<Application>()
        val backup = settings.exportBackupJson()
        viewModelScope.launch(Dispatchers.IO) {
            val written = try {
                application.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(backup.toByteArray(Charsets.UTF_8))
                } != null
            } catch (exception: Exception) {
                // A document provider can fail for reasons beyond IOException,
                // such as a security exception, so the boundary reports any
                // failure with the same message.
                Log.w(TAG, "Could not write settings backup: $exception")
                false
            }
            showToast(if (written) MESSAGE_EXPORTED else MESSAGE_WRITE_FAILED)
        }
    }

    /**
     * Reads and validates the backup at [uri] without changing any setting. A
     * valid backup is parked in [pendingImport] until the user confirms it.
     */
    fun prepareImport(uri: Uri) {
        val application = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val text = try {
                application.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                    it.readText()
                }
            } catch (exception: Exception) {
                Log.w(TAG, "Could not read settings backup: $exception")
                null
            }
            if (text == null) {
                showToast(MESSAGE_READ_FAILED)
                return@launch
            }
            when (val result = settings.parseBackupJson(text)) {
                is BackupParseResult.Valid -> _pendingImport.value = result.backup
                is BackupParseResult.Invalid -> showToast(
                    when (result.error) {
                        BackupParseError.NOT_A_BACKUP -> MESSAGE_NOT_A_BACKUP
                        BackupParseError.NEWER_VERSION -> MESSAGE_NEWER_VERSION
                    },
                )
            }
        }
    }

    /** Applies the backup [prepareImport] parked, if the user confirmed it. */
    fun applyImport() {
        val backup = _pendingImport.value ?: return
        _pendingImport.value = null
        settings.applyBackup(backup)
        refreshFromSettings()
        viewModelScope.launch { showToast(MESSAGE_IMPORTED) }
    }

    fun cancelImport() {
        _pendingImport.value = null
    }

    /** Reloads every flow from storage after an import rewrote the settings. */
    private fun refreshFromSettings() {
        _openAiApiKey.value = settings.openAiApiKey
        _elevenLabsApiKey.value = settings.elevenLabsApiKey
        _transcriptionProvider.value = settings.transcriptionProvider
        _deviceType.value = settings.deviceType
        _ringDeviceAddress.value = settings.ringDeviceAddress
        _backgroundSyncEnabled.value = settings.backgroundSyncEnabled
        _transcriptionEnabled.value = settings.transcriptionEnabled
        _webhookEnabled.value = settings.webhookEnabled
        _webhookUrl.value = settings.webhookUrl
        _webhookBodyTemplate.value = settings.webhookBodyTemplate
        _isPaired.value = settings.isPaired
        _pairingToken.value = settings.pairingToken
    }

    private suspend fun showToast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        // Index 01 rings bond under a name of the form "Pebble Index XXX",
        // where the suffix identifies the individual ring.
        private const val RING_NAME_PREFIX = "Pebble Index"
        private const val TAG = "SettingsViewModel"

        private const val MESSAGE_EXPORTED = "Settings exported."
        private const val MESSAGE_IMPORTED = "Settings imported."
        private const val MESSAGE_WRITE_FAILED = "Could not write the file."
        private const val MESSAGE_READ_FAILED = "Could not read that file."
        private const val MESSAGE_NOT_A_BACKUP = "That file is not a Middle settings backup."
        private const val MESSAGE_NEWER_VERSION = "That backup is from a newer version of the app."
    }
}
