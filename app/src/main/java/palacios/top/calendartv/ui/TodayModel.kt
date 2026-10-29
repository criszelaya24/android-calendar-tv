package palacios.top.calendartv.ui

import palacios.top.calendartv.data.local.EventEntity

data class TodaySections(
    val allDay: List<EventEntity>,
    val now: List<EventEntity>,
    val later: List<EventEntity>,
    val finished: List<EventEntity>,
) {
    val isEmpty get() = allDay.isEmpty() && now.isEmpty() && later.isEmpty() && finished.isEmpty()
}

object TodayModel {

    /** Same floor the reminder code uses: an event with no real duration still "lasts" a little. */
    private const val MIN_DURATION_MS = 15 * 60_000L

    fun group(events: List<EventEntity>, now: Long): TodaySections {
        val (allDay, timed) = events.partition { it.allDay }
        val ongoing = mutableListOf<EventEntity>()
        val later = mutableListOf<EventEntity>()
        val finished = mutableListOf<EventEntity>()
        for (event in timed.sortedBy { it.startMillis }) {
            val end = maxOf(event.endMillis, event.startMillis + MIN_DURATION_MS)
            when {
                event.startMillis > now -> later += event
                now < end -> ongoing += event
                else -> finished += event
            }
        }
        return TodaySections(allDay.sortedBy { it.title }, ongoing, later, finished)
    }
}
