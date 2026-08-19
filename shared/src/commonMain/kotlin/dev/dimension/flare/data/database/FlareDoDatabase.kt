package dev.dimension.flare.data.database

import androidx.room3.AutoMigration
import androidx.room3.ConstructedBy
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.Transaction
import androidx.room3.Upsert

/**
 * Non-secret cache metadata for forum paging resources.
 *
 * Credentials and cookies must never be added to this table. Authentication data is represented
 * only by opaque secure-vault references in the session layer introduced in stage 5.
 */
@Entity(
    tableName = "forum_cache_metadata",
    primaryKeys = ["accountId", "cacheKey"],
)
public data class ForumCacheMetadataEntity(
    val accountId: String,
    val cacheKey: String,
    val updatedAtEpochMillis: Long,
    val etag: String? = null,
    val lastModified: String? = null,
)

@Dao
public interface ForumCacheMetadataDao {
    @Query("SELECT * FROM forum_cache_metadata WHERE accountId = :accountId AND cacheKey = :cacheKey")
    public suspend fun get(
        accountId: String,
        cacheKey: String,
    ): ForumCacheMetadataEntity?

    @Upsert
    public suspend fun upsert(entity: ForumCacheMetadataEntity)

    @Query("DELETE FROM forum_cache_metadata WHERE accountId = :accountId")
    public suspend fun deleteForAccount(accountId: String)
}

/**
 * Versioned, non-secret forum cache payload shared by every platform database.
 *
 * [accountId] is a local public account partition such as `anonymous` or a server-owned numeric
 * account ID; it is never a cookie, token, vault reference, or other authentication material.
 * [cacheKey] is a bounded application-owned feed/topic key, and [payload] contains only serialized
 * public forum UI data. Keeping this generic prevents the Room schema from changing whenever the
 * cached presentation contract gains an optional field.
 */
@Entity(
    tableName = "forum_cache_entries",
    primaryKeys = ["accountId", "cacheKey"],
    indices = [Index(value = ["accountId", "updatedAtEpochMillis"])],
)
public data class ForumCacheEntryEntity(
    val accountId: String,
    val cacheKey: String,
    val payload: String,
    val updatedAtEpochMillis: Long,
)

/** DAO used by bounded Room-backed forum cache adapters. */
@Dao
public interface ForumCacheEntryDao {
    @Query("SELECT * FROM forum_cache_entries WHERE accountId = :accountId AND cacheKey = :cacheKey")
    public suspend fun get(
        accountId: String,
        cacheKey: String,
    ): ForumCacheEntryEntity?

    @Upsert
    public suspend fun upsert(entity: ForumCacheEntryEntity)

    /** Atomically writes one entry and restores the per-account persistent bound. */
    @Transaction
    public suspend fun upsertBounded(
        entity: ForumCacheEntryEntity,
        maxEntries: Int,
    ) {
        upsert(entity)
        pruneToNewest(entity.accountId, maxEntries)
    }

    @Query("DELETE FROM forum_cache_entries WHERE accountId = :accountId AND cacheKey = :cacheKey")
    public suspend fun delete(
        accountId: String,
        cacheKey: String,
    )

    /** Deletes a corrupt row only if no concurrent writer has replaced its observed payload. */
    @Query(
        """
        DELETE FROM forum_cache_entries
        WHERE accountId = :accountId AND cacheKey = :cacheKey AND payload = :observedPayload
        """,
    )
    public suspend fun deleteIfPayloadMatches(
        accountId: String,
        cacheKey: String,
        observedPayload: String,
    )

    @Query("DELETE FROM forum_cache_entries WHERE accountId = :accountId")
    public suspend fun deleteForAccount(accountId: String)

    /**
     * Keeps the newest [maxEntries] rows for one account using a deterministic key tie-breaker.
     *
     * The adapter validates that the limit is positive before calling this query. Performing the
     * bound in SQLite means the persistent cache remains bounded across process restarts instead of
     * relying on an in-memory index that can be lost before cleanup.
     */
    @Query(
        """
        DELETE FROM forum_cache_entries
        WHERE accountId = :accountId
          AND cacheKey NOT IN (
            SELECT cacheKey FROM forum_cache_entries
            WHERE accountId = :accountId
            ORDER BY updatedAtEpochMillis DESC, cacheKey DESC
            LIMIT :maxEntries
          )
        """,
    )
    public suspend fun pruneToNewest(
        accountId: String,
        maxEntries: Int,
    )

    @Query("SELECT COUNT(*) FROM forum_cache_entries WHERE accountId = :accountId")
    public suspend fun countForAccount(accountId: String): Int
}

/** Current additive Room schema version. */
public const val FLARE_DO_DATABASE_VERSION: Int = 2

@Database(
    entities = [ForumCacheMetadataEntity::class, ForumCacheEntryEntity::class],
    version = FLARE_DO_DATABASE_VERSION,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = FLARE_DO_DATABASE_VERSION)],
)
@ConstructedBy(FlareDoDatabaseConstructor::class)
public abstract class FlareDoDatabase : RoomDatabase() {
    public abstract fun forumCacheMetadataDao(): ForumCacheMetadataDao

    public abstract fun forumCacheEntryDao(): ForumCacheEntryDao
}

// Room generates actual constructors for every KMP target during KSP compilation.
@Suppress("NO_ACTUAL_FOR_EXPECT")
public expect object FlareDoDatabaseConstructor : RoomDatabaseConstructor<FlareDoDatabase> {
    override fun initialize(): FlareDoDatabase
}
