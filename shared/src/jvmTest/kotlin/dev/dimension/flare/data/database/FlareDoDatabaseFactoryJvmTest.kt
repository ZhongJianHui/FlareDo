package dev.dimension.flare.data.database

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
        const val PENDING_AUTH_SLOT: String = "pending-auth"
    }
}
