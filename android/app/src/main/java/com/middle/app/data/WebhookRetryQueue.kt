package com.middle.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.UUID

private const val TAG = "WebhookRetryQueue"
private const val MAX_RETRIES = 10
private const val BASE_DELAY_MILLIS = 2_000L
private const val MAX_DELAY_MILLIS = 24 * 3600 * 1000L

class WebhookRetryQueue(context: Context, private val scope: CoroutineScope) {

    private val webhooksDirectory = File(context.filesDir, "webhooks").also { it.mkdirs() }
    private var retryJob: Job? = null

    fun startRetryLoopIfNeeded() {
        if (retryJob?.isActive == true) return
        if (pendingCount() == 0) return
        retryJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                processRetries()
                if (pendingCount() == 0) break
                delay(1000)
            }
        }
    }

    fun enqueue(
        transcript: String,
        webhookUrl: String,
        bodyTemplate: String,
        filename: String,
    ) {
        val json = JSONObject().apply {
            put("transcript", transcript)
            put("webhookUrl", webhookUrl)
            put("bodyTemplate", bodyTemplate)
            put("retryCount", 0)
            put("lastRetryTimeMillis", System.currentTimeMillis())
            put("filename", filename)
        }
        val file = File(webhooksDirectory, "${UUID.randomUUID()}.json")
        file.writeText(json.toString())
        Log.d(TAG, "Enqueued retry for $filename")
        startRetryLoopIfNeeded()
    }

    fun processRetries() {
        val files = webhooksDirectory.listFiles { file -> file.extension == "json" }
            ?: return

        for (file in files) {
            val json = try {
                JSONObject(file.readText())
            } catch (exception: JSONException) {
                WebhookLog.error("Malformed retry file ${file.name}, discarding")
                Log.w(TAG, "Malformed retry file ${file.name}", exception)
                file.delete()
                continue
            }

            val transcript = json.optString("transcript")
            val webhookUrl = json.optString("webhookUrl")
            val bodyTemplate = json.optString("bodyTemplate")
            val retryCount = json.optInt("retryCount", 0)
            val lastRetryTimeMillis = json.optLong("lastRetryTimeMillis", 0L)
            val recordingFilename = json.optString("filename")

            val delayMillis = minOf(
                (1L shl retryCount) * BASE_DELAY_MILLIS,
                MAX_DELAY_MILLIS,
            )
            val nextRetryTimeMillis = lastRetryTimeMillis + delayMillis

            if (System.currentTimeMillis() < nextRetryTimeMillis) {
                continue
            }

            val result = try {
                WebhookClient.post(webhookUrl, transcript, bodyTemplate)
            } catch (exception: Exception) {
                WebhookLog.error("Webhook retry exception for $recordingFilename: ${exception.message}")
                Log.w(TAG, "Webhook retry exception for $recordingFilename", exception)
                handleRetryableFailure(file, json, retryCount, recordingFilename)
                continue
            }

            when {
                result.success -> {
                    file.delete()
                    WebhookLog.info("Webhook retry succeeded for $recordingFilename (attempt ${retryCount + 1})")
                    Log.d(TAG, "Webhook retry succeeded for $recordingFilename")
                }
                result.code in 400..499 -> {
                    file.delete()
                    WebhookLog.error(
                        "Webhook abandoned for $recordingFilename: client error ${result.code} ${result.message}",
                    )
                    Log.w(TAG, "Webhook abandoned for $recordingFilename: ${result.code}")
                }
                else -> {
                    // 5xx or unexpected codes are treated as retryable.
                    WebhookLog.error(
                        "Webhook retry failed for $recordingFilename: ${result.code} ${result.message}",
                    )
                    handleRetryableFailure(file, json, retryCount, recordingFilename)
                }
            }
        }
    }

    fun removeForRecording(filename: String) {
        val files = webhooksDirectory.listFiles { file -> file.extension == "json" }
            ?: return

        for (file in files) {
            val json = try {
                JSONObject(file.readText())
            } catch (exception: JSONException) {
                Log.w(TAG, "Malformed retry file ${file.name} during removal, deleting", exception)
                file.delete()
                continue
            }
            if (json.optString("filename") == filename) {
                file.delete()
                Log.d(TAG, "Removed retry for $filename")
            }
        }
    }

    fun pendingCount(): Int =
        webhooksDirectory.listFiles { file -> file.extension == "json" }?.size ?: 0

    private fun handleRetryableFailure(
        file: File,
        json: JSONObject,
        retryCount: Int,
        recordingFilename: String,
    ) {
        val newRetryCount = retryCount + 1
        if (newRetryCount >= MAX_RETRIES) {
            file.delete()
            WebhookLog.error("Webhook abandoned for $recordingFilename after $MAX_RETRIES attempts")
            Log.w(TAG, "Webhook abandoned for $recordingFilename after $MAX_RETRIES attempts")
            return
        }
        json.put("retryCount", newRetryCount)
        json.put("lastRetryTimeMillis", System.currentTimeMillis())
        file.writeText(json.toString())
    }
}
