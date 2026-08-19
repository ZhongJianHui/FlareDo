package dev.dimension.flare.data.network.discourse.forum

import dev.dimension.flare.data.network.discourse.DiscourseTopicFeed
import dev.dimension.flare.data.network.discourse.DiscourseTopicListRequest
import dev.dimension.flare.data.network.discourse.DiscourseTopicPostPage
import dev.dimension.flare.data.network.discourse.content.DiscourseCookedHtmlParser
import dev.dimension.flare.data.network.discourse.error.DiscourseNetworkException
import dev.dimension.flare.data.network.discourse.error.DiscourseNetworkFailureKind
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationException
import dev.dimension.flare.data.network.discourse.model.DiscourseCategoryList
import dev.dimension.flare.data.network.discourse.model.DiscourseCategoryListResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseTagsResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicDetail
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicList
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicListResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicSummary
import dev.dimension.flare.data.network.discourse.paging.DiscourseTopicStreamCursor
import dev.dimension.flare.data.network.discourse.paging.DiscourseTopicStreamPager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.StaleDiscourseSessionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class DiscourseForumRepositoryTest {
    private val mapper = DiscourseForumMapper(DiscourseCookedHtmlParser())

    @Test
    fun listRoutesUseZeroBasedPagingAndFeedSpecificFilters() =
        runTest {
            val remote = RecordingForumRemote()
            val repository = repository(remote)
            val category =
                DiscourseForumFeed.Category(
                    id = 8L,
                    slug = "mobile",
                    parentSlug = "development",
                    name = "Mobile",
                )
            val tag = DiscourseForumFeed.Tag(name = "Kotlin", slug = "kotlin")

            repository.loadFeed(DiscourseForumFeed.Latest)
            repository.loadFeed(DiscourseForumFeed.Hot, page = 1)
            repository.loadFeed(category, page = 2)
            repository.loadFeed(tag)

            assertEquals(listOf(0, 1, 2, 0), remote.topicRequests.map { it.page.value })
            assertEquals(DiscourseTopicFeed.Latest, remote.topicRequests[0].feed)
            assertEquals(DiscourseTopicFeed.Hot, remote.topicRequests[1].feed)
            assertEquals(8L, remote.topicRequests[2].category?.id)
            assertEquals("development", remote.topicRequests[2].category?.parentSlug)
            assertEquals(listOf("kotlin"), remote.topicRequests[3].tags)
        }

    @Test
    fun typedFailureReturnsExplicitStaleSnapshotWithoutWritingItBack() =
        runTest {
            val cache = MemoryDiscourseForumCache()
            val success = RecordingForumRemote()
            success.topicsBlock = {
                topicListResponse(
                    topics = listOf(DiscourseTopicSummary(1L, "Cached topic", "cached-topic")),
                )
            }
            val fresh = repository(success, cache, now = 10L).loadFeed(DiscourseForumFeed.Latest)
            val failure = RecordingForumRemote()
            failure.topicsBlock = {
                throw DiscourseNetworkException(DiscourseNetworkFailureKind.Connection)
            }

            val stale = repository(failure, cache, now = 20L).loadFeed(DiscourseForumFeed.Latest)

            assertEquals(DiscourseForumContentSource.StaleCache, stale.source)
            assertEquals(DiscourseForumFailureKind.Network, stale.fallbackFailure)
            assertEquals(fresh.updatedAtEpochMillis, stale.updatedAtEpochMillis)
            val stored = requireNotNull(cache.getFeed(DiscourseForumFeed.Latest, 0))
            assertEquals(DiscourseForumContentSource.Network, stored.source)
            assertEquals(null, stored.fallbackFailure)
        }

    @Test
    fun topicAggregationSkipsCompleteBatchesButRefetchesAnEntirePartialBatch() =
        runTest {
            val stream = (1L..45L).toList()
            val remote = RecordingForumRemote()
            remote.topicBlock = { _, _ ->
                discourseTopicDetail(
                    stream = stream,
                    initialPosts = (1L..21L).map { discoursePost(it) },
                )
            }
            remote.topicPostsBlock = { topicId, originalStream, cursor, _ ->
                assertEquals(stream, originalStream)
                val batch = DiscourseTopicStreamPager(originalStream).batch(cursor)
                DiscourseTopicPostPage(
                    posts = batch.postIds.map { discoursePost(it, topicId = topicId) },
                    nextCursor = batch.nextCursor,
                    hasMore = batch.hasMore,
                )
            }

            val topic = repository(remote).loadTopic(42L)

            assertEquals(
                listOf(DiscourseTopicStreamCursor(20), DiscourseTopicStreamCursor(40)),
                remote.topicPostCursors,
            )
            assertEquals(stream.map { "discourse-post:$it" }, topic.articles.map { it.itemKey })
        }

    @Test
    fun duplicateAuthoritativeStreamIdentityFailsBeforeAnyBatchRequest() =
        runTest {
            val remote = RecordingForumRemote()
            remote.topicBlock = { _, _ ->
                discourseTopicDetail(
                    stream = listOf(1L, 2L, 1L),
                    initialPosts = listOf(discoursePost(1L), discoursePost(2L)),
                )
            }

            assertFailsWith<DiscourseSerializationException> {
                repository(remote).loadTopic(42L)
            }
            assertEquals(emptyList(), remote.topicPostCursors)
        }

    @Test
    fun cancellationIsNeverConvertedToStaleContent() =
        runTest {
            val remote = RecordingForumRemote()
            val cancellation = CancellationException("test cancellation")
            remote.topicsBlock = { throw cancellation }

            val thrown =
                assertFailsWith<CancellationException> {
                    repository(remote).loadFeed(DiscourseForumFeed.Latest)
                }

            assertEquals(cancellation.message, thrown.message)
        }

    @Test
    fun authenticatedResponsesNeverReadOrWriteTheAnonymousCache() =
        runTest {
            val sessionManager = DiscourseSessionManager()
            sessionManager.startAuthenticatedSession(accountId = "account-42")
            val cache = RecordingForumCache()
            val remote = RecordingForumRemote()
            val repository = repository(remote, cache, sessionManager = sessionManager)

            repository.loadFeed(DiscourseForumFeed.Latest)
            repository.loadCategories()
            repository.loadTags()
            repository.loadTopic(topicId = 42L)

            assertEquals(0, cache.readCount)
            assertEquals(0, cache.writeCount)

            remote.topicsBlock = {
                throw DiscourseNetworkException(DiscourseNetworkFailureKind.Connection)
            }
            assertFailsWith<DiscourseNetworkException> {
                repository.loadFeed(DiscourseForumFeed.Latest)
            }
            assertEquals(0, cache.readCount)
            assertEquals(0, cache.writeCount)
        }

    @Test
    fun generationReplacementCannotLateWriteAResponseFromTheOldGuestLease() =
        runTest {
            supervisorScope {
                val sessionManager = DiscourseSessionManager()
                val requestStarted = CompletableDeferred<Unit>()
                val cache = RecordingForumCache()
                val remote = RecordingForumRemote()
                remote.topicsBlock = {
                    requestStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } catch (_: CancellationException) {
                        // Simulate a faulty transport which swallows the generation cancellation and
                        // still returns account-derived response data to its caller.
                        topicListResponse(
                            topics = listOf(DiscourseTopicSummary(42L, "Private state", "private-state")),
                        )
                    }
                }
                val repository = repository(remote, cache, sessionManager = sessionManager)
                val loading = async { repository.loadFeed(DiscourseForumFeed.Latest) }
                requestStarted.await()

                sessionManager.startAuthenticatedSession(accountId = "replacement-account")

                assertFailsWith<StaleDiscourseSessionException> { loading.await() }
                assertEquals(0, cache.writeCount)
            }
        }

    private fun repository(
        remote: DiscourseForumRemoteDataSource,
        cache: DiscourseForumCache = MemoryDiscourseForumCache(),
        now: Long = 100L,
        sessionManager: DiscourseSessionManager = DiscourseSessionManager(),
    ): DefaultDiscourseForumRepository =
        DefaultDiscourseForumRepository(
            remote = remote,
            mapper = mapper,
            cache = cache,
            sessionManager = sessionManager,
            nowEpochMillis = { now },
        )
}

private class RecordingForumCache(
    private val delegate: DiscourseForumCache = MemoryDiscourseForumCache(),
) : DiscourseForumCache {
    var readCount: Int = 0
        private set
    var writeCount: Int = 0
        private set

    override suspend fun getFeed(
        feed: DiscourseForumFeed,
        page: Int,
    ): DiscourseForumFeedPage? {
        readCount += 1
        return delegate.getFeed(feed, page)
    }

    override suspend fun putFeed(value: DiscourseForumFeedPage) {
        writeCount += 1
        delegate.putFeed(value)
    }

    override suspend fun getCategories(): DiscourseForumCategories? {
        readCount += 1
        return delegate.getCategories()
    }

    override suspend fun putCategories(value: DiscourseForumCategories) {
        writeCount += 1
        delegate.putCategories(value)
    }

    override suspend fun getTags(): DiscourseForumTags? {
        readCount += 1
        return delegate.getTags()
    }

    override suspend fun putTags(value: DiscourseForumTags) {
        writeCount += 1
        delegate.putTags(value)
    }

    override suspend fun getTopic(topicId: Long): DiscourseForumTopic? {
        readCount += 1
        return delegate.getTopic(topicId)
    }

    override suspend fun putTopic(value: DiscourseForumTopic) {
        writeCount += 1
        delegate.putTopic(value)
    }

    override suspend fun clear() {
        delegate.clear()
    }
}

private class RecordingForumRemote : DiscourseForumRemoteDataSource {
    val topicRequests = mutableListOf<DiscourseTopicListRequest>()
    val topicPostCursors = mutableListOf<DiscourseTopicStreamCursor>()

    var topicsBlock: suspend (DiscourseTopicListRequest) -> DiscourseTopicListResponse = {
        topicListResponse()
    }
    var topicBlock: suspend (Long, Boolean) -> DiscourseTopicDetail = { topicId, _ ->
        discourseTopicDetail(topicId = topicId, stream = listOf(1L), initialPosts = listOf(discoursePost(1L, topicId)))
    }
    var topicPostsBlock:
        suspend (Long, List<Long>, DiscourseTopicStreamCursor, Boolean) -> DiscourseTopicPostPage =
        { topicId, stream, cursor, _ ->
            val batch = DiscourseTopicStreamPager(stream).batch(cursor)
            DiscourseTopicPostPage(
                posts = batch.postIds.map { discoursePost(it, topicId) },
                nextCursor = batch.nextCursor,
                hasMore = batch.hasMore,
            )
        }

    override suspend fun categories(): DiscourseCategoryListResponse = DiscourseCategoryListResponse(DiscourseCategoryList())

    override suspend fun tags(): DiscourseTagsResponse = DiscourseTagsResponse()

    override suspend fun topics(request: DiscourseTopicListRequest): DiscourseTopicListResponse {
        topicRequests += request
        return topicsBlock(request)
    }

    override suspend fun topic(
        topicId: Long,
        trackVisit: Boolean,
    ): DiscourseTopicDetail = topicBlock(topicId, trackVisit)

    override suspend fun topicPosts(
        topicId: Long,
        streamPostIds: List<Long>,
        cursor: DiscourseTopicStreamCursor,
        includeSuggested: Boolean,
    ): DiscourseTopicPostPage {
        topicPostCursors += cursor
        return topicPostsBlock(topicId, streamPostIds, cursor, includeSuggested)
    }
}

private fun topicListResponse(topics: List<DiscourseTopicSummary> = emptyList()): DiscourseTopicListResponse =
    DiscourseTopicListResponse(
        topicList = DiscourseTopicList(topics = topics),
    )
