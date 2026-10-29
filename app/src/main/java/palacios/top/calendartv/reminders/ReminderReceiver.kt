package palacios.top.calendartv.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import palacios.top.calendartv.CalendarTvApp
import palacios.top.calendartv.banner.AvatarStyle
import palacios.top.calendartv.banner.BannerItem
import palacios.top.calendartv.banner.BannerPresenter
import palacios.top.calendartv.data.SettingsStore
import palacios.top.calendartv.data.local.CalendarDao

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val container = (context.applicationContext as CalendarTvApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                ReminderFirer(context.applicationContext, container.db.calendarDao(), container.settings, container.scheduler)
                    .fireDue()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "palacios.top.calendartv.FIRE"
    }
}

/** Turns every due, unfired reminder into one banner (+ panel notification) per event. */
class ReminderFirer(
    private val context: Context,
    private val dao: CalendarDao,
    private val settings: SettingsStore,
    private val scheduler: ReminderScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun fireDue() {
        val now = clock()
        val due = dao.dueReminders(now)
        val items = mutableListOf<BannerItem>()

        // Several offsets of one event may be due together (e.g. after standby): show the closest one.
        for ((_, group) in due.groupBy { it.calendarId to it.eventId }) {
            group.forEach { dao.markFired(it.calendarId, it.eventId, it.minutes) }
            val reminder = group.minBy { it.minutes }
            val event = dao.event(reminder.calendarId, reminder.eventId) ?: continue
            val effectiveEnd = maxOf(event.endMillis, event.startMillis + 15 * 60_000L)
            if (effectiveEnd <= now) continue

            val calendar = dao.calendar(event.calendarId)
            val account = calendar?.let { dao.account(it.accountEmail) }
            val color = calendar?.color ?: "#4285F4"
            val time = if (event.allDay) "All day" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.startMillis))
            val subtitle = listOf(
                ReminderFormatter.relative(event.startMillis, now).replaceFirstChar { it.uppercase() },
                time,
                event.location,
            ).filter { it.isNotBlank() }.joinToString(" · ")
            items += BannerItem(
                title = event.title,
                subtitle = subtitle,
                color = color,
                description = DescriptionText.plain(event.description),
                avatarPath = account?.photoPath.orEmpty(),
                accountInitial = account?.let { AvatarStyle.initial(it.displayName, it.email) }.orEmpty(),
                avatarColor = account?.let { AvatarStyle.color(it.email) }.orEmpty(),
            )
        }

        if (items.isNotEmpty()) BannerPresenter.show(context, items, settings.current())
        scheduler.reschedule()
    }
}
