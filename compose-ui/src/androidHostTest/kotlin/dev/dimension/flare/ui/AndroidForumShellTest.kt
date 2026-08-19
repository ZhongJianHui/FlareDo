package dev.dimension.flare.ui

import dev.dimension.flare.data.network.discourse.forum.DiscourseForumAction
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFeed
import kotlin.test.Test
import kotlin.test.assertEquals

internal class AndroidForumShellTest {
    @Test
    fun compactRootContainsOnlyTheSelectedFeed() {
        assertEquals(
            listOf(ForumFeedRoute(DiscourseForumFeed.Latest)),
            forumRoutesFor(
                feed = DiscourseForumFeed.Latest,
                selectedTopicId = null,
                layoutClass = ForumLayoutClass.Compact,
            ),
        )
    }

    @Test
    fun mediumAndExpandedRootsUseStaticPlaceholderPanes() {
        assertEquals(
            listOf(ForumFeedRoute(DiscourseForumFeed.Latest), ForumEmptyDetailRoute),
            forumRoutesFor(
                feed = DiscourseForumFeed.Latest,
                selectedTopicId = null,
                layoutClass = ForumLayoutClass.Medium,
            ),
        )
        assertEquals(
            listOf(
                ForumFeedRoute(DiscourseForumFeed.Latest),
                ForumSupportingRoute,
                ForumEmptyDetailRoute,
            ),
            forumRoutesFor(
                feed = DiscourseForumFeed.Latest,
                selectedTopicId = null,
                layoutClass = ForumLayoutClass.Expanded,
            ),
        )
    }

    @Test
    fun expandedTopicKeepsSupportingPaneOutOfUserHistory() {
        assertEquals(
            listOf(
                ForumFeedRoute(DiscourseForumFeed.Hot),
                ForumSupportingRoute,
                ForumTopicRoute(42L),
            ),
            forumRoutesFor(
                feed = DiscourseForumFeed.Hot,
                selectedTopicId = 42L,
                layoutClass = ForumLayoutClass.Expanded,
            ),
        )
    }

    @Test
    fun restoredRoutesRehydrateFeedBeforeTopic() {
        val restoredFeed =
            DiscourseForumFeed.Category(
                id = 7L,
                slug = "development",
                name = "Development",
            )

        assertEquals(
            listOf(
                DiscourseForumAction.SelectFeed(restoredFeed),
                DiscourseForumAction.OpenTopic(42L),
            ),
            restoredForumActions(
                state = ForumPreviewFixtures.loaded(withSelectedTopic = false),
                restoredFeed = restoredFeed,
                restoredTopicId = 42L,
            ),
        )
    }

    @Test
    fun restoredRootClosesPresenterTopicAndFeedSwitchReplacesRoutes() {
        assertEquals(
            listOf(DiscourseForumAction.CloseTopic),
            restoredForumActions(
                state = ForumPreviewFixtures.loaded(withSelectedTopic = true),
                restoredFeed = DiscourseForumFeed.Latest,
                restoredTopicId = null,
            ),
        )

        val switchedFeed = DiscourseForumFeed.Tag(name = "kotlin")
        assertEquals(
            listOf(ForumFeedRoute(switchedFeed), ForumSupportingRoute, ForumEmptyDetailRoute),
            forumRoutesFor(
                feed = switchedFeed,
                selectedTopicId = null,
                layoutClass = ForumLayoutClass.Expanded,
            ),
        )
    }

    @Test
    fun matchingRestoredStateDoesNotDispatchDuplicateActions() {
        assertEquals(
            emptyList(),
            restoredForumActions(
                state = ForumPreviewFixtures.loaded(withSelectedTopic = true),
                restoredFeed = DiscourseForumFeed.Latest,
                restoredTopicId = 4_102L,
            ),
        )
    }
}
