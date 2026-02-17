package com.middle.app.transcription

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class TranscriptionClient(private val apiKey: String) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Transcribe an audio file using OpenAI's gpt-4o-transcribe model.
     * Returns the transcription text, or null if it fails.
     */
    fun transcribe(audioFile: File): String? {
        val mimeType = "audio/mp4"
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", TRANSCRIPTION_MODEL)
            .addFormDataPart("response_format", "json")
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody(mimeType.toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url(TRANSCRIPTION_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Transcription failed: ${response.code} ${response.body?.string()}")
                null
            } else {
                val body = response.body?.string() ?: return null
                JSONObject(body).getString("text")
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Transcription request failed: $exception")
            null
        }
    }

    companion object {
        private const val TAG = "Transcription"
        private const val TRANSCRIPTION_MODEL = "gpt-4o-transcribe"
        private const val TRANSCRIPTION_URL = "https://api.openai.com/v1/audio/transcriptions"
    }
}
