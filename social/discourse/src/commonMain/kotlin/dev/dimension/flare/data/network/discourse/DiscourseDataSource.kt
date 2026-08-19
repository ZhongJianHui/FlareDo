package dev.dimension.flare.data.network.discourse

import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationException
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationPhase
import dev.dimension.flare.data.network.discourse.model.DiscourseCategoryListResponse
import dev.dimension.flare.data.network.discourse.model.DiscoursePost
import dev.dimension.flare.data.network.discourse.model.DiscourseSearchResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseSiteResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseTagsResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicDetail
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicListResponse
import dev.dimension.flare.data.network.discourse.paging.DiscourseSearchPage
import dev.dimension.flare.data.network.discourse.paging.DiscourseTopicStreamCursor
import dev.dimension.flare.data.network.discourse.paging.DiscourseTopicStreamPager

/** One verified page of a topic's authoritative post stream. */
public data class DiscourseTopicPostPage(
    public val posts: List<DiscoursePost>,
    public val nextCursor: DiscourseTopicStreamCursor,
    public val hasMore: Boolean,
)

/**
 * Repository-facing Linux.do data source.
 *
 * This class owns cross-response invariants that do not belong in transport DTOs. In particular,
 * topic details supply the only authoritative post-ID order; batch responses are accepted only when
 * they contain exactly those requested IDs and all posts still belong to the requested topic.
 */
public class DiscourseDataSource(
    public val api: DiscourseApi,
) {
    public suspend fun site(): DiscourseSiteResponse = api.site()

    public suspend fun categories(): DiscourseCategoryListResponse = api.categories()

    public suspend fun tags(): DiscourseTagsResponse = api.tags()

    public suspend fun topics(request: DiscourseTopicListRequest = DiscourseTopicListRequest()): DiscourseTopicListResponse =
        api.topics(request)

    public suspend fun topic(
        topicId: Long,
        trackVisit: Boolean = false,
    ): DiscourseTopicDetail {
        val detail = api.topic(topicId = topicId, trackVisit = trackVisit)
        val streamIds = detail.postStream.stream.toSet()
        val initialPosts = detail.postStream.posts
        if (
            detail.id != topicId ||
            detail.postStream.stream.any { it <= 0L } ||
            (detail.postsCount > 0 && detail.postStream.stream.isEmpty()) ||
            initialPosts.map(DiscoursePost::id).distinct().size != initialPosts.size ||
            initialPosts.any { it.id !in streamIds || it.topicId != topicId }
        ) {
            throw protocolFailure()
        }
        // Constructing the pager also rejects malformed IDs and establishes the normalized order.
        DiscourseTopicStreamPager(detail.postStream.stream)
        return detail
    }

    /**
     * Loads one exact 20-ID slice from [streamPostIds].
     *
     * The response is reordered to the original stream, never to network arrival order. Missing,
     * duplicate, unexpected, or cross-topic posts are treated as protocol failures rather than
     * silently advancing the cursor and leaving a permanent gap in the local cache.
     */
    public suspend fun topicPosts(
        topicId: Long,
        streamPostIds: List<Long>,
        cursor: DiscourseTopicStreamCursor = DiscourseTopicStreamCursor.Initial,
        includeSuggested: Boolean = false,
    ): DiscourseTopicPostPage {
        val batch = DiscourseTopicStreamPager(streamPostIds).batch(cursor)
        if (batch.postIds.isEmpty()) {
            return DiscourseTopicPostPage(
                posts = emptyList(),
                nextCursor = batch.nextCursor,
                hasMore = false,
            )
        }

        val response =
            api.topicPosts(
                topicId = topicId,
                postIds = batch.postIds,
                includeSuggested = includeSuggested,
            )
        val postsById = response.posts.associateBy(DiscoursePost::id)
        if (
            postsById.size != response.posts.size ||
            postsById.keys != batch.postIds.toSet() ||
            response.posts.any { it.topicId != topicId }
        ) {
            throw protocolFailure()
        }

        return DiscourseTopicPostPage(
            posts = batch.postIds.map { postId -> checkNotNull(postsById[postId]) },
            nextCursor = batch.nextCursor,
            hasMore = batch.hasMore,
        )
    }

    public suspend fun search(
        query: String,
        page: DiscourseSearchPage = DiscourseSearchPage.Initial,
        type: DiscourseSearchType? = null,
    ): DiscourseSearchResponse = api.search(query = query, page = page, type = type)
}

private fun protocolFailure(): DiscourseSerializationException =
    DiscourseSerializationException(DiscourseSerializationPhase.ResponseDecoding)
