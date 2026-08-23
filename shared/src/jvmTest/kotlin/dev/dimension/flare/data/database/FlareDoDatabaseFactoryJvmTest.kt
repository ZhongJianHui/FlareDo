package dev.dimension.flare.data.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlareDoDatabaseFactoryJvmTest {
    @Test
    fun rejectsRelativeDatabasePathBeforeTouchingDisk() {
        val relativePath = Path.of("relative", "flaredo.db")

        assertFailsWith<IllegalArgumentException> {
            createJvmFlareDoDatabase(relativePath)
        }

        assertFalse(relativePath.exists())
    }

    @Test
    fun persistsForumCacheAndPrunesOldestEntry() =
        runTest {
            withTemporaryDatabase { database ->
                val dao = database.forumCacheEntryDao()
                val oldest = cacheEntry(key = "feed:latest:page:0", payload = "old", updatedAt = 10L)
                val newest = cacheEntry(key = "topic:42", payload = "new", updatedAt = 20L)

                dao.upsertBounded(oldest, maxEntries = 1)
                dao.upsertBounded(newest, maxEntries = 1)

                assertNull(dao.get(ANONYMOUS_ACCOUNT_ID, oldest.cacheKey))
                assertEquals(newest, dao.get(ANONYMOUS_ACCOUNT_ID, newest.cacheKey))
                assertEquals(1, dao.countForAccount(ANONYMOUS_ACCOUNT_ID))
            }
        }

    @Test
    fun vaultReferenceConsumeCannotDeleteAReplacement() =
        runTest {
            withTemporaryDatabase { database ->
                val dao = database.secureVaultReferenceDao()
                val first = vaultReference(reference = "vault:first", createdAt = 10L)
                val replacement = vaultReference(reference = "vault:replacement", createdAt = 20L)

                assertNull(dao.replace(first))
                assertEquals(first, dao.replace(replacement))
                assertNull(
                    dao.consume(
                        slot = PENDING_AUTH_SLOT,
                        expectedCredentialRef = first.credentialRef,
                    ),
                )
                assertEquals(replacement, dao.get(PENDING_AUTH_SLOT))
                assertEquals(
                    replacement,
                    dao.consume(
                        slot = PENDING_AUTH_SLOT,
                        expectedCredentialRef = replacement.credentialRef,
                    ),
                )
                assertNull(dao.get(PENDING_AUTH_SLOT))
            }
        }

    @Test
    fun persistsBoundedComposerDraftsAndConditionallyDeletesOnlyObservedRevision() =
        runTest {
            withTemporaryDatabase { database ->
                val dao = database.composerDraftDao()
                val first = composerDraft(key = "topic:1:reply:root", revision = 1L, updatedAt = 10L)
                val replacement = first.copy(payload = "newer", revision = 2L, updatedAtEpochMillis = 20L)
                val second = composerDraft(key = "topic:2:reply:root", revision = 1L, updatedAt = 30L)

                dao.upsertBounded(first, maxEntries = 2)
                dao.upsertBounded(replacement, maxEntries = 2)
                assertEquals(0, dao.deleteIfRevisionMatches(ANONYMOUS_ACCOUNT_ID, first.draftKey, 1L))
                assertEquals(replacement, dao.get(ANONYMOUS_ACCOUNT_ID, first.draftKey))

                dao.upsertBounded(second, maxEntries = 1)
                assertNull(dao.get(ANONYMOUS_ACCOUNT_ID, first.draftKey))
                assertEquals(second, dao.get(ANONYMOUS_ACCOUNT_ID, second.draftKey))
                assertEquals(1, dao.countForAccount(ANONYMOUS_ACCOUNT_ID))
            }
        }

    @Test
    fun messageBusCursorCasIsMonotonicAndAccountScoped() =
        runTest {
            withTemporaryDatabase { database ->
                val dao = database.messageBusCursorDao()
                val duplicateResults =
                    supervisorScope {
                        List(24) {
                            async { dao.advance(ACCOUNT_42, NOTIFICATION_42, 5L) }
                        }.map { it.await() }
                    }

                assertEquals(1, duplicateResults.count(MessageBusCursorAdvanceResult::advanced))
                assertTrue(duplicateResults.all { it.cursor == 5L })

                supervisorScope {
                    listOf(4L, 9L, 6L, 12L, 8L)
                        .map { candidate ->
                            async { dao.advance(ACCOUNT_42, NOTIFICATION_42, candidate) }
                        }.forEach { it.await() }
                }
                dao.advance(ACCOUNT_84, NOTIFICATION_84, 21L)

                assertEquals(12L, dao.get(ACCOUNT_42, NOTIFICATION_42)?.messageId)
                assertEquals(21L, dao.get(ACCOUNT_84, NOTIFICATION_84)?.messageId)
                assertEquals(1, dao.deleteForAccount(ACCOUNT_42))
                assertNull(dao.get(ACCOUNT_42, NOTIFICATION_42))
                assertEquals(21L, dao.get(ACCOUNT_84, NOTIFICATION_84)?.messageId)
            }
        }

    @Test
    fun notificationCursorSurvivesDatabaseRestart() =
        runTest {
            val directory = Files.createTempDirectory("flaredo-room-cursor-restart-")
            val databasePath = directory.resolve("forum.db")
            try {
                val firstProcess = createJvmFlareDoDatabase(databasePath)
                try {
                    assertEquals(
                        MessageBusCursorAdvanceResult(cursor = 37L, advanced = true),
                        firstProcess.messageBusCursorDao().advance(
                            accountId = ACCOUNT_42,
                            channel = NOTIFICATION_42,
                            messageId = 37L,
                        ),
                    )
                } finally {
                    firstProcess.close()
                }

                val restartedProcess = createJvmFlareDoDatabase(databasePath)
                try {
                    assertEquals(
                        37L,
                        restartedProcess
                            .messageBusCursorDao()
                            .get(ACCOUNT_42, NOTIFICATION_42)
                            ?.messageId,
                    )
                } finally {
                    restartedProcess.close()
                }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun migratesVersionFourDataAndCreatesTheMessageBusCursorTable() =
        runTest {
            val directory = Files.createTempDirectory("flaredo-room-migration-4-5-")
            val databasePath = directory.resolve("forum.db")
            try {
                createVersionFourDatabase(databasePath)
                val database = createJvmFlareDoDatabase(databasePath)
                try {
                    assertEquals(
                        ForumCacheEntryEntity(
                            accountId = ACCOUNT_42,
                            cacheKey = VERSION_FOUR_CACHE_KEY,
                            payload = VERSION_FOUR_CACHE_PAYLOAD,
                            updatedAtEpochMillis = 123L,
                        ),
                        database.forumCacheEntryDao().get(ACCOUNT_42, VERSION_FOUR_CACHE_KEY),
                    )
                    assertEquals(
                        MessageBusCursorAdvanceResult(cursor = 51L, advanced = true),
                        database.messageBusCursorDao().advance(
                            accountId = ACCOUNT_42,
                            channel = NOTIFICATION_42,
                            messageId = 51L,
                        ),
                    )
                } finally {
                    database.close()
                }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    private suspend fun withTemporaryDatabase(block: suspend (FlareDoDatabase) -> Unit) {
        val directory = Files.createTempDirectory("flaredo-room-test-")
        val databasePath = directory.resolve("forum.db")
        val database = createJvmFlareDoDatabase(databasePath)
        try {
            block(database)
            assertTrue(databasePath.exists())
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    /** Creates the exact exported v4 schema without involving the v5 Room constructor. */
    private fun createVersionFourDatabase(path: Path) {
        val connection = BundledSQLiteDriver().open(path.toString())
        try {
            VERSION_FOUR_SCHEMA_SQL.forEach { sql -> connection.executeStatement(sql) }
        } finally {
            connection.close()
        }
    }

    private fun SQLiteConnection.executeStatement(sql: String) {
        val statement = prepare(sql)
        try {
            statement.step()
        } finally {
            statement.close()
        }
    }

    private fun cacheEntry(
        key: String,
        payload: String,
        updatedAt: Long,
    ): ForumCacheEntryEntity =
        ForumCacheEntryEntity(
            accountId = ANONYMOUS_ACCOUNT_ID,
            cacheKey = key,
            payload = payload,
            updatedAtEpochMillis = updatedAt,
        )

    private fun vaultReference(
        reference: String,
        createdAt: Long,
    ): SecureVaultReferenceEntity =
        SecureVaultReferenceEntity(
            slot = PENDING_AUTH_SLOT,
            credentialRef = reference,
            createdAtEpochMillis = createdAt,
            expiresAtEpochMillis = createdAt + 600_000L,
        )

    private fun composerDraft(
        key: String,
        revision: Long,
        updatedAt: Long,
    ): ComposerDraftEntity =
        ComposerDraftEntity(
            accountId = ANONYMOUS_ACCOUNT_ID,
            draftKey = key,
            payload = "payload-$revision",
            revision = revision,
            updatedAtEpochMillis = updatedAt,
        )

    private companion object {
        const val ANONYMOUS_ACCOUNT_ID: String = "anonymous"
        const val ACCOUNT_42: String = "42"
        const val ACCOUNT_84: String = "84"
        const val NOTIFICATION_42: String = "/notification/42"
        const val NOTIFICATION_84: String = "/notification/84"
        const val PENDING_AUTH_SLOT: String = "pending-auth"
        const val VERSION_FOUR_CACHE_KEY: String = "topic:7"
        const val VERSION_FOUR_CACHE_PAYLOAD: String = "v4-cache-payload"

        val VERSION_FOUR_SCHEMA_SQL: List<String> =
            listOf(
                """
                CREATE TABLE IF NOT EXISTS forum_cache_metadata (
                    accountId TEXT NOT NULL,
                    cacheKey TEXT NOT NULL,
                    updatedAtEpochMillis INTEGER NOT NULL,
                    etag TEXT,
                    lastModified TEXT,
                    PRIMARY KEY(accountId, cacheKey)
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS forum_cache_entries (
                    accountId TEXT NOT NULL,
                    cacheKey TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    updatedAtEpochMillis INTEGER NOT NULL,
                    PRIMARY KEY(accountId, cacheKey)
                )
                """.trimIndent(),
                """
                CREATE INDEX IF NOT EXISTS index_forum_cache_entries_accountId_updatedAtEpochMillis
                ON forum_cache_entries (accountId, updatedAtEpochMillis)
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS composer_drafts (
                    accountId TEXT NOT NULL,
                    draftKey TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    revision INTEGER NOT NULL,
                    updatedAtEpochMillis INTEGER NOT NULL,
                    PRIMARY KEY(accountId, draftKey)
                )
                """.trimIndent(),
                """
                CREATE INDEX IF NOT EXISTS index_composer_drafts_accountId_updatedAtEpochMillis
                ON composer_drafts (accountId, updatedAtEpochMillis)
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS secure_vault_references (
                    slot TEXT NOT NULL,
                    credentialRef TEXT NOT NULL,
                    relatedCredentialRef TEXT,
                    accountId TEXT,
                    username TEXT,
                    createdAtEpochMillis INTEGER NOT NULL,
                    expiresAtEpochMillis INTEGER,
                    PRIMARY KEY(slot)
                )
                """.trimIndent(),
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
                """
                INSERT OR REPLACE INTO room_master_table (id, identity_hash)
                VALUES(42, 'b7ea9e3c063847051f23575f321c41ee')
                """.trimIndent(),
                "PRAGMA user_version = 4",
                """
                INSERT INTO forum_cache_entries (accountId, cacheKey, payload, updatedAtEpochMillis)
                VALUES('42', 'topic:7', 'v4-cache-payload', 123)
                """.trimIndent(),
            )
    }
}
