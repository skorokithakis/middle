package com.middle.app.transcription

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

data class SpotifyTrack(val uri: String, val name: String, val artist: String)

sealed class SpotifySearchResult {
    data class Success(val track: SpotifyTrack) : SpotifySearchResult()
    data object NotFound : SpotifySearchResult()
    data class Failure(val message: String) : SpotifySearchResult()
}

/**
 * Extracts the first track from a Spotify search response body. Returns null
 * when the search matched nothing; throws when the payload is malformed, which
 * the caller turns into a Failure.
 */
fun parseSearchResponseBody(json: String): SpotifyTrack? {
    val items = JSONObject(json).getJSONObject(TRACKS_KEY).getJSONArray(ITEMS_KEY)
    if (items.length() == 0) return null
    val track = items.getJSONObject(0)
    return SpotifyTrack(
        uri = track.getString(URI_KEY),
        name = track.getString(NAME_KEY),
        artist = track.getJSONArray(ARTISTS_KEY).getJSONObject(0).getString(NAME_KEY),
    )
}

/**
 * Searches Spotify's Web API for a track. The app can only read Spotify's
 * catalogue, not the user's library, so a client-credentials token is used. A
 * token is fetched for every search; the token and the client secret are never
 * logged.
 */
class SpotifySearchClient(
    private val clientId: String,
    private val clientSecret: String,
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun search(query: String): SpotifySearchResult {
        val accessToken = requestAccessToken()
            ?: return SpotifySearchResult.Failure("Could not get a Spotify access token")

        val url = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter(QUERY_PARAM, query)
            .addQueryParameter(TYPE_PARAM, TRACK_TYPE)
            .addQueryParameter(LIMIT_PARAM, "1")
            .build()

        return try {
            // Built inside the try so an invalid token value (a rejected
            // header) is reported as a Failure instead of escaping search().
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "Spotify search failed: HTTP ${response.code}")
                    SpotifySearchResult.Failure("Spotify search failed: HTTP ${response.code}")
                } else {
                    parseSearchResponseBody(body)
                        ?.let { SpotifySearchResult.Success(it) }
                        ?: SpotifySearchResult.NotFound
                }
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Spotify search request failed: $exception")
            SpotifySearchResult.Failure(
                "Spotify search failed: ${exception::class.simpleName}: ${exception.message}",
            )
        }
    }

    /** Null means the token request failed; the response body is never logged. */
    private fun requestAccessToken(): String? {
        val credentials = Base64.getEncoder()
            .encodeToString("$clientId:$clientSecret".toByteArray(Charsets.UTF_8))
        val request = Request.Builder()
            .url(TOKEN_URL)
            .header("Authorization", "Basic $credentials")
            .post("grant_type=client_credentials".toRequestBody(FORM_MEDIA_TYPE))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Spotify token request failed: HTTP ${response.code}")
                    null
                } else {
                    JSONObject(response.body?.string() ?: "").getString(ACCESS_TOKEN_KEY)
                }
            }
        } catch (exception: Exception) {
            // Only the type is logged: a JSONException message can contain the
            // response body, and a malformed body could echo a token.
            Log.e(TAG, "Spotify token request failed: ${exception::class.simpleName}")
            null
        }
    }

    companion object {
        private const val TAG = "SpotifySearch"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val SEARCH_URL = "https://api.spotify.com/v1/search"
        private const val ACCESS_TOKEN_KEY = "access_token"
        private const val QUERY_PARAM = "q"
        private const val TYPE_PARAM = "type"
        private const val TRACK_TYPE = "track"
        private const val LIMIT_PARAM = "limit"
        private val FORM_MEDIA_TYPE: MediaType =
            "application/x-www-form-urlencoded".toMediaType()
    }
}

private const val TRACKS_KEY = "tracks"
private const val ITEMS_KEY = "items"
private const val URI_KEY = "uri"
private const val NAME_KEY = "name"
private const val ARTISTS_KEY = "artists"
