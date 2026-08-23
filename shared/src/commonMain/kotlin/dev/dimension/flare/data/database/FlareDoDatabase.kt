package dev.dimension.flare.data.database

import androidx.room3.AutoMigration
import androidx.room3.ConstructedBy
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
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
 * Restart-persistent composer content partitioned by a public account ID and application draft key.
 *
 * Unlike authentication state, draft text is intentionally allowed in Room so an expired session
 * cannot destroy unfinished work. [payload] contains only the versioned composer model serialized
 * by the Discourse module; it must never contain cookies, CSRF tokens, upload bytes, or a queued
 * network operation. [revision] supports compare-and-delete after publishing, preserving a newer
 * edit that was saved while the older revision was in flight.
 */
@Entity(
    tableName = "composer_drafts",
    primaryKeys = ["accountId", "draftKey"],
    indices = [Index(value = ["accountId", "updatedAtEpochMillis"])],
)
public data class ComposerDraftEntity(
    val accountId: String,
    val draftKey: String,
    val payload: String,
    val revision: Long,
    val updatedAtEpochMillis: Long,
)

/** Atomic persistence operations for bounded per-account composer drafts. */
@Dao
public interface ComposerDraftDao {
    @Query("SELECT * FROM composer_drafts WHERE accountId = :accountId AND draftKey = :draftKey")
    public suspend fun get(
        accountId: String,
        draftKey: String,
    ): ComposerDraftEntity?

    @Query(
        """
        SELECT * FROM composer_drafts
        WHERE accountId = :accountId
        ORDER BY updatedAtEpochMillis DESC, draftKey DESC
        """,
    )
    public suspend fun listForAccount(accountId: String): List<ComposerDraftEntity>

    @Upsert
    public suspend fun upsert(entity: ComposerDraftEntity)

    /** Atomically writes one revision and restores the persistent per-account bound. */
    @Transaction
    public suspend fun upsertBounded(
        entity: ComposerDraftEntity,
        maxEntries: Int,
    ) {
        upsert(entity)
        pruneToNewest(entity.accountId, maxEntries)
    }

    @Query("DELETE FROM composer_drafts WHERE accountId = :accountId AND draftKey = :draftKey")
    public suspend fun delete(
        accountId: String,
        draftKey: String,
    )

    /**
     * Deletes only the revision submitted to Linux.do.
     *
     * A composer may save a newer revision before the publish response arrives. Including the
     * observed revision in this query prevents that successful older request from deleting the
     * user's newer text.
     */
    @Query(
        """
        DELETE FROM composer_drafts
        WHERE accountId = :accountId AND draftKey = :draftKey AND revision = :expectedRevision
        """,
    )
    public suspend fun deleteIfRevisionMatches(
        accountId: String,
        draftKey: String,
        expectedRevision: Long,
    ): Int

    @Query(
        """
        DELETE FROM composer_drafts
        WHERE accountId = :accountId
          AND draftKey NOT IN (
            SELECT draftKey FROM composer_drafts
            WHERE accountId = :accountId
            ORDER BY updatedAtEpochMillis DESC, draftKey DESC
            LIMIT :maxEntries
          )
        """,
    )
    public suspend fun pruneToNewest(
        accountId: String,
        maxEntries: Int,
    )

    @Query("SELECT COUNT(*) FROM composer_drafts WHERE accountId = :accountId")
    public suspend fun countForAccount(accountId: String): Int
}

/**
 * Last accepted MessageBus message ID for one authenticated account and channel.
 *
 * Only the public account ID, the application-validated channel name, and its monotonically
 * increasing numeric cursor belong in this table. Message payloads, MessageBus client IDs, shared
 * session keys, cookies, CSRF values, and any other session material must never be persisted here.
 * The Discourse adapter intentionally writes only `/notification/{accountId}`; other foreground
 * subscriptions remain process-local even though the schema stays generic enough for atomic DAO
 * tests and a future explicitly reviewed persistence policy.
 */
@Entity(
    tableName = "message_bus_cursors",
    primaryKeys = ["accountId", "channel"],
)
public data class MessageBusCursorEntity(
    val accountId: String,
    val channel: String,
    val messageId: Long,
) {
    init {
        require(messageId >= 0L) { "MessageBus cursor must not be negative" }
    }
}

/** Result of one atomic cursor compare-and-set operation. */
public data class MessageBusCursorAdvanceResult(
    val cursor: Long,
    val advanced: Boolean,
)

/** Atomic, non-secret cursor operations used by the Room-backed MessageBus cursor store. */
@Dao
public interface MessageBusCursorDao {
    @Query("SELECT * FROM message_bus_cursors WHERE accountId = :accountId AND channel = :channel")
    public suspend fun get(
        accountId: String,
        channel: String,
    ): MessageBusCursorEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insertIfAbsent(entity: MessageBusCursorEntity): Long

    /** Advances an existing row only; a delayed or duplicate event can never lower its cursor. */
    @Query(
        """
        UPDATE message_bus_cursors
        SET messageId = :messageId
        WHERE accountId = :accountId AND channel = :channel AND messageId < :messageId
        """,
    )
    public suspend fun updateIfNewer(
        accountId: String,
        channel: String,
        messageId: Long,
    ): Int

    /**
     * Atomically creates or advances one cursor and returns the authoritative database value.
     *
     * Room serializes this transaction with competing writers. Reading after the conditional
     * update is important: the caller must observe a larger cursor already committed by another
     * event instead of assuming that its own candidate won the race.
     */
    @Transaction
    public suspend fun advance(
        accountId: String,
        channel: String,
        messageId: Long,
    ): MessageBusCursorAdvanceResult {
        require(messageId >= 0L) { "MessageBus cursor must not be negative" }
        val inserted =
            insertIfAbsent(
                MessageBusCursorEntity(
                    accountId = accountId,
                    channel = channel,
                    messageId = messageId,
                ),
            ) != -1L
        val updated = !inserted && updateIfNewer(accountId, channel, messageId) == 1
        val authoritative =
            checkNotNull(get(accountId, channel)) {
                "MessageBus cursor transaction did not create its row"
            }.messageId
        return MessageBusCursorAdvanceResult(
            cursor = authoritative,
            advanced = inserted || updated,
        )
    }

    @Query("DELETE FROM message_bus_cursors WHERE accountId = :accountId")
    public suspend fun deleteForAccount(accountId: String): Int
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
public const val FLARE_DO_DATABASE_VERSION: Int = 5

@Database(
    entities = [
        ForumCacheMetadataEntity::class,
        ForumCacheEntryEntity::class,
        ComposerDraftEntity::class,
        MessageBusCursorEntity::class,
        SecureVaultReferenceEntity::class,
    ],
    version = FLARE_DO_DATABASE_VERSION,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = FLARE_DO_DATABASE_VERSION),
    ],
)
@ConstructedBy(FlareDoDatabaseConstructor::class)
public abstract class FlareDoDatabase : RoomDatabase() {
    public abstract fun forumCacheMetadataDao(): ForumCacheMetadataDao

    public abstract fun forumCacheEntryDao(): ForumCacheEntryDao

    public abstract fun composerDraftDao(): ComposerDraftDao

    public abstract fun messageBusCursorDao(): MessageBusCursorDao

    public abstract fun secureVaultReferenceDao(): SecureVaultReferenceDao
}

// Room generates actual constructors for every KMP target during KSP compilation.
@Suppress("NO_ACTUAL_FOR_EXPECT")
public expect object FlareDoDatabaseConstructor : RoomDatabaseConstructor<FlareDoDatabase> {
    override fun initialize(): FlareDoDatabase
}
