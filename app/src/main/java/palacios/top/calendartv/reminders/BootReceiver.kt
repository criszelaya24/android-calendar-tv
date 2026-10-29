package palacios.top.calendartv.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import palacios.top.calendartv.CalendarTvApp

/** Boot, app update, clock and timezone changes drop or shift alarms: re-arm and refresh. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as CalendarTvApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                if (container.settings.current().signedIn) {
                    container.syncScheduler.ensurePeriodic()
                    container.scheduler.reschedule()
                    if (intent.action != "android.intent.action.TIME_SET") container.syncScheduler.syncNow()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
