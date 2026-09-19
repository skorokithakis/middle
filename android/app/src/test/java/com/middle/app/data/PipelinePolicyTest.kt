package com.middle.app.data

import com.middle.app.transcription.TranscriptionResult
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class PipelinePolicyTest {

    @Test
    fun backoffStartsAtTwoSecondsAndDoubles() {
        assertEquals(2_000L, PipelinePolicy.backoffDelayMillis(0))
        assertEquals(2_000L, PipelinePolicy.backoffDelayMillis(1))
        assertEquals(4_000L, PipelinePolicy.backoffDelayMillis(2))
        assertEquals(8_000L, PipelinePolicy.backoffDelayMillis(3))
        assertEquals(16_000L, PipelinePolicy.backoffDelayMillis(4))
    }

    @Test
    fun backoffIsCappedAtThirtyMinutes() {
        assertEquals(PipelinePolicy.MAX_BACKOFF_MILLIS, PipelinePolicy.backoffDelayMillis(11))
        assertEquals(PipelinePolicy.MAX_BACKOFF_MILLIS, PipelinePolicy.backoffDelayMillis(25))
        assertEquals(PipelinePolicy.MAX_BACKOFF_MILLIS, PipelinePolicy.backoffDelayMillis(1000))
    }

    @Test
    fun transcriptionOutcomesAreClassified() {
        assertEquals(
            TranscriptionOutcome.SUCCESS,
            PipelinePolicy.classifyTranscription(TranscriptionResult.Success("hello")),
        )
        assertEquals(
            TranscriptionOutcome.TRANSIENT,
            PipelinePolicy.classifyTranscription(TranscriptionResult.NetworkError(IOException("timeout"))),
        )
        assertEquals(
            TranscriptionOutcome.TRANSIENT,
            PipelinePolicy.classifyTranscription(TranscriptionResult.ParseError("bad json")),
        )
        assertEquals(
            TranscriptionOutcome.AUTH,
            PipelinePolicy.classifyTranscription(TranscriptionResult.HttpError(401, "")),
        )
        assertEquals(
            TranscriptionOutcome.AUTH,
            PipelinePolicy.classifyTranscription(TranscriptionResult.HttpError(403, "")),
        )
        for (code in listOf(400, 413, 415, 422)) {
            assertEquals(
                "HTTP $code should be a bad file",
                TranscriptionOutcome.BAD_FILE,
                PipelinePolicy.classifyTranscription(TranscriptionResult.HttpError(code, "")),
            )
        }
        for (code in listOf(408, 429, 500, 503)) {
            assertEquals(
                "HTTP $code should be transient",
                TranscriptionOutcome.TRANSIENT,
                PipelinePolicy.classifyTranscription(TranscriptionResult.HttpError(code, "")),
            )
        }
    }

    @Test
    fun webhookOutcomesAreClassified() {
        assertEquals(WebhookOutcome.SUCCESS, PipelinePolicy.classifyWebhook(success = true, code = 200))
        assertEquals(WebhookOutcome.DROP, PipelinePolicy.classifyWebhook(success = false, code = 400))
        assertEquals(WebhookOutcome.DROP, PipelinePolicy.classifyWebhook(success = false, code = 404))
        assertEquals(WebhookOutcome.RETRY, PipelinePolicy.classifyWebhook(success = false, code = 408))
        assertEquals(WebhookOutcome.RETRY, PipelinePolicy.classifyWebhook(success = false, code = 429))
        assertEquals(WebhookOutcome.RETRY, PipelinePolicy.classifyWebhook(success = false, code = 500))
        assertEquals(WebhookOutcome.RETRY, PipelinePolicy.classifyWebhook(success = false, code = 302))
    }
}
