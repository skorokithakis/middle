package com.middle.app.data

import com.middle.app.transcription.TranscriptionResult

/**
 * Backoff and outcome decisions for [PipelineQueue]. Deliberately free of
 * Android and network dependencies so the rules can be unit tested directly.
 */
object PipelinePolicy {

    const val BASE_BACKOFF_MILLIS = 2_000L
    const val MAX_BACKOFF_MILLIS = 30 * 60 * 1000L

    /**
     * Delay before the next attempt after [attempts] consecutive failures.
     * Starts at [BASE_BACKOFF_MILLIS] and doubles per attempt, capped at
     * [MAX_BACKOFF_MILLIS]. Attempt 0 is treated as the first failure so a job
     * that was never tried still gets a non-zero wait.
     */
    fun backoffDelayMillis(attempts: Int): Long {
        val exponent = (attempts.coerceAtLeast(1) - 1).coerceAtMost(30)
        return minOf(BASE_BACKOFF_MILLIS shl exponent, MAX_BACKOFF_MILLIS)
    }

    fun classifyTranscription(result: TranscriptionResult): TranscriptionOutcome = when (result) {
        is TranscriptionResult.Success -> TranscriptionOutcome.SUCCESS
        is TranscriptionResult.NetworkError -> TranscriptionOutcome.TRANSIENT
        is TranscriptionResult.ParseError -> TranscriptionOutcome.TRANSIENT
        is TranscriptionResult.HttpError -> when (result.code) {
            401, 403 -> TranscriptionOutcome.AUTH
            400, 413, 415, 422 -> TranscriptionOutcome.BAD_FILE
            // 408, 429, 5xx and anything unclassified are retryable so a
            // misclassified response never drops a recording.
            else -> TranscriptionOutcome.TRANSIENT
        }
    }

    /**
     * A webhook exception is retried by the caller, so only HTTP responses are
     * classified here.
     */
    fun classifyWebhook(success: Boolean, code: Int): WebhookOutcome = when {
        success -> WebhookOutcome.SUCCESS
        code == 408 || code == 429 -> WebhookOutcome.RETRY
        code in 400..499 -> WebhookOutcome.DROP
        else -> WebhookOutcome.RETRY
    }
}

enum class TranscriptionOutcome {
    SUCCESS,
    TRANSIENT,
    AUTH,
    BAD_FILE,
}

enum class WebhookOutcome {
    SUCCESS,
    RETRY,
    DROP,
}
