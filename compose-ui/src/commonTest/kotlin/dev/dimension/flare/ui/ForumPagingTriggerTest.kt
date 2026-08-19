package dev.dimension.flare.ui

import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFailureKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class ForumPagingTriggerTest {
    @Test
    fun requestsNextPageWhenListTailBecomesVisible() {
        assertTrue(
            shouldAutomaticallyLoadNextPage(
                lastVisibleIndex = 7,
                topicCount = 10,
                nextPage = 2,
                isAppending = false,
                failure = null,
            ),
        )
    }

    @Test
    fun doesNotAutomaticallyRetryAnAppendFailure() {
        assertFalse(
            shouldAutomaticallyLoadNextPage(
                lastVisibleIndex = 9,
                topicCount = 10,
                nextPage = 2,
                isAppending = false,
                failure = DiscourseForumFailureKind.Network,
            ),
        )
    }

    @Test
    fun ignoresIncompleteOrAlreadyRunningPagingStates() {
        assertFalse(
            shouldAutomaticallyLoadNextPage(
                lastVisibleIndex = 9,
                topicCount = 10,
                nextPage = null,
                isAppending = false,
                failure = null,
            ),
        )
        assertFalse(
            shouldAutomaticallyLoadNextPage(
                lastVisibleIndex = 9,
                topicCount = 10,
                nextPage = 2,
                isAppending = true,
                failure = null,
            ),
        )
        assertFalse(
            shouldAutomaticallyLoadNextPage(
                lastVisibleIndex = -1,
                topicCount = 0,
                nextPage = 1,
                isAppending = false,
                failure = null,
            ),
        )
    }
}
