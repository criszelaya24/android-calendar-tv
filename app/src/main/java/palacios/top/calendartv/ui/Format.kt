package palacios.top.calendartv.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import palacios.top.calendartv.data.local.EventEntity

object Format {

    private val zone get() = ZoneId.systemDefault()
    private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val day = DateTimeFormatter.ofPattern("EEEE, d MMMM")
    private val dayLong = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")

    fun timeRange(event: EventEntity): String {
        if (event.allDay) return "All day"
        val start = Instant.ofEpochMilli(event.startMillis).atZone(zone)
        val end = Instant.ofEpochMilli(event.endMillis).atZone(zone)
        return "${time.format(start)} – ${time.format(end)}"
    }

    fun dayHeader(date: LocalDate): String {
        val today = LocalDate.now(zone)
        val label = day.format(date)
        return when (date) {
            today -> "Today · $label"
            today.plusDays(1) -> "Tomorrow · $label"
            else -> label
        }
    }

    fun eventDate(event: EventEntity): String =
        dayLong.format(Instant.ofEpochMilli(event.startMillis).atZone(zone))

    fun syncTime(millis: Long): String =
        DateTimeFormatter.ofPattern("d MMM HH:mm").format(Instant.ofEpochMilli(millis).atZone(zone))
}
