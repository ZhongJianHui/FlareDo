package dev.dimension.flare.data.network.discourse.forum

import dev.dimension.flare.data.network.discourse.error.DiscourseNetworkException
import dev.dimension.flare.data.network.discourse.error.DiscourseNetworkFailureKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class DiscourseForumPresenterTest {
    @Test
    fun appendIsSingleFlightAndDeduplicatesAnOverlappingPage() =
        runTest {
            val append = CompletableDeferred<DiscourseForumFeedPage>()
            val repository = FakeForumRepository()
            repository.feedHandler = { feed, page ->
                when (page) {
                    0 -> forumFeedPage(feed, page, listOf(1L, 2L), nextPage = 1)
                    1 -> append.await()
                    else -> error("Unexpected page")
                }
            }
            val presenter =
                DiscourseForumPresenter(
                    repository = repository,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertEquals(listOf(1L, 2L), models.value.topics.topicIds())

                assertTrue(presenter.dispatch(DiscourseForumAction.LoadNextPage))
                runCurrent()
                assertTrue(models.value.isAppending)
                assertTrue(presenter.dispatch(DiscourseForumAction.LoadNextPage))
                runCurrent()
                assertEquals(1, repository.feedCalls.count { it.second == 1 })

                append.complete(
                    forumFeedPage(
                        page = 1,
                        topicIds = listOf(2L, 3L),
                        nextPage = null,
                    ),
                )
                advanceUntilIdle()

                assertEquals(listOf(1L, 2L, 3L), models.value.topics.topicIds())
                assertFalse(models.value.isAppending)
                assertFalse(models.value.hasMore)
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun refreshSuppressesAnOlderSameFeedResultThatFinishesLast() =
        runTest {
            val oldRequest = CompletableDeferred<DiscourseForumFeedPage>()
            val repository = FakeForumRepository()
            var call = 0
            repository.feedHandler = { feed, _ ->
                call += 1
                if (call == 1) {
                    // Simulates a platform call that finishes despite cancellation. The presenter
                    // still must reject it by request generation.
                    withContext(NonCancellable) { oldRequest.await() }
                } else {
                    forumFeedPage(feed = feed, topicIds = listOf(2L))
                }
            }
            val presenter =
                DiscourseForumPresenter(
                    repository = repository,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                runCurrent()
                assertTrue(presenter.dispatch(DiscourseForumAction.Refresh))
                runCurrent()
                assertEquals(listOf(2L), models.value.topics.topicIds())

                oldRequest.complete(forumFeedPage(topicIds = listOf(1L)))
                advanceUntilIdle()

                assertEquals(listOf(2L), models.value.topics.topicIds())
                assertEquals(2, repository.feedCalls.size)
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun openingTopicBPreventsLateTopicAFromReplacingIt() =
        runTest {
            val topicA = CompletableDeferred<DiscourseForumTopic>()
            val repository = FakeForumRepository()
            repository.topicHandler = { topicId ->
                if (topicId == 1L) {
                    withContext(NonCancellable) { topicA.await() }
                } else {
                    forumTopic(topicId)
                }
            }
            val presenter =
                DiscourseForumPresenter(
                    repository = repository,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertTrue(presenter.dispatch(DiscourseForumAction.OpenTopic(1L)))
                runCurrent()
                assertTrue(presenter.dispatch(DiscourseForumAction.OpenTopic(2L)))
                runCurrent()

                assertEquals(2L, models.value.selectedTopicId)
                assertEquals(2L, models.value.selectedTopic?.topicId)

                topicA.complete(forumTopic(1L))
                advanceUntilIdle()

                assertEquals(2L, models.value.selectedTopicId)
                assertEquals(2L, models.value.selectedTopic?.topicId)
                assertEquals(listOf(1L, 2L), repository.topicCalls)
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun staleSourceAndFailureMarkerRemainVisibleAfterSuccessfulFallback() =
        runTest {
            val repository = FakeForumRepository()
            repository.feedHandler = { feed, page ->
                forumFeedPage(
                    feed = feed,
                    page = page,
                    source = DiscourseForumContentSource.StaleCache,
                    fallbackFailure = DiscourseForumFailureKind.Network,
                )
            }
            val presenter =
                DiscourseForumPresenter(
                    repository = repository,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                advanceUntilIdle()

                assertEquals(DiscourseForumContentSource.StaleCache, models.value.feedSource)
                assertEquals(DiscourseForumFailureKind.Network, models.value.feedFailure)
                assertFalse(models.value.isFeedLoading)
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun appendFailureSuppressesAutomaticPagingButRetainsCursorForExplicitRetry() =
        runTest {
            val repository = FakeForumRepository()
            var appendShouldFail = true
            repository.feedHandler = { feed, page ->
                when (page) {
                    0 -> {
                        forumFeedPage(feed, page, listOf(1L), nextPage = 1)
                    }

                    1 -> {
                        if (appendShouldFail) {
                            throw DiscourseNetworkException(DiscourseNetworkFailureKind.Connection)
                        }
                        forumFeedPage(feed, page, listOf(2L), nextPage = null)
                    }

                    else -> {
                        error("Unexpected page")
                    }
                }
            }
            val presenter =
                DiscourseForumPresenter(
                    repository = repository,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                presenter.dispatch(DiscourseForumAction.LoadNextPage)
                advanceUntilIdle()

                assertEquals(1, models.value.nextPage)
                assertEquals(DiscourseForumFailureKind.Network, models.value.appendFailure)
                assertFalse(models.value.hasMore)

                appendShouldFail = false
                presenter.dispatch(DiscourseForumAction.LoadNextPage)
                advanceUntilIdle()

                assertEquals(listOf(1L, 2L), models.value.topics.topicIds())
                assertEquals(null, models.value.appendFailure)
                assertEquals(null, models.value.nextPage)
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun taxonomyFailureHasAnIndependentRetryAction() =
        runTest {
            val repository = FakeForumRepository()
            var shouldFail = true
            repository.categoriesHandler = {
                if (shouldFail) {
                    throw DiscourseNetworkException(DiscourseNetworkFailureKind.Connection)
                }
                DiscourseForumCategories(
                    items =
                        listOf(
                            DiscourseForumCategoryOption(
                                id = 5L,
                                name = "Development",
                                slug = "development",
                            ),
                        ),
                    source = DiscourseForumContentSource.Network,
                    updatedAtEpochMillis = 2L,
                )
            }
            val presenter =
                DiscourseForumPresenter(
                    repository = repository,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertEquals(DiscourseForumFailureKind.Network, models.value.taxonomyFailure)
                assertFalse(models.value.isTaxonomyLoading)

                shouldFail = false
                assertTrue(presenter.dispatch(DiscourseForumAction.RetryTaxonomy))
                advanceUntilIdle()

                assertEquals(null, models.value.taxonomyFailure)
                assertEquals(
                    "Development",
                    models.value.categories
                        .single()
                        .name,
                )
                assertEquals(2, repository.categoryCalls)
            } finally {
                presenter.close()
                runCurrent()
            }
        }
}

private class FakeForumRepository : DiscourseForumRepository {
    val feedCalls = mutableListOf<Pair<DiscourseForumFeed, Int>>()
    val topicCalls = mutableListOf<Long>()
    var categoryCalls: Int = 0

    var feedHandler: suspend (DiscourseForumFeed, Int) -> DiscourseForumFeedPage = { feed, page ->
        forumFeedPage(feed = feed, page = page)
    }
    var topicHandler: suspend (Long) -> DiscourseForumTopic = { forumTopic(it) }
    var categoriesHandler: suspend () -> DiscourseForumCategories = {
        DiscourseForumCategories(
            items = emptyList(),
            source = DiscourseForumContentSource.Network,
            updatedAtEpochMillis = 1L,
        )
    }

    override suspend fun loadFeed(
        feed: DiscourseForumFeed,
        page: Int,
    ): DiscourseForumFeedPage {
        feedCalls += feed to page
        return feedHandler(feed, page)
    }

    override suspend fun loadCategories(): DiscourseForumCategories {
        categoryCalls += 1
        return categoriesHandler()
    }

    override suspend fun loadTags(): DiscourseForumTags =
        DiscourseForumTags(
            items = emptyList(),
            source = DiscourseForumContentSource.Network,
            updatedAtEpochMillis = 1L,
        )

    override suspend fun loadTopic(topicId: Long): DiscourseForumTopic {
        topicCalls += topicId
        return topicHandler(topicId)
    }
}

private fun List<dev.dimension.flare.ui.model.UiTimelineV2.Topic>.topicIds(): List<Long> = map { requireNotNull(it.discourse).ref.topicId }
