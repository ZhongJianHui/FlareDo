package dev.dimension.flare.data.network.discourse.realtime

import dev.dimension.flare.data.database.MessageBusCursorDao
import dev.dimension.flare.data.database.MessageBusCursorEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscourseMessageBusCursorStoreTest {
    @Test
    fun memoryCursorAdvancesMonotonicallyAndReportsTheUniqueWinner() =
        runTest {
            val store = MemoryDiscourseMessageBusCursorStore()

            assertEquals(
                DiscourseMessageBusCursorAdvance(cursor = 5L, advanced = true),
                store.advance(ACCOUNT_42, LATEST, 5L),
            )
            assertEquals(
                DiscourseMessageBusCursorAdvance(cursor = 5L, advanced = false),
                store.advance(ACCOUNT_42, LATEST, 5L),
            )
            assertEquals(
                DiscourseMessageBusCursorAdvance(cursor = 5L, advanced = false),
                store.advance(ACCOUNT_42, LATEST, 3L),
            )
            assertEquals(
                DiscourseMessageBusCursorAdvance(cursor = 8L, advanced = true),
                store.advance(ACCOUNT_42, LATEST, 8L),
            )
            assertEquals(8L, store.read(ACCOUNT_42, LATEST))
        }

    @Test
    fun concurrentDuplicateHasExactlyOneWinner() =
        runTest {
            val store = MemoryDiscourseMessageBusCursorStore()
            val results =
                supervisorScope {
                    List(32) {
                        async { store.advance(ACCOUNT_42, NOTIFICATION_42, 9L) }
                    }.map { it.await() }
                }

            assertEquals(1, results.count(DiscourseMessageBusCursorAdvance::advanced))
            assertTrue(results.all { it.cursor == 9L })
        }

    @Test
    fun accountAndChannelPartitionsDoNotLeakAndClearIsAccountScoped() =
        runTest {
            val store = MemoryDiscourseMessageBusCursorStore()

            store.advance(ACCOUNT_42, LATEST, 10L)
            store.advance(ACCOUNT_42, TOPIC_REACTIONS_7, 20L)
            store.advance(ACCOUNT_84, LATEST, 30L)
            store.clearAccount(ACCOUNT_42)

            assertNull(store.read(ACCOUNT_42, LATEST))
            assertNull(store.read(ACCOUNT_42, TOPIC_REACTIONS_7))
            assertEquals(30L, store.read(ACCOUNT_84, LATEST))
        }

    @Test
    fun topicLruEvictsTheOldestPairAndKeepsRecentlyReadAndFixedChannels() =
        runTest {
            val store =
                MemoryDiscourseMessageBusCursorStore(
                    maxTopicsPerAccount = 2,
                    maxTopicsGlobally = 4,
                )
            store.advance(ACCOUNT_42, LATEST, 50L)
            store.advance(ACCOUNT_42, "/topic/1", 1L)
            store.advance(ACCOUNT_42, "/topic/1/reactions", 2L)
            store.advance(ACCOUNT_42, "/topic/2", 3L)
            store.advance(ACCOUNT_42, "/topic/2/reactions", 4L)

            // A read is an access for LRU purposes, so topic 2 becomes the eviction victim.
            assertEquals(1L, store.read(ACCOUNT_42, "/topic/1"))
            store.advance(ACCOUNT_42, "/topic/3", 5L)
            store.advance(ACCOUNT_42, "/topic/3/reactions", 6L)

            assertEquals(1L, store.read(ACCOUNT_42, "/topic/1"))
            assertEquals(2L, store.read(ACCOUNT_42, "/topic/1/reactions"))
            assertNull(store.read(ACCOUNT_42, "/topic/2"))
            assertNull(store.read(ACCOUNT_42, "/topic/2/reactions"))
            assertEquals(5L, store.read(ACCOUNT_42, "/topic/3"))
            assertEquals(6L, store.read(ACCOUNT_42, "/topic/3/reactions"))
            assertEquals(50L, store.read(ACCOUNT_42, LATEST))
        }

    @Test
    fun topicLruAppliesPerAccountAndGlobalBoundsWithoutCrossAccountPairDeletion() =
        runTest {
            val perAccountStore =
                MemoryDiscourseMessageBusCursorStore(
                    maxTopicsPerAccount = 1,
                    maxTopicsGlobally = 4,
                )
            perAccountStore.advance(ACCOUNT_42, "/topic/1", 1L)
            perAccountStore.advance(ACCOUNT_84, "/topic/8", 8L)
            perAccountStore.advance(ACCOUNT_42, "/topic/2", 2L)

            assertNull(perAccountStore.read(ACCOUNT_42, "/topic/1"))
            assertEquals(2L, perAccountStore.read(ACCOUNT_42, "/topic/2"))
            assertEquals(8L, perAccountStore.read(ACCOUNT_84, "/topic/8"))

            val globalStore =
                MemoryDiscourseMessageBusCursorStore(
                    maxTopicsPerAccount = 4,
                    maxTopicsGlobally = 2,
                )
            globalStore.advance(ACCOUNT_42, "/topic/1", 1L)
            globalStore.advance(ACCOUNT_84, "/topic/8", 8L)
            globalStore.advance(ACCOUNT_42, "/topic/2", 2L)

            assertNull(globalStore.read(ACCOUNT_42, "/topic/1"))
            assertEquals(8L, globalStore.read(ACCOUNT_84, "/topic/8"))
            assertEquals(2L, globalStore.read(ACCOUNT_42, "/topic/2"))
        }

    @Test
    fun unknownCrossAccountAndInvalidIdentifiersFailClosed() =
        runTest {
            val store = MemoryDiscourseMessageBusCursorStore()

            assertFailsWith<IllegalArgumentException> {
                store.advance(ACCOUNT_42, "/arbitrary/plugin/channel", 1L)
            }
            assertFailsWith<IllegalArgumentException> {
                store.advance(ACCOUNT_42, "/notification/84", 1L)
            }
            assertFailsWith<IllegalArgumentException> {
                store.advance(ACCOUNT_42, "/topic/0", 1L)
            }
            assertFailsWith<IllegalArgumentException> {
                store.advance(ACCOUNT_42, "/topic/9999999999999999999", 1L)
            }
            assertFailsWith<IllegalArgumentException> {
                store.advance(ACCOUNT_42, LATEST, -1L)
            }
            assertEquals(
                DiscourseMessageBusCursorAdvance(cursor = 0L, advanced = true),
                store.advance(ACCOUNT_42, NEW, 0L),
            )
        }

    @Test
    fun roomPolicyPersistsOnlyTheMatchingNotificationChannel() =
        runTest {
            val dao = FakeMessageBusCursorDao()
            val firstProcess = roomDiscourseMessageBusCursorStore(dao)

            firstProcess.advance(ACCOUNT_42, NOTIFICATION_42, 11L)
            firstProcess.advance(ACCOUNT_42, LATEST, 12L)
            assertEquals(1, dao.rowCount)

            val restartedProcess = roomDiscourseMessageBusCursorStore(dao)
            assertEquals(11L, restartedProcess.read(ACCOUNT_42, NOTIFICATION_42))
            assertNull(restartedProcess.read(ACCOUNT_42, LATEST))
        }

    @Test
    fun roomPolicyClearsDurableAndVolatilePartitionsForOnlyOneAccount() =
        runTest {
            val dao = FakeMessageBusCursorDao()
            val store = roomDiscourseMessageBusCursorStore(dao)

            store.advance(ACCOUNT_42, NOTIFICATION_42, 11L)
            store.advance(ACCOUNT_42, LATEST, 12L)
            store.advance(ACCOUNT_84, NOTIFICATION_84, 21L)
            store.advance(ACCOUNT_84, LATEST, 22L)
            store.clearAccount(ACCOUNT_42)

            assertNull(store.read(ACCOUNT_42, NOTIFICATION_42))
            assertNull(store.read(ACCOUNT_42, LATEST))
            assertEquals(21L, store.read(ACCOUNT_84, NOTIFICATION_84))
            assertEquals(22L, store.read(ACCOUNT_84, LATEST))
        }

    private companion object {
        const val ACCOUNT_42: String = "42"
        const val ACCOUNT_84: String = "84"
        const val LATEST: String = "/latest"
        const val NEW: String = "/new"
        const val NOTIFICATION_42: String = "/notification/42"
        const val NOTIFICATION_84: String = "/notification/84"
        const val TOPIC_REACTIONS_7: String = "/topic/7/reactions"
    }
}

private class FakeMessageBusCursorDao : MessageBusCursorDao {
    private val rows: MutableMap<Pair<String, String>, MessageBusCursorEntity> = mutableMapOf()

    val rowCount: Int
        get() = rows.size

    override suspend fun get(
        accountId: String,
        channel: String,
    ): MessageBusCursorEntity? = rows[accountId to channel]

    override suspend fun insertIfAbsent(entity: MessageBusCursorEntity): Long {
        val identity = entity.accountId to entity.channel
        if (identity in rows) return -1L
        rows[identity] = entity
        return 1L
    }

    override suspend fun updateIfNewer(
        accountId: String,
        channel: String,
        messageId: Long,
    ): Int {
        val identity = accountId to channel
        val current = rows[identity] ?: return 0
        if (current.messageId >= messageId) return 0
        rows[identity] = current.copy(messageId = messageId)
        return 1
    }

    override suspend fun deleteForAccount(accountId: String): Int {
        val before = rows.size
        rows.keys.removeAll { it.first == accountId }
        return before - rows.size
    }
}
