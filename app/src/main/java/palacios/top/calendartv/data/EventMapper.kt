package palacios.top.calendartv.data

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import palacios.top.calendartv.data.local.CalendarEntity
import palacios.top.calendartv.data.local.EventEntity
import palacios.top.calendartv.data.local.ReminderEntity
import palacios.top.calendartv.data.local.toMinutesList
import palacios.top.calendartv.data.remote.CalendarDto
import palacios.top.calendartv.data.remote.EventDto
import palacios.top.calendartv.data.remote.EventTimeDto

object EventMapper {

    private const val POPUP = "popup"

    fun calendarId(accountEmail: String, remoteId: String) = "$accountEmail|$remoteId"

    fun toCalendar(accountEmail: String, dto: CalendarDto, existing: CalendarEntity?) = CalendarEntity(
        id = calendarId(accountEmail, dto.id),
        accountEmail = accountEmail,
        remoteId = dto.id,
        name = dto.summaryOverride ?: dto.summary,
        color = dto.backgroundColor ?: "#4285F4",
        enabled = existing?.enabled ?: (dto.selected || dto.primary),
        defaultReminderMinutes = dto.defaultReminders
            .filter { it.method == POPUP }
            .joinToString(",") { it.minutes.toString() },
    )

    /** Returns null for cancelled events and events without a usable start time. */
    fun toEvent(calendar: CalendarEntity, dto: EventDto, zone: ZoneId): EventEntity? {
        if (dto.status == "cancelled") return null
        val start = dto.start?.toMillis(zone) ?: return null
        val end = dto.end?.toMillis(zone) ?: start

        val reminders = dto.reminders
        val minutes = if (reminders == null || reminders.useDefault) {
            calendar.defaultReminderMinutes.toMinutesList()
        } else {
            reminders.overrides.filter { it.method == POPUP }.map { it.minutes }
        }

        return EventEntity(
            calendarId = calendar.id,
            id = dto.id,
            title = dto.summary?.takeIf { it.isNotBlank() } ?: "(No title)",
            description = dto.description.orEmpty(),
            location = dto.location.orEmpty(),
            startMillis = start,
            endMillis = end,
            allDay = dto.start.date != null,
            htmlLink = dto.htmlLink.orEmpty(),
            attendees = dto.attendees.joinToString("\n") { a ->
                val name = a.displayName ?: a.email.orEmpty()
                val email = a.email?.takeIf { it != name }?.let { " <$it>" }.orEmpty()
                val status = a.responseStatus?.let { " ($it)" }.orEmpty()
                "$name$email$status"
            },
            reminderMinutes = minutes.distinct().sorted().joinToString(","),
        )
    }

    private fun EventTimeDto.toMillis(zone: ZoneId): Long? = when {
        dateTime != null -> OffsetDateTime.parse(dateTime).toInstant().toEpochMilli()
        date != null -> LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
        else -> null
    }
}

object ReminderPlanner {

    /** A reminder that came due this long ago is still shown (device was asleep / sync was late). */
    const val GRACE_MS = 10 * 60_000L

    private data class Key(val eventId: String, val minutes: Int, val triggerAt: Long)

    /**
     * Builds the reminder rows for [events] of one calendar. Rows already fired in [previous]
     * stay fired; newly discovered reminders that are long past are born fired so that a first
     * sync doesn't replay the last day of events.
     */
    fun plan(
        events: List<EventEntity>,
        previous: List<ReminderEntity>,
        now: Long,
    ): List<ReminderEntity> {
        val fired = previous.filter { it.fired }.map { Key(it.eventId, it.minutes, it.triggerAt) }.toSet()
        return events.flatMap { event ->
            event.reminderMinutes.toMinutesList().map { minutes ->
                val triggerAt = event.startMillis - minutes * 60_000L
                ReminderEntity(
                    calendarId = event.calendarId,
                    eventId = event.id,
                    minutes = minutes,
                    triggerAt = triggerAt,
                    fired = Key(event.id, minutes, triggerAt) in fired || triggerAt < now - GRACE_MS,
                )
            }
        }
    }
}
