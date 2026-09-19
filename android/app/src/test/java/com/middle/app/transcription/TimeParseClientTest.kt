package com.middle.app.transcription

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

class TimeParseClientTest {

    private fun body(content: String): String =
        """
        {
          "choices": [
            {
              "message": {
                "role": "assistant",
                "content": ${JSONObject.quote(content)}
              }
            }
          ]
        }
        """.trimIndent()

    @Test
    fun nullStartWhenNoTimeIsPresent() {
        val command = parseResponseBody(
            body("""{"start":null,"end":null,"allDay":false,"title":null}"""),
        )

        assertNull(command.start)
        assertNull(command.end)
        assertEquals(false, command.allDay)
        assertNull(command.title)
    }

    @Test
    fun timedStartWithEndAndTitle() {
        val command = parseResponseBody(
            body(
                """{"start":"2026-09-21T07:30","end":"2026-09-21T08:00","allDay":false,"title":"Standup"}""",
            ),
        )

        assertEquals(LocalDateTime.of(2026, 9, 21, 7, 30), command.start)
        assertEquals(LocalDateTime.of(2026, 9, 21, 8, 0), command.end)
        assertEquals(false, command.allDay)
        assertEquals("Standup", command.title)
    }

    @Test
    fun allDayWithDateAndNoTime() {
        val command = parseResponseBody(
            body("""{"start":"2026-09-21T00:00","end":null,"allDay":true,"title":"Holiday"}"""),
        )

        assertTrue(command.allDay)
        assertEquals(LocalDateTime.of(2026, 9, 21, 0, 0), command.start)
        assertNull(command.end)
        assertEquals("Holiday", command.title)
    }

    @Test
    fun malformedContentThrows() {
        val malformed = """{"choices":[{"message":{"content":"this is not json"}}]}"""

        assertThrows(JSONException::class.java) {
            parseResponseBody(malformed)
        }
    }

    @Test
    fun malformedStartDateTimeThrows() {
        assertThrows(DateTimeParseException::class.java) {
            parseResponseBody(
                body("""{"start":"tomorrow","end":null,"allDay":false,"title":null}"""),
            )
        }
    }

    @Test
    fun missingChoicesThrows() {
        assertThrows(JSONException::class.java) {
            parseResponseBody("""{"choices":[]}""")
        }
    }
}
