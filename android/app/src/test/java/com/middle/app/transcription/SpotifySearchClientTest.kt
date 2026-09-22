package com.middle.app.transcription

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SpotifySearchClientTest {

    private fun body(items: String): String =
        """
        {
          "tracks": {
            "items": [$items]
          }
        }
        """.trimIndent()

    @Test
    fun firstTrackIsReturnedFromASearchHit() {
        val track = parseSearchResponseBody(
            body(
                """
                {
                  "uri": "spotify:track:4uLU6hMCjMI75M1A2tKUQC",
                  "name": "Never Gonna Give You Up",
                  "artists": [{"name": "Rick Astley"}]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(
            SpotifyTrack(
                uri = "spotify:track:4uLU6hMCjMI75M1A2tKUQC",
                name = "Never Gonna Give You Up",
                artist = "Rick Astley",
            ),
            track,
        )
    }

    @Test
    fun emptyItemsReadsAsNoResult() {
        assertNull(parseSearchResponseBody(body("")))
    }

    @Test
    fun malformedBodyThrows() {
        assertThrows(JSONException::class.java) {
            parseSearchResponseBody("""{"tracks":{}}""")
        }
    }
}
