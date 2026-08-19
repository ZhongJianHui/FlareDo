package dev.dimension.flare.ui

import dev.dimension.flare.data.network.discourse.forum.DiscourseForumAction
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumDestination
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class AndroidForumShellTest {
    @Test
    fun compactRootContainsOnlyTheSelectedFeed() {
        assertEquals(
            listOf(
                ForumFeedRoute(
                    DiscourseForumFeed.Latest,
                    DiscourseForumDestination.Latest,
                ),
            ),
            forumRoutesFor(
                destination = DiscourseForumDestination.Latest,
                feed = DiscourseForumFeed.Latest,
                selectedTopicId = null,
                selectedPostNumber = null,
                layoutClass = ForumLayoutClass.Compact,
            ),
        )
    }

    @Test
    fun mediumAndExpandedRootsUseStaticPlaceholderPanes() {
        assertEquals(
            listOf(
                ForumFeedRoute(
                    DiscourseForumFeed.Latest,
                    DiscourseForumDestination.Latest,
                ),
                ForumEmptyDetailRoute,
            ),
            forumRoutesFor(
                destination = DiscourseForumDestination.Latest,
                feed = DiscourseForumFeed.Latest,
                selectedTopicId = null,
                selectedPostNumber = null,
                layoutClass = ForumLayoutClass.Medium,
            ),
        )
        assertEquals(
            listOf(
                ForumFeedRoute(
                    DiscourseForumFeed.Latest,
                    DiscourseForumDestination.Latest,
                ),
                ForumSupportingRoute,
                ForumEmptyDetailRoute,
            ),
            forumRoutesFor(
                destination = DiscourseForumDestination.Latest,
                feed = DiscourseForumFeed.Latest,
                selectedTopicId = null,
                selectedPostNumber = null,
                layoutClass = ForumLayoutClass.Expanded,
            ),
        )
    }

    @Test
    fun expandedTopicKeepsSupportingPaneOutOfUserHistory() {
        assertEquals(
            listOf(
                ForumFeedRoute(
                    DiscourseForumFeed.Hot,
                    DiscourseForumDestination.Hot,
                ),
                ForumSupportingRoute,
                ForumTopicRoute(42L, 7),
            ),
            forumRoutesFor(
                destination = DiscourseForumDestination.Hot,
                feed = DiscourseForumFeed.Hot,
                selectedTopicId = 42L,
                selectedPostNumber = 7,
                layoutClass = ForumLayoutClass.Expanded,
            ),
        )
    }

    @Test
    fun compactSearchRetainsThePreviouslySelectedTaxonomyFeed() {
        val selectedFeed = DiscourseForumFeed.Tag(name = "kotlin")

        assertEquals(
            listOf(ForumFeedRoute(selectedFeed, DiscourseForumDestination.Search)),
            forumRoutesFor(
                destination = DiscourseForumDestination.Search,
                feed = selectedFeed,
                selectedTopicId = null,
                selectedPostNumber = null,
                layoutClass = ForumLayoutClass.Compact,
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
                DiscourseForumAction.OpenTopic(42L, 3),
            ),
            restoredForumActions(
                state = ForumPreviewFixtures.loaded(withSelectedTopic = false),
                restoredDestination = DiscourseForumDestination.Latest,
                restoredFeed = restoredFeed,
                restoredTopicId = 42L,
                restoredPostNumber = 3,
            ),
        )
    }

    @Test
    fun restoredRootClosesPresenterTopicAndFeedSwitchReplacesRoutes() {
        assertEquals(
            listOf(DiscourseForumAction.CloseTopic),
            restoredForumActions(
                state = ForumPreviewFixtures.loaded(withSelectedTopic = true),
                restoredDestination = DiscourseForumDestination.Latest,
                restoredFeed = DiscourseForumFeed.Latest,
                restoredTopicId = null,
                restoredPostNumber = null,
            ),
        )

        val switchedFeed = DiscourseForumFeed.Tag(name = "kotlin")
        assertEquals(
            listOf(
                ForumFeedRoute(switchedFeed, DiscourseForumDestination.Latest),
                ForumSupportingRoute,
                ForumEmptyDetailRoute,
            ),
            forumRoutesFor(
                destination = DiscourseForumDestination.Latest,
                feed = switchedFeed,
                selectedTopicId = null,
                selectedPostNumber = null,
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
                restoredDestination = DiscourseForumDestination.Latest,
                restoredFeed = DiscourseForumFeed.Latest,
                restoredTopicId = 4_102L,
                restoredPostNumber = null,
            ),
        )
    }

    @Test
    fun restoringSearchAfterTaxonomyReappliesDestinationAfterFeed() {
        val restoredFeed = DiscourseForumFeed.Tag(name = "kotlin")

        assertEquals(
            listOf(
                DiscourseForumAction.SelectFeed(restoredFeed),
                DiscourseForumAction.SelectDestination(DiscourseForumDestination.Search),
            ),
            restoredForumActions(
                state = ForumPreviewFixtures.loaded(withSelectedTopic = false),
                restoredDestination = DiscourseForumDestination.Search,
                restoredFeed = restoredFeed,
                restoredTopicId = null,
                restoredPostNumber = null,
            ),
        )
    }

    @Test
    fun postNumberCannotExistWithoutAValidTopicRoute() {
        assertFailsWith<IllegalArgumentException> { ForumTopicRoute(topicId = 0L) }
        assertFailsWith<IllegalArgumentException> { ForumTopicRoute(topicId = 42L, postNumber = 0) }
        assertFailsWith<IllegalArgumentException> {
            forumRoutesFor(
                destination = DiscourseForumDestination.Search,
                feed = DiscourseForumFeed.Latest,
                selectedTopicId = null,
                selectedPostNumber = 3,
                layoutClass = ForumLayoutClass.Compact,
            )
        }
    }
}
