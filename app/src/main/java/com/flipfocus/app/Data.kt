package com.flipfocus.app

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

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
)

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val type: String, // PICKUP, CALL_BYPASS, BLOCK, EARLY_END
    val ts: Long,
    val pkg: String = "",
)

data class PkgCount(val pkg: String, val n: Int)

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val days: Int, // bit0 = Monday ... bit6 = Sunday
    val startMin: Int,
    val endMin: Int,
    val enabled: Boolean = true,
    val apps: String = "", // comma-separated package names blocked by this schedule
)

@Dao
interface FocusDao {
    @Insert suspend fun insert(s: SessionEntity): Long
    @Update suspend fun update(s: SessionEntity)
    @Insert suspend fun insertEvent(e: EventEntity)
    @Query("SELECT * FROM sessions WHERE endMs > 0 ORDER BY startMs DESC") fun sessions(): Flow<List<SessionEntity>>
    @Query("SELECT COUNT(*) FROM events WHERE type = :t") fun count(t: String): Flow<Int>
    @Query("SELECT pkg, COUNT(*) AS n FROM events WHERE type = 'BLOCK' GROUP BY pkg ORDER BY n DESC LIMIT 8") fun topBlocked(): Flow<List<PkgCount>>
    @Query("SELECT * FROM schedules ORDER BY startMin") fun schedules(): Flow<List<ScheduleEntity>>
    @Query("SELECT * FROM schedules") suspend fun schedulesNow(): List<ScheduleEntity>
    @Query("SELECT * FROM schedules WHERE id = :id") suspend fun schedule(id: Int): ScheduleEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSchedule(s: ScheduleEntity): Long
    @Delete suspend fun deleteSchedule(s: ScheduleEntity)
    @Query("UPDATE schedules SET enabled = :e WHERE id = :id") suspend fun setEnabled(id: Int, e: Boolean)
}

@Database(entities = [SessionEntity::class, EventEntity::class, ScheduleEntity::class], version = 3, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): FocusDao

    companion object {
        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "flipfocus.db").fallbackToDestructiveMigration(true).build().also { inst = it }
        }
    }
}

/** Violated time = time face-up + the unfinished remainder of an abandoned session. */
fun SessionEntity.violatedMs(): Long {
    val actual = (endMs - startMs).coerceAtLeast(0)
    val missed = if (completed) 0L else (plannedMs - actual).coerceAtLeast(0)
    return (faceUpMs + missed).coerceAtMost(plannedMs)
}

fun SessionEntity.adherence(): Float =
    if (plannedMs <= 0) 1f else (1f - violatedMs().toFloat() / plannedMs).coerceIn(0f, 1f)

/** Overall Adherence Score (0..100), weighted by planned time. */
fun List<SessionEntity>.adherencePct(): Int {
    val planned = sumOf { it.plannedMs }
    if (planned <= 0) return 100
    return Math.round(100f * (1f - sumOf { it.violatedMs() }.toFloat() / planned))
}

enum class Phase { IDLE, ARMING, FOCUSED, VIOLATION, PAUSED_CALL, UTILITY }

/** In-process live state shared by FocusService, the UI and BlockerService. */
object FocusState {
    val phase = MutableStateFlow(Phase.IDLE)
    val remainingMs = MutableStateFlow(0L)
    val totalMs = MutableStateFlow(0L)
    @Volatile var sessionId = 0L
    @Volatile var blockedApps: Set<String> = emptySet()
    @Volatile var utilityApps: Set<String> = emptySet()
    @Volatile var utilityActive = false
    fun reset() { phase.value = Phase.IDLE; remainingMs.value = 0; sessionId = 0; blockedApps = emptySet(); utilityActive = false }
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("ff", Context.MODE_PRIVATE)
    fun blocked(c: Context): Set<String> = sp(c).getStringSet("blocked", emptySet()) ?: emptySet()
    fun utility(c: Context): Set<String> = sp(c).getStringSet("utility", emptySet()) ?: emptySet()
    fun setUtility(c: Context, s: Set<String>) = sp(c).edit().putStringSet("utility", HashSet(s)).apply()
    fun setBlocked(c: Context, s: Set<String>) = sp(c).edit().putStringSet("blocked", HashSet(s)).apply()
}
