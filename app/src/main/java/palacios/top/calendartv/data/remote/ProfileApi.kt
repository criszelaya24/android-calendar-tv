package palacios.top.calendartv.data.remote

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class UserInfo(val name: String = "", val picture: String = "")

/** Name and photo of a Google account; needs the `profile` scope on the access token. */
class ProfileApi(private val http: OkHttpClient = OkHttpClient()) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun userInfo(token: String): UserInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/oauth2/v3/userinfo")
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, body.take(300))
            json.decodeFromString<UserInfo>(body)
        }
    }

    /** Saves the picture at 96 px to [target]; returns false if there is none or the download fails. */
    suspend fun downloadPhoto(pictureUrl: String, target: File): Boolean = withContext(Dispatchers.IO) {
        if (pictureUrl.isBlank()) return@withContext false
        val url = sized(pictureUrl)
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val bytes = response.body?.bytes()
            if (!response.isSuccessful || bytes == null || bytes.isEmpty()) return@use false
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            true
        }
    }

    internal fun sized(url: String): String =
        if (Regex("=s\\d+(-c)?$").containsMatchIn(url)) url.replace(Regex("=s\\d+(-c)?$"), "=s96-c") else "$url=s96-c"
}
