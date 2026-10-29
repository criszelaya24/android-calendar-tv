package palacios.top.calendartv.reminders

import kotlin.math.abs
import kotlin.math.roundToLong

object ReminderFormatter {

    /** "in 5 min", "in 2 hours", "now", "started 3 min ago". */
    fun relative(startMillis: Long, nowMillis: Long): String {
        val minutes = ((startMillis - nowMillis) / 60_000.0).roundToLong()
        return when {
            minutes < 0 -> "started ${plural(-minutes, "min")} ago"
            minutes == 0L -> "now"
            minutes >= 1440 -> "in ${plural(minutes / 1440, "day")}"
            minutes >= 60 -> "in ${plural(minutes / 60, "hour")}"
            else -> "in ${plural(minutes, "min", short = true)}"
        }
    }

    /** "At time of event", "10 minutes before", "1 day before". */
    fun offset(minutes: Int): String {
        val m = abs(minutes)
        return when {
            m == 0 -> "At time of event"
            m % 10_080 == 0 -> "${plural((m / 10_080).toLong(), "week")} before"
            m % 1440 == 0 -> "${plural((m / 1440).toLong(), "day")} before"
            m % 60 == 0 -> "${plural((m / 60).toLong(), "hour")} before"
            else -> "${plural(m.toLong(), "minute")} before"
        }
    }

    private fun plural(n: Long, unit: String, short: Boolean = false): String =
        if (short) "$n $unit" else if (n == 1L) "1 $unit" else "$n ${unit}s"
}
