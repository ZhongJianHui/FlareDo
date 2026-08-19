package dev.dimension.flare.data.database

import androidx.room3.ConstructedBy
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
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

@Database(
    entities = [ForumCacheMetadataEntity::class],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(FlareDoDatabaseConstructor::class)
public abstract class FlareDoDatabase : RoomDatabase() {
    public abstract fun forumCacheMetadataDao(): ForumCacheMetadataDao
}

// Room generates actual constructors for every KMP target during KSP compilation.
@Suppress("NO_ACTUAL_FOR_EXPECT")
public expect object FlareDoDatabaseConstructor : RoomDatabaseConstructor<FlareDoDatabase> {
    override fun initialize(): FlareDoDatabase
}
