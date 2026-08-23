package dev.dimension.flare.data.network.discourse.forum

import dev.dimension.flare.data.network.discourse.content.DiscourseCookedHtmlParser
import dev.dimension.flare.data.network.discourse.error.DiscourseNetworkException
import dev.dimension.flare.data.network.discourse.error.DiscourseNetworkFailureKind
import dev.dimension.flare.data.network.discourse.model.DiscourseNotificationResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUser
import dev.dimension.flare.data.network.discourse.model.DiscourseUserActionsResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserSummary
import dev.dimension.flare.data.network.discourse.model.DiscourseUserSummaryResponse
import dev.dimension.flare.data.network.discourse.paging.DiscourseNotificationOffset
import dev.dimension.flare.data.network.discourse.paging.DiscourseSearchPage
import dev.dimension.flare.data.network.discourse.realtime.DiscourseMessageBus
import dev.dimension.flare.data.network.discourse.realtime.DiscourseMessageBusBatch
import dev.dimension.flare.data.network.discourse.realtime.DiscourseMessageBusClientIdFactory
import dev.dimension.flare.data.network.discourse.realtime.DiscourseMessageBusPollRequest
import dev.dimension.flare.data.network.discourse.realtime.DiscourseMessageBusTransport
import dev.dimension.flare.data.network.discourse.realtime.DiscourseRealtimeCoordinator
import dev.dimension.flare.data.network.discourse.realtime.MemoryDiscourseMessageBusCursorStore
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.ui.model.DiscourseTopicRef
import dev.dimension.flare.ui.model.UiAuthor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class DiscourseForumPresenterStage6Test {
    @Test
    fun sessionGenerationSwitchDiscardsOldResultThatIgnoresCancellation() =
        runTest {
            val oldRequestStarted = CompletableDeferred<Unit>()
            val oldResult = CompletableDeferred<DiscourseForumFeedPage>()
            val forumRepository = RecordingPresenterForumRepository()
            var call = 0
            forumRepository.feedHandler = { feed, page ->
                call += 1
                if (call == 1) {
                    oldRequestStarted.complete(Unit)
                    withContext(NonCancellable) { oldResult.await() }
                } else {
                    forumFeedPage(feed = feed, page = page, topicIds = listOf(2L))
                }
            }
            val sessionManager = DiscourseSessionManager()
            val presenter =
                stage6Presenter(
                    forumRepository = forumRepository,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                runCurrent()
                assertTrue(oldRequestStarted.isCompleted)

                sessionManager.startAuthenticatedSession(
                    accountId = "42",
                    username = "member",
                )
                runCurrent()

                assertEquals(1L, models.value.sessionGeneration)
                assertTrue(models.value.isAuthenticated)
                assertEquals(listOf(2L), models.value.topics.topicIdsForStage6())

                oldResult.complete(forumFeedPage(topicIds = listOf(1L)))
                advanceUntilIdle()

                assertEquals(listOf(2L), models.value.topics.topicIdsForStage6())
                assertEquals(2, forumRepository.feedCalls.size)
            } finally {
                oldResult.complete(forumFeedPage(topicIds = listOf(1L)))
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun logoutClearsAccountTaxonomyAndResetsRestrictedFeed() =
        runTest {
            val sessionManager = authenticatedPresenterSession()
            val forumRepository = RecordingPresenterForumRepository()
            var categoryCall = 0
            var tagCall = 0
            forumRepository.categoriesHandler = {
                categoryCall += 1
                if (categoryCall == 1) {
                    DiscourseForumCategories(
                        items =
                            listOf(
                                DiscourseForumCategoryOption(
                                    id = 91L,
                                    name = "Members",
                                    slug = "members",
                                ),
                            ),
                        source = DiscourseForumContentSource.Network,
                        updatedAtEpochMillis = 1L,
                    )
                } else {
                    throw DiscourseNetworkException(DiscourseNetworkFailureKind.Connection)
                }
            }
            forumRepository.tagsHandler = {
                tagCall += 1
                if (tagCall == 1) {
                    DiscourseForumTags(
                        items = listOf(DiscourseForumTagOption(id = 92L, name = "staff-note", slug = "staff-note")),
                        source = DiscourseForumContentSource.Network,
                        updatedAtEpochMillis = 1L,
                    )
                } else {
                    throw DiscourseNetworkException(DiscourseNetworkFailureKind.Connection)
                }
            }
            val presenter =
                stage6Presenter(
                    forumRepository = forumRepository,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models
            val restrictedFeed =
                DiscourseForumFeed.Category(
                    id = 91L,
                    slug = "members",
                    name = "Members",
                )

            try {
                advanceUntilIdle()
                assertTrue(presenter.dispatch(DiscourseForumAction.SelectFeed(restrictedFeed)))
                advanceUntilIdle()
                assertEquals(restrictedFeed, models.value.selection)
                assertEquals(listOf("Members"), models.value.categories.map { it.name })
                assertEquals(listOf("staff-note"), models.value.tags.map { it.name })

                sessionManager.logout()
                advanceUntilIdle()

                assertFalse(models.value.isAuthenticated)
                assertEquals(2L, models.value.sessionGeneration)
                assertEquals(DiscourseForumFeed.Latest, models.value.selection)
                assertTrue(models.value.categories.isEmpty())
                assertTrue(models.value.tags.isEmpty())
                assertEquals(DiscourseForumFailureKind.Network, models.value.taxonomyFailure)
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun guestNotificationsFailWithoutCallingRemoteTransport() =
        runTest {
            val sessionManager = DiscourseSessionManager()
            val remote = CountingPresenterAccountRemote()
            val accountRepository =
                DefaultDiscourseForumAccountRepository(
                    remote = remote,
                    mapper = DiscourseForumAccountMapper(DiscourseCookedHtmlParser()),
                    sessionManager = sessionManager,
                )
            val presenter =
                stage6Presenter(
                    accountRepository = accountRepository,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertTrue(
                    presenter.dispatch(
                        DiscourseForumAction.SelectDestination(DiscourseForumDestination.Notifications),
                    ),
                )
                advanceUntilIdle()

                assertEquals(0, remote.notificationCalls)
                assertEquals(0, remote.markReadCalls)
                assertEquals(DiscourseForumFailureKind.Authentication, models.value.notifications.failure)
                assertNull(models.value.notifications.snapshot)
                assertFalse(models.value.notifications.isLoading)
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun searchAppendDeduplicatesRowsThatOverlapThePreviousPage() =
        runTest {
            val searchRepository = RecordingPresenterSearchRepository()
            searchRepository.handler = { query, page, _ ->
                when (page.value) {
                    1 -> {
                        DiscourseForumSearchPage(
                            query = query,
                            page = page,
                            items = listOf(searchHit(101L), searchHit(102L)),
                            nextPage = DiscourseSearchPage(2),
                        )
                    }

                    2 -> {
                        DiscourseForumSearchPage(
                            query = query,
                            page = page,
                            items = listOf(searchHit(102L), searchHit(103L)),
                            nextPage = null,
                        )
                    }

                    else -> {
                        error("Unexpected search page")
                    }
                }
            }
            val presenter =
                stage6Presenter(
                    searchRepository = searchRepository,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertTrue(presenter.dispatch(DiscourseForumAction.UpdateSearchQuery("contract query")))
                assertTrue(presenter.dispatch(DiscourseForumAction.SubmitSearch))
                advanceUntilIdle()

                assertEquals(
                    listOf(101L, 102L),
                    models.value.search.items
                        .map { it.postId },
                )
                assertEquals(DiscourseSearchPage(2), models.value.search.nextPage)

                assertTrue(presenter.dispatch(DiscourseForumAction.LoadNextSearchPage))
                advanceUntilIdle()

                assertEquals(
                    listOf(101L, 102L, 103L),
                    models.value.search.items
                        .map { it.postId },
                )
                assertNull(models.value.search.nextPage)
                assertEquals(
                    listOf(
                        SearchRequest("contract query", DiscourseSearchPage(1), emptySet()),
                        SearchRequest("contract query", DiscourseSearchPage(2), setOf(101L, 102L)),
                    ),
                    searchRepository.requests,
                )
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun activityAppendUsesTheServerDerivedOffsetAndKnownKeys() =
        runTest {
            val accountRepository = RecordingPresenterAccountRepository()
            accountRepository.activityHandler = { _, offset, _ ->
                when (offset) {
                    0 -> {
                        DiscourseForumActivityPage(
                            offset = 0,
                            items = listOf(activity(201L)),
                            nextOffset = 20,
                        )
                    }

                    20 -> {
                        DiscourseForumActivityPage(
                            offset = 20,
                            items = listOf(activity(202L)),
                            nextOffset = null,
                        )
                    }

                    else -> {
                        error("Unexpected activity offset")
                    }
                }
            }
            val presenter =
                stage6Presenter(
                    accountRepository = accountRepository,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertTrue(presenter.dispatch(DiscourseForumAction.OpenProfile("member")))
                advanceUntilIdle()

                assertEquals(
                    listOf("activity:201"),
                    models.value.profile.activity
                        .map { it.itemKey },
                )
                assertEquals(20, models.value.profile.nextOffset)

                assertTrue(presenter.dispatch(DiscourseForumAction.LoadNextActivityPage))
                advanceUntilIdle()

                assertEquals(
                    listOf("activity:201", "activity:202"),
                    models.value.profile.activity
                        .map { it.itemKey },
                )
                assertNull(models.value.profile.nextOffset)
                assertEquals(
                    listOf(
                        ActivityRequest("member", offset = 0, knownKeys = emptySet()),
                        ActivityRequest("member", offset = 20, knownKeys = setOf("activity:201")),
                    ),
                    accountRepository.activityRequests,
                )
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun notificationAppendUsesOffsetAndReplacesOneOverlappingRow() =
        runTest {
            val sessionManager = authenticatedPresenterSession()
            val accountRepository = RecordingPresenterAccountRepository()
            accountRepository.notificationHandler = { offset, _ ->
                when (offset.value) {
                    0 -> {
                        notificationPage(
                            offset = offset,
                            items = listOf(notification(301L), notification(302L)),
                            nextOffset = DiscourseNotificationOffset(2),
                        )
                    }

                    2 -> {
                        notificationPage(
                            offset = offset,
                            items = listOf(notification(302L, read = true), notification(303L)),
                            nextOffset = null,
                        )
                    }

                    else -> {
                        error("Unexpected notification offset")
                    }
                }
            }
            val presenter =
                stage6Presenter(
                    accountRepository = accountRepository,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertTrue(
                    presenter.dispatch(
                        DiscourseForumAction.SelectDestination(DiscourseForumDestination.Notifications),
                    ),
                )
                advanceUntilIdle()

                assertEquals(
                    listOf(301L, 302L),
                    models.value.notifications.snapshot
                        ?.items
                        ?.map { it.id },
                )
                assertEquals(DiscourseNotificationOffset(2), models.value.notifications.nextOffset)

                assertTrue(presenter.dispatch(DiscourseForumAction.LoadNextNotificationsPage))
                advanceUntilIdle()

                val snapshot = requireNotNull(models.value.notifications.snapshot)
                assertEquals(listOf(301L, 302L, 303L), snapshot.items.map { it.id })
                assertTrue(snapshot.items.single { it.id == 302L }.read)
                assertEquals(2, snapshot.unreadCount)
                assertNull(models.value.notifications.nextOffset)
                assertEquals(
                    listOf(
                        NotificationRequest(DiscourseNotificationOffset(0), emptySet()),
                        NotificationRequest(DiscourseNotificationOffset(2), setOf(301L, 302L)),
                    ),
                    accountRepository.notificationRequests,
                )
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun failedMarkReadLeavesTheLocalUnreadSnapshotUntouched() =
        runTest {
            val sessionManager = authenticatedPresenterSession()
            val accountRepository = RecordingPresenterAccountRepository()
            accountRepository.notificationHandler = { offset, _ ->
                notificationPage(
                    offset = offset,
                    items = listOf(notification(401L), notification(402L)),
                    nextOffset = null,
                )
            }
            accountRepository.markReadHandler = { _, _ ->
                throw DiscourseNetworkException(DiscourseNetworkFailureKind.Connection)
            }
            val presenter =
                stage6Presenter(
                    accountRepository = accountRepository,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertTrue(
                    presenter.dispatch(
                        DiscourseForumAction.SelectDestination(DiscourseForumDestination.Notifications),
                    ),
                )
                advanceUntilIdle()
                val before = requireNotNull(models.value.notifications.snapshot)
                assertEquals(2, before.unreadCount)

                assertTrue(presenter.dispatch(DiscourseForumAction.MarkNotificationsRead(401L)))
                advanceUntilIdle()

                val after = requireNotNull(models.value.notifications.snapshot)
                assertEquals(before, after)
                assertEquals(2, after.unreadCount)
                assertFalse(after.items.single { it.id == 401L }.read)
                assertEquals(DiscourseForumFailureKind.Network, models.value.notifications.markFailure)
                assertFalse(models.value.notifications.isMarkingRead)
                assertEquals(listOf<Long?>(401L), accountRepository.markReadRequests)
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun refreshWaitsForMarkReadAndReplaysAfterItCompletes() =
        runTest {
            val sessionManager = authenticatedPresenterSession()
            val accountRepository = RecordingPresenterAccountRepository()
            val markStarted = CompletableDeferred<Unit>()
            val releaseMark = CompletableDeferred<Unit>()
            var notificationCall = 0
            accountRepository.notificationHandler = { offset, _ ->
                notificationCall += 1
                notificationPage(
                    offset = offset,
                    items =
                        if (notificationCall == 1) {
                            listOf(notification(501L))
                        } else {
                            listOf(notification(501L, read = true), notification(502L))
                        },
                    nextOffset = null,
                )
            }
            accountRepository.markReadHandler = { current, notificationId ->
                markStarted.complete(Unit)
                withContext(NonCancellable) { releaseMark.await() }
                current.copy(
                    items =
                        current.items.map { item ->
                            if (item.id == notificationId) item.copy(read = true) else item
                        },
                )
            }
            val presenter =
                stage6Presenter(
                    accountRepository = accountRepository,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertTrue(
                    presenter.dispatch(
                        DiscourseForumAction.SelectDestination(DiscourseForumDestination.Notifications),
                    ),
                )
                advanceUntilIdle()
                assertEquals(
                    listOf(501L),
                    models.value.notifications.snapshot
                        ?.items
                        ?.map { it.id },
                )

                assertTrue(presenter.dispatch(DiscourseForumAction.MarkNotificationsRead(501L)))
                runCurrent()
                assertTrue(markStarted.isCompleted)
                assertTrue(presenter.dispatch(DiscourseForumAction.RefreshNotifications))
                runCurrent()
                assertEquals(
                    listOf(501L),
                    models.value.notifications.snapshot
                        ?.items
                        ?.map { it.id },
                )
                assertEquals(1, notificationCall)

                releaseMark.complete(Unit)
                advanceUntilIdle()

                val snapshot = requireNotNull(models.value.notifications.snapshot)
                assertEquals(listOf(501L, 502L), snapshot.items.map { it.id })
                assertTrue(snapshot.items.single { it.id == 501L }.read)
                assertFalse(snapshot.items.single { it.id == 502L }.read)
                assertEquals(1, snapshot.unreadCount)
                assertFalse(models.value.notifications.isMarkingRead)
                assertEquals(2, notificationCall)
            } finally {
                releaseMark.complete(Unit)
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun markReadIsIgnoredWhileNotificationRefreshIsInFlight() =
        runTest {
            val sessionManager = authenticatedPresenterSession()
            val accountRepository = RecordingPresenterAccountRepository()
            val refreshStarted = CompletableDeferred<Unit>()
            val releaseRefresh = CompletableDeferred<Unit>()
            var notificationCall = 0
            accountRepository.notificationHandler = { offset, _ ->
                notificationCall += 1
                if (notificationCall > 1) {
                    refreshStarted.complete(Unit)
                    withContext(NonCancellable) { releaseRefresh.await() }
                }
                notificationPage(
                    offset = offset,
                    items =
                        if (notificationCall == 1) {
                            listOf(notification(601L))
                        } else {
                            listOf(notification(601L), notification(602L))
                        },
                    nextOffset = null,
                )
            }
            val presenter =
                stage6Presenter(
                    accountRepository = accountRepository,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertTrue(
                    presenter.dispatch(
                        DiscourseForumAction.SelectDestination(DiscourseForumDestination.Notifications),
                    ),
                )
                advanceUntilIdle()

                assertTrue(presenter.dispatch(DiscourseForumAction.RefreshNotifications))
                runCurrent()
                assertTrue(refreshStarted.isCompleted)
                assertTrue(models.value.notifications.isLoading)

                assertTrue(presenter.dispatch(DiscourseForumAction.MarkNotificationsRead(601L)))
                runCurrent()
                assertTrue(accountRepository.markReadRequests.isEmpty())
                assertFalse(models.value.notifications.isMarkingRead)

                releaseRefresh.complete(Unit)
                advanceUntilIdle()
                assertEquals(
                    listOf(601L, 602L),
                    models.value.notifications.snapshot
                        ?.items
                        ?.map { it.id },
                )

                assertTrue(presenter.dispatch(DiscourseForumAction.MarkNotificationsRead(601L)))
                advanceUntilIdle()
                assertEquals(listOf<Long?>(601L), accountRepository.markReadRequests)
                assertTrue(requireNotNull(models.value.notifications.snapshot).items.first().read)
            } finally {
                releaseRefresh.complete(Unit)
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun openTopicRetainsRequestedPostNumberAfterTopicLoads() =
        runTest {
            val forumRepository = RecordingPresenterForumRepository()
            val presenter =
                stage6Presenter(
                    forumRepository = forumRepository,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertTrue(presenter.dispatch(DiscourseForumAction.OpenTopic(topicId = 7L, postNumber = 4)))
                advanceUntilIdle()

                assertEquals(7L, models.value.selectedTopicId)
                assertEquals(4, models.value.selectedPostNumber)
                assertEquals(7L, models.value.selectedTopic?.topicId)
                assertEquals(listOf(7L), forumRepository.topicCalls)
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun foregroundRealtimeCatchUpReconcilesFeedTopicAndUnreadNotifications() =
        runTest {
            val sessionManager = authenticatedPresenterSession()
            val forumRepository = RecordingPresenterForumRepository()
            val accountRepository = RecordingPresenterAccountRepository()
            var realtimePhase = false
            forumRepository.feedHandler = { feed, page ->
                forumFeedPage(
                    feed = feed,
                    page = page,
                    topicIds = listOf(if (realtimePhase) 81L else 1L),
                )
            }
            forumRepository.topicHandler = { topicId ->
                forumTopic(if (realtimePhase) topicId + 100L else topicId)
                    .copy(topicId = topicId)
            }
            accountRepository.notificationHandler = { offset, _ ->
                notificationPage(
                    offset = offset,
                    items = if (realtimePhase) listOf(notification(803L)) else emptyList(),
                    nextOffset = null,
                )
            }
            val transport = SuspendingPresenterRealtimeTransport()
            val coordinator =
                DiscourseRealtimeCoordinator(
                    sessionManager = sessionManager,
                    messageBus =
                        DiscourseMessageBus(
                            transport = transport,
                            cursorStore = MemoryDiscourseMessageBusCursorStore(),
                            clientIdFactory =
                                DiscourseMessageBusClientIdFactory {
                                    "00000000000040008000000000000000"
                                },
                        ),
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val presenter =
                stage6Presenter(
                    forumRepository = forumRepository,
                    accountRepository = accountRepository,
                    sessionManager = sessionManager,
                    realtimeCoordinator = coordinator,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                runCurrent()
                assertTrue(presenter.dispatch(DiscourseForumAction.OpenTopic(7L)))
                runCurrent()
                assertTrue(transport.requests.isEmpty(), "Background presenters must not poll")

                realtimePhase = true
                presenter.setForeground(true)
                runCurrent()

                assertEquals(listOf(81L), models.value.topics.topicIdsForStage6())
                assertEquals(7L, models.value.selectedTopic?.topicId)
                assertEquals(
                    listOf(803L),
                    models.value.notifications.snapshot
                        ?.items
                        ?.map { it.id },
                )
                assertEquals(
                    1,
                    models.value.notifications.snapshot
                        ?.unreadCount,
                )
                assertEquals(
                    setOf(
                        "/latest",
                        "/new",
                        "/notification/42",
                        "/topic/7",
                        "/topic/7/reactions",
                    ),
                    transport.requests
                        .single()
                        .channels.keys,
                )
            } finally {
                presenter.close()
                runCurrent()
            }
        }
}

private fun stage6Presenter(
    dispatcher: CoroutineDispatcher,
    forumRepository: DiscourseForumRepository = RecordingPresenterForumRepository(),
    searchRepository: DiscourseForumSearchRepository = RecordingPresenterSearchRepository(),
    accountRepository: DiscourseForumAccountRepository = RecordingPresenterAccountRepository(),
    sessionManager: DiscourseSessionManager = DiscourseSessionManager(),
    realtimeCoordinator: DiscourseRealtimeCoordinator? = null,
): DiscourseForumPresenter =
    DiscourseForumPresenter(
        repository = forumRepository,
        searchRepository = searchRepository,
        accountRepository = accountRepository,
        sessionManager = sessionManager,
        realtimeCoordinator = realtimeCoordinator,
        dispatcher = dispatcher,
    )

private class SuspendingPresenterRealtimeTransport : DiscourseMessageBusTransport {
    val requests = mutableListOf<DiscourseMessageBusPollRequest>()

    override fun poll(request: DiscourseMessageBusPollRequest): Flow<DiscourseMessageBusBatch> =
        flow {
            requests += request
            try {
                awaitCancellation()
            } catch (cancellation: CancellationException) {
                throw cancellation
            }
        }
}

private class RecordingPresenterForumRepository : DiscourseForumRepository {
    val feedCalls = mutableListOf<Pair<DiscourseForumFeed, Int>>()
    val topicCalls = mutableListOf<Long>()

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
    var tagsHandler: suspend () -> DiscourseForumTags = {
        DiscourseForumTags(
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

    override suspend fun loadCategories(): DiscourseForumCategories = categoriesHandler()

    override suspend fun loadTags(): DiscourseForumTags = tagsHandler()

    override suspend fun loadTopic(topicId: Long): DiscourseForumTopic {
        topicCalls += topicId
        return topicHandler(topicId)
    }
}

private data class SearchRequest(
    val query: String,
    val page: DiscourseSearchPage,
    val knownPostIds: Set<Long>,
)

private class RecordingPresenterSearchRepository : DiscourseForumSearchRepository {
    val requests = mutableListOf<SearchRequest>()
    var handler: suspend (String, DiscourseSearchPage, Set<Long>) -> DiscourseForumSearchPage = { query, page, _ ->
        DiscourseForumSearchPage(
            query = query,
            page = page,
            items = emptyList(),
            nextPage = null,
        )
    }

    override suspend fun search(
        query: String,
        page: DiscourseSearchPage,
        knownPostIds: Set<Long>,
    ): DiscourseForumSearchPage {
        requests += SearchRequest(query, page, knownPostIds)
        return handler(query, page, knownPostIds)
    }
}

private data class ActivityRequest(
    val username: String,
    val offset: Int,
    val knownKeys: Set<String>,
)

private data class NotificationRequest(
    val offset: DiscourseNotificationOffset,
    val knownIds: Set<Long>,
)

private class RecordingPresenterAccountRepository : DiscourseForumAccountRepository {
    val activityRequests = mutableListOf<ActivityRequest>()
    val notificationRequests = mutableListOf<NotificationRequest>()
    val markReadRequests = mutableListOf<Long?>()

    var profileHandler: suspend (String) -> DiscourseForumProfile = ::profile
    var activityHandler: suspend (String, Int, Set<String>) -> DiscourseForumActivityPage = { _, offset, _ ->
        DiscourseForumActivityPage(offset = offset, items = emptyList(), nextOffset = null)
    }
    var notificationHandler:
        suspend (DiscourseNotificationOffset, Set<Long>) -> DiscourseForumNotificationPage = { offset, _ ->
            notificationPage(offset = offset, items = emptyList(), nextOffset = null)
        }
    var markReadHandler:
        suspend (DiscourseForumNotificationSnapshot, Long?) -> DiscourseForumNotificationSnapshot = { current, id ->
            current.copy(
                items =
                    current.items.map { item ->
                        if (id == null || item.id == id) item.copy(read = true) else item
                    },
            )
        }

    override suspend fun loadProfile(username: String): DiscourseForumProfile = profileHandler(username)

    override suspend fun loadActivity(
        username: String,
        offset: Int,
        knownItemKeys: Set<String>,
    ): DiscourseForumActivityPage {
        activityRequests += ActivityRequest(username, offset, knownItemKeys)
        return activityHandler(username, offset, knownItemKeys)
    }

    override suspend fun loadNotifications(
        offset: DiscourseNotificationOffset,
        knownIds: Set<Long>,
        limit: Int,
    ): DiscourseForumNotificationPage {
        notificationRequests += NotificationRequest(offset, knownIds)
        return notificationHandler(offset, knownIds)
    }

    override suspend fun markNotificationsRead(
        current: DiscourseForumNotificationSnapshot,
        notificationId: Long?,
    ): DiscourseForumNotificationSnapshot {
        markReadRequests += notificationId
        return markReadHandler(current, notificationId)
    }
}

private class CountingPresenterAccountRemote : DiscourseForumAccountRemoteDataSource {
    var notificationCalls: Int = 0
    var markReadCalls: Int = 0

    override suspend fun user(username: String): DiscourseUserResponse = DiscourseUserResponse(DiscourseUser(id = 42L, username = username))

    override suspend fun userSummary(username: String): DiscourseUserSummaryResponse = DiscourseUserSummaryResponse(DiscourseUserSummary())

    override suspend fun userActions(
        username: String,
        offset: Int,
    ): DiscourseUserActionsResponse = DiscourseUserActionsResponse(emptyList())

    override suspend fun notifications(
        offset: DiscourseNotificationOffset,
        limit: Int,
    ): DiscourseNotificationResponse {
        notificationCalls += 1
        return DiscourseNotificationResponse(emptyList())
    }

    override suspend fun markNotificationsRead(notificationId: Long?) {
        markReadCalls += 1
    }
}

private suspend fun authenticatedPresenterSession(): DiscourseSessionManager =
    DiscourseSessionManager().also { manager ->
        manager.startAuthenticatedSession(
            accountId = "42",
            username = "member",
        )
    }

private fun searchHit(postId: Long): DiscourseForumSearchHit =
    DiscourseForumSearchHit(
        itemKey = searchItemKey(postId),
        postId = postId,
        topic = DiscourseTopicRef(topicId = 7L, postNumber = 2),
        topicSlug = "safe-topic",
        title = "Safe search topic",
        excerpt = "Safe search excerpt",
        author = UiAuthor(username = "member", displayName = "Member"),
        createdAtEpochMillis = postId,
        likeCount = 0,
        categoryId = null,
        tags = emptyList(),
    )

private fun profile(username: String): DiscourseForumProfile =
    DiscourseForumProfile(
        userId = 42L,
        username = username,
        displayName = "Member",
        avatarUrl = null,
        title = null,
        trustLevel = 1,
        moderator = false,
        admin = false,
        staff = false,
        active = true,
        suspended = false,
        canSendPrivateMessages = false,
        canEdit = false,
        createdAtEpochMillis = null,
        lastPostedAtEpochMillis = null,
        lastSeenAtEpochMillis = null,
        websiteName = null,
        websiteUrl = null,
        location = null,
        primaryGroupName = null,
        bio = emptyList(),
        badges = emptyList(),
        summary =
            DiscourseForumUserSummary(
                likesGiven = 0,
                likesReceived = 0,
                topicsEntered = 0,
                postsReadCount = 0,
                daysVisited = 0,
                topicCount = 0,
                postCount = 0,
                timeReadSeconds = 0L,
                recentTimeReadSeconds = 0L,
                solvedCount = 0,
            ),
    )

private fun activity(id: Long): DiscourseForumActivity =
    DiscourseForumActivity(
        itemKey = "activity:$id",
        actionType = 5,
        kind = DiscourseForumActivityKind.Replied,
        createdAtEpochMillis = id,
        user = null,
        actingUser = null,
        topic = DiscourseTopicRef(topicId = 7L, postNumber = id.toInt()),
        postId = id,
        topicSlug = "safe-topic",
        title = "Safe activity",
        excerpt = "Safe activity excerpt",
        categoryId = null,
        closed = false,
        archived = false,
        hidden = false,
        deleted = false,
    )

private fun notification(
    id: Long,
    read: Boolean = false,
): DiscourseForumNotification =
    DiscourseForumNotification(
        id = id,
        recipientUserId = 42L,
        kind = DiscourseForumNotificationKind.Reply,
        read = read,
        highPriority = false,
        createdAtEpochMillis = id,
        topic = DiscourseTopicRef(topicId = 7L, postNumber = 2),
        topicSlug = "safe-topic",
        title = "Safe notification",
        actingUser = null,
        data = DiscourseForumNotificationData(),
    )

private fun notificationPage(
    offset: DiscourseNotificationOffset,
    items: List<DiscourseForumNotification>,
    nextOffset: DiscourseNotificationOffset?,
): DiscourseForumNotificationPage =
    DiscourseForumNotificationPage(
        offset = offset,
        items = items,
        nextOffset = nextOffset,
        totalRows = 3,
        seenNotificationId = 0L,
    )

private fun List<dev.dimension.flare.ui.model.UiTimelineV2.Topic>.topicIdsForStage6(): List<Long> =
    map { requireNotNull(it.discourse).ref.topicId }
