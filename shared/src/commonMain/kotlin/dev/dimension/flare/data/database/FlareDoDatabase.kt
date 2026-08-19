package dev.dimension.flare.data.database

import androidx.room3.AutoMigration
import androidx.room3.ConstructedBy
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
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

/**
 * Non-secret pointer to one value owned by a platform credential vault.
 *
 * This table is the only authentication persistence surface in Room. [credentialRef] is an opaque
 * locator, not the credential itself. Cookie values, CSRF tokens, RSA key material, authorization
 * nonces, User API Keys, OTPs, and serialized vault payloads are forbidden here. [accountId] and
 * [username] are optional public display metadata for the single active-account slot; pending
 * authorization and installation slots leave them null. [relatedCredentialRef] is used only when
 * one logical record owns a second vault item, such as the one-use RSA private key belonging to an
 * encrypted pending-authorization envelope. It remains an opaque locator and never contains key
 * material itself.
 */
@Entity(tableName = "secure_vault_references")
public data class SecureVaultReferenceEntity(
    @PrimaryKey
    val slot: String,
    val credentialRef: String,
    val relatedCredentialRef: String? = null,
    val accountId: String? = null,
    val username: String? = null,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long? = null,
)

/**
 * Atomic reference ownership used by authentication restore and callback replay protection.
 *
 * The compare-and-delete operations include the observed opaque reference. A delayed callback or
 * cleanup task can therefore never delete a newer pending authorization that reused the same
 * logical [SecureVaultReferenceEntity.slot].
 */
@Dao
public interface SecureVaultReferenceDao {
    @Query("SELECT * FROM secure_vault_references WHERE slot = :slot")
    public suspend fun get(slot: String): SecureVaultReferenceEntity?

    @Upsert
    public suspend fun upsert(entity: SecureVaultReferenceEntity)

    @Query(
        """
        DELETE FROM secure_vault_references
        WHERE slot = :slot AND credentialRef = :expectedCredentialRef
        """,
    )
    public suspend fun deleteIfMatches(
        slot: String,
        expectedCredentialRef: String,
    ): Int

    @Query("DELETE FROM secure_vault_references WHERE slot = :slot")
    public suspend fun delete(slot: String)

    /** Replaces one logical slot and returns the previous reference for vault cleanup. */
    @Transaction
    public suspend fun replace(entity: SecureVaultReferenceEntity): SecureVaultReferenceEntity? {
        val previous = get(entity.slot)
        upsert(entity)
        return previous
    }

    /**
     * Consumes exactly the observed reference or returns null after a replay/replacement race.
     */
    @Transaction
    public suspend fun consume(
        slot: String,
        expectedCredentialRef: String,
    ): SecureVaultReferenceEntity? {
        val current = get(slot) ?: return null
        if (current.credentialRef != expectedCredentialRef) return null
        return if (deleteIfMatches(slot, expectedCredentialRef) == 1) current else null
    }
}

/** Current additive Room schema version. */
public const val FLARE_DO_DATABASE_VERSION: Int = 3

@Database(
    entities = [
        ForumCacheMetadataEntity::class,
        ForumCacheEntryEntity::class,
        SecureVaultReferenceEntity::class,
    ],
    version = FLARE_DO_DATABASE_VERSION,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = FLARE_DO_DATABASE_VERSION),
    ],
)
@ConstructedBy(FlareDoDatabaseConstructor::class)
public abstract class FlareDoDatabase : RoomDatabase() {
    public abstract fun forumCacheMetadataDao(): ForumCacheMetadataDao

    public abstract fun forumCacheEntryDao(): ForumCacheEntryDao

    public abstract fun secureVaultReferenceDao(): SecureVaultReferenceDao
}

// Room generates actual constructors for every KMP target during KSP compilation.
@Suppress("NO_ACTUAL_FOR_EXPECT")
public expect object FlareDoDatabaseConstructor : RoomDatabaseConstructor<FlareDoDatabase> {
    override fun initialize(): FlareDoDatabase
}
