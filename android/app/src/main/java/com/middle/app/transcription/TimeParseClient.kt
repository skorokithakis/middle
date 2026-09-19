package com.middle.app.transcription

import android.util.Log
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ParsedCommand(
    val start: LocalDateTime?,
    val end: LocalDateTime?,
    val allDay: Boolean,
    val title: String?,
)

sealed class TimeParseResult {
    data class Success(val command: ParsedCommand) : TimeParseResult()
    data class Failure(val message: String) : TimeParseResult()
}

/**
 * Extracts the structured command from an OpenAI Chat Completions response body.
 * Throws when the payload is malformed; the caller turns that into a Failure.
 */
fun parseResponseBody(json: String): ParsedCommand {
    val choices = JSONObject(json).getJSONArray("choices")
    val content = choices.getJSONObject(0).getJSONObject("message").getString("content")
    val command = JSONObject(content)
    return ParsedCommand(
        start = command.optionalDateTime(START_KEY),
        end = command.optionalDateTime(END_KEY),
        allDay = command.getBoolean(ALL_DAY_KEY),
        title = if (command.isNull(TITLE_KEY)) null else command.getString(TITLE_KEY),
    )
}

private fun JSONObject.optionalDateTime(key: String): LocalDateTime? =
    if (isNull(key)) null else LocalDateTime.parse(getString(key), DATE_TIME_FORMAT)

class TimeParseClient(private val apiKey: String) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun parse(text: String, now: ZonedDateTime): TimeParseResult {
        val request = Request.Builder()
            .url(CHAT_COMPLETIONS_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(buildRequestBody(text, now))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "Time parse failed: ${response.code} $body")
                    TimeParseResult.Failure("Time parse failed: HTTP ${response.code}")
                } else {
                    TimeParseResult.Success(parseResponseBody(body))
                }
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Time parse request failed: $exception")
            TimeParseResult.Failure(
                "Time parse failed: ${exception::class.simpleName}: ${exception.message}",
            )
        }
    }

    private fun buildRequestBody(text: String, now: ZonedDateTime): RequestBody {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", buildSystemPrompt(now)))
            .put(JSONObject().put("role", "user").put("content", text))

        val responseFormat = JSONObject()
            .put("type", "json_schema")
            .put(
                "json_schema",
                JSONObject()
                    .put("name", "time_parse")
                    .put("strict", true)
                    .put("schema", RESPONSE_SCHEMA),
            )

        val payload = JSONObject()
            .put("model", MODEL)
            .put("messages", messages)
            .put("response_format", responseFormat)

        return payload.toString().toRequestBody(JSON_MEDIA_TYPE)
    }

    companion object {
        const val MODEL = "gpt-5.6-luna"

        private const val TAG = "TimeParse"
        private const val CHAT_COMPLETIONS_URL = "https://api.openai.com/v1/chat/completions"
        private val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()
    }
}

private const val START_KEY = "start"
private const val END_KEY = "end"
private const val ALL_DAY_KEY = "allDay"
private const val TITLE_KEY = "title"

private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

private val RESPONSE_SCHEMA: JSONObject
    get() = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put(START_KEY, nullableString("Local date-time as YYYY-MM-DDTHH:MM, or null."))
                .put(END_KEY, nullableString("Local date-time as YYYY-MM-DDTHH:MM, or null."))
                .put(ALL_DAY_KEY, JSONObject().put("type", "boolean"))
                .put(TITLE_KEY, nullableString("Short label for the event, or null.")),
        )
        .put("required", JSONArray(listOf(START_KEY, END_KEY, ALL_DAY_KEY, TITLE_KEY)))
        .put("additionalProperties", false)

private fun nullableString(description: String): JSONObject = JSONObject()
    .put("type", JSONArray(listOf("string", "null")))
    .put("description", description)

private fun buildSystemPrompt(now: ZonedDateTime): String {
    val dateTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    return "You extract alarm and reminder times from a spoken command. " +
        "The current local date and time is $dateTime, the weekday is $weekday, " +
        "and the timezone is ${now.zone.id}. The user is issuing a spoken alarm or " +
        "reminder command. Return the referenced start time, and an end time if one is " +
        "given. The start and end are local date-times formatted as YYYY-MM-DDTHH:MM. " +
        "If the referenced time already passed today and no date was given, use the next " +
        "occurrence. An ambiguous bare hour means the next occurrence of that hour. If no " +
        "date or time is present, return start=null. Set allDay=true when a date but no " +
        "time of day is given. title is a short label for what the alarm or event is " +
        "about, or null."
}
