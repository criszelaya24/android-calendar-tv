package palacios.top.calendartv.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class Settings(
    val signedIn: Boolean = false,
    val authNeeded: Boolean = false,
    val soundId: String = "raw:sound_chime",
    val volume: Int = 80,
    val bannerSeconds: Int = 10,
    val lastSyncAt: Long = 0L,
    val lastError: String = "",
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val signedIn = booleanPreferencesKey("signed_in")
        val authNeeded = booleanPreferencesKey("auth_needed")
        val soundId = stringPreferencesKey("sound_id")
        val volume = intPreferencesKey("volume")
        val bannerSeconds = intPreferencesKey("banner_seconds")
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val lastError = stringPreferencesKey("last_error")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        val d = Settings()
        Settings(
            signedIn = p[Keys.signedIn] ?: d.signedIn,
            authNeeded = p[Keys.authNeeded] ?: d.authNeeded,
            soundId = p[Keys.soundId] ?: d.soundId,
            volume = p[Keys.volume] ?: d.volume,
            bannerSeconds = p[Keys.bannerSeconds] ?: d.bannerSeconds,
            lastSyncAt = p[Keys.lastSyncAt] ?: d.lastSyncAt,
            lastError = p[Keys.lastError] ?: d.lastError,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setSignedIn(value: Boolean) = context.dataStore.edit { it[Keys.signedIn] = value }
    suspend fun setAuthNeeded(value: Boolean) = context.dataStore.edit { it[Keys.authNeeded] = value }
    suspend fun setSound(id: String) = context.dataStore.edit { it[Keys.soundId] = id }
    suspend fun setVolume(value: Int) = context.dataStore.edit { it[Keys.volume] = value.coerceIn(0, 100) }
    suspend fun setBannerSeconds(value: Int) =
        context.dataStore.edit { it[Keys.bannerSeconds] = value.coerceIn(3, 60) }

    suspend fun setSyncResult(at: Long, error: String) = context.dataStore.edit {
        if (error.isEmpty()) it[Keys.lastSyncAt] = at
        it[Keys.lastError] = error
    }

    suspend fun clearAccount() = context.dataStore.edit {
        it[Keys.signedIn] = false
        it[Keys.authNeeded] = false
        it[Keys.lastSyncAt] = 0L
        it[Keys.lastError] = ""
    }
}
