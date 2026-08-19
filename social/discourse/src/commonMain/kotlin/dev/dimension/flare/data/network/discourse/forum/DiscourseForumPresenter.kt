package dev.dimension.flare.data.network.discourse.forum

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.dimension.flare.data.network.discourse.error.DiscourseException
import dev.dimension.flare.data.network.discourse.paging.DiscourseNotificationOffset
import dev.dimension.flare.data.network.discourse.paging.DiscourseSearchPage
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionState
import dev.dimension.flare.data.network.discourse.session.StaleDiscourseSessionException
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.presenter.PresenterBase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Molecule presenter shared by Compose and SwiftUI forum shells.
 *
 * Public actions and session transitions enter one bounded actor. Child jobs return immutable
 * events, and every request family carries its own monotonically increasing id. A login or logout
 * therefore cancels all generation-bound work, clears account-derived presentation state, and
 * prevents a late result from crossing into the replacement session.
 */
public class DiscourseForumPresenter(
    private val repository: DiscourseForumRepository,
    private val searchRepository: DiscourseForumSearchRepository,
    private val accountRepository: DiscourseForumAccountRepository,
    private val sessionManager: DiscourseSessionManager,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PresenterBase<DiscourseForumState>(dispatcher) {
    private val actions = Channel<DiscourseForumAction>(capacity = ACTION_CHANNEL_CAPACITY)

    /** Returns false after close or while the bounded UI action queue is full. */
    public fun dispatch(action: DiscourseForumAction): Boolean = actions.trySend(action).isSuccess

    @Composable
    override fun body(): DiscourseForumState {
        var state by remember { mutableStateOf(DiscourseForumState()) }
        LaunchedEffect(repository, searchRepository, accountRepository, sessionManager) {
            runActor(
                state = { state },
                setState = { state = it },
            )
        }
        return state
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun runActor(
        state: () -> DiscourseForumState,
        setState: (DiscourseForumState) -> Unit,
    ): Unit =
        coroutineScope {
            val events = Channel<ForumPresenterEvent>(capacity = RESULT_CHANNEL_CAPACITY)
            var feedJob: Job? = null
            var topicJob: Job? = null
            var categoriesJob: Job? = null
            var tagsJob: Job? = null
            var searchJob: Job? = null
            var profileJob: Job? = null
            var activityJob: Job? = null
            var notificationsJob: Job? = null
            var markReadJob: Job? = null

            var feedRequest = 0L
            var topicRequest = 0L
            var taxonomyRequest = 0L
            var searchRequest = 0L
            var profileRequest = 0L
            var activityRequest = 0L
            var notificationsRequest = 0L
            var markReadRequest = 0L
            var pendingNotificationsOffset: DiscourseNotificationOffset? = null
            var pendingNotificationsAppend = false
            var categoriesLoading = true
            var tagsLoading = true

            fun update(transform: (DiscourseForumState) -> DiscourseForumState) {
                setState(transform(state()))
            }

            fun clearTopicSelection() {
                topicJob?.cancel()
                topicRequest = topicRequest.nextRequestId()
                update {
                    it.copy(
                        selectedTopicId = null,
                        selectedPostNumber = null,
                        selectedTopic = null,
                        isTopicLoading = false,
                        topicSource = null,
                        topicFailure = null,
                    )
                }
            }

            fun startFeed(
                feed: DiscourseForumFeed,
                page: Int,
                append: Boolean,
                clearExisting: Boolean,
            ) {
                feedJob?.cancel()
                feedRequest = feedRequest.nextRequestId()
                val requestId = feedRequest
                update { current ->
                    current.copy(
                        selection = feed,
                        topics = if (clearExisting) emptyList() else current.topics,
                        nextPage = if (clearExisting) null else current.nextPage,
                        isFeedLoading = !append,
                        isAppending = append,
                        feedSource = if (clearExisting) null else current.feedSource,
                        feedFailure = null,
                        appendFailure = null,
                    )
                }
                feedJob =
                    launch {
                        loadPresenterEvent(
                            block = {
                                repository.loadFeed(feed, page).also { loaded ->
                                    check(loaded.feed == feed && loaded.page == page)
                                }
                            },
                            success = { ForumPresenterEvent.FeedLoaded(requestId, it, append) },
                            failure = { ForumPresenterEvent.FeedFailed(requestId, it, append) },
                        )?.let { events.send(it) }
                    }
            }

            fun startTopic(
                topicId: Long,
                postNumber: Int?,
            ) {
                topicJob?.cancel()
                topicRequest = topicRequest.nextRequestId()
                val requestId = topicRequest
                update { current ->
                    current.copy(
                        selectedTopicId = topicId,
                        selectedPostNumber = postNumber,
                        selectedTopic = null,
                        isTopicLoading = true,
                        topicSource = null,
                        topicFailure = null,
                    )
                }
                topicJob =
                    launch {
                        loadPresenterEvent(
                            block = {
                                repository.loadTopic(topicId).also { loaded ->
                                    check(loaded.topicId == topicId)
                                }
                            },
                            success = { ForumPresenterEvent.TopicLoaded(requestId, topicId, it) },
                            failure = { ForumPresenterEvent.TopicFailed(requestId, topicId, it) },
                        )?.let { events.send(it) }
                    }
            }

            fun startTaxonomy() {
                taxonomyRequest = taxonomyRequest.nextRequestId()
                val requestId = taxonomyRequest
                categoriesLoading = true
                tagsLoading = true
                update { it.copy(isTaxonomyLoading = true, taxonomyFailure = null) }
                categoriesJob?.cancel()
                categoriesJob =
                    launch {
                        loadPresenterEvent(
                            block = repository::loadCategories,
                            success = { ForumPresenterEvent.CategoriesLoaded(requestId, it) },
                            failure = { ForumPresenterEvent.CategoriesFailed(requestId, it) },
                        )?.let { events.send(it) }
                    }
                tagsJob?.cancel()
                tagsJob =
                    launch {
                        loadPresenterEvent(
                            block = repository::loadTags,
                            success = { ForumPresenterEvent.TagsLoaded(requestId, it) },
                            failure = { ForumPresenterEvent.TagsFailed(requestId, it) },
                        )?.let { events.send(it) }
                    }
            }

            fun startSearch(
                query: String,
                page: DiscourseSearchPage,
                append: Boolean,
            ) {
                val normalized = query.trim()
                searchJob?.cancel()
                searchRequest = searchRequest.nextRequestId()
                if (normalized.isEmpty()) {
                    update {
                        it.copy(
                            search =
                                it.search.copy(
                                    submittedQuery = "",
                                    items = emptyList(),
                                    nextPage = null,
                                    isLoading = false,
                                    isAppending = false,
                                    failure = null,
                                    appendFailure = null,
                                ),
                        )
                    }
                    return
                }
                val requestId = searchRequest
                val knownPostIds =
                    if (append) state().search.items.mapTo(mutableSetOf()) { it.postId } else emptySet()
                update { current ->
                    current.copy(
                        search =
                            current.search.copy(
                                submittedQuery = normalized,
                                items = if (append) current.search.items else emptyList(),
                                nextPage = if (append) current.search.nextPage else null,
                                isLoading = !append,
                                isAppending = append,
                                failure = null,
                                appendFailure = null,
                            ),
                    )
                }
                searchJob =
                    launch {
                        loadPresenterEvent(
                            block = { searchRepository.search(normalized, page, knownPostIds) },
                            success = { loaded ->
                                check(loaded.query == normalized && loaded.page == page)
                                ForumPresenterEvent.SearchLoaded(requestId, loaded, append)
                            },
                            failure = { ForumPresenterEvent.SearchFailed(requestId, it, append) },
                        )?.let { events.send(it) }
                    }
            }

            fun startProfile(username: String) {
                profileJob?.cancel()
                profileRequest = profileRequest.nextRequestId()
                val requestId = profileRequest
                update { current ->
                    current.copy(
                        profile =
                            current.profile.copy(
                                username = username,
                                value = null,
                                isLoading = true,
                                failure = null,
                            ),
                    )
                }
                profileJob =
                    launch {
                        loadPresenterEvent(
                            block = { accountRepository.loadProfile(username) },
                            success = { ForumPresenterEvent.ProfileLoaded(requestId, username, it) },
                            failure = { ForumPresenterEvent.ProfileFailed(requestId, username, it) },
                        )?.let { events.send(it) }
                    }
            }

            fun startActivity(
                username: String,
                offset: Int,
                append: Boolean,
            ) {
                activityJob?.cancel()
                activityRequest = activityRequest.nextRequestId()
                val requestId = activityRequest
                val knownKeys =
                    if (append) state().profile.activity.mapTo(mutableSetOf()) { it.itemKey } else emptySet()
                update { current ->
                    current.copy(
                        profile =
                            current.profile.copy(
                                username = username,
                                activity = if (append) current.profile.activity else emptyList(),
                                nextOffset = if (append) current.profile.nextOffset else null,
                                isActivityLoading = !append,
                                isAppendingActivity = append,
                                activityFailure = null,
                                activityAppendFailure = null,
                            ),
                    )
                }
                activityJob =
                    launch {
                        loadPresenterEvent(
                            block = { accountRepository.loadActivity(username, offset, knownKeys) },
                            success = { loaded ->
                                check(loaded.offset == offset)
                                ForumPresenterEvent.ActivityLoaded(requestId, username, loaded, append)
                            },
                            failure = { ForumPresenterEvent.ActivityFailed(requestId, username, it, append) },
                        )?.let { events.send(it) }
                    }
            }

            fun startNotifications(
                offset: DiscourseNotificationOffset,
                append: Boolean,
            ) {
                // A mark-read response is derived from the current immutable snapshot. Loading a
                // different snapshot concurrently would make either completion order stale.
                if (state().notifications.isMarkingRead) {
                    // Keep one bounded replay. A root refresh supersedes an append because its
                    // response replaces the list and establishes a new offset lineage.
                    if (!append || pendingNotificationsOffset == null) {
                        pendingNotificationsOffset = offset
                        pendingNotificationsAppend = append
                    }
                    return
                }
                notificationsJob?.cancel()
                notificationsRequest = notificationsRequest.nextRequestId()
                val requestId = notificationsRequest
                val knownIds =
                    if (append) {
                        state()
                            .notifications
                            .snapshot
                            ?.items
                            ?.mapTo(mutableSetOf()) { it.id }
                            .orEmpty()
                    } else {
                        emptySet()
                    }
                update { current ->
                    current.copy(
                        notifications =
                            current.notifications.copy(
                                snapshot = if (append) current.notifications.snapshot else null,
                                nextOffset = if (append) current.notifications.nextOffset else null,
                                isLoading = !append,
                                isAppending = append,
                                failure = null,
                                appendFailure = null,
                                markFailure = null,
                            ),
                    )
                }
                notificationsJob =
                    launch {
                        loadPresenterEvent(
                            block = { accountRepository.loadNotifications(offset, knownIds) },
                            success = { loaded ->
                                check(loaded.offset == offset)
                                ForumPresenterEvent.NotificationsLoaded(requestId, loaded, append)
                            },
                            failure = { ForumPresenterEvent.NotificationsFailed(requestId, it, append) },
                        )?.let { events.send(it) }
                    }
            }

            fun startPendingNotifications() {
                val offset = pendingNotificationsOffset ?: return
                val append = pendingNotificationsAppend
                pendingNotificationsOffset = null
                pendingNotificationsAppend = false
                startNotifications(offset, append)
            }

            fun startMarkNotificationsRead(notificationId: Long?) {
                val notifications = state().notifications
                val snapshot = notifications.snapshot ?: return
                if (notifications.isLoading || notifications.isAppending || notifications.isMarkingRead) return
                markReadJob?.cancel()
                markReadRequest = markReadRequest.nextRequestId()
                val requestId = markReadRequest
                update {
                    it.copy(
                        notifications = it.notifications.copy(isMarkingRead = true, markFailure = null),
                    )
                }
                markReadJob =
                    launch {
                        loadPresenterEvent(
                            block = { accountRepository.markNotificationsRead(snapshot, notificationId) },
                            success = { ForumPresenterEvent.NotificationsMarkedRead(requestId, it) },
                            failure = { ForumPresenterEvent.NotificationsMarkFailed(requestId, it) },
                        )?.let { events.send(it) }
                    }
            }

            fun startCurrentProfile() {
                val username = state().accountUsername
                if (username == null) {
                    update {
                        it.copy(
                            profile =
                                DiscourseForumProfileState(
                                    failure = DiscourseForumFailureKind.Authentication,
                                ),
                        )
                    }
                } else {
                    startProfile(username)
                    startActivity(username, offset = 0, append = false)
                }
            }

            fun cancelGenerationWork() {
                listOfNotNull(
                    feedJob,
                    topicJob,
                    categoriesJob,
                    tagsJob,
                    searchJob,
                    profileJob,
                    activityJob,
                    notificationsJob,
                    markReadJob,
                ).forEach(Job::cancel)
                feedRequest = feedRequest.nextRequestId()
                topicRequest = topicRequest.nextRequestId()
                taxonomyRequest = taxonomyRequest.nextRequestId()
                searchRequest = searchRequest.nextRequestId()
                profileRequest = profileRequest.nextRequestId()
                activityRequest = activityRequest.nextRequestId()
                notificationsRequest = notificationsRequest.nextRequestId()
                markReadRequest = markReadRequest.nextRequestId()
                pendingNotificationsOffset = null
                pendingNotificationsAppend = false
            }

            fun handleSessionChanged(session: DiscourseSessionState) {
                if (session.generation == state().sessionGeneration) return
                cancelGenerationWork()
                val previous = state()
                val authenticated = session as? DiscourseSessionState.Authenticated
                val feed =
                    when (previous.destination) {
                        DiscourseForumDestination.Hot -> DiscourseForumFeed.Hot

                        // Category and tag visibility can differ between accounts. A generation
                        // transition must therefore return to a public root before any replacement
                        // taxonomy request is allowed to populate the workspace.
                        else -> DiscourseForumFeed.Latest
                    }
                update {
                    it.copy(
                        sessionGeneration = session.generation,
                        isAuthenticated = authenticated != null,
                        accountUsername = authenticated?.username,
                        selection = feed,
                        topics = emptyList(),
                        categories = emptyList(),
                        tags = emptyList(),
                        nextPage = null,
                        isFeedLoading = true,
                        isAppending = false,
                        isTaxonomyLoading = true,
                        feedSource = null,
                        feedFailure = null,
                        appendFailure = null,
                        taxonomyFailure = null,
                        selectedTopicId = null,
                        selectedPostNumber = null,
                        selectedTopic = null,
                        isTopicLoading = false,
                        topicSource = null,
                        topicFailure = null,
                        search =
                            DiscourseForumSearchState(
                                query = previous.search.query,
                                submittedQuery = previous.search.submittedQuery,
                            ),
                        profile = DiscourseForumProfileState(),
                        notifications = DiscourseForumNotificationsState(),
                    )
                }
                startFeed(feed, page = 0, append = false, clearExisting = true)
                startTaxonomy()
                if (
                    previous.destination == DiscourseForumDestination.Search &&
                    previous.search.submittedQuery.isNotBlank()
                ) {
                    startSearch(
                        previous.search.submittedQuery,
                        DiscourseSearchPage.Initial,
                        append = false,
                    )
                } else if (previous.destination == DiscourseForumDestination.Notifications) {
                    startNotifications(DiscourseNotificationOffset.Initial, append = false)
                } else if (previous.destination == DiscourseForumDestination.Profile) {
                    startCurrentProfile()
                }
            }

            val actionForwarder =
                launch {
                    for (action in actions) events.send(ForumPresenterEvent.Action(action))
                }
            val sessionForwarder =
                launch {
                    sessionManager.state.collect { session ->
                        events.send(ForumPresenterEvent.SessionChanged(session))
                    }
                }

            try {
                for (event in events) {
                    when (event) {
                        is ForumPresenterEvent.SessionChanged -> {
                            handleSessionChanged(event.value)
                        }

                        is ForumPresenterEvent.Action -> {
                            when (val action = event.value) {
                                is DiscourseForumAction.SelectDestination -> {
                                    clearTopicSelection()
                                    update { it.copy(destination = action.destination) }
                                    when (action.destination) {
                                        DiscourseForumDestination.Latest -> {
                                            startFeed(DiscourseForumFeed.Latest, 0, append = false, clearExisting = true)
                                        }

                                        DiscourseForumDestination.Hot -> {
                                            startFeed(DiscourseForumFeed.Hot, 0, append = false, clearExisting = true)
                                        }

                                        DiscourseForumDestination.Search -> {
                                            val search = state().search
                                            if (search.submittedQuery.isNotBlank() && search.items.isEmpty()) {
                                                startSearch(search.submittedQuery, DiscourseSearchPage.Initial, append = false)
                                            }
                                        }

                                        DiscourseForumDestination.Notifications -> {
                                            startNotifications(DiscourseNotificationOffset.Initial, append = false)
                                        }

                                        DiscourseForumDestination.Profile -> {
                                            startCurrentProfile()
                                        }
                                    }
                                }

                                is DiscourseForumAction.SelectFeed -> {
                                    clearTopicSelection()
                                    update {
                                        it.copy(
                                            destination =
                                                if (action.feed == DiscourseForumFeed.Hot) {
                                                    DiscourseForumDestination.Hot
                                                } else {
                                                    DiscourseForumDestination.Latest
                                                },
                                        )
                                    }
                                    startFeed(action.feed, 0, append = false, clearExisting = true)
                                }

                                DiscourseForumAction.Refresh -> {
                                    when (state().destination) {
                                        DiscourseForumDestination.Latest,
                                        DiscourseForumDestination.Hot,
                                        -> {
                                            startFeed(state().selection, 0, append = false, clearExisting = false)
                                        }

                                        DiscourseForumDestination.Search -> {
                                            startSearch(state().search.submittedQuery, DiscourseSearchPage.Initial, append = false)
                                        }

                                        DiscourseForumDestination.Notifications -> {
                                            startNotifications(DiscourseNotificationOffset.Initial, append = false)
                                        }

                                        DiscourseForumDestination.Profile -> {
                                            startCurrentProfile()
                                        }
                                    }
                                }

                                DiscourseForumAction.RetryTaxonomy -> {
                                    if (!state().isTaxonomyLoading) startTaxonomy()
                                }

                                DiscourseForumAction.LoadNextPage -> {
                                    val current = state()
                                    val page = current.nextPage
                                    if (page != null && !current.isFeedLoading && !current.isAppending) {
                                        startFeed(current.selection, page, append = true, clearExisting = false)
                                    }
                                }

                                is DiscourseForumAction.OpenTopic -> {
                                    startTopic(action.topicId, action.postNumber)
                                }

                                DiscourseForumAction.CloseTopic -> {
                                    clearTopicSelection()
                                }

                                DiscourseForumAction.RetryTopic -> {
                                    state().selectedTopicId?.let { startTopic(it, state().selectedPostNumber) }
                                }

                                is DiscourseForumAction.UpdateSearchQuery -> {
                                    update { it.copy(search = it.search.copy(query = action.query)) }
                                }

                                DiscourseForumAction.SubmitSearch -> {
                                    clearTopicSelection()
                                    update { it.copy(destination = DiscourseForumDestination.Search) }
                                    startSearch(state().search.query, DiscourseSearchPage.Initial, append = false)
                                }

                                DiscourseForumAction.LoadNextSearchPage -> {
                                    val search = state().search
                                    val page = search.nextPage
                                    if (page != null && !search.isLoading && !search.isAppending) {
                                        startSearch(search.submittedQuery, page, append = true)
                                    }
                                }

                                DiscourseForumAction.RetrySearch -> {
                                    val search = state().search
                                    val append = search.appendFailure != null && search.nextPage != null
                                    startSearch(
                                        search.submittedQuery,
                                        if (append) checkNotNull(search.nextPage) else DiscourseSearchPage.Initial,
                                        append,
                                    )
                                }

                                is DiscourseForumAction.OpenProfile -> {
                                    clearTopicSelection()
                                    update { it.copy(destination = DiscourseForumDestination.Profile) }
                                    startProfile(action.username)
                                    startActivity(action.username, 0, append = false)
                                }

                                DiscourseForumAction.RetryProfile -> {
                                    state().profile.username?.let { username ->
                                        startProfile(username)
                                        startActivity(username, 0, append = false)
                                    } ?: startCurrentProfile()
                                }

                                DiscourseForumAction.LoadNextActivityPage -> {
                                    val profile = state().profile
                                    val username = profile.username
                                    val offset = profile.nextOffset
                                    if (
                                        username != null &&
                                        offset != null &&
                                        !profile.isActivityLoading &&
                                        !profile.isAppendingActivity
                                    ) {
                                        startActivity(username, offset, append = true)
                                    }
                                }

                                DiscourseForumAction.RefreshNotifications,
                                DiscourseForumAction.RetryNotifications,
                                -> {
                                    startNotifications(DiscourseNotificationOffset.Initial, append = false)
                                }

                                DiscourseForumAction.LoadNextNotificationsPage -> {
                                    val notifications = state().notifications
                                    val offset = notifications.nextOffset
                                    if (offset != null && !notifications.isLoading && !notifications.isAppending) {
                                        startNotifications(offset, append = true)
                                    }
                                }

                                is DiscourseForumAction.MarkNotificationsRead -> {
                                    startMarkNotificationsRead(action.notificationId)
                                }
                            }
                        }

                        is ForumPresenterEvent.FeedLoaded -> {
                            if (event.requestId != feedRequest) continue
                            update { current ->
                                val merged =
                                    if (event.append) {
                                        (current.topics + event.page.topics).distinctBy(UiTimelineV2.Topic::itemKey)
                                    } else {
                                        event.page.topics
                                    }
                                current.copy(
                                    topics = merged.withCategoryNames(current.categories),
                                    nextPage = event.page.nextPage,
                                    isFeedLoading = false,
                                    isAppending = false,
                                    feedSource = event.page.source,
                                    feedFailure = event.page.fallbackFailure,
                                    appendFailure = null,
                                )
                            }
                        }

                        is ForumPresenterEvent.FeedFailed -> {
                            if (event.requestId != feedRequest) continue
                            update { current ->
                                current.copy(
                                    isFeedLoading = false,
                                    isAppending = false,
                                    feedFailure = if (event.append) current.feedFailure else event.failure,
                                    appendFailure = if (event.append) event.failure else null,
                                )
                            }
                        }

                        is ForumPresenterEvent.CategoriesLoaded -> {
                            if (event.requestId != taxonomyRequest) continue
                            categoriesLoading = false
                            update { current ->
                                current.copy(
                                    categories = event.value.items,
                                    topics = current.topics.withCategoryNames(event.value.items),
                                    isTaxonomyLoading = tagsLoading,
                                    taxonomyFailure = event.value.fallbackFailure ?: current.taxonomyFailure,
                                )
                            }
                        }

                        is ForumPresenterEvent.CategoriesFailed -> {
                            if (event.requestId != taxonomyRequest) continue
                            categoriesLoading = false
                            update { it.copy(isTaxonomyLoading = tagsLoading, taxonomyFailure = event.failure) }
                        }

                        is ForumPresenterEvent.TagsLoaded -> {
                            if (event.requestId != taxonomyRequest) continue
                            tagsLoading = false
                            update { current ->
                                current.copy(
                                    tags = event.value.items,
                                    isTaxonomyLoading = categoriesLoading,
                                    taxonomyFailure = event.value.fallbackFailure ?: current.taxonomyFailure,
                                )
                            }
                        }

                        is ForumPresenterEvent.TagsFailed -> {
                            if (event.requestId != taxonomyRequest) continue
                            tagsLoading = false
                            update { it.copy(isTaxonomyLoading = categoriesLoading, taxonomyFailure = event.failure) }
                        }

                        is ForumPresenterEvent.TopicLoaded -> {
                            if (event.requestId != topicRequest || state().selectedTopicId != event.topicId) continue
                            update {
                                it.copy(
                                    selectedTopic = event.value,
                                    isTopicLoading = false,
                                    topicSource = event.value.source,
                                    topicFailure = event.value.fallbackFailure,
                                )
                            }
                        }

                        is ForumPresenterEvent.TopicFailed -> {
                            if (event.requestId != topicRequest || state().selectedTopicId != event.topicId) continue
                            update { it.copy(isTopicLoading = false, topicFailure = event.failure) }
                        }

                        is ForumPresenterEvent.SearchLoaded -> {
                            if (event.requestId != searchRequest) continue
                            update { current ->
                                current.copy(
                                    search =
                                        current.search.copy(
                                            items =
                                                if (event.append) {
                                                    (current.search.items + event.page.items)
                                                        .distinctBy(DiscourseForumSearchHit::postId)
                                                } else {
                                                    event.page.items
                                                },
                                            nextPage = event.page.nextPage,
                                            isLoading = false,
                                            isAppending = false,
                                            failure = null,
                                            appendFailure = null,
                                        ),
                                )
                            }
                        }

                        is ForumPresenterEvent.SearchFailed -> {
                            if (event.requestId != searchRequest) continue
                            update { current ->
                                current.copy(
                                    search =
                                        current.search.copy(
                                            isLoading = false,
                                            isAppending = false,
                                            failure = if (event.append) current.search.failure else event.failure,
                                            appendFailure = if (event.append) event.failure else null,
                                        ),
                                )
                            }
                        }

                        is ForumPresenterEvent.ProfileLoaded -> {
                            if (event.requestId != profileRequest || state().profile.username != event.username) continue
                            update { it.copy(profile = it.profile.copy(value = event.value, isLoading = false, failure = null)) }
                        }

                        is ForumPresenterEvent.ProfileFailed -> {
                            if (event.requestId != profileRequest || state().profile.username != event.username) continue
                            update { it.copy(profile = it.profile.copy(isLoading = false, failure = event.failure)) }
                        }

                        is ForumPresenterEvent.ActivityLoaded -> {
                            if (event.requestId != activityRequest || state().profile.username != event.username) continue
                            update { current ->
                                current.copy(
                                    profile =
                                        current.profile.copy(
                                            activity =
                                                if (event.append) {
                                                    mergeActivity(current.profile.activity, event.page.items)
                                                } else {
                                                    event.page.items
                                                },
                                            nextOffset = event.page.nextOffset,
                                            isActivityLoading = false,
                                            isAppendingActivity = false,
                                            activityFailure = null,
                                            activityAppendFailure = null,
                                        ),
                                )
                            }
                        }

                        is ForumPresenterEvent.ActivityFailed -> {
                            if (event.requestId != activityRequest || state().profile.username != event.username) continue
                            update { current ->
                                current.copy(
                                    profile =
                                        current.profile.copy(
                                            isActivityLoading = false,
                                            isAppendingActivity = false,
                                            activityFailure =
                                                if (event.append) current.profile.activityFailure else event.failure,
                                            activityAppendFailure = if (event.append) event.failure else null,
                                        ),
                                )
                            }
                        }

                        is ForumPresenterEvent.NotificationsLoaded -> {
                            if (event.requestId != notificationsRequest) continue
                            update { current ->
                                val snapshot =
                                    if (event.append && current.notifications.snapshot != null) {
                                        mergeNotifications(current.notifications.snapshot, event.page)
                                    } else {
                                        event.page.toSnapshot()
                                    }
                                current.copy(
                                    notifications =
                                        current.notifications.copy(
                                            snapshot = snapshot,
                                            nextOffset = event.page.nextOffset,
                                            isLoading = false,
                                            isAppending = false,
                                            failure = null,
                                            appendFailure = null,
                                        ),
                                )
                            }
                        }

                        is ForumPresenterEvent.NotificationsFailed -> {
                            if (event.requestId != notificationsRequest) continue
                            update { current ->
                                current.copy(
                                    notifications =
                                        current.notifications.copy(
                                            isLoading = false,
                                            isAppending = false,
                                            failure = if (event.append) current.notifications.failure else event.failure,
                                            appendFailure = if (event.append) event.failure else null,
                                        ),
                                )
                            }
                        }

                        is ForumPresenterEvent.NotificationsMarkedRead -> {
                            if (event.requestId != markReadRequest) continue
                            update { current ->
                                current.copy(
                                    notifications =
                                        current.notifications.copy(
                                            snapshot =
                                                current.notifications.snapshot?.let { latest ->
                                                    mergeMarkedNotificationState(latest, event.value)
                                                },
                                            isMarkingRead = false,
                                            markFailure = null,
                                        ),
                                )
                            }
                            startPendingNotifications()
                        }

                        is ForumPresenterEvent.NotificationsMarkFailed -> {
                            if (event.requestId != markReadRequest) continue
                            update {
                                it.copy(
                                    notifications =
                                        it.notifications.copy(
                                            isMarkingRead = false,
                                            markFailure = event.failure,
                                        ),
                                )
                            }
                            startPendingNotifications()
                        }
                    }
                }
            } finally {
                cancelGenerationWork()
                actionForwarder.cancel()
                sessionForwarder.cancel()
                actions.close()
                events.close()
            }
        }
}

private sealed interface ForumPresenterEvent {
    data class Action(
        val value: DiscourseForumAction,
    ) : ForumPresenterEvent

    data class SessionChanged(
        val value: DiscourseSessionState,
    ) : ForumPresenterEvent

    data class FeedLoaded(
        val requestId: Long,
        val page: DiscourseForumFeedPage,
        val append: Boolean,
    ) : ForumPresenterEvent

    data class FeedFailed(
        val requestId: Long,
        val failure: DiscourseForumFailureKind,
        val append: Boolean,
    ) : ForumPresenterEvent

    data class CategoriesLoaded(
        val requestId: Long,
        val value: DiscourseForumCategories,
    ) : ForumPresenterEvent

    data class CategoriesFailed(
        val requestId: Long,
        val failure: DiscourseForumFailureKind,
    ) : ForumPresenterEvent

    data class TagsLoaded(
        val requestId: Long,
        val value: DiscourseForumTags,
    ) : ForumPresenterEvent

    data class TagsFailed(
        val requestId: Long,
        val failure: DiscourseForumFailureKind,
    ) : ForumPresenterEvent

    data class TopicLoaded(
        val requestId: Long,
        val topicId: Long,
        val value: DiscourseForumTopic,
    ) : ForumPresenterEvent

    data class TopicFailed(
        val requestId: Long,
        val topicId: Long,
        val failure: DiscourseForumFailureKind,
    ) : ForumPresenterEvent

    data class SearchLoaded(
        val requestId: Long,
        val page: DiscourseForumSearchPage,
        val append: Boolean,
    ) : ForumPresenterEvent

    data class SearchFailed(
        val requestId: Long,
        val failure: DiscourseForumFailureKind,
        val append: Boolean,
    ) : ForumPresenterEvent

    data class ProfileLoaded(
        val requestId: Long,
        val username: String,
        val value: DiscourseForumProfile,
    ) : ForumPresenterEvent

    data class ProfileFailed(
        val requestId: Long,
        val username: String,
        val failure: DiscourseForumFailureKind,
    ) : ForumPresenterEvent

    data class ActivityLoaded(
        val requestId: Long,
        val username: String,
        val page: DiscourseForumActivityPage,
        val append: Boolean,
    ) : ForumPresenterEvent

    data class ActivityFailed(
        val requestId: Long,
        val username: String,
        val failure: DiscourseForumFailureKind,
        val append: Boolean,
    ) : ForumPresenterEvent

    data class NotificationsLoaded(
        val requestId: Long,
        val page: DiscourseForumNotificationPage,
        val append: Boolean,
    ) : ForumPresenterEvent

    data class NotificationsFailed(
        val requestId: Long,
        val failure: DiscourseForumFailureKind,
        val append: Boolean,
    ) : ForumPresenterEvent

    data class NotificationsMarkedRead(
        val requestId: Long,
        val value: DiscourseForumNotificationSnapshot,
    ) : ForumPresenterEvent

    data class NotificationsMarkFailed(
        val requestId: Long,
        val failure: DiscourseForumFailureKind,
    ) : ForumPresenterEvent
}

private suspend fun <T> loadPresenterEvent(
    block: suspend () -> T,
    success: (T) -> ForumPresenterEvent,
    failure: (DiscourseForumFailureKind) -> ForumPresenterEvent,
): ForumPresenterEvent? =
    try {
        success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: StaleDiscourseSessionException) {
        // A session event owns the replacement state and reload. Old work has no user-facing error.
        null
    } catch (expected: DiscourseException) {
        failure(expected.toForumFailureKind())
    } catch (_: Exception) {
        failure(DiscourseForumFailureKind.InvalidResponse)
    }

private fun mergeActivity(
    current: List<DiscourseForumActivity>,
    newer: List<DiscourseForumActivity>,
): List<DiscourseForumActivity> {
    val replacements = newer.associateBy(DiscourseForumActivity::itemKey)
    val currentKeys = current.mapTo(mutableSetOf(), DiscourseForumActivity::itemKey)
    return current.map { replacements[it.itemKey] ?: it } + newer.filter { it.itemKey !in currentKeys }
}

private fun mergeNotifications(
    current: DiscourseForumNotificationSnapshot,
    page: DiscourseForumNotificationPage,
): DiscourseForumNotificationSnapshot {
    val replacements = page.items.associateBy(DiscourseForumNotification::id)
    val currentIds = current.items.mapTo(mutableSetOf(), DiscourseForumNotification::id)
    return DiscourseForumNotificationSnapshot(
        items = current.items.map { replacements[it.id] ?: it } + page.items.filter { it.id !in currentIds },
        totalRows = page.totalRows,
        seenNotificationId = page.seenNotificationId,
    )
}

/**
 * Applies only the monotonic unread-to-read transition produced by a completed mutation.
 *
 * The mutation starts from an immutable snapshot, but a refresh or append can complete before its
 * response returns. Replacing the current snapshot would then drop new rows and rewind cursor
 * metadata. Rows absent from [marked] are retained, and a newer `read=true` value is never reverted.
 */
private fun mergeMarkedNotificationState(
    current: DiscourseForumNotificationSnapshot,
    marked: DiscourseForumNotificationSnapshot,
): DiscourseForumNotificationSnapshot {
    val markedById = marked.items.associateBy(DiscourseForumNotification::id)
    return current.copy(
        items =
            current.items.map { item ->
                val markedItem = markedById[item.id]
                if (markedItem?.read == true && !item.read) item.copy(read = true) else item
            },
    )
}

private fun List<UiTimelineV2.Topic>.withCategoryNames(categories: List<DiscourseForumCategoryOption>): List<UiTimelineV2.Topic> {
    if (isEmpty() || categories.isEmpty()) return this
    val names = categories.associate { it.id to it.name }
    return map { topic ->
        val categoryName = topic.discourse?.categoryId?.let(names::get) ?: topic.categoryName
        if (categoryName == topic.categoryName) topic else topic.copy(categoryName = categoryName)
    }
}

private fun Long.nextRequestId(): Long {
    check(this < Long.MAX_VALUE) { "Forum presenter request id is exhausted" }
    return this + 1L
}

private const val ACTION_CHANNEL_CAPACITY: Int = 48
private const val RESULT_CHANNEL_CAPACITY: Int = 48
