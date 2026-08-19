package dev.dimension.flare.data.network.discourse.paging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

internal class DiscoursePaginationTest {
    @Test
    fun topicListsStartAtZeroAndOmitOnlyTheInitialPage() {
        val initial = DiscourseListPage.Initial

        assertEquals(0, initial.value)
        assertNull(initial.queryValueOrNull())
        assertEquals(1, initial.next().queryValueOrNull())
        assertFailsWith<IllegalArgumentException> { DiscourseListPage(-1) }
    }

    @Test
    fun searchStartsAtOneAndOmitOnlyTheInitialPage() {
        val initial = DiscourseSearchPage.Initial

        assertEquals(1, initial.value)
        assertNull(initial.queryValueOrNull())
        assertEquals(2, initial.next().queryValueOrNull())
        assertFailsWith<IllegalArgumentException> { DiscourseSearchPage(0) }
    }

    @Test
    fun notificationOffsetAdvancesByAcceptedRows() {
        val initial = DiscourseNotificationOffset.Initial

        assertNull(initial.queryValueOrNull())
        assertEquals(60, initial.advanceBy(60).queryValueOrNull())
        assertEquals(initial, initial.advanceBy(0))
        assertFailsWith<IllegalArgumentException> { initial.advanceBy(-1) }
    }

    @Test
    fun allCursorsRejectIntegerOverflow() {
        assertFailsWith<IllegalStateException> { DiscourseListPage(Int.MAX_VALUE).next() }
        assertFailsWith<IllegalStateException> { DiscourseSearchPage(Int.MAX_VALUE).next() }
        assertFailsWith<IllegalStateException> {
            DiscourseNotificationOffset(Int.MAX_VALUE).advanceBy(1)
        }
    }
}
