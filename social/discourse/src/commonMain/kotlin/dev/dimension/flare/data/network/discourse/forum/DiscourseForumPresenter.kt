package dev.dimension.flare.data.network.discourse.forum

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.dimension.flare.data.network.discourse.error.DiscourseException
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.presenter.PresenterBase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Molecule presenter shared by Compose and SwiftUI forum shells.
 *
 * Public actions enter a bounded channel. A single actor owns every state mutation while child jobs
 * only return immutable results, so an old feed/topic request can never overwrite a newer selection.
 * [PresenterBase.close] cancels the Molecule scope; the actor then cancels its children and closes
 * both channels. Hosts must call `close()` from their screen/ViewModel lifecycle.
 */
public class DiscourseForumPresenter(
    private val repository: DiscourseForumRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PresenterBase<DiscourseForumState>(dispatcher) {
    private val actions = Channel<DiscourseForumAction>(capacity = ACTION_CHANNEL_CAPACITY)

    /**
     * Queues an action without blocking a UI thread.
     *
     * False means the presenter is closed or the bounded queue is full; callers may safely ignore a
     * repeated scroll/paging action and try again after the next rendered state.
     */
    public fun dispatch(action: DiscourseForumAction): Boolean = actions.trySend(action).isSuccess

    @Composable
    override fun body(): DiscourseForumState {
        var state by remember { mutableStateOf(DiscourseForumState()) }
        LaunchedEffect(repository) {
            runActor(
                state = { state },
                setState = { state = it },
            )
        }
        return state
    }

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
            var feedRequest = 0L
            var topicRequest = 0L
            var taxonomyRequest = 0L
            var categoriesLoading = true
            var tagsLoading = true

            fun update(transform: (DiscourseForumState) -> DiscourseForumState) {
                setState(transform(state()))
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
                        val result =
                            try {
                                val loaded = repository.loadFeed(feed, page)
                                check(loaded.feed == feed && loaded.page == page)
                                ForumPresenterEvent.FeedLoaded(requestId, loaded, append)
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (failure: DiscourseException) {
                                ForumPresenterEvent.FeedFailed(
                                    requestId,
                                    failure.toForumFailureKind(),
                                    append,
                                )
                            } catch (_: Exception) {
                                ForumPresenterEvent.FeedFailed(
                                    requestId,
                                    DiscourseForumFailureKind.InvalidResponse,
                                    append,
                                )
                            }
                        events.send(result)
                    }
            }

            fun startTopic(topicId: Long) {
                topicJob?.cancel()
                topicRequest = topicRequest.nextRequestId()
                val requestId = topicRequest
                update { current ->
                    current.copy(
                        selectedTopicId = topicId,
                        selectedTopic = null,
                        isTopicLoading = true,
                        topicSource = null,
                        topicFailure = null,
                    )
                }
                topicJob =
                    launch {
                        val result =
                            try {
                                val loaded = repository.loadTopic(topicId)
                                check(loaded.topicId == topicId)
                                ForumPresenterEvent.TopicLoaded(requestId, topicId, loaded)
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (failure: DiscourseException) {
                                ForumPresenterEvent.TopicFailed(
                                    requestId,
                                    topicId,
                                    failure.toForumFailureKind(),
                                )
                            } catch (_: Exception) {
                                ForumPresenterEvent.TopicFailed(
                                    requestId,
                                    topicId,
                                    DiscourseForumFailureKind.InvalidResponse,
                                )
                            }
                        events.send(result)
                    }
            }

            fun startTaxonomy() {
                taxonomyRequest = taxonomyRequest.nextRequestId()
                val requestId = taxonomyRequest
                categoriesLoading = true
                tagsLoading = true
                update {
                    it.copy(
                        isTaxonomyLoading = true,
                        taxonomyFailure = null,
                    )
                }
                categoriesJob?.cancel()
                categoriesJob =
                    launch {
                        val result =
                            try {
                                ForumPresenterEvent.CategoriesLoaded(
                                    requestId,
                                    repository.loadCategories(),
                                )
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (failure: DiscourseException) {
                                ForumPresenterEvent.CategoriesFailed(
                                    requestId,
                                    failure.toForumFailureKind(),
                                )
                            } catch (_: Exception) {
                                ForumPresenterEvent.CategoriesFailed(
                                    requestId,
                                    DiscourseForumFailureKind.InvalidResponse,
                                )
                            }
                        events.send(result)
                    }
                tagsJob?.cancel()
                tagsJob =
                    launch {
                        val result =
                            try {
                                ForumPresenterEvent.TagsLoaded(requestId, repository.loadTags())
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (failure: DiscourseException) {
                                ForumPresenterEvent.TagsFailed(
                                    requestId,
                                    failure.toForumFailureKind(),
                                )
                            } catch (_: Exception) {
                                ForumPresenterEvent.TagsFailed(
                                    requestId,
                                    DiscourseForumFailureKind.InvalidResponse,
                                )
                            }
                        events.send(result)
                    }
            }

            val actionForwarder =
                launch {
                    for (action in actions) events.send(ForumPresenterEvent.Action(action))
                }

            startFeed(
                feed = state().selection,
                page = 0,
                append = false,
                clearExisting = true,
            )
            startTaxonomy()

            try {
                for (event in events) {
                    when (event) {
                        is ForumPresenterEvent.Action -> {
                            when (val action = event.value) {
                                is DiscourseForumAction.SelectFeed -> {
                                    topicJob?.cancel()
                                    topicRequest = topicRequest.nextRequestId()
                                    update {
                                        it.copy(
                                            selectedTopicId = null,
                                            selectedTopic = null,
                                            isTopicLoading = false,
                                            topicSource = null,
                                            topicFailure = null,
                                        )
                                    }
                                    startFeed(
                                        feed = action.feed,
                                        page = 0,
                                        append = false,
                                        clearExisting = true,
                                    )
                                }

                                DiscourseForumAction.Refresh -> {
                                    startFeed(
                                        feed = state().selection,
                                        page = 0,
                                        append = false,
                                        clearExisting = false,
                                    )
                                }

                                DiscourseForumAction.RetryTaxonomy -> {
                                    if (!state().isTaxonomyLoading) startTaxonomy()
                                }

                                DiscourseForumAction.LoadNextPage -> {
                                    val current = state()
                                    val page = current.nextPage
                                    if (
                                        page != null &&
                                        !current.isFeedLoading &&
                                        !current.isAppending
                                    ) {
                                        startFeed(
                                            feed = current.selection,
                                            page = page,
                                            append = true,
                                            clearExisting = false,
                                        )
                                    }
                                }

                                is DiscourseForumAction.OpenTopic -> {
                                    startTopic(action.topicId)
                                }

                                DiscourseForumAction.CloseTopic -> {
                                    topicJob?.cancel()
                                    topicRequest = topicRequest.nextRequestId()
                                    update {
                                        it.copy(
                                            selectedTopicId = null,
                                            selectedTopic = null,
                                            isTopicLoading = false,
                                            topicSource = null,
                                            topicFailure = null,
                                        )
                                    }
                                }

                                DiscourseForumAction.RetryTopic -> {
                                    state().selectedTopicId?.let(::startTopic)
                                }
                            }
                        }

                        is ForumPresenterEvent.FeedLoaded -> {
                            if (event.requestId != feedRequest) continue
                            update { current ->
                                val merged =
                                    if (event.append) {
                                        (current.topics + event.page.topics)
                                            .distinctBy(UiTimelineV2.Topic::itemKey)
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
                                    taxonomyFailure =
                                        event.value.fallbackFailure ?: current.taxonomyFailure,
                                )
                            }
                        }

                        is ForumPresenterEvent.CategoriesFailed -> {
                            if (event.requestId != taxonomyRequest) continue
                            categoriesLoading = false
                            update {
                                it.copy(
                                    isTaxonomyLoading = tagsLoading,
                                    taxonomyFailure = event.failure,
                                )
                            }
                        }

                        is ForumPresenterEvent.TagsLoaded -> {
                            if (event.requestId != taxonomyRequest) continue
                            tagsLoading = false
                            update { current ->
                                current.copy(
                                    tags = event.value.items,
                                    isTaxonomyLoading = categoriesLoading,
                                    taxonomyFailure =
                                        event.value.fallbackFailure ?: current.taxonomyFailure,
                                )
                            }
                        }

                        is ForumPresenterEvent.TagsFailed -> {
                            if (event.requestId != taxonomyRequest) continue
                            tagsLoading = false
                            update {
                                it.copy(
                                    isTaxonomyLoading = categoriesLoading,
                                    taxonomyFailure = event.failure,
                                )
                            }
                        }

                        is ForumPresenterEvent.TopicLoaded -> {
                            if (
                                event.requestId != topicRequest ||
                                state().selectedTopicId != event.topicId
                            ) {
                                continue
                            }
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
                            if (
                                event.requestId != topicRequest ||
                                state().selectedTopicId != event.topicId
                            ) {
                                continue
                            }
                            update {
                                it.copy(
                                    isTopicLoading = false,
                                    topicFailure = event.failure,
                                )
                            }
                        }
                    }
                }
            } finally {
                feedJob?.cancel()
                topicJob?.cancel()
                categoriesJob?.cancel()
                tagsJob?.cancel()
                actionForwarder.cancel()
                actions.close()
                events.close()
            }
        }
}

private sealed interface ForumPresenterEvent {
    data class Action(
        val value: DiscourseForumAction,
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

private const val ACTION_CHANNEL_CAPACITY: Int = 32
private const val RESULT_CHANNEL_CAPACITY: Int = 32
