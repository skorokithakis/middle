package com.middle.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WebhookClientTest {

    @Test
    fun substitutesTranscriptAndRest() {
        val body = renderBody(
            bodyTemplate = """{"phrase": "${'$'}transcript", "tail": "${'$'}rest"}""",
            transcript = "note to self buy milk",
            rest = "buy milk",
        )

        assertEquals("""{"phrase": "note to self buy milk", "tail": "buy milk"}""", body)
    }

    @Test
    fun valuesAreJsonEscaped() {
        val body = renderBody(
            bodyTemplate = """{"phrase": "${'$'}transcript"}""",
            transcript = "he said \"hi\"\nthen left",
            rest = "",
        )

        assertEquals("""{"phrase": "he said \"hi\"\nthen left"}""", body)
    }

    @Test
    fun anInsertedValueIsNotSubstitutedAgain() {
        // The transcript literally contains $rest; it must survive as text
        // rather than be replaced with the rest value.
        val body = renderBody(
            bodyTemplate = """{"phrase": "${'$'}transcript"}""",
            transcript = "mention ${'$'}rest here",
            rest = "should-not-appear",
        )

        assertEquals("""{"phrase": "mention ${'$'}rest here"}""", body)
    }

    @Test
    fun templateWithoutVariablesIsUnchanged() {
        assertEquals("""{"fixed": true}""", renderBody("""{"fixed": true}""", "a", "b"))
    }
}
