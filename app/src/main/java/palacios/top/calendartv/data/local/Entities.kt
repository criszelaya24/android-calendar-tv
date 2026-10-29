package palacios.top.calendartv.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val email: String,
    @ColumnInfo(defaultValue = "''") val displayName: String = "",
    /** Local file with the account's Google profile photo, or "" until the profile scope is granted. */
    @ColumnInfo(defaultValue = "''") val photoPath: String = "",
)

@Entity(tableName = "calendars")
data class CalendarEntity(
    /** "<accountEmail>|<remoteId>": the same Google calendar can be visible from two accounts. */
    @PrimaryKey val id: String,
    val accountEmail: String,
    /** Calendar id as the Google Calendar API knows it. */
    val remoteId: String,
    val name: String,
    val color: String,
    val enabled: Boolean,
    /** Popup reminder offsets (minutes) used by events with `useDefault`, comma separated. */
    val defaultReminderMinutes: String,
)

@Entity(
    tableName = "events",
    primaryKeys = ["calendarId", "id"],
    indices = [Index("startMillis")],
)
data class EventEntity(
    val calendarId: String,
    val id: String,
    val title: String,
    val description: String,
    val location: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val htmlLink: String,
    /** One attendee per line: "Name <email> (status)". */
    val attendees: String,
    /** Popup reminder offsets (minutes before start), comma separated. */
    val reminderMinutes: String,
)

@Entity(
    tableName = "reminders",
    primaryKeys = ["calendarId", "eventId", "minutes"],
    indices = [Index("triggerAt")],
)
data class ReminderEntity(
    val calendarId: String,
    val eventId: String,
    val minutes: Int,
    val triggerAt: Long,
    val fired: Boolean,
)

fun String.toMinutesList(): List<Int> =
    split(',').mapNotNull { it.trim().toIntOrNull() }
