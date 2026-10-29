package palacios.top.calendartv.ui

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import palacios.top.calendartv.CalendarTvApp
import palacios.top.calendartv.auth.AuthResult
import palacios.top.calendartv.banner.AvatarStyle
import palacios.top.calendartv.banner.BannerItem
import palacios.top.calendartv.banner.BannerPresenter
import palacios.top.calendartv.banner.SoundPlayer
import palacios.top.calendartv.data.Settings
import palacios.top.calendartv.data.local.AccountEntity
import palacios.top.calendartv.data.local.CalendarEntity
import palacios.top.calendartv.data.local.EventEntity

sealed interface Screen {
    /** Landing screen: only today's events. */
    data object Today : Screen
    /** "Upcoming": today and the next 30 days, grouped by day. */
    data object Agenda : Screen
    data class Detail(val calendarId: String, val eventId: String) : Screen
    data object Preferences : Screen
}

data class DayGroup(val date: LocalDate, val events: List<EventEntity>)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as CalendarTvApp).container
    private val dao = container.db.calendarDao()
    private val zone = ZoneId.systemDefault()

    var screen: Screen by mutableStateOf(Screen.Today)
        private set
    private var detailOrigin: Screen = Screen.Today

    var message: String? by mutableStateOf(null)

    /** Google accounts found on this TV while the "add account" picker is open; null when closed. */
    var picker: List<String>? by mutableStateOf(null)

    /** Accounts still to be asked for the profile-photo permission (one consent screen each). */
    var photoQueue: List<String> by mutableStateOf(emptyList())
        private set

    /** Account whose profile consent is currently on screen; decides how the consent result is used. */
    var profileTarget: String? = null

    fun open(target: Screen) {
        if (target is Screen.Detail) detailOrigin = screen
        screen = target
    }

    /** Back: Detail returns to the tab it came from, everything else returns to Today. */
    fun back() {
        screen = if (screen is Screen.Detail) detailOrigin else Screen.Today
    }

    val settings: StateFlow<Settings> =
        container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val accounts: StateFlow<List<AccountEntity>> =
        dao.observeAccounts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val calendars: StateFlow<List<CalendarEntity>> =
        dao.observeCalendars().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Re-emits when the calendar day changes, so the lists never go stale after midnight. */
    private val today = flow {
        while (true) {
            emit(LocalDate.now(zone))
            delay(60_000)
        }
    }.distinctUntilChanged()

    /** Ticks every 30 s so "now" / "finished" states on the Today screen stay current. */
    val now: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, System.currentTimeMillis())

    private fun LocalDate.millis() = atStartOfDay(zone).toInstant().toEpochMilli()

    val todayEvents: StateFlow<List<EventEntity>> = today.flatMapLatest { day ->
        dao.observeEvents(day.millis(), day.plusDays(1).millis())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val days: StateFlow<List<DayGroup>> = today.flatMapLatest { day ->
        dao.observeEvents(day.millis(), day.plusDays(30).millis()).map { events ->
            events.groupBy { Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate().coerceAtLeast(day) }
                .map { (date, list) -> DayGroup(date, list) }
                .sortedBy { it.date }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Opens the picker with the Google accounts on this TV (needs GET_ACCOUNTS; empty list if denied). */
    fun openPicker() {
        val accountManager = android.accounts.AccountManager.get(getApplication())
        picker = try {
            accountManager.getAccountsByType("com.google").map { it.name }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /** Calendar + profile photo scopes; [email] targets one TV account, null lets Google show its chooser. */
    suspend fun authorize(email: String?): AuthResult? = try {
        container.auth.authorizeWithProfile(email)
    } catch (e: Exception) {
        message = "Google sign-in failed: ${e.message ?: e.javaClass.simpleName}"
        null
    }

    fun onConsentResult(data: Intent?) {
        try {
            val result = container.auth.fromConsentResult(data)
            if (result !is AuthResult.Token) {
                message = "Google did not return an access token"
                skipProfile()
                return
            }
            val target = profileTarget
            if (target != null) onProfileToken(target, result.accessToken) else onToken(result.accessToken)
        } catch (e: Exception) {
            message = "Google sign-in failed: ${e.message ?: e.javaClass.simpleName}"
            skipProfile()
        }
    }

    fun onConsentCancelled() {
        if (profileTarget != null) skipProfile() else message = "Sign-in was cancelled"
    }

    fun onToken(token: String) {
        viewModelScope.launch {
            try {
                container.syncer.addAccount(token, withProfile = true)
                message = null
                container.syncScheduler.ensurePeriodic()
                container.syncScheduler.syncNow()
            } catch (e: Exception) {
                message = "Could not add the account: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    // ---- profile photos -------------------------------------------------------------------------------------------

    fun startPhotoUpdate() {
        photoQueue = accounts.value.map { it.email }
    }

    fun onProfileToken(email: String, token: String) {
        viewModelScope.launch {
            container.syncer.refreshProfile(email, token)
            profileTarget = null
            photoQueue = photoQueue.drop(1)
        }
    }

    fun skipProfile() {
        profileTarget = null
        photoQueue = photoQueue.drop(1)
    }

    // ---- accounts / settings --------------------------------------------------------------------------------------

    fun removeAccount(email: String) {
        viewModelScope.launch { container.syncer.removeAccount(email) }
    }

    fun refresh() = container.syncScheduler.syncNow()

    fun setCalendarEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            dao.setEnabled(id, enabled)
            container.scheduler.reschedule()
            container.syncScheduler.syncNow()
        }
    }

    fun selectSound(id: String) {
        viewModelScope.launch {
            container.settings.setSound(id)
            SoundPlayer.play(getApplication(), id, settings.value.volume)
        }
    }

    fun changeVolume(delta: Int) {
        viewModelScope.launch {
            val volume = (settings.value.volume + delta).coerceIn(0, 100)
            container.settings.setVolume(volume)
            SoundPlayer.play(getApplication(), settings.value.soundId, volume)
        }
    }

    fun changeBannerSeconds(delta: Int) {
        viewModelScope.launch { container.settings.setBannerSeconds(settings.value.bannerSeconds + delta) }
    }

    fun testBanner() {
        val account = accounts.value.firstOrNull()
        BannerPresenter.show(
            getApplication(),
            listOf(
                BannerItem(
                    title = "Team standup",
                    subtitle = "In 5 min · 3:00 PM · Meeting room",
                    color = "#4285F4",
                    description = "Bring the Q4 numbers. Room 3 is booked until 3 PM, agenda in the invite.",
                    avatarPath = account?.photoPath.orEmpty(),
                    accountInitial = account?.let { AvatarStyle.initial(it.displayName, it.email) }.orEmpty(),
                    avatarColor = account?.let { AvatarStyle.color(it.email) }.orEmpty(),
                ),
            ),
            settings.value,
        )
    }

    fun signOut() {
        viewModelScope.launch {
            container.syncScheduler.cancelAll()
            dao.clearReminders()
            dao.clearEvents()
            dao.clearCalendars()
            dao.clearAccounts()
            container.settings.clearAccount()
            container.scheduler.reschedule()
            screen = Screen.Today
        }
    }
}
