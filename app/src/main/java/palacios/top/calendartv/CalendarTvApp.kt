package palacios.top.calendartv

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import palacios.top.calendartv.auth.GoogleAuth
import palacios.top.calendartv.data.SettingsStore
import palacios.top.calendartv.data.local.AppDatabase
import palacios.top.calendartv.data.local.MIGRATION_2_3
import palacios.top.calendartv.data.remote.ProfileApi
import palacios.top.calendartv.data.remote.CalendarApi
import palacios.top.calendartv.reminders.ReminderScheduler
import palacios.top.calendartv.sync.SyncScheduler
import palacios.top.calendartv.sync.Syncer

/** Hand-wired dependencies: receivers, workers and the UI all share these instances. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "calendar-tv.db")
        .addMigrations(MIGRATION_2_3)
        .fallbackToDestructiveMigration() // v1 and anything unknown: the DB is a re-syncable cache
        .build()
    val settings = SettingsStore(app)
    val auth = GoogleAuth(app)
    val scheduler = ReminderScheduler(app, db.calendarDao())
    val syncScheduler = SyncScheduler(app)
    val syncer = Syncer(db, CalendarApi(), ProfileApi(), java.io.File(app.filesDir, "avatars"), auth, settings, scheduler)
}

class CalendarTvApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch {
            // The DB is a rebuildable cache: a "signed in" flag with no accounts means it was wiped.
            if (container.settings.current().signedIn && container.db.calendarDao().accounts().isEmpty()) {
                container.settings.clearAccount()
            }
            if (container.settings.current().signedIn) {
                container.syncScheduler.ensurePeriodic()
                container.scheduler.reschedule()
            }
        }
    }
}
