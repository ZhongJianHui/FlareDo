package dev.dimension.flare.data.network.discourse.forum

import dev.dimension.flare.data.database.ForumCacheEntryDao
import dev.dimension.flare.data.database.ForumCacheEntryEntity
import dev.dimension.flare.data.network.discourse.model.discourseJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class DiscourseForumCacheTest {
    @Test
    fun memoryCacheSerializesConcurrentWritesAndKeepsNewestBoundedEntries() =
        runTest {
            val cache = MemoryDiscourseForumCache(maxEntries = 8)

            (1L..40L)
                .map { topicId -> async { cache.putTopic(forumTopic(topicId)) } }
                .awaitAll()

            assertNull(cache.getTopic(1L))
            assertNotNull(cache.getTopic(40L))
        }

    @Test
    fun staleSnapshotCannotOverwriteLastFreshValue() =
        runTest {
            val cache = MemoryDiscourseForumCache()
            val fresh = forumTopic(topicId = 7L, updatedAtEpochMillis = 10L)
            cache.putTopic(fresh)

            assertFailsWith<IllegalArgumentException> {
                cache.putTopic(
                    fresh.copy(
                        source = DiscourseForumContentSource.StaleCache,
                        fallbackFailure = DiscourseForumFailureKind.Network,
                    ),
                )
            }

            assertEquals(fresh, cache.getTopic(7L))
        }

    @Test
    fun roomCacheSurvivesAdapterRecreationAndPrunesAtomically() =
        runTest {
            val dao = FakeForumCacheEntryDao()
            val writer = RoomDiscourseForumCache(dao, maxEntries = 2)
            writer.putTopic(forumTopic(1L, updatedAtEpochMillis = 1L))
            writer.putTopic(forumTopic(2L, updatedAtEpochMillis = 2L))
            writer.putTopic(forumTopic(3L, updatedAtEpochMillis = 3L))

            val reader = RoomDiscourseForumCache(dao, maxEntries = 2)

            assertEquals(2, dao.countForAccount(ANONYMOUS_FORUM_CACHE_ACCOUNT_ID))
            assertNull(reader.getTopic(1L))
            assertEquals(3L, reader.getTopic(3L)?.topicId)
        }

    @Test
    fun roomCacheDeletesCorruptOversizedAndMismatchedRows() =
        runTest {
            val dao = FakeForumCacheEntryDao()
            val cache = RoomDiscourseForumCache(dao)

            dao.upsert(rawEntry("categories", "{"))
            assertNull(cache.getCategories())
            assertNull(dao.get(ANONYMOUS_FORUM_CACHE_ACCOUNT_ID, "categories"))

            dao.upsert(rawEntry("categories", "x".repeat(2_000_001)))
            assertNull(cache.getCategories())
            assertNull(dao.get(ANONYMOUS_FORUM_CACHE_ACCOUNT_ID, "categories"))

            dao.upsert(
                rawEntry(
                    cacheKey = "topic:99",
                    payload = discourseJson.encodeToString(forumTopic(topicId = 1L)),
                ),
            )
            assertNull(cache.getTopic(99L))
            assertNull(dao.get(ANONYMOUS_FORUM_CACHE_ACCOUNT_ID, "topic:99"))
        }
}

private fun rawEntry(
    cacheKey: String,
    payload: String,
): ForumCacheEntryEntity =
    ForumCacheEntryEntity(
        accountId = ANONYMOUS_FORUM_CACHE_ACCOUNT_ID,
        cacheKey = cacheKey,
        payload = payload,
        updatedAtEpochMillis = 1L,
    )

private class FakeForumCacheEntryDao : ForumCacheEntryDao {
    private val mutex = Mutex()
    private val entries = mutableMapOf<Pair<String, String>, ForumCacheEntryEntity>()

    override suspend fun get(
        accountId: String,
        cacheKey: String,
    ): ForumCacheEntryEntity? = mutex.withLock { entries[accountId to cacheKey] }

    override suspend fun upsert(entity: ForumCacheEntryEntity) {
        mutex.withLock { entries[entity.accountId to entity.cacheKey] = entity }
    }

    override suspend fun delete(
        accountId: String,
        cacheKey: String,
    ) {
        mutex.withLock { entries.remove(accountId to cacheKey) }
    }

    override suspend fun deleteIfPayloadMatches(
        accountId: String,
        cacheKey: String,
        observedPayload: String,
    ) {
        mutex.withLock {
            val key = accountId to cacheKey
            if (entries[key]?.payload == observedPayload) entries.remove(key)
        }
    }

    override suspend fun deleteForAccount(accountId: String) {
        mutex.withLock { entries.keys.removeAll { it.first == accountId } }
    }

    override suspend fun pruneToNewest(
        accountId: String,
        maxEntries: Int,
    ) {
        mutex.withLock {
            val retained =
                entries.values
                    .filter { it.accountId == accountId }
                    .sortedWith(
                        compareByDescending<ForumCacheEntryEntity> { it.updatedAtEpochMillis }
                            .thenByDescending { it.cacheKey },
                    ).take(maxEntries)
                    .map { it.accountId to it.cacheKey }
                    .toSet()
            entries.keys.removeAll { it.first == accountId && it !in retained }
        }
    }

    override suspend fun countForAccount(accountId: String): Int = mutex.withLock { entries.values.count { it.accountId == accountId } }
}
