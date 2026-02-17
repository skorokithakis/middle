package com.middle.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class WebhookLogEntry(
    val timestamp: String,
    val message: String,
    val isError: Boolean,
)

object WebhookLog {
    private const val MAX_ENTRIES = 50
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val _entries = MutableStateFlow<List<WebhookLogEntry>>(emptyList())
    val entries: StateFlow<List<WebhookLogEntry>> = _entries

    fun info(message: String) = add(message, isError = false)
    fun error(message: String) = add(message, isError = true)

    private fun add(message: String, isError: Boolean) {
        val entry = WebhookLogEntry(
            timestamp = LocalTime.now().format(timeFormat),
            message = message,
            isError = isError,
        )
        _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
    }
}
