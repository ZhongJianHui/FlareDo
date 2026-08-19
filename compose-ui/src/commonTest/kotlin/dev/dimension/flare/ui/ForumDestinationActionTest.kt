package dev.dimension.flare.ui

import dev.dimension.flare.data.network.discourse.forum.DiscourseForumAction
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumCategoryOption
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFailureKind
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFeed
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class ForumDestinationActionTest {
    @Test
    fun failedTaxonomyCanBeRetriedFromCompactAndRailNavigation() {
        val failedState =
            DiscourseForumState(
                isTaxonomyLoading = false,
                taxonomyFailure = DiscourseForumFailureKind.Network,
            )

        assertEquals(
            DiscourseForumAction.RetryTaxonomy,
            ForumRootDestination.Categories.navigationAction(failedState),
        )
        assertEquals(
            DiscourseForumAction.RetryTaxonomy,
            ForumRootDestination.Tags.navigationAction(failedState),
        )
    }

    @Test
    fun loadedTaxonomySelectsItsFirstFeedAndCurrentDestinationIsInert() {
        val category =
            DiscourseForumCategoryOption(
                id = 7L,
                name = "Development",
                slug = "development",
            )
        val loadedState =
            DiscourseForumState(
                categories = listOf(category),
                isTaxonomyLoading = false,
            )

        assertEquals(
            DiscourseForumAction.SelectFeed(category.asForumFeed()),
            ForumRootDestination.Categories.navigationAction(loadedState),
        )
        assertNull(
            ForumRootDestination.Latest.navigationAction(
                loadedState.copy(selection = DiscourseForumFeed.Latest),
            ),
        )
    }

    @Test
    fun loadingOrValidEmptyTaxonomyDoesNotCreateARetryLoop() {
        assertNull(
            ForumRootDestination.Categories.navigationAction(
                DiscourseForumState(
                    isTaxonomyLoading = true,
                    taxonomyFailure = DiscourseForumFailureKind.Network,
                ),
            ),
        )
        assertNull(
            ForumRootDestination.Tags.navigationAction(
                DiscourseForumState(
                    isTaxonomyLoading = false,
                    taxonomyFailure = null,
                ),
            ),
        )
    }
}
