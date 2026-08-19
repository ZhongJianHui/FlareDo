package dev.dimension.flare.data.network.discourse.paging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class DiscourseTopicStreamPagerTest {
    @Test
    fun exactBatchesPreserveServerOrderAcrossEveryPostId() {
        val serverStream = (1L..45L).toList()
        val pager = DiscourseTopicStreamPager(serverStream)
        val batches = mutableListOf<List<Long>>()
        var cursor = DiscourseTopicStreamCursor.Initial

        do {
            val batch = pager.batch(cursor)
            batches += batch.postIds
            cursor = batch.nextCursor
        } while (batch.hasMore)

        assertEquals(listOf(20, 20, 5), batches.map(List<Long>::size))
        assertEquals(serverStream, batches.flatten())
        assertEquals(serverStream.size, cursor.nextIndex)
    }

    @Test
    fun duplicateIdsAreRemovedWithoutReorderingTheStream() {
        val pager =
            DiscourseTopicStreamPager(
                streamPostIds = listOf(91L, 12L, 91L, 44L, 12L, 3L),
                batchSize = 2,
            )

        val first = pager.batch()
        val second = pager.batch(first.nextCursor)

        assertEquals(listOf(91L, 12L), first.postIds)
        assertTrue(first.hasMore)
        assertEquals(listOf(44L, 3L), second.postIds)
        assertFalse(second.hasMore)
        assertEquals(listOf(91L, 12L, 44L, 3L), pager.postIds)
    }

    @Test
    fun restoredCursorPastTheEndReturnsAClampedTerminalBatch() {
        val pager = DiscourseTopicStreamPager(listOf(5L, 6L, 7L))

        val batch = pager.batch(DiscourseTopicStreamCursor(10_000))

        assertTrue(batch.postIds.isEmpty())
        assertEquals(3, batch.nextCursor.nextIndex)
        assertFalse(batch.hasMore)
    }

    @Test
    fun emptyStreamHasOneEmptyTerminalBatch() {
        val batch = DiscourseTopicStreamPager(emptyList()).batch()

        assertTrue(batch.postIds.isEmpty())
        assertEquals(0, batch.nextCursor.nextIndex)
        assertFalse(batch.hasMore)
    }

    @Test
    fun invalidIdsBatchSizesAndCursorsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            DiscourseTopicStreamPager(listOf(1L, 0L, 2L))
        }
        assertFailsWith<IllegalArgumentException> {
            DiscourseTopicStreamPager(listOf(1L), batchSize = 0)
        }
        assertFailsWith<IllegalArgumentException> { DiscourseTopicStreamCursor(-1) }
    }
}
