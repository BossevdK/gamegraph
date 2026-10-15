package nl.gamegraph.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import java.io.File

@Entity(tableName = "user_games")
data class UserGameEntity(
    @PrimaryKey val gameId: String,
    val reaction: String,
    val saved: Boolean,
    val favorite: Boolean,
    val wishlist: Boolean,
    val rating: Int?,
    val lastViewedAt: Long?,
    val updatedAt: Long,
)

@Entity(tableName = "feedback_events")
data class FeedbackEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: String,
    val type: String,
    val value: Float,
    val createdAt: Long,
)

@Entity(tableName = "dna_prefs")
data class DnaPrefEntity(@PrimaryKey val axis: String, val value: Float)

/** Games uit een externe bron, met herkomst en moment van laatste controle. */
@Entity(tableName = "cached_games")
data class CachedGameEntity(
    @PrimaryKey val id: String,
    val json: String,
    val source: String,
    val checkedAt: Long?,
)

@Dao
interface UserGameDao {
    @Query("SELECT * FROM user_games")
    fun observeAll(): Flow<List<UserGameEntity>>

    @Query("SELECT * FROM user_games")
    suspend fun getAll(): List<UserGameEntity>

    @Query("SELECT * FROM user_games WHERE gameId = :id")
    suspend fun get(id: String): UserGameEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserGameEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<UserGameEntity>)

    @Query("DELETE FROM user_games")
    suspend fun clear()
}

@Dao
interface FeedbackDao {
    @Query("SELECT * FROM feedback_events ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<FeedbackEventEntity>>

    @Query("SELECT * FROM feedback_events ORDER BY createdAt DESC, id DESC")
    suspend fun getAll(): List<FeedbackEventEntity>

    @Insert
    suspend fun insert(entity: FeedbackEventEntity)

    @Insert
    suspend fun insertAll(entities: List<FeedbackEventEntity>)

    @Query("DELETE FROM feedback_events")
    suspend fun clear()
}

@Dao
interface DnaPrefDao {
    @Query("SELECT * FROM dna_prefs")
    fun observeAll(): Flow<List<DnaPrefEntity>>

    @Query("SELECT * FROM dna_prefs")
    suspend fun getAll(): List<DnaPrefEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DnaPrefEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<DnaPrefEntity>)

    @Query("DELETE FROM dna_prefs WHERE axis = :axis")
    suspend fun delete(axis: String)

    @Query("DELETE FROM dna_prefs")
    suspend fun clear()
}

@Dao
interface CachedGameDao {
    @Query("SELECT * FROM cached_games")
    fun observeAll(): Flow<List<CachedGameEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CachedGameEntity>)

    @Query("DELETE FROM cached_games")
    suspend fun clear()
}

@Database(
    entities = [UserGameEntity::class, FeedbackEventEntity::class, DnaPrefEntity::class, CachedGameEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class GameDatabase : RoomDatabase() {
    abstract fun userGames(): UserGameDao
    abstract fun feedback(): FeedbackDao
    abstract fun dnaPrefs(): DnaPrefDao
    abstract fun cachedGames(): CachedGameDao

    companion object {
        const val FILE_NAME = "gamegraph.db"

        /**
         * Opent de database. Is het bestand beschadigd, dan wordt het eerst bewaard als *.corrupt
         * (niets wordt stilletjes weggegooid) en start de app met een lege database.
         * [corruptionHandled] meldt dat dit is gebeurd, zodat de UI het kan tonen.
         */
        fun open(context: Context, corruptionHandled: () -> Unit = {}): GameDatabase {
            fun build() = Room.databaseBuilder(context, GameDatabase::class.java, FILE_NAME).build()
            val file = context.getDatabasePath(FILE_NAME)
            // Android's eigen foutafhandeling gooit een beschadigd bestand stilletjes weg. Wij bewaren het eerst.
            fun quarantine() {
                runCatching { if (file.exists()) file.copyTo(File(file.parentFile, "$FILE_NAME.corrupt"), overwrite = true) }
                context.deleteDatabase(FILE_NAME)
                corruptionHandled()
            }
            if (looksCorrupt(file)) quarantine()
            val db = build()
            return try {
                db.openHelper.writableDatabase
                db
            } catch (e: Exception) {
                runCatching { db.close() }
                quarantine()
                build()
            }
        }

        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        /** Een bestaand, niet-leeg bestand zonder SQLite-kop is beschadigd. */
        internal fun looksCorrupt(file: File): Boolean {
            if (!file.exists() || file.length() == 0L) return false
            return runCatching {
                val head = ByteArray(SQLITE_HEADER.size)
                file.inputStream().use { it.read(head) }
                !head.contentEquals(SQLITE_HEADER)
            }.getOrDefault(true)
        }

        fun inMemory(context: Context): GameDatabase =
            Room.inMemoryDatabaseBuilder(context, GameDatabase::class.java).allowMainThreadQueries().build()
    }
}
