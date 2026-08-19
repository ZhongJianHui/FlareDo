package dev.dimension.flare.data.network.discourse.forum

import dev.dimension.flare.data.network.discourse.DiscourseCategoryRoute
import dev.dimension.flare.data.network.discourse.DiscourseDataSource
import dev.dimension.flare.data.network.discourse.DiscourseTopicFeed
import dev.dimension.flare.data.network.discourse.DiscourseTopicListRequest
import dev.dimension.flare.data.network.discourse.DiscourseTopicPostPage
import dev.dimension.flare.data.network.discourse.error.DiscourseAuthenticationException
import dev.dimension.flare.data.network.discourse.error.DiscourseCloudflareChallengeException
import dev.dimension.flare.data.network.discourse.error.DiscourseCsrfException
import dev.dimension.flare.data.network.discourse.error.DiscourseException
import dev.dimension.flare.data.network.discourse.error.DiscourseHttpException
import dev.dimension.flare.data.network.discourse.error.DiscourseNetworkException
import dev.dimension.flare.data.network.discourse.error.DiscoursePermissionException
import dev.dimension.flare.data.network.discourse.error.DiscoursePostEnqueuedException
import dev.dimension.flare.data.network.discourse.error.DiscourseRateLimitException
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationException
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationPhase
import dev.dimension.flare.data.network.discourse.error.DiscourseServerException
import dev.dimension.flare.data.network.discourse.model.DiscourseCategoryListResponse
import dev.dimension.flare.data.network.discourse.model.DiscoursePost
import dev.dimension.flare.data.network.discourse.model.DiscourseTagsResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicDetail
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicListResponse
import dev.dimension.flare.data.network.discourse.paging.DiscourseListPage
import dev.dimension.flare.data.network.discourse.paging.DiscourseTopicStreamCursor
import dev.dimension.flare.data.network.discourse.paging.DiscourseTopicStreamPager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.Clock

/** Read-only forum operations consumed by the shared Molecule presenter. */
public interface DiscourseForumRepository {
    public suspend fun loadFeed(
        feed: DiscourseForumFeed,
        page: Int = 0,
    ): DiscourseForumFeedPage

    public suspend fun loadCategories(): DiscourseForumCategories

    public suspend fun loadTags(): DiscourseForumTags

    public suspend fun loadTopic(topicId: Long): DiscourseForumTopic
}

/**
 * Linux.do repository with deterministic paging, strict post-stream aggregation, and guest-only
 * stale cache fallback.
 *
 * Every remote request, mapping pass, and eligible cache access runs inside one immutable
 * [DiscourseSessionManager] lease. Authenticated responses can contain permissions, unread state,
 * and other account-specific fields, so they are never read from or written to the anonymous
 * persistent cache. Only typed [DiscourseException] failures in a guest lease may select a stale
 * public snapshot. Cancellation is always rethrown, and local programming/database failures remain
 * visible to their owner instead of being mislabeled as an offline response.
 */
public class DefaultDiscourseForumRepository internal constructor(
    private val remote: DiscourseForumRemoteDataSource,
    private val mapper: DiscourseForumMapper,
    private val cache: DiscourseForumCache,
    private val sessionManager: DiscourseSessionManager,
    private val nowEpochMillis: () -> Long,
) : DiscourseForumRepository {
    public constructor(
        dataSource: DiscourseDataSource,
        mapper: DiscourseForumMapper,
        cache: DiscourseForumCache,
        sessionManager: DiscourseSessionManager,
        nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ) : this(
        remote = DefaultDiscourseForumRemoteDataSource(dataSource),
        mapper = mapper,
        cache = cache,
        sessionManager = sessionManager,
        nowEpochMillis = nowEpochMillis,
    )

    override suspend fun loadFeed(
        feed: DiscourseForumFeed,
        page: Int,
    ): DiscourseForumFeedPage {
        require(page >= 0) { "Forum feed page cannot be negative" }
        return sessionManager.runForCurrentSession {
            val mayUsePublicCache = this is DiscourseSessionState.Guest
            try {
                val categoryNames =
                    if (mayUsePublicCache) {
                        cache
                            .getCategories()
                            ?.items
                            .orEmpty()
                            .associate { it.id to it.name }
                    } else {
                        emptyMap()
                    }
                val response =
                    remote.topics(
                        DiscourseTopicListRequest(
                            feed =
                                when (feed) {
                                    DiscourseForumFeed.Hot -> DiscourseTopicFeed.Hot
                                    else -> DiscourseTopicFeed.Latest
                                },
                            page = DiscourseListPage(page),
                            category =
                                (feed as? DiscourseForumFeed.Category)?.let {
                                    DiscourseCategoryRoute(
                                        id = it.id,
                                        slug = it.slug,
                                        parentSlug = it.parentSlug,
                                    )
                                },
                            tags = (feed as? DiscourseForumFeed.Tag)?.let { listOf(it.slug) }.orEmpty(),
                        ),
                    )
                val fresh =
                    mapper.mapFeedPage(
                        response = response,
                        feed = feed,
                        page = page,
                        updatedAtEpochMillis = checkedNow(),
                        categoryNames = categoryNames,
                    )
                if (mayUsePublicCache) {
                    // A hostile or faulty transport can swallow cancellation. Check the lease again
                    // before any account-derived response is allowed to reach anonymous storage.
                    currentCoroutineContext().ensureActive()
                    cache.putFeed(fresh)
                }
                fresh
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: DiscourseException) {
                if (!mayUsePublicCache) throw failure
                currentCoroutineContext().ensureActive()
                cache.getFeed(feed, page)?.asStale(failure.toForumFailureKind()) ?: throw failure
            }
        }
    }

    override suspend fun loadCategories(): DiscourseForumCategories =
        sessionManager.runForCurrentSession {
            val mayUsePublicCache = this is DiscourseSessionState.Guest
            try {
                val fresh = mapper.mapCategories(remote.categories(), checkedNow())
                if (mayUsePublicCache) {
                    currentCoroutineContext().ensureActive()
                    cache.putCategories(fresh)
                }
                fresh
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: DiscourseException) {
                if (!mayUsePublicCache) throw failure
                currentCoroutineContext().ensureActive()
                cache.getCategories()?.asStale(failure.toForumFailureKind()) ?: throw failure
            }
        }

    override suspend fun loadTags(): DiscourseForumTags =
        sessionManager.runForCurrentSession {
            val mayUsePublicCache = this is DiscourseSessionState.Guest
            try {
                val fresh = mapper.mapTags(remote.tags(), checkedNow())
                if (mayUsePublicCache) {
                    currentCoroutineContext().ensureActive()
                    cache.putTags(fresh)
                }
                fresh
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: DiscourseException) {
                if (!mayUsePublicCache) throw failure
                currentCoroutineContext().ensureActive()
                cache.getTags()?.asStale(failure.toForumFailureKind()) ?: throw failure
            }
        }

    override suspend fun loadTopic(topicId: Long): DiscourseForumTopic {
        require(topicId > 0L) { "Forum topic id must be positive" }
        return sessionManager.runForCurrentSession {
            val mayUsePublicCache = this is DiscourseSessionState.Guest
            try {
                val detail = remote.topic(topicId = topicId, trackVisit = false)
                val orderedPosts = loadAuthoritativePostStream(topicId, detail)
                val fresh = mapper.mapTopic(detail, orderedPosts, checkedNow())
                if (mayUsePublicCache) {
                    currentCoroutineContext().ensureActive()
                    cache.putTopic(fresh)
                }
                fresh
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: DiscourseException) {
                if (!mayUsePublicCache) throw failure
                currentCoroutineContext().ensureActive()
                cache.getTopic(topicId)?.asStale(failure.toForumFailureKind()) ?: throw failure
            }
        }
    }

    private suspend fun loadAuthoritativePostStream(
        topicId: Long,
        detail: DiscourseTopicDetail,
    ): List<DiscoursePost> {
        validateTopicEnvelope(topicId, detail)
        val stream = detail.postStream.stream
        val pager =
            try {
                DiscourseTopicStreamPager(stream)
            } catch (_: IllegalArgumentException) {
                throw protocolFailure()
            }
        val postsById =
            detail.postStream.posts
                .associateBy(DiscoursePost::id)
                .toMutableMap()
        var cursor = DiscourseTopicStreamCursor.Initial

        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = pager.batch(cursor)
            if (batch.postIds.isEmpty()) break
            if (batch.postIds.any { it !in postsById }) {
                val loaded =
                    remote.topicPosts(
                        topicId = topicId,
                        streamPostIds = stream,
                        cursor = cursor,
                        includeSuggested = false,
                    )
                if (
                    loaded.nextCursor != batch.nextCursor ||
                    loaded.hasMore != batch.hasMore ||
                    loaded.posts.map(DiscoursePost::id) != batch.postIds ||
                    loaded.posts.any { it.topicId != topicId }
                ) {
                    throw protocolFailure()
                }
                loaded.posts.forEach { postsById[it.id] = it }
            }
            cursor = batch.nextCursor
            if (!batch.hasMore) break
        }

        return pager.postIds.map { postId -> postsById[postId] ?: throw protocolFailure() }
    }

    private fun checkedNow(): Long = nowEpochMillis().also { require(it >= 0L) { "Forum clock cannot be negative" } }
}

/** Narrow adapter used to unit-test repository ordering and cancellation without a real HTTP client. */
internal interface DiscourseForumRemoteDataSource {
    suspend fun categories(): DiscourseCategoryListResponse

    suspend fun tags(): DiscourseTagsResponse

    suspend fun topics(request: DiscourseTopicListRequest): DiscourseTopicListResponse

    suspend fun topic(
        topicId: Long,
        trackVisit: Boolean,
    ): DiscourseTopicDetail

    suspend fun topicPosts(
        topicId: Long,
        streamPostIds: List<Long>,
        cursor: DiscourseTopicStreamCursor,
        includeSuggested: Boolean,
    ): DiscourseTopicPostPage
}

private class DefaultDiscourseForumRemoteDataSource(
    private val dataSource: DiscourseDataSource,
) : DiscourseForumRemoteDataSource {
    override suspend fun categories(): DiscourseCategoryListResponse = dataSource.categories()

    override suspend fun tags(): DiscourseTagsResponse = dataSource.tags()

    override suspend fun topics(request: DiscourseTopicListRequest): DiscourseTopicListResponse = dataSource.topics(request)

    override suspend fun topic(
        topicId: Long,
        trackVisit: Boolean,
    ): DiscourseTopicDetail = dataSource.topic(topicId, trackVisit)

    override suspend fun topicPosts(
        topicId: Long,
        streamPostIds: List<Long>,
        cursor: DiscourseTopicStreamCursor,
        includeSuggested: Boolean,
    ): DiscourseTopicPostPage =
        dataSource.topicPosts(
            topicId = topicId,
            streamPostIds = streamPostIds,
            cursor = cursor,
            includeSuggested = includeSuggested,
        )
}

internal fun DiscourseException.toForumFailureKind(): DiscourseForumFailureKind =
    when (this) {
        is DiscourseNetworkException -> {
            DiscourseForumFailureKind.Network
        }

        is DiscourseAuthenticationException -> {
            DiscourseForumFailureKind.Authentication
        }

        is DiscoursePermissionException, is DiscourseCsrfException -> {
            DiscourseForumFailureKind.Permission
        }

        is DiscourseRateLimitException -> {
            DiscourseForumFailureKind.RateLimited
        }

        is DiscourseCloudflareChallengeException -> {
            DiscourseForumFailureKind.ChallengeRequired
        }

        is DiscourseServerException -> {
            DiscourseForumFailureKind.Server
        }

        is DiscourseSerializationException, is DiscoursePostEnqueuedException -> {
            DiscourseForumFailureKind.InvalidResponse
        }

        is DiscourseHttpException -> {
            DiscourseForumFailureKind.Http
        }
    }

private fun validateTopicEnvelope(
    topicId: Long,
    detail: DiscourseTopicDetail,
) {
    val streamIds = detail.postStream.stream
    val initialPosts = detail.postStream.posts
    if (
        detail.id != topicId ||
        streamIds.any { it <= 0L } ||
        streamIds.distinct().size != streamIds.size ||
        (detail.postsCount > 0 && streamIds.isEmpty()) ||
        initialPosts.map(DiscoursePost::id).distinct().size != initialPosts.size ||
        initialPosts.any { it.id !in streamIds || it.topicId != topicId }
    ) {
        throw protocolFailure()
    }
}

private fun DiscourseForumFeedPage.asStale(failure: DiscourseForumFailureKind): DiscourseForumFeedPage =
    copy(source = DiscourseForumContentSource.StaleCache, fallbackFailure = failure)

private fun DiscourseForumCategories.asStale(failure: DiscourseForumFailureKind): DiscourseForumCategories =
    copy(source = DiscourseForumContentSource.StaleCache, fallbackFailure = failure)

private fun DiscourseForumTags.asStale(failure: DiscourseForumFailureKind): DiscourseForumTags =
    copy(source = DiscourseForumContentSource.StaleCache, fallbackFailure = failure)

private fun DiscourseForumTopic.asStale(failure: DiscourseForumFailureKind): DiscourseForumTopic =
    copy(source = DiscourseForumContentSource.StaleCache, fallbackFailure = failure)

private fun protocolFailure(): DiscourseSerializationException =
    DiscourseSerializationException(DiscourseSerializationPhase.ResponseDecoding)
