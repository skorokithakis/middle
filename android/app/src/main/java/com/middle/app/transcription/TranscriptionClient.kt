package com.middle.app.transcription

import android.util.Log
import com.middle.app.data.Settings
import com.middle.app.data.WebhookLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

sealed class TranscriptionResult {
    data class Success(val text: String) : TranscriptionResult()
    data class HttpError(val code: Int, val body: String) : TranscriptionResult()
    data class NetworkError(val exception: Exception) : TranscriptionResult()
    data class ParseError(val message: String) : TranscriptionResult()
}

class TranscriptionClient(
    private val provider: String,
    private val apiKey: String,
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun transcribe(audioFile: File): TranscriptionResult {
        return when (provider) {
            Settings.TRANSCRIPTION_PROVIDER_OPENAI -> transcribeOpenAi(audioFile)
            Settings.TRANSCRIPTION_PROVIDER_ELEVENLABS -> transcribeElevenLabs(audioFile)
            else -> {
                Log.e(TAG, "Unsupported transcription provider: $provider")
                TranscriptionResult.NetworkError(
                    IllegalArgumentException("Unsupported transcription provider: $provider"),
                )
            }
        }
    }

    private fun transcribeOpenAi(audioFile: File): TranscriptionResult {
        val mimeType = "audio/mp4"
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", OPENAI_TRANSCRIPTION_MODEL)
            .addFormDataPart("response_format", "json")
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody(mimeType.toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url(OPENAI_TRANSCRIPTION_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "Transcription failed: ${response.code} $body")
                    WebhookLog.error("Transcription failed (OpenAI): ${response.code} $body")
                    TranscriptionResult.HttpError(response.code, body)
                } else {
                    parseTranscriptText(body, "OpenAI")
                }
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Transcription request failed: $exception")
            WebhookLog.error("Transcription request failed (OpenAI): ${exception::class.simpleName}: ${exception.message}")
            TranscriptionResult.NetworkError(exception)
        }
    }

    private fun transcribeElevenLabs(audioFile: File): TranscriptionResult {
        val mimeType = "audio/mp4"
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model_id", ELEVENLABS_TRANSCRIPTION_MODEL)
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody(mimeType.toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url(ELEVENLABS_TRANSCRIPTION_URL)
            .header("xi-api-key", apiKey)
            .post(requestBody)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "Transcription failed: ${response.code} $body")
                    WebhookLog.error("Transcription failed (ElevenLabs): ${response.code} $body")
                    TranscriptionResult.HttpError(response.code, body)
                } else {
                    parseTranscriptText(body, "ElevenLabs")
                }
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Transcription request failed: $exception")
            WebhookLog.error("Transcription request failed (ElevenLabs): ${exception::class.simpleName}: ${exception.message}")
            TranscriptionResult.NetworkError(exception)
        }
    }

    private fun parseTranscriptText(body: String, providerDisplayName: String): TranscriptionResult {
        return try {
            val json = JSONObject(body)
            val text = when {
                json.has("text") -> json.optString("text")
                json.has("transcription") -> json.optString("transcription")
                json.has("transcript") -> json.optString("transcript")
                else -> {
                    val message = "Transcription response missing text field ($providerDisplayName): $body"
                    Log.e(TAG, message)
                    WebhookLog.error(message)
                    null
                }
            }?.takeIf { it.isNotBlank() }
            if (text == null) {
                TranscriptionResult.ParseError(
                    "Transcription response missing text field ($providerDisplayName)",
                )
            } else {
                TranscriptionResult.Success(text)
            }
        } catch (exception: Exception) {
            val message = "Transcription response parse failed ($providerDisplayName): ${exception::class.simpleName}: ${exception.message}"
            Log.e(TAG, "$message. Body: $body")
            WebhookLog.error(message)
            TranscriptionResult.ParseError(message)
        }
    }

    companion object {
        private const val TAG = "Transcription"
        private const val OPENAI_TRANSCRIPTION_MODEL = "gpt-4o-transcribe"
        private const val OPENAI_TRANSCRIPTION_URL = "https://api.openai.com/v1/audio/transcriptions"
        private const val ELEVENLABS_TRANSCRIPTION_MODEL = "scribe_v2"
        private const val ELEVENLABS_TRANSCRIPTION_URL = "https://api.elevenlabs.io/v1/speech-to-text"
    }
}
