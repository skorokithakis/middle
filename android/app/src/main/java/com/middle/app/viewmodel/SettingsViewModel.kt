package com.middle.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.middle.app.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = Settings(application)

    private val _apiKey = MutableStateFlow(settings.openAiApiKey)
    val apiKey: StateFlow<String> = _apiKey

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

    fun setApiKey(key: String) {
        settings.openAiApiKey = key
        _apiKey.value = key
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
}
