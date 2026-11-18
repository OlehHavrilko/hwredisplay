package dev.hardwaredisplay.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "hosts")
data class HostEntity(
    @PrimaryKey val hostId: String,
    val label: String,
    val ip: String,
    val port: Int,
    val certSha256: String,
    val clientId: String,
    val lastSeenUtc: Long?,
)

@Entity(
    tableName = "presets",
    foreignKeys = [
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["hostId"],
            childColumns = ["hostId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("hostId")],
)
data class PresetEntity(
    @PrimaryKey val presetId: String,
    val hostId: String,
    val name: String,
    val position: Int,
)

enum class CardRole { PRIMARY, SECONDARY, COMPARE }

enum class CardView { NUMBER, LINE }

@Entity(
    tableName = "cards",
    foreignKeys = [
        ForeignKey(
            entity = PresetEntity::class,
            parentColumns = ["presetId"],
            childColumns = ["presetId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("presetId")],
)
data class CardEntity(
    @PrimaryKey val cardId: String,
    val presetId: String,
    val position: Int,
    val role: String,
    val view: String,
    val hostId: String,
    val sensorId: String,
    val deviceKind: String,
    val metricKey: String,
)

@Entity(
    tableName = "history",
    primaryKeys = ["hostId", "sensorId", "streamEpoch", "seq"],
    indices = [Index(value = ["hostId", "sensorId", "capturedAtUtc"])],
)
data class HistoryPointEntity(
    val hostId: String,
    val sensorId: String,
    val streamEpoch: String,
    val seq: Long,
    val capturedAtUtc: Long,
    val value: Double,
)

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY label")
    fun observeAll(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts WHERE hostId = :hostId")
    fun observe(hostId: String): Flow<HostEntity?>

    @Query("SELECT * FROM hosts WHERE hostId = :hostId")
    suspend fun get(hostId: String): HostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(host: HostEntity)

    @Query("DELETE FROM hosts WHERE hostId = :hostId")
    suspend fun delete(hostId: String)
}

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets WHERE hostId = :hostId ORDER BY position")
    fun observePresets(hostId: String): Flow<List<PresetEntity>>

    @Query("SELECT * FROM presets WHERE hostId = :hostId ORDER BY position")
    suspend fun presets(hostId: String): List<PresetEntity>

    @Query("SELECT * FROM presets WHERE presetId = :presetId")
    suspend fun preset(presetId: String): PresetEntity?

    @Query("SELECT * FROM cards WHERE presetId = :presetId ORDER BY position")
    fun observeCards(presetId: String): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards WHERE presetId = :presetId ORDER BY position")
    suspend fun cards(presetId: String): List<CardEntity>

    @Query("SELECT * FROM cards WHERE presetId IN (SELECT presetId FROM presets WHERE hostId = :hostId)")
    suspend fun allCardsForHost(hostId: String): List<CardEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: PresetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCards(cards: List<CardEntity>)

    @Query("DELETE FROM cards WHERE presetId = :presetId")
    suspend fun clearCards(presetId: String)

    @Query("UPDATE presets SET name = :name WHERE presetId = :presetId")
    suspend fun rename(presetId: String, name: String)

    @Query("DELETE FROM presets WHERE presetId = :presetId")
    suspend fun deletePreset(presetId: String)

    @Transaction
    suspend fun replaceCards(presetId: String, cards: List<CardEntity>) {
        clearCards(presetId)
        insertCards(cards)
    }
}

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(points: List<HistoryPointEntity>)

    @Query(
        "SELECT * FROM history WHERE hostId = :hostId AND sensorId = :sensorId AND capturedAtUtc >= :fromUtc " +
            "ORDER BY capturedAtUtc"
    )
    suspend fun range(hostId: String, sensorId: String, fromUtc: Long): List<HistoryPointEntity>

    @Query("SELECT COUNT(*) FROM history")
    suspend fun count(): Long

    @Query("DELETE FROM history WHERE capturedAtUtc < :beforeUtc")
    suspend fun deleteOlderThan(beforeUtc: Long)

    @Query(
        "DELETE FROM history WHERE rowid IN (" +
            "SELECT rowid FROM history ORDER BY capturedAtUtc LIMIT :excess)"
    )
    suspend fun deleteOldest(excess: Int)

    @Query("DELETE FROM history WHERE hostId = :hostId")
    suspend fun deleteForHost(hostId: String)
}

@Database(
    entities = [HostEntity::class, PresetEntity::class, CardEntity::class, HistoryPointEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hosts(): HostDao
    abstract fun presets(): PresetDao
    abstract fun history(): HistoryDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "hardwaredisplay.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
