package palacios.top.calendartv.data.remote

import kotlinx.serialization.Serializable

@Serializable
data class CalendarListResponse(
    val items: List<CalendarDto> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
data class CalendarDto(
    val id: String,
    val summary: String = "",
    val summaryOverride: String? = null,
    val backgroundColor: String? = null,
    val selected: Boolean = false,
    val primary: Boolean = false,
    val defaultReminders: List<ReminderDto> = emptyList(),
)

@Serializable
data class ReminderDto(
    val method: String = "popup",
    val minutes: Int = 0,
)

@Serializable
data class EventsResponse(
    val items: List<EventDto> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
data class EventDto(
    val id: String,
    val status: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val location: String? = null,
    val htmlLink: String? = null,
    val start: EventTimeDto? = null,
    val end: EventTimeDto? = null,
    val reminders: EventRemindersDto? = null,
    val attendees: List<AttendeeDto> = emptyList(),
)

@Serializable
data class EventTimeDto(
    val dateTime: String? = null,
    val date: String? = null,
    val timeZone: String? = null,
)

@Serializable
data class EventRemindersDto(
    val useDefault: Boolean = false,
    val overrides: List<ReminderDto> = emptyList(),
)

@Serializable
data class AttendeeDto(
    val email: String? = null,
    val displayName: String? = null,
    val responseStatus: String? = null,
)
