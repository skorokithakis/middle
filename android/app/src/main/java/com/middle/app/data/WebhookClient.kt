package com.middle.app.data

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

object WebhookClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Result(
        val success: Boolean,
        val code: Int,
        val message: String,
        val body: String
    )

    fun post(webhookUrl: String, transcript: String, rest: String, bodyTemplate: String): Result {
        val uri = URI(webhookUrl)
        val userInfo = uri.userInfo

        // Rebuild the URL without credentials so OkHttp doesn't log them and
        // so we can add Basic Auth as a header instead.
        val urlWithoutCredentials = if (userInfo != null) {
            val port = if (uri.port != -1) ":${uri.port}" else ""
            "${uri.scheme}://${uri.host}$port${uri.path ?: ""}${uri.query?.let { "?$it" } ?: ""}"
        } else {
            webhookUrl
        }

        val body = renderBody(bodyTemplate, transcript, rest)
            .toRequestBody("application/json".toMediaType())

        val requestBuilder = Request.Builder()
            .url(urlWithoutCredentials)
            .post(body)

        if (userInfo != null) {
            val credentials = Base64.encodeToString(userInfo.toByteArray(), Base64.NO_WRAP)
            requestBuilder.header("Authorization", "Basic $credentials")
        }

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val responseBody = response.body?.string()?.take(500) ?: ""
            return Result(
                success = response.isSuccessful,
                code = response.code,
                message = response.message,
                body = responseBody
            )
        }
    }
}

/**
 * Substitutes `$transcript` and `$rest` in a webhook body template with the
 * JSON-escaped values. One pass over the template: an inserted value that
 * happens to contain another variable token is not substituted again.
 */
internal fun renderBody(bodyTemplate: String, transcript: String, rest: String): String {
    val values = mapOf(
        "transcript" to jsonEscaped(transcript),
        "rest" to jsonEscaped(rest),
    )
    return TEMPLATE_VARIABLE.replace(bodyTemplate) { match ->
        values[match.groupValues[1]] ?: match.value
    }
}

private val TEMPLATE_VARIABLE = Regex("\\$(transcript|rest)")

private fun jsonEscaped(value: String): String =
    JSONObject.quote(value).removeSurrounding("\"")
