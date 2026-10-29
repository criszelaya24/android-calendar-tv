package palacios.top.calendartv.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import palacios.top.calendartv.data.local.CalendarDao

/**
 * Keeps exactly one alarm armed: the next unfired reminder (or "right now" if one is already due).
 * Every sync, boot, clock or timezone change just calls [reschedule] again.
 */
class ReminderScheduler(
    private val context: Context,
    private val dao: CalendarDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val alarms = context.getSystemService(AlarmManager::class.java)

    suspend fun reschedule() {
        val now = clock()
        val at = if (dao.dueReminders(now).isNotEmpty()) now + 1_000 else dao.nextTrigger(now)
        val pending = pendingIntent()
        if (at == null) {
            alarms.cancel(pending)
            return
        }
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exactAllowed) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
