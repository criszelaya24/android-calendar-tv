@file:OptIn(ExperimentalTvMaterial3Api::class)

package palacios.top.calendartv.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import palacios.top.calendartv.banner.BannerPresenter
import palacios.top.calendartv.banner.Sounds
import java.time.LocalDate
import palacios.top.calendartv.data.local.AccountEntity
import palacios.top.calendartv.data.local.EventEntity
import palacios.top.calendartv.data.local.toMinutesList
import palacios.top.calendartv.reminders.DescriptionText
import palacios.top.calendartv.reminders.ReminderFormatter

private fun parseColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF4285F4))

@Composable
private fun ColorDot(color: Color) {
    Box(Modifier.size(14.dp).background(color, CircleShape))
}

@Composable
private fun overlayGranted(): Boolean {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(BannerPresenter.canDrawOverlay(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = BannerPresenter.canDrawOverlay(context) }
    return granted
}

@Composable
private fun OverlayPermissionRow() {
    val context = LocalContext.current
    val granted = overlayGranted()
    if (granted) {
        Text("Banner over other apps: allowed", style = MaterialTheme.typography.bodyMedium)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Banner over other apps: NOT allowed. Reminders will only reach the notification panel.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "adb shell appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("Open overlay permission screen") }
        }
    }
}

@Composable
fun SignInScreen(viewModel: MainViewModel, onSignIn: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Calendar TV", style = MaterialTheme.typography.displaySmall)
        Text(
            "Read-only view of your Google Calendar, with reminder banners over any app. " +
                "Sign in with the Google account already on this TV.",
            style = MaterialTheme.typography.bodyLarge,
        )
        OverlayPermissionRow()
        Button(onClick = onSignIn) { Text("Sign in with Google") }
        viewModel.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

private class RowContext(val colors: Map<String, Color>, val accountOf: (String) -> AccountEntity?)

@Composable
private fun rememberRowContext(viewModel: MainViewModel): RowContext {
    val calendars by viewModel.calendars.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    return remember(calendars, accounts) {
        val byEmail = accounts.associateBy { it.email }
        val byCalendar = calendars.associateBy { it.id }
        RowContext(calendars.associate { it.id to parseColor(it.color) }) { calendarId ->
            byCalendar[calendarId]?.accountEmail?.let(byEmail::get)
        }
    }
}

@Composable
private fun TopBar(viewModel: MainViewModel, current: Screen) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Calendar", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(end = 20.dp))
        TabButton("Today", current == Screen.Today) { viewModel.open(Screen.Today) }
        TabButton("Upcoming", current == Screen.Agenda) { viewModel.open(Screen.Agenda) }
        Spacer(Modifier.weight(1f))
        Button(onClick = viewModel::refresh) { Text("Refresh") }
        Button(onClick = { viewModel.open(Screen.Preferences) }) { Text("Settings") }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    // Switching tabs recreates the bar; without this the remote would lose its place after every switch.
    val focusRequester = remember { FocusRequester() }
    if (selected) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }
    Button(
        onClick = onClick,
        modifier = Modifier.focusRequester(focusRequester),
        colors = if (selected) {
            ButtonDefaults.colors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            ButtonDefaults.colors()
        },
    ) { Text(label) }
}

@Composable
private fun SyncStatus(viewModel: MainViewModel, onReconnect: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    when {
        settings.authNeeded -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Google access expired. Reconnect to keep receiving reminders.", color = MaterialTheme.colorScheme.error)
            Button(onClick = onReconnect) { Text("Reconnect") }
        }
        settings.lastError.isNotEmpty() ->
            Text("Last sync failed: ${settings.lastError}", color = MaterialTheme.colorScheme.error, maxLines = 2)
        settings.lastSyncAt > 0 ->
            Text("Synced ${Format.syncTime(settings.lastSyncAt)}", style = MaterialTheme.typography.bodySmall)
        else -> Text("Syncing…", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun TodayScreen(viewModel: MainViewModel, onReconnect: () -> Unit) {
    val events by viewModel.todayEvents.collectAsState()
    val now by viewModel.now.collectAsState()
    val rows = rememberRowContext(viewModel)
    val sections = remember(events, now) { TodayModel.group(events, now) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TopBar(viewModel, Screen.Today)
        Text(Format.dayHeader(LocalDate.now()), style = MaterialTheme.typography.titleLarge)
        SyncStatus(viewModel, onReconnect)
        if (sections.isEmpty) Text("Nothing scheduled today.", style = MaterialTheme.typography.bodyLarge)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            eventSection("All day", sections.allDay, rows, viewModel)
            eventSection("Happening now", sections.now, rows, viewModel)
            eventSection("Later today", sections.later, rows, viewModel)
            eventSection("Finished", sections.finished, rows, viewModel, dimmed = true)
        }
    }
}

private fun LazyListScope.eventSection(
    title: String,
    events: List<EventEntity>,
    rows: RowContext,
    viewModel: MainViewModel,
    dimmed: Boolean = false,
) {
    if (events.isEmpty()) return
    item(key = "section-$title") {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
        )
    }
    items(events, key = { "$title/${it.calendarId}/${it.id}" }) { event ->
        EventRow(event, rows.colors[event.calendarId] ?: Color(0xFF4285F4), rows.accountOf(event.calendarId), dimmed) {
            viewModel.open(Screen.Detail(event.calendarId, event.id))
        }
    }
}

@Composable
fun AgendaScreen(viewModel: MainViewModel, onReconnect: () -> Unit) {
    val days by viewModel.days.collectAsState()
    val rows = rememberRowContext(viewModel)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TopBar(viewModel, Screen.Agenda)
        SyncStatus(viewModel, onReconnect)
        if (days.isEmpty()) {
            Text("No events in the next 30 days.", style = MaterialTheme.typography.bodyLarge)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { day ->
                item(key = "day-${day.date}") {
                    Text(
                        Format.dayHeader(day.date),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                    )
                }
                items(day.events, key = { "${it.calendarId}/${it.id}" }) { event ->
                    EventRow(event, rows.colors[event.calendarId] ?: Color(0xFF4285F4), rows.accountOf(event.calendarId), false) {
                        viewModel.open(Screen.Detail(event.calendarId, event.id))
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: EventEntity, color: Color, account: AccountEntity?, dimmed: Boolean, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        modifier = if (dimmed) Modifier.alpha(0.5f) else Modifier,
        leadingContent = { ColorDot(color) },
        headlineContent = { Text(event.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOf(Format.timeRange(event), event.location).filter { it.isNotBlank() }.joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = { AccountAvatar(account) },
    )
}

@Composable
fun DetailScreen(viewModel: MainViewModel, screen: Screen.Detail) {
    val days by viewModel.days.collectAsState()
    val calendars by viewModel.calendars.collectAsState()
    val event = days.flatMap { it.events }.firstOrNull { it.calendarId == screen.calendarId && it.id == screen.eventId }
    val calendar = calendars.firstOrNull { it.id == screen.calendarId }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(onClick = viewModel::back) { Text("← Back") }
        if (event == null) {
            Text("This event is no longer available.")
            return@Column
        }
        Text(event.title, style = MaterialTheme.typography.headlineLarge)
        Text("${Format.eventDate(event)} · ${Format.timeRange(event)}", style = MaterialTheme.typography.titleMedium)
        if (event.location.isNotBlank()) Text("📍 ${event.location}", style = MaterialTheme.typography.bodyLarge)
        calendar?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorDot(parseColor(it.color))
                Text("${it.name} · ${it.accountEmail}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(6.dp))
        val reminders = event.reminderMinutes.toMinutesList()
        Text("Reminders", style = MaterialTheme.typography.titleSmall)
        if (reminders.isEmpty()) Text("None", style = MaterialTheme.typography.bodyMedium)
        reminders.forEach { Text("• ${ReminderFormatter.offset(it)}", style = MaterialTheme.typography.bodyMedium) }
        if (event.attendees.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text("Guests", style = MaterialTheme.typography.titleSmall)
            event.attendees.lines().take(8).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
        }
        if (event.description.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text("Description", style = MaterialTheme.typography.titleSmall)
            Text(
                DescriptionText.plain(event.description, maxChars = 2000),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun PickAccountScreen(viewModel: MainViewModel, tvAccounts: List<String>, onPick: (String?) -> Unit) {
    val connected = viewModel.accounts.collectAsState().value.map { it.email }.toSet()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Choose a Google account", style = MaterialTheme.typography.headlineLarge)
        Text("Accounts already signed in on this TV:", style = MaterialTheme.typography.bodyMedium)
        tvAccounts.forEach { email ->
            ListItem(
                selected = email in connected,
                onClick = { onPick(email) },
                headlineContent = { Text(email) },
                trailingContent = { if (email in connected) Text("Connected · OK to reconnect") },
            )
        }
        ListItem(selected = false, onClick = { onPick(null) }, headlineContent = { Text("Other accounts on this TV…") })
        Button(onClick = { viewModel.picker = null }) { Text("Cancel") }
        viewModel.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun SettingsScreen(viewModel: MainViewModel, onAddAccount: () -> Unit) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val calendars by viewModel.calendars.collectAsState()
    val sounds = remember { Sounds.options(context) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = viewModel::back) { Text("← Back") }
                Text("Settings", style = MaterialTheme.typography.headlineLarge)
            }
        }
        item { SectionTitle("Banner") }
        item { OverlayPermissionRow() }
        item { Stepper("Volume", "${settings.volume}%", onMinus = { viewModel.changeVolume(-10) }, onPlus = { viewModel.changeVolume(10) }) }
        item {
            Stepper(
                "Banner stays on screen",
                "${settings.bannerSeconds} s",
                onMinus = { viewModel.changeBannerSeconds(-2) },
                onPlus = { viewModel.changeBannerSeconds(2) },
            )
        }
        item { Button(onClick = viewModel::testBanner) { Text("Show test banner") } }

        item { SectionTitle("Notification sound (tap to preview)") }
        items(sounds, key = { it.id }) { sound ->
            ListItem(
                selected = sound.id == settings.soundId,
                onClick = { viewModel.selectSound(sound.id) },
                headlineContent = { Text(sound.label) },
                trailingContent = { if (sound.id == settings.soundId) Text("✓") },
            )
        }

        item { SectionTitle("Calendars to show") }
        items(calendars, key = { it.id }) { calendar ->
            ListItem(
                selected = calendar.enabled,
                onClick = { viewModel.setCalendarEnabled(calendar.id, !calendar.enabled) },
                leadingContent = { ColorDot(parseColor(calendar.color)) },
                headlineContent = { Text(calendar.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(calendar.accountEmail, maxLines = 1) },
                trailingContent = { Text(if (calendar.enabled) "ON" else "OFF") },
            )
        }

        item { SectionTitle("Google accounts (press OK on one to remove it)") }
        items(accounts, key = { "acc-${it.email}" }) { account ->
            ListItem(
                selected = false,
                onClick = { viewModel.removeAccount(account.email) },
                leadingContent = { AccountAvatar(account) },
                headlineContent = { Text(account.email) },
                supportingContent = { Text(if (account.photoPath.isBlank()) "No photo yet" else account.displayName.ifBlank { "Photo saved" }) },
                trailingContent = { Text("Remove") },
            )
        }
        item { Button(onClick = onAddAccount) { Text("Add a Google account") } }
        item { Button(onClick = viewModel::startPhotoUpdate) { Text("Update account photos") } }
        viewModel.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        item { Button(onClick = viewModel::refresh) { Text("Sync now") } }
        item { Button(onClick = viewModel::signOut) { Text("Remove all accounts") } }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, modifier = Modifier.width(280.dp))
        Button(onClick = onMinus) { Text("−") }
        Text(value, modifier = Modifier.width(80.dp))
        Button(onClick = onPlus) { Text("+") }
    }
}
