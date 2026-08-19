package dev.dimension.flare.ui

import dev.dimension.flare.data.network.discourse.forum.DiscourseForumAction
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumCategoryOption
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumDestination
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFeed
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class ForumDestinationActionTest {
    @Test
    fun switchingWorkspaceDestinationDispatchesOneSemanticAction() {
        assertEquals(
            DiscourseForumAction.SelectDestination(DiscourseForumDestination.Search),
            DiscourseForumDestination.Search.navigationAction(DiscourseForumState()),
        )
        assertEquals(
            DiscourseForumAction.SelectDestination(DiscourseForumDestination.Notifications),
            DiscourseForumDestination.Notifications.navigationAction(DiscourseForumState()),
        )
    }

    @Test
    fun currentDestinationIsInert() {
        DiscourseForumDestination.entries.forEach { destination ->
            assertNull(
                destination.navigationAction(DiscourseForumState(destination = destination)),
            )
        }
    }

    @Test
    fun taxonomyOptionsRemainExplicitFeedSelections() {
        val category =
            DiscourseForumCategoryOption(
                id = 7L,
                name = "Development",
                slug = "development",
            )
        assertEquals(
            DiscourseForumFeed.Category(
                id = 7L,
                slug = "development",
                name = "Development",
            ),
            category.asForumFeed(),
        )
    }
}
