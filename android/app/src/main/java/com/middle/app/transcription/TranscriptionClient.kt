package com.middle.app.transcription

import android.util.Log
import com.middle.app.data.Settings
import com.middle.app.data.WebhookLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
            Settings.TRANSCRIPTION_PROVIDER_SONIOX -> transcribeSoniox(audioFile)
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

    /**
     * Soniox transcribes asynchronously: the audio is uploaded, a transcription
     * is created against the uploaded file, and the text only exists after the
     * job completes. Both resources are deleted on every exit path, because
     * Soniox keeps uploaded audio for 30 days otherwise. A failed cleanup is
     * logged but never turns a transcript that was already fetched into a
     * failure.
     */
    private fun transcribeSoniox(audioFile: File): TranscriptionResult {
        var uploadedFileId: String? = null
        var transcriptionId: String? = null
        try {
            val uploadBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody("audio/mp4".toMediaType()),
                )
                .build()
            val upload = sonioxCall(
                request = sonioxRequest(SONIOX_FILES_URL).post(uploadBody).build(),
                stage = "upload",
                rejectDropsRecording = true,
            )
            val uploadJson = when (upload) {
                is SonioxResponse.Failure -> return upload.result
                is SonioxResponse.Json -> upload.body
            }
            val fileId = uploadJson.optString("id").takeIf { it.isNotEmpty() }
                ?: return sonioxMissingField("upload", "id", uploadJson)
            uploadedFileId = fileId

            val createBody = JSONObject()
                .put("model", SONIOX_TRANSCRIPTION_MODEL)
                .put("file_id", fileId)
                .toString()
                .toRequestBody("application/json".toMediaType())
            val create = sonioxCall(
                request = sonioxRequest(SONIOX_TRANSCRIPTIONS_URL).post(createBody).build(),
                stage = "create",
            )
            val createJson = when (create) {
                is SonioxResponse.Failure -> return create.result
                is SonioxResponse.Json -> create.body
            }
            val createdId = createJson.optString("id").takeIf { it.isNotEmpty() }
                ?: return sonioxMissingField("create", "id", createJson)
            transcriptionId = createdId

            val deadlineNanos = System.nanoTime() + SONIOX_POLL_TIMEOUT_MILLIS * NANOS_PER_MILLI
            while (true) {
                // A monotonic clock keeps the deadline honest even if the
                // device's wall clock is adjusted mid-poll.
                val remaining = remainingMillis(deadlineNanos)
                if (remaining <= 0L) return sonioxTimeout()
                val status = sonioxCall(
                    request = sonioxRequest("$SONIOX_TRANSCRIPTIONS_URL/$createdId").get().build(),
                    stage = "status",
                    callTimeoutMillis = remaining,
                )
                val statusJson = when (status) {
                    is SonioxResponse.Failure -> return status.result
                    is SonioxResponse.Json -> status.body
                }
                when (statusJson.optString("status")) {
                    "completed" -> break
                    // The docs call this "error" in the status enum but describe
                    // a "failed" status in the error section, so both are
                    // treated as terminal.
                    "error", "failed" -> {
                        val errorType = statusJson.optString("error_type")
                        val errorMessage = statusJson.optString("error_message")
                        Log.e(TAG, "Transcription failed (Soniox): $errorType: $errorMessage")
                        WebhookLog.error("Transcription failed (Soniox): $errorType: $errorMessage")
                        return TranscriptionResult.HttpError(
                            sonioxErrorStatus(errorType),
                            statusJson.toString(),
                        )
                    }
                }
                val sleepMillis =
                    minOf(SONIOX_POLL_INTERVAL_MILLIS, remainingMillis(deadlineNanos))
                if (sleepMillis <= 0L) return sonioxTimeout()
                Thread.sleep(sleepMillis)
            }

            val transcriptRemaining = remainingMillis(deadlineNanos)
            if (transcriptRemaining <= 0L) return sonioxTimeout()
            val transcript = sonioxCall(
                request = sonioxRequest("$SONIOX_TRANSCRIPTIONS_URL/$createdId/transcript")
                    .get()
                    .build(),
                stage = "transcript",
                callTimeoutMillis = transcriptRemaining,
            )
            return when (transcript) {
                is SonioxResponse.Failure -> transcript.result
                // An empty transcript is a blank text field, which the shared
                // parser treats as a parse failure exactly like the other
                // providers do.
                is SonioxResponse.Json ->
                    parseTranscriptText(transcript.body.toString(), "Soniox")
            }
        } catch (exception: Exception) {
            val message =
                "Transcription request failed (Soniox): ${exception::class.simpleName}: ${exception.message}"
            Log.e(TAG, message)
            WebhookLog.error(message)
            return TranscriptionResult.NetworkError(exception)
        } finally {
            transcriptionId?.let {
                deleteSonioxResource("$SONIOX_TRANSCRIPTIONS_URL/$it", "transcription")
            }
            uploadedFileId?.let { deleteSonioxResource("$SONIOX_FILES_URL/$it", "file") }
        }
    }

    /**
     * Runs one Soniox call and parses a JSON body, so the flow above can return
     * the failure unchanged at any stage.
     */
    private fun sonioxCall(
        request: Request,
        stage: String,
        rejectDropsRecording: Boolean = false,
        callTimeoutMillis: Long? = null,
    ): SonioxResponse {
        val call = httpClient.newCall(request)
        if (callTimeoutMillis != null) {
            call.timeout().timeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        }
        return try {
            call.execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    // A later-stage 400 means a request problem a retry may fix,
                    // not a bad recording, so it must not reach the drop codes.
                    val code = if (rejectDropsRecording) {
                        response.code
                    } else {
                        retryableHttpCode(response.code)
                    }
                    Log.e(TAG, "Transcription failed (Soniox, $stage): ${response.code} $body")
                    WebhookLog.error("Transcription failed (Soniox, $stage): ${response.code} $body")
                    SonioxResponse.Failure(TranscriptionResult.HttpError(code, body))
                } else {
                    try {
                        SonioxResponse.Json(JSONObject(body))
                    } catch (exception: Exception) {
                        val message =
                            "Transcription response parse failed (Soniox, $stage): ${exception::class.simpleName}: ${exception.message}"
                        Log.e(TAG, "$message. Body: $body")
                        WebhookLog.error(message)
                        SonioxResponse.Failure(TranscriptionResult.ParseError(message))
                    }
                }
            }
        } catch (exception: Exception) {
            val message =
                "Transcription request failed (Soniox, $stage): ${exception::class.simpleName}: ${exception.message}"
            Log.e(TAG, message)
            WebhookLog.error(message)
            SonioxResponse.Failure(TranscriptionResult.NetworkError(exception))
        }
    }

    private fun sonioxRequest(url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $apiKey")

    private fun remainingMillis(deadlineNanos: Long): Long =
        (deadlineNanos - System.nanoTime()) / NANOS_PER_MILLI

    private fun sonioxTimeout(): TranscriptionResult {
        val message =
            "Transcription timed out (Soniox) after ${SONIOX_POLL_TIMEOUT_MILLIS / 1000} seconds"
        Log.e(TAG, message)
        WebhookLog.error(message)
        return TranscriptionResult.NetworkError(TimeoutException(message))
    }

    private fun sonioxMissingField(
        stage: String,
        field: String,
        body: JSONObject,
    ): TranscriptionResult {
        val message = "Transcription response missing $field (Soniox, $stage): $body"
        Log.e(TAG, message)
        WebhookLog.error(message)
        return TranscriptionResult.ParseError(message)
    }

    /**
     * The async job reports a failure in a 200 poll body rather than as an HTTP
     * status, so the documented error types are mapped back onto their HTTP
     * equivalents to let the existing outcome classification reuse its rules.
     * Anything unrecognised maps to 500 and is retried rather than dropping the
     * recording.
     */
    private fun sonioxErrorStatus(errorType: String): Int = when (errorType) {
        "invalid_audio_file" -> 400
        "file_not_found" -> 404
        else -> 500
    }

    /**
     * The pipeline drops the job for 400/413/415/422, so a non-upload call's
     * copy of those codes is reported as a retryable 500 instead.
     */
    private fun retryableHttpCode(code: Int): Int =
        if (code == 400 || code == 413 || code == 415 || code == 422) 500 else code

    /**
     * Deletes one Soniox resource, treating an already-absent resource as
     * success. Failures are logged only: cleanup must never change the result
     * of a transcription that already finished. The short call timeout keeps a
     * hung delete from stalling the single pipeline worker on the way out.
     */
    private fun deleteSonioxResource(url: String, stage: String) {
        val call = httpClient.newCall(sonioxRequest(url).delete().build())
        call.timeout().timeout(SONIOX_CLEANUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful && response.code != 404) {
                    val body = response.body?.string() ?: ""
                    val message = "Soniox $stage cleanup failed: ${response.code} $body"
                    Log.w(TAG, message)
                    WebhookLog.error(message)
                }
            }
        } catch (exception: Exception) {
            val message =
                "Soniox $stage cleanup failed: ${exception::class.simpleName}: ${exception.message}"
            Log.w(TAG, message)
            WebhookLog.error(message)
        }
    }

    private sealed interface SonioxResponse {
        data class Json(val body: JSONObject) : SonioxResponse
        data class Failure(val result: TranscriptionResult) : SonioxResponse
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
        private const val SONIOX_API_BASE_URL = "https://api.soniox.com"
        private const val SONIOX_FILES_URL = "$SONIOX_API_BASE_URL/v1/files"
        private const val SONIOX_TRANSCRIPTIONS_URL = "$SONIOX_API_BASE_URL/v1/transcriptions"
        private const val SONIOX_TRANSCRIPTION_MODEL = "stt-async-v6"
        private const val SONIOX_POLL_INTERVAL_MILLIS = 1_000L

        // The pipeline retries a timeout, so the deadline only has to stop one
        // attempt from hanging the worker indefinitely, not cover the longest
        // possible recording.
        private const val SONIOX_POLL_TIMEOUT_MILLIS = 2 * 60 * 1_000L
        private const val SONIOX_CLEANUP_TIMEOUT_MILLIS = 10_000L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
