package com.middle.app.viewmodel

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.middle.app.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
        _bondedDevices.value = adapter.bondedDevices.map { BondedDevice(it.name, it.address) }
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
}
