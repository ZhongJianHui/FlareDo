package dev.dimension.flare.data.network.discourse.paging

import kotlin.jvm.JvmInline

/** Default number of exact post IDs sent to `/t/{topicId}/posts.json` in one request. */
public const val DISCOURSE_TOPIC_STREAM_BATCH_SIZE: Int = 20

/**
 * Position of the next unread ID in a normalized Discourse `post_stream.stream` list.
 *
 * A cursor may be greater than a particular stream's size when a cached cursor is restored after
 * posts were removed. [DiscourseTopicStreamPager.batch] clamps that case to the current end rather
 * than performing an out-of-bounds slice.
 */
@JvmInline
public value class DiscourseTopicStreamCursor(
    public val nextIndex: Int,
) {
    init {
        require(nextIndex >= 0) { "A topic stream cursor cannot be negative" }
    }

    public companion object {
        /** Cursor pointing at the first post ID. */
        public val Initial: DiscourseTopicStreamCursor = DiscourseTopicStreamCursor(0)
    }
}

/**
 * Exact post IDs for one topic-stream request and the cursor to use after it completes.
 *
 * [postIds] always preserves server stream order. It never contains an ID that was absent from the
 * stream supplied to [DiscourseTopicStreamPager].
 */
public data class DiscourseTopicPostBatch(
    public val postIds: List<Long>,
    public val nextCursor: DiscourseTopicStreamCursor,
    public val hasMore: Boolean,
)

/**
 * Splits the ordered ID vector returned in `post_stream.stream` into exact request batches.
 *
 * Discourse detail responses contain only a window of post objects but include the complete ordered
 * post-ID vector. Loading by post number can return a server-selected window and is therefore not a
 * reliable cache or de-duplication boundary. This pager instead sends only IDs copied from that
 * vector to the `post_ids[]` query parameter.
 *
 * Duplicate IDs are removed while retaining their first server position. Although a valid Discourse
 * response should already be unique, normalizing here prevents duplicate entities and cursor stalls
 * after malformed or overlapping fixture responses. Non-positive IDs are rejected because silently
 * dropping them would change cursor meaning and hide a corrupt response.
 */
public class DiscourseTopicStreamPager(
    streamPostIds: List<Long>,
    public val batchSize: Int = DISCOURSE_TOPIC_STREAM_BATCH_SIZE,
) {
    init {
        require(batchSize > 0) { "A topic stream batch must contain at least one post" }
        require(streamPostIds.all { it > 0L }) { "Discourse post IDs must be positive" }
    }

    /** Ordered, de-duplicated post IDs used for every batch calculation. */
    public val postIds: List<Long> = streamPostIds.distinct()

    /** Number of IDs in the normalized stream. */
    public val size: Int get() = postIds.size

    /**
     * Returns the batch beginning at [cursor].
     *
     * A cursor past the current end produces an empty terminal batch whose next cursor is clamped to
     * [size]. End calculation subtracts before adding, so even an extreme cursor or batch size cannot
     * overflow an `Int` or create an invalid `subList` range.
     */
    public fun batch(cursor: DiscourseTopicStreamCursor = DiscourseTopicStreamCursor.Initial): DiscourseTopicPostBatch {
        val start = cursor.nextIndex.coerceAtMost(size)
        val count = batchSize.coerceAtMost(size - start)
        val endExclusive = start + count
        return DiscourseTopicPostBatch(
            postIds = postIds.subList(start, endExclusive).toList(),
            nextCursor = DiscourseTopicStreamCursor(endExclusive),
            hasMore = endExclusive < size,
        )
    }
}
