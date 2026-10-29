package palacios.top.calendartv.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarDao {

    @Query("SELECT * FROM calendars ORDER BY name")
    fun observeCalendars(): Flow<List<CalendarEntity>>

    @Query("SELECT * FROM calendars")
    suspend fun calendars(): List<CalendarEntity>

    @Query("SELECT * FROM calendars WHERE id = :id")
    suspend fun calendar(id: String): CalendarEntity?

    @Upsert
    suspend fun upsertCalendars(calendars: List<CalendarEntity>)

    @Query("DELETE FROM calendars WHERE accountEmail = :email AND id NOT IN (:keepIds)")
    suspend fun deleteCalendarsNotIn(email: String, keepIds: List<String>)

    @Query("DELETE FROM events WHERE calendarId NOT IN (SELECT id FROM calendars)")
    suspend fun deleteOrphanEvents()

    @Query("DELETE FROM reminders WHERE calendarId NOT IN (SELECT id FROM calendars)")
    suspend fun deleteOrphanReminders()

    @Query("SELECT * FROM accounts ORDER BY email")
    fun observeAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY email")
    suspend fun accounts(): List<AccountEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAccountIfMissing(account: AccountEntity)

    @Query("SELECT * FROM accounts WHERE email = :email")
    suspend fun account(email: String): AccountEntity?

    @Query("UPDATE accounts SET displayName = :name, photoPath = :path WHERE email = :email")
    suspend fun updateProfile(email: String, name: String, path: String)

    @Query("DELETE FROM accounts WHERE email = :email")
    suspend fun deleteAccount(email: String)

    @Query("DELETE FROM calendars WHERE accountEmail = :email")
    suspend fun deleteCalendarsOf(email: String)

    @Query("DELETE FROM accounts")
    suspend fun clearAccounts()

    @Query("UPDATE calendars SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query(
        "SELECT * FROM events WHERE (endMillis > :from OR startMillis >= :from) AND startMillis < :to " +
            "AND calendarId IN (SELECT id FROM calendars WHERE enabled = 1) " +
            "ORDER BY startMillis, title",
    )
    fun observeEvents(from: Long, to: Long): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE calendarId = :calendarId AND id = :id")
    suspend fun event(calendarId: String, id: String): EventEntity?

    @Query("DELETE FROM events WHERE calendarId = :calendarId")
    suspend fun deleteEvents(calendarId: String)

    @Insert
    suspend fun insertEvents(events: List<EventEntity>)

    @Query("SELECT * FROM reminders WHERE calendarId = :calendarId AND fired = 1")
    suspend fun firedReminders(calendarId: String): List<ReminderEntity>

    @Query("DELETE FROM reminders WHERE calendarId = :calendarId")
    suspend fun deleteReminders(calendarId: String)

    @Insert
    suspend fun insertReminders(reminders: List<ReminderEntity>)

    @Query(
        "SELECT * FROM reminders WHERE fired = 0 AND triggerAt <= :now " +
            "AND calendarId IN (SELECT id FROM calendars WHERE enabled = 1) ORDER BY triggerAt",
    )
    suspend fun dueReminders(now: Long): List<ReminderEntity>

    @Query(
        "SELECT MIN(triggerAt) FROM reminders WHERE fired = 0 AND triggerAt > :now " +
            "AND calendarId IN (SELECT id FROM calendars WHERE enabled = 1)",
    )
    suspend fun nextTrigger(now: Long): Long?

    @Query(
        "UPDATE reminders SET fired = 1 " +
            "WHERE calendarId = :calendarId AND eventId = :eventId AND minutes = :minutes",
    )
    suspend fun markFired(calendarId: String, eventId: String, minutes: Int)

    @Query("DELETE FROM events")
    suspend fun clearEvents()

    @Query("DELETE FROM reminders")
    suspend fun clearReminders()

    @Query("DELETE FROM calendars")
    suspend fun clearCalendars()
}

@Database(
    entities = [AccountEntity::class, CalendarEntity::class, EventEntity::class, ReminderEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun calendarDao(): CalendarDao
}

/** v2 -> v3: Google profile name and photo per account. Kept non-destructive so fired-reminder flags survive. */
val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE accounts ADD COLUMN displayName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE accounts ADD COLUMN photoPath TEXT NOT NULL DEFAULT ''")
    }
}
