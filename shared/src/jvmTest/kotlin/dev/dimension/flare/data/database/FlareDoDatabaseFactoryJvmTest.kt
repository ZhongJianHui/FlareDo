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

    private companion object {
        const val ANONYMOUS_ACCOUNT_ID: String = "anonymous"
    }
}
