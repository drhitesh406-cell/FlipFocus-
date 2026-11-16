package com.flipfocus.app

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.Calendar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

const val MAX_UTILITY_APPS = 10
val TAG_REASONS = listOf("Call", "Utility", "Urge", "Other")

data class Daypart(val label: String, val short: String, val startHour: Int, val endHour: Int)
val DAYPARTS = listOf(
    Daypart("Night", "Ngt", 0, 6),
    Daypart("Morning", "Morn", 6, 12),
    Daypart("Afternoon", "Aft", 12, 18),
    Daypart("Evening", "Eve", 18, 24),
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val plannedMs: Long,
    val endMs: Long = 0,
    val pickups: Int = 0,
    val faceUpMs: Long = 0,
    val callBypasses: Int = 0,
    val completed: Boolean = false,
    val failed: Boolean = false,
    val scheduled: Boolean = false,
)

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val type: String, // PICKUP, CALL_BYPASS, APP_OPEN, EARLY_END, FAILED
    val ts: Long,
    val pkg: String = "",
    val tag: String = "", // interruption reason, or "Skipped"
)

@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val minutes: Int,
    val apps: String = "",       // legacy, unused (formerly blocked packages)
    val utilities: String = "",  // comma-separated utility packages
)

val BUILT_IN_TEMPLATES = listOf(
    TemplateEntity(id = -1, name = "Deep Work", minutes = 50),
    TemplateEntity(id = -2, name = "Study", minutes = 25),
    TemplateEntity(id = -3, name = "Reading", minutes = 45),
)

data class PkgCount(val pkg: String, val n: Int)
data class TagCount(val tag: String, val n: Int)
data class PickupTag(val eventId: Long, val ts: Long)
data class HeatRow(val day: String, val values: List<Long>)

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val days: Int, // bit0 = Monday ... bit6 = Sunday
    val startMin: Int,
    val endMin: Int,
    val enabled: Boolean = true,
    val apps: String = "", // legacy, unused (formerly blocked packages)
)

@Dao
interface FocusDao {
    @Insert suspend fun insert(s: SessionEntity): Long
    @Update suspend fun update(s: SessionEntity)
    @Insert suspend fun insertEvent(e: EventEntity): Long
    @Query("SELECT * FROM sessions WHERE endMs > 0 ORDER BY startMs DESC") fun sessions(): Flow<List<SessionEntity>>
    @Query("SELECT COUNT(*) FROM events WHERE type = :t") fun count(t: String): Flow<Int>
    @Query("SELECT pkg, COUNT(*) AS n FROM events WHERE type = 'APP_OPEN' GROUP BY pkg ORDER BY n DESC LIMIT 8") fun topOpened(): Flow<List<PkgCount>>
    @Query("SELECT tag AS tag, COUNT(*) AS n FROM events WHERE type = 'PICKUP' AND tag != '' AND tag != 'Skipped' GROUP BY tag ORDER BY n DESC LIMIT 4") fun tagCounts(): Flow<List<TagCount>>
    @Query("SELECT * FROM events WHERE type = 'PICKUP' AND tag = '' ORDER BY ts") suspend fun untaggedPickups(): List<EventEntity>
    @Query("UPDATE events SET tag = :tag WHERE id = :id") suspend fun tagEvent(id: Long, tag: String)
    @Query("UPDATE events SET tag = 'Skipped' WHERE type = 'PICKUP' AND tag = ''") suspend fun skipUntaggedPickups()
    @Query("SELECT * FROM schedules ORDER BY startMin") fun schedules(): Flow<List<ScheduleEntity>>
    @Query("SELECT * FROM schedules") suspend fun schedulesNow(): List<ScheduleEntity>
    @Query("SELECT * FROM schedules WHERE id = :id") suspend fun schedule(id: Int): ScheduleEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSchedule(s: ScheduleEntity): Long
    @Delete suspend fun deleteSchedule(s: ScheduleEntity)
    @Query("UPDATE schedules SET enabled = :e WHERE id = :id") suspend fun setEnabled(id: Int, e: Boolean)
    @Query("SELECT * FROM templates ORDER BY name") fun templates(): Flow<List<TemplateEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTemplate(t: TemplateEntity): Long
    @Delete suspend fun deleteTemplate(t: TemplateEntity)
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN failed INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sessions ADD COLUMN scheduled INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN tag TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS templates (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "name TEXT NOT NULL, minutes INTEGER NOT NULL, " +
                "apps TEXT NOT NULL, utilities TEXT NOT NULL)",
        )
    }
}

@Database(
    entities = [SessionEntity::class, EventEntity::class, ScheduleEntity::class, TemplateEntity::class],
    version = 5,
    exportSchema = false,
)
abstract class Db : RoomDatabase() {
    abstract fun dao(): FocusDao

    companion object {
        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "flipfocus.db")
                .fallbackToDestructiveMigration(true)
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                .build().also { inst = it }
        }
    }
}

/** Failed sessions are declared unsuccessful and excluded from total focus time. */
fun SessionEntity.violatedMs(): Long {
    if (failed) return plannedMs
    val actual = (endMs - startMs).coerceAtLeast(0)
    val missed = if (completed) 0L else (plannedMs - actual).coerceAtLeast(0)
    return (faceUpMs + missed).coerceAtMost(plannedMs)
}

fun SessionEntity.effectiveMs(): Long =
    if (failed) 0L else (endMs - startMs - faceUpMs).coerceAtLeast(0)

fun SessionEntity.adherence(): Float =
    if (plannedMs <= 0) 1f else (1f - violatedMs().toFloat() / plannedMs).coerceIn(0f, 1f)

fun List<SessionEntity>.adherencePct(): Int {
    val planned = sumOf { it.plannedMs }
    if (planned <= 0) return 100
    return Math.round(100f * (1f - sumOf { it.violatedMs() }.toFloat() / planned))
}

private fun dayStart(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Consecutive days with at least one non-failed session; today may still be in progress. */
fun List<SessionEntity>.currentStreak(): Int {
    val good = filter { !it.failed }.map { dayStart(it.startMs) }.toSet()
    if (good.isEmpty()) return 0
    val cal = Calendar.getInstance().apply { timeInMillis = dayStart(System.currentTimeMillis()) }
    if (cal.timeInMillis !in good) cal.add(Calendar.DAY_OF_YEAR, -1)
    var n = 0
    while (cal.timeInMillis in good) { n++; cal.add(Calendar.DAY_OF_YEAR, -1) }
    return n
}

fun List<SessionEntity>.last7GoodDays(): List<Boolean> {
    val good = filter { !it.failed }.map { dayStart(it.startMs) }.toSet()
    val cal = Calendar.getInstance().apply { timeInMillis = dayStart(System.currentTimeMillis()) }
    return (6 downTo 0).map { i ->
        val d = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -i) }
        d.timeInMillis in good
    }
}

/** Successful focus minutes by weekday and 6-hour daypart, based on session start time. */
fun List<SessionEntity>.heatmapRows(): List<HeatRow> {
    val cal = Calendar.getInstance().apply { timeInMillis = dayStart(System.currentTimeMillis()) }
    return (6 downTo 0).map { i ->
        val d = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -i) }
        val s0 = d.timeInMillis; val e0 = s0 + 86_400_000L
        val day = "SMTWTFS"[d.get(Calendar.DAY_OF_WEEK) - 1].toString()
        val values = DAYPARTS.map { p ->
            filter { (it.startMs in s0 until e0) && !it.failed }.sumOf { s ->
                val h = Calendar.getInstance().apply { timeInMillis = s.startMs }.get(Calendar.HOUR_OF_DAY)
                if (h in p.startHour until p.endHour) s.effectiveMs() else 0L
            } / 60_000
        }
        HeatRow(day, values)
    }
}

enum class Phase { IDLE, ARMING, FOCUSED, VIOLATION, PAUSED_CALL, UTILITY, BREAK }

/** In-process live state shared by FocusService and the UI. */
object FocusState {
    val phase = MutableStateFlow(Phase.IDLE)
    val remainingMs = MutableStateFlow(0L)
    val totalMs = MutableStateFlow(0L)
    val scheduled = MutableStateFlow(false)
    val failed = MutableStateFlow(false)
    val runningScheduleId = MutableStateFlow(0)
    val utilityApps = MutableStateFlow<Set<String>>(emptySet())
    val activeUtility = MutableStateFlow<String?>(null)
    val pendingTags = MutableStateFlow<List<PickupTag>>(emptyList())
    @Volatile var utilityActive = false
    @Volatile var sessionId = 0L
    val z = MutableStateFlow<Float?>(null)
    val near = MutableStateFlow<Boolean?>(null)
    val armed = MutableStateFlow(false)

    fun setUtility(pkg: String?) {
        utilityActive = pkg != null
        activeUtility.value = pkg
    }

    fun clearPendingTags() { pendingTags.value = emptyList() }

    fun reset() {
        phase.value = Phase.IDLE
        remainingMs.value = 0L
        totalMs.value = 0L
        scheduled.value = false
        failed.value = false
        runningScheduleId.value = 0
        setUtility(null)
        sessionId = 0L
        z.value = null
        near.value = null
        armed.value = false
        // utilityApps and pendingTags are intentionally preserved:
        // utilityApps is the saved selection; pendingTags is post-session work.
    }
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("ff", Context.MODE_PRIVATE)
    fun utility(c: Context): Set<String> = sp(c).getStringSet("utility", emptySet()) ?: emptySet()
    fun setUtility(c: Context, s: Set<String>) = sp(c).edit().putStringSet("utility", HashSet(s)).apply()
}

fun Context.hasUsageAccess(): Boolean = getSystemService(AppOpsManager::class.java)
    .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED

/** USE_FULL_SCREEN_INTENT is only user-revocable on Android 14+. */
fun Context.canFsi(): Boolean = android.os.Build.VERSION.SDK_INT < 34 ||
    getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()

/** Android 16+ "live notifications" (status-bar chip / Samsung Now Bar) can be switched off per app. */
fun Context.canLive(): Boolean = android.os.Build.VERSION.SDK_INT < 36 ||
    getSystemService(android.app.NotificationManager::class.java).canPostPromotedNotifications()
