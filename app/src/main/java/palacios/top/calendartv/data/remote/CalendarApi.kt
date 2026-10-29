package palacios.top.calendartv.data.remote

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class ApiException(val code: Int, message: String) : Exception("HTTP $code: $message")

/** Read-only client for the Google Calendar v3 REST API. */
class CalendarApi(private val http: OkHttpClient = OkHttpClient()) {

    private val json = Json { ignoreUnknownKeys = true }
    private val base = "https://www.googleapis.com/calendar/v3".toHttpUrl()

    suspend fun calendars(token: String): List<CalendarDto> {
        val all = mutableListOf<CalendarDto>()
        var page: String? = null
        do {
            val url = base.newBuilder()
                .addPathSegments("users/me/calendarList")
                .apply { page?.let { addQueryParameter("pageToken", it) } }
                .build()
            val response = json.decodeFromString<CalendarListResponse>(get(url, token))
            all += response.items
            page = response.nextPageToken
        } while (page != null)
        return all
    }

    suspend fun events(
        token: String,
        calendarId: String,
        timeMin: Instant,
        timeMax: Instant,
    ): List<EventDto> {
        val all = mutableListOf<EventDto>()
        var page: String? = null
        do {
            val url = base.newBuilder()
                .addPathSegment("calendars")
                .addPathSegment(calendarId)
                .addPathSegment("events")
                .addQueryParameter("singleEvents", "true")
                .addQueryParameter("orderBy", "startTime")
                .addQueryParameter("maxResults", "250")
                .addQueryParameter("timeMin", timeMin.toString())
                .addQueryParameter("timeMax", timeMax.toString())
                .apply { page?.let { addQueryParameter("pageToken", it) } }
                .build()
            val response = json.decodeFromString<EventsResponse>(get(url, token))
            all += response.items
            page = response.nextPageToken
        } while (page != null)
        return all
    }

    private suspend fun get(url: HttpUrl, token: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, body.take(300))
            body
        }
    }
}
