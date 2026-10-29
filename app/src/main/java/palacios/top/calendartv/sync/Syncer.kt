package palacios.top.calendartv.sync

import android.util.Log
import androidx.room.withTransaction
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import palacios.top.calendartv.auth.AuthResult
import palacios.top.calendartv.auth.GoogleAuth
import palacios.top.calendartv.data.EventMapper
import palacios.top.calendartv.data.ReminderPlanner
import palacios.top.calendartv.data.SettingsStore
import palacios.top.calendartv.data.local.AccountEntity
import palacios.top.calendartv.data.local.AppDatabase
import palacios.top.calendartv.data.remote.ApiException
import palacios.top.calendartv.data.remote.CalendarApi
import palacios.top.calendartv.data.remote.ProfileApi
import palacios.top.calendartv.reminders.ReminderScheduler

enum class SyncOutcome { OK, NEEDS_AUTH, RETRY }

/** Pulls the next [WINDOW_DAYS] days from every connected Google account and rebuilds events and reminders. */
class Syncer(
    private val db: AppDatabase,
    private val api: CalendarApi,
    private val profiles: ProfileApi,
    private val avatarDir: File,
    private val auth: GoogleAuth,
    private val settings: SettingsStore,
    private val scheduler: ReminderScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val dao get() = db.calendarDao()

    /** Stores the account that owns [token]. Its primary calendar's id is the account's email address. */
    suspend fun addAccount(token: String, withProfile: Boolean): String {
        val email = api.calendars(token).firstOrNull { it.primary }?.id
            ?: error("Could not tell which Google account this is")
        dao.insertAccountIfMissing(AccountEntity(email))
        settings.setSignedIn(true)
        settings.setAuthNeeded(false)
        if (withProfile) refreshProfile(email, token)
        return email
    }

    /** Downloads the account's name and photo. Never throws: a missing photo must not break sign-in or sync. */
    suspend fun refreshProfile(email: String, token: String) {
        try {
            val info = profiles.userInfo(token)
            val file = File(avatarDir, "${sha1(email)}.jpg")
            val saved = profiles.downloadPhoto(info.picture, file)
            dao.updateProfile(email, info.name, if (saved) file.path else "")
        } catch (e: Exception) {
            Log.w(TAG, "profile refresh failed for account", e)
        }
    }

    /** Photo for an account that has none yet, without any UI: only works if the profile scope was already granted. */
    private suspend fun trySilentProfile(email: String) {
        if (dao.account(email)?.photoPath?.isNotBlank() == true) return
        try {
            val result = auth.authorizeWithProfile(email)
            if (result is AuthResult.Token) refreshProfile(email, result.accessToken)
        } catch (e: Exception) {
            Log.w(TAG, "silent profile skipped", e)
        }
    }

    private fun sha1(text: String) =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun removeAccount(email: String) {
        db.withTransaction {
            dao.deleteCalendarsOf(email)
            dao.deleteOrphanEvents()
            dao.deleteOrphanReminders()
            dao.deleteAccount(email)
        }
        if (dao.accounts().isEmpty()) settings.clearAccount()
        scheduler.reschedule()
    }

    suspend fun sync(): SyncOutcome {
        if (!settings.current().signedIn) return SyncOutcome.OK
        val accounts = dao.accounts()
        if (accounts.isEmpty()) return SyncOutcome.OK

        val now = clock()
        val errors = mutableListOf<String>()
        var needsAuth = false
        var retry = false
        for (account in accounts) {
            try {
                when (val result = auth.authorize(account.email)) {
                    is AuthResult.Token -> {
                        syncAccount(account.email, result.accessToken, now)
                        trySilentProfile(account.email)
                    }
                    is AuthResult.NeedsConsent -> {
                        needsAuth = true
                        errors += "${account.email}: access must be granted again"
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "sync failed (network)", e)
                retry = true
                errors += "${account.email}: network error ${e.message}"
            } catch (e: ApiException) {
                Log.w(TAG, "sync failed (api)", e)
                if (e.code >= 500 || e.code == 429) retry = true else needsAuth = true
                errors += "${account.email}: ${e.message}"
            } catch (e: Exception) {
                Log.e(TAG, "sync failed", e)
                retry = true
                errors += "${account.email}: ${e.javaClass.simpleName} ${e.message}"
            }
        }

        scheduler.reschedule()
        settings.setAuthNeeded(needsAuth)
        settings.setSyncResult(now, errors.joinToString("; "))
        return when {
            retry -> SyncOutcome.RETRY
            needsAuth -> SyncOutcome.NEEDS_AUTH
            else -> SyncOutcome.OK
        }
    }

    private suspend fun syncAccount(email: String, token: String, now: Long) {
        val zone = ZoneId.systemDefault()
        val existing = dao.calendars().associateBy { it.id }
        val calendars = api.calendars(token).map { dto ->
            EventMapper.toCalendar(email, dto, existing[EventMapper.calendarId(email, dto.id)])
        }
        db.withTransaction {
            dao.upsertCalendars(calendars)
            dao.deleteCalendarsNotIn(email, calendars.map { it.id })
            dao.deleteOrphanEvents()
            dao.deleteOrphanReminders()
        }

        val timeMin = Instant.ofEpochMilli(now).minus(1, ChronoUnit.DAYS)
        val timeMax = Instant.ofEpochMilli(now).plus(WINDOW_DAYS, ChronoUnit.DAYS)
        for (calendar in calendars.filter { it.enabled }) {
            val events = api.events(token, calendar.remoteId, timeMin, timeMax)
                .mapNotNull { EventMapper.toEvent(calendar, it, zone) }
            db.withTransaction {
                val previous = dao.firedReminders(calendar.id)
                dao.deleteEvents(calendar.id)
                dao.deleteReminders(calendar.id)
                dao.insertEvents(events)
                dao.insertReminders(ReminderPlanner.plan(events, previous, now))
            }
        }
    }

    private companion object {
        const val TAG = "CalendarTvSync"
        const val WINDOW_DAYS = 30L
    }
}
