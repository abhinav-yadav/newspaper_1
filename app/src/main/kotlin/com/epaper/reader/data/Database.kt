package com.epaper.reader.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "editions")
data class EditionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val dateEpochDay: Long,
    val publishedAt: Long,
    val url: String,
)

enum class LinkStatus { AVAILABLE, PENDING, OFF }

@Entity(tableName = "papers", indices = [Index("editionId"), Index("publishedAt")])
data class PaperEntity(
    @PrimaryKey val id: String,
    val editionId: String,
    val position: Int,
    val title: String,
    val code: String,
    val subtitle: String,
    val category: String,
    val language: String?,
    val status: LinkStatus,
    val link: String?,
    val offReason: String?,
    val dateEpochDay: Long,
    val publishedAt: Long,
    val postUrl: String,
    /** Set when the resolver found the link dead; cleared if the blog changes the link. */
    val removedReason: String? = null,
)

/** Per-paper user state, kept apart from [PaperEntity] so refreshes never touch it. */
@Entity(tableName = "library")
data class LibraryEntity(
    @PrimaryKey val paperId: String,
    val bookmarked: Boolean = false,
    val lastOpenedAt: Long? = null,
    val lastPage: Int = 0,
    val pageCount: Int = 0,
    /** The user stopped or deleted this download: automatic downloading must leave it alone. */
    val autoSkip: Boolean = false,
)

/** A favourite publication (by title, e.g. "The H. Edition"), not a single issue. */
@Entity(tableName = "favorites")
data class FavoriteEntity(@PrimaryKey val title: String)

enum class DownloadState { QUEUED, RUNNING, PAUSED, COMPLETE, FAILED }

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val paperId: String,
    val url: String,
    /** Headers needed by the host (cookies captured by the in-app browser), as JSON. */
    val headersJson: String = "{}",
    val state: DownloadState,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = -1,
    val filePath: String? = null,
    val error: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** A paper with everything the UI shows about it. */
data class PaperRow(
    @Embedded val paper: PaperEntity,
    val editionTitle: String,
    val bookmarked: Boolean?,
    val lastOpenedAt: Long?,
    val lastPage: Int?,
    val pageCount: Int?,
    val autoSkip: Boolean,
    val favorite: Boolean,
    val dlState: DownloadState?,
    val dlBytes: Long?,
    val dlTotal: Long?,
    val dlPath: String?,
    val dlError: String?,
)

@Dao
interface PaperDao {
    @Query(
        """
        SELECT p.*, e.title AS editionTitle,
               l.bookmarked AS bookmarked, l.lastOpenedAt AS lastOpenedAt, l.lastPage AS lastPage, l.pageCount AS pageCount,
               COALESCE(l.autoSkip, 0) AS autoSkip,
               (f.title IS NOT NULL) AS favorite,
               d.state AS dlState, d.downloadedBytes AS dlBytes, d.totalBytes AS dlTotal, d.filePath AS dlPath, d.error AS dlError
        FROM papers p
        JOIN editions e ON e.id = p.editionId
        LEFT JOIN library l ON l.paperId = p.id
        LEFT JOIN favorites f ON f.title = p.title
        LEFT JOIN downloads d ON d.paperId = p.id
        ORDER BY p.publishedAt DESC, p.position ASC
        """
    )
    fun observeAll(): Flow<List<PaperRow>>

    @Query("SELECT * FROM papers WHERE id = :id")
    suspend fun paper(id: String): PaperEntity?

    @Query("SELECT * FROM papers WHERE id = :id")
    fun observePaper(id: String): Flow<PaperEntity?>

    @Query("SELECT id, link, removedReason FROM papers WHERE removedReason IS NOT NULL")
    suspend fun removedLinks(): List<RemovedLink>

    @Upsert suspend fun upsertEditions(e: List<EditionEntity>)
    @Upsert suspend fun upsertPapers(p: List<PaperEntity>)

    @Query("UPDATE papers SET removedReason = :reason WHERE id = :id")
    suspend fun setRemoved(id: String, reason: String?)

    /** Papers of the [editions] newest editions, newest first. */
    @Query("SELECT p.* FROM papers p WHERE p.editionId IN (SELECT id FROM editions ORDER BY publishedAt DESC LIMIT :editions) ORDER BY p.publishedAt DESC, p.position")
    suspend fun recent(editions: Int): List<PaperEntity>

    @Query("SELECT COUNT(*) FROM editions")
    suspend fun editionCount(): Int
}

data class RemovedLink(val id: String, val link: String?, val removedReason: String?)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library WHERE paperId = :id")
    suspend fun get(id: String): LibraryEntity?

    @Query("SELECT * FROM library WHERE paperId = :id")
    fun observe(id: String): Flow<LibraryEntity?>

    @Upsert suspend fun upsert(e: LibraryEntity)

    @Query("UPDATE library SET lastOpenedAt = NULL, lastPage = 0")
    suspend fun clearHistory()

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE title = :title)")
    suspend fun isFavorite(title: String): Boolean

    @Upsert suspend fun addFavorite(f: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE title = :title")
    suspend fun removeFavorite(title: String)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE paperId = :id")
    suspend fun get(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE paperId = :id")
    fun observe(id: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads")
    suspend fun all(): List<DownloadEntity>

    @Upsert suspend fun upsert(e: DownloadEntity)

    @Query("UPDATE downloads SET state = :state, downloadedBytes = :bytes, totalBytes = :total, error = :error, updatedAt = :now WHERE paperId = :id")
    suspend fun progress(id: String, state: DownloadState, bytes: Long, total: Long, error: String? = null, now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET state = 'COMPLETE', filePath = :path, downloadedBytes = :size, totalBytes = :size, error = NULL, updatedAt = :now WHERE paperId = :id")
    suspend fun complete(id: String, path: String, size: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM downloads WHERE paperId = :id")
    suspend fun delete(id: String)
}

@Database(
    entities = [EditionEntity::class, PaperEntity::class, LibraryEntity::class, FavoriteEntity::class, DownloadEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun papers(): PaperDao
    abstract fun library(): LibraryDao
    abstract fun downloads(): DownloadDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "epaper.db").addMigrations(MIGRATION_1_2).build()

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE library ADD COLUMN autoSkip INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
