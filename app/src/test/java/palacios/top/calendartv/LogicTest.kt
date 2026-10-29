package palacios.top.calendartv

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import palacios.top.calendartv.data.EventMapper
import palacios.top.calendartv.data.ReminderPlanner
import palacios.top.calendartv.data.local.CalendarEntity
import palacios.top.calendartv.data.local.EventEntity
import palacios.top.calendartv.data.local.ReminderEntity
import palacios.top.calendartv.data.remote.EventDto
import palacios.top.calendartv.data.remote.EventRemindersDto
import palacios.top.calendartv.data.remote.EventTimeDto
import palacios.top.calendartv.data.remote.ReminderDto
import palacios.top.calendartv.reminders.DescriptionText
import palacios.top.calendartv.reminders.ReminderFormatter
import palacios.top.calendartv.ui.TodayModel

private const val MIN = 60_000L

class EventMapperTest {

    private val zone = ZoneId.of("America/Mexico_City")
    private val calendar = CalendarEntity("me@x.com|cal", "me@x.com", "cal", "Work", "#112233", true, "10,1440")

    private fun dto(
        reminders: EventRemindersDto? = EventRemindersDto(useDefault = true),
        start: EventTimeDto = EventTimeDto(dateTime = "2026-11-02T15:00:00-06:00"),
        status: String? = "confirmed",
    ) = EventDto(
        id = "e1", status = status, summary = "Standup", start = start,
        end = EventTimeDto(dateTime = "2026-11-02T15:30:00-06:00"), reminders = reminders,
    )

    @Test fun useDefaultTakesCalendarDefaults() {
        assertEquals("10,1440", EventMapper.toEvent(calendar, dto(), zone)!!.reminderMinutes)
    }

    @Test fun overridesReplaceDefaultsAndDropEmail() {
        val reminders = EventRemindersDto(
            useDefault = false,
            overrides = listOf(ReminderDto("popup", 5), ReminderDto("email", 60), ReminderDto("popup", 2880)),
        )
        assertEquals("5,2880", EventMapper.toEvent(calendar, dto(reminders), zone)!!.reminderMinutes)
    }

    @Test fun noRemindersAtAll() {
        val none = EventRemindersDto(useDefault = false, overrides = emptyList())
        assertEquals("", EventMapper.toEvent(calendar, dto(none), zone)!!.reminderMinutes)
    }

    @Test fun timedEventUsesOffsetInstant() {
        val event = EventMapper.toEvent(calendar, dto(), zone)!!
        assertFalse(event.allDay)
        assertEquals(1_793_653_200_000L, event.startMillis) // 2026-11-02T21:00:00Z
        assertEquals(30 * MIN, event.endMillis - event.startMillis)
    }

    @Test fun allDayEventStartsAtLocalMidnight() {
        val event = EventMapper.toEvent(calendar, dto(start = EventTimeDto(date = "2026-11-02")), zone)!!
        assertTrue(event.allDay)
        assertEquals(1_793_599_200_000L, event.startMillis) // 2026-11-02T06:00:00Z = 00:00 in UTC-6
    }

    @Test fun sameRemoteCalendarFromTwoAccountsDoesNotCollide() {
        val dto = palacios.top.calendartv.data.remote.CalendarDto(id = "family@group.calendar.google.com", summary = "Familia")
        val a = EventMapper.toCalendar("a@x.com", dto, null)
        val b = EventMapper.toCalendar("b@x.com", dto, null)
        assertFalse(a.id == b.id)
        assertEquals(dto.id, a.remoteId)
    }

    @Test fun cancelledEventsAreSkipped() {
        assertNull(EventMapper.toEvent(calendar, dto(status = "cancelled"), zone))
    }
}

class ReminderPlannerTest {

    private val now = 1_000_000_000_000L

    private fun event(start: Long, minutes: String) = EventEntity(
        "cal", "e1", "T", "", "", start, start + 30 * MIN, false, "", "", minutes,
    )

    @Test fun futureReminderIsPending() {
        val plan = ReminderPlanner.plan(listOf(event(now + 60 * MIN, "10")), emptyList(), now)
        assertEquals(now + 50 * MIN, plan.single().triggerAt)
        assertFalse(plan.single().fired)
    }

    @Test fun daysBeforeIsMinutesTimes1440() {
        val plan = ReminderPlanner.plan(listOf(event(now + 3 * 1440 * MIN, "1440")), emptyList(), now)
        assertEquals(now + 2 * 1440 * MIN, plan.single().triggerAt)
    }

    @Test fun longPastReminderIsBornFiredSoFirstSyncDoesNotReplayHistory() {
        val plan = ReminderPlanner.plan(listOf(event(now - 5 * 60 * MIN, "10")), emptyList(), now)
        assertTrue(plan.single().fired)
    }

    @Test fun recentlyMissedReminderStillFires() {
        val plan = ReminderPlanner.plan(listOf(event(now + 5 * MIN, "10")), emptyList(), now)
        assertFalse(plan.single().fired) // due 5 minutes ago, inside the grace window
    }

    @Test fun alreadyFiredReminderStaysFiredAcrossSyncs() {
        val e = event(now + 60 * MIN, "10")
        val previous = listOf(ReminderEntity("cal", "e1", 10, now + 50 * MIN, fired = true))
        assertTrue(ReminderPlanner.plan(listOf(e), previous, now).single().fired)
    }

    @Test fun rescheduledEventRearmsItsReminder() {
        val e = event(now + 120 * MIN, "10") // moved later than the previously fired trigger
        val previous = listOf(ReminderEntity("cal", "e1", 10, now + 50 * MIN, fired = true))
        assertFalse(ReminderPlanner.plan(listOf(e), previous, now).single().fired)
    }
}

class ReminderFormatterTest {

    @Test fun relative() {
        assertEquals("in 5 min", ReminderFormatter.relative(5 * MIN, 0))
        assertEquals("in 1 hour", ReminderFormatter.relative(60 * MIN, 0))
        assertEquals("in 2 hours", ReminderFormatter.relative(120 * MIN, 0))
        assertEquals("in 1 day", ReminderFormatter.relative(1440 * MIN, 0))
        assertEquals("in 3 days", ReminderFormatter.relative(3 * 1440 * MIN, 0))
        assertEquals("now", ReminderFormatter.relative(10_000, 0))
        assertEquals("started 4 mins ago", ReminderFormatter.relative(0, 4 * MIN))
    }

    @Test fun offset() {
        assertEquals("At time of event", ReminderFormatter.offset(0))
        assertEquals("10 minutes before", ReminderFormatter.offset(10))
        assertEquals("1 hour before", ReminderFormatter.offset(60))
        assertEquals("1 day before", ReminderFormatter.offset(1440))
        assertEquals("2 days before", ReminderFormatter.offset(2880))
        assertEquals("1 week before", ReminderFormatter.offset(10_080))
    }
}

class DescriptionTextTest {

    @Test fun htmlBecomesPlainText() {
        assertEquals("Bring notes\nRoom 3 & coffee", DescriptionText.plain("<p>Bring <b>notes</b></p>Room 3 &amp; coffee"))
    }

    @Test fun breaksAndListsKeepStructure() {
        assertEquals("Agenda:\n• Budget\n• Hiring", DescriptionText.plain("Agenda:<br><ul><li>Budget</li><li>Hiring</li></ul>"))
    }

    @Test fun blankLinesCollapseAndEdgesTrim() {
        assertEquals("a\n\nb", DescriptionText.plain("\n\n a <br><br><br><br> b \n"))
    }

    @Test fun escapedMarkupIsNotDoubleDecoded() {
        assertEquals("1 < 2 &lt;", DescriptionText.plain("1 &lt; 2 &amp;lt;"))
    }

    @Test fun longTextIsEllipsized() {
        val text = DescriptionText.plain("x".repeat(500), maxChars = 160)
        assertEquals(161, text.length)
        assertTrue(text.endsWith("…"))
    }

    @Test fun blankStaysEmpty() {
        assertEquals("", DescriptionText.plain("   "))
    }
}

class TodayModelTest {

    private val now = 1_000_000_000_000L

    private fun event(id: String, start: Long, end: Long, allDay: Boolean = false) =
        EventEntity("cal", id, id, "", "", start, end, allDay, "", "", "")

    @Test fun groupsByWhereTheEventIsRightNow() {
        val sections = TodayModel.group(
            listOf(
                event("later", now + 60 * MIN, now + 90 * MIN),
                event("done", now - 120 * MIN, now - 60 * MIN),
                event("ongoing", now - 10 * MIN, now + 20 * MIN),
                event("allday", now - 600 * MIN, now + 600 * MIN, allDay = true),
            ),
            now,
        )
        assertEquals(listOf("allday"), sections.allDay.map { it.id })
        assertEquals(listOf("ongoing"), sections.now.map { it.id })
        assertEquals(listOf("later"), sections.later.map { it.id })
        assertEquals(listOf("done"), sections.finished.map { it.id })
    }

    @Test fun eventWithoutDurationStaysCurrentForFifteenMinutes() {
        val start = now - 5 * MIN
        assertEquals(1, TodayModel.group(listOf(event("e", start, start)), now).now.size)
        assertEquals(1, TodayModel.group(listOf(event("e", start, start)), now + 20 * MIN).finished.size)
    }

    @Test fun eventsAreSortedByStart() {
        val sections = TodayModel.group(listOf(event("b", now + 90 * MIN, now + 100 * MIN), event("a", now + 30 * MIN, now + 40 * MIN)), now)
        assertEquals(listOf("a", "b"), sections.later.map { it.id })
    }

    @Test fun emptyDay() {
        assertTrue(TodayModel.group(emptyList(), now).isEmpty)
    }
}
