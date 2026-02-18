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
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class Result(
        val success: Boolean,
        val code: Int,
        val message: String,
        val body: String
    )

    fun post(webhookUrl: String, transcript: String, bodyTemplate: String): Result {
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

        val jsonEscapedText = JSONObject.quote(transcript).removeSurrounding("\"")
        val json = bodyTemplate.replace("\$transcript", jsonEscapedText)
        val body = json.toRequestBody("application/json".toMediaType())

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
