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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

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

            assertSame(cancellation, thrown)
        }

    private fun repository(
        remote: DiscourseForumRemoteDataSource,
        cache: DiscourseForumCache = MemoryDiscourseForumCache(),
        now: Long = 100L,
    ): DefaultDiscourseForumRepository =
        DefaultDiscourseForumRepository(
            remote = remote,
            mapper = mapper,
            cache = cache,
            nowEpochMillis = { now },
        )
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
