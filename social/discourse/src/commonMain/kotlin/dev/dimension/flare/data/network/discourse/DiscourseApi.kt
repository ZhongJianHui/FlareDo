package dev.dimension.flare.data.network.discourse

import dev.dimension.flare.data.network.discourse.model.DiscourseActionResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseBookmarkResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseCategoryListResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseCreateBookmarkRequest
import dev.dimension.flare.data.network.discourse.model.DiscourseCreatePostRequest
import dev.dimension.flare.data.network.discourse.model.DiscourseCurrentSessionResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseNotificationResponse
import dev.dimension.flare.data.network.discourse.model.DiscoursePostActionRequest
import dev.dimension.flare.data.network.discourse.model.DiscoursePostMutationResponse
import dev.dimension.flare.data.network.discourse.model.DiscoursePostStream
import dev.dimension.flare.data.network.discourse.model.DiscourseSearchResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseSiteResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseTagsResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicDetail
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicListResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUpdatePostRequest
import dev.dimension.flare.data.network.discourse.model.DiscourseUploadResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserActionsResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserBookmarkListResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserSummaryResponse
import dev.dimension.flare.data.network.discourse.paging.DiscourseListPage
import dev.dimension.flare.data.network.discourse.paging.DiscourseNotificationOffset
import dev.dimension.flare.data.network.discourse.paging.DiscourseSearchPage

/** Topic-list routes supported by Linux.do's Discourse installation. */
public enum class DiscourseTopicFeed(
    internal val pathSegment: String,
) {
    Latest("latest"),
    Hot("hot"),
    Top("top"),
    New("new"),
    Unread("unread"),
    Unseen("unseen"),
}

/** Search result family accepted by `/search.json`. */
public enum class DiscourseSearchType(
    internal val wireValue: String,
) {
    Topic("topic"),
    Post("post"),
    User("user"),
    Category("category"),
    Tag("tag"),
}

/** Stable category path components used by category-filtered topic feeds. */
public data class DiscourseCategoryRoute(
    public val id: Long,
    public val slug: String,
    public val parentSlug: String? = null,
) {
    init {
        require(id > 0L) { "Category id must be positive" }
        requireSafeRouteSegment(slug, "Category slug")
        parentSlug?.let { requireSafeRouteSegment(it, "Parent category slug") }
    }
}

/**
 * Filters common to category, tag, and root topic lists.
 *
 * Page zero is represented explicitly but omitted on the wire. [tags] are encoded as repeated
 * `tags[]` parameters and are never interpolated into a URL without path-component encoding.
 */
public data class DiscourseTopicListRequest(
    public val feed: DiscourseTopicFeed = DiscourseTopicFeed.Latest,
    public val page: DiscourseListPage = DiscourseListPage.Initial,
    public val category: DiscourseCategoryRoute? = null,
    public val tags: List<String> = emptyList(),
    public val period: String? = null,
    public val order: String? = null,
    public val ascending: Boolean? = null,
    public val subset: String? = null,
) {
    init {
        require(tags.size <= MAX_FILTER_TAGS) { "Too many topic filter tags" }
        tags.forEach { requireSafeRouteSegment(it, "Tag") }
        period?.let { requireSafeQueryToken(it, "Top period") }
        order?.let { requireSafeQueryToken(it, "Topic order") }
        subset?.let { requireSafeQueryToken(it, "Topic subset") }
    }

    private companion object {
        const val MAX_FILTER_TAGS: Int = 20
    }
}

/** Bytes and non-secret metadata for one composer upload. */
public data class DiscourseUploadRequest(
    public val bytes: ByteArray,
    public val fileName: String,
    public val contentType: String? = null,
    public val messageBusClientId: String? = null,
) {
    init {
        require(bytes.isNotEmpty()) { "Upload must not be empty" }
        require(fileName.isNotBlank()) { "Upload file name must not be blank" }
        require(fileName.length <= 512) { "Upload file name is too long" }
        require(fileName.none(Char::isControlCharacter)) {
            "Upload file name must not contain control characters"
        }
        contentType?.let {
            require(it.length <= 256 && it.none(Char::isControlCharacter)) {
                "Upload content type is invalid"
            }
        }
        messageBusClientId?.let { requireSafeQueryToken(it, "MessageBus client id") }
    }

    override fun equals(other: Any?): Boolean =
        other is DiscourseUploadRequest &&
            bytes.contentEquals(other.bytes) &&
            fileName == other.fileName &&
            contentType == other.contentType &&
            messageBusClientId == other.messageBusClientId

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + (contentType?.hashCode() ?: 0)
        result = 31 * result + (messageBusClientId?.hashCode() ?: 0)
        return result
    }
}

/**
 * Session-aware Linux.do API consumed by repositories and presenters.
 *
 * Implementations guarantee the fixed origin, cookie isolation, session-generation cancellation,
 * sanitized errors, and one CSRF refresh at most. Callers therefore must not add their own blind
 * retries around mutation methods.
 */
public interface DiscourseApi {
    public suspend fun site(): DiscourseSiteResponse

    public suspend fun categories(): DiscourseCategoryListResponse

    public suspend fun tags(): DiscourseTagsResponse

    public suspend fun topics(request: DiscourseTopicListRequest = DiscourseTopicListRequest()): DiscourseTopicListResponse

    public suspend fun topic(
        topicId: Long,
        trackVisit: Boolean = false,
    ): DiscourseTopicDetail

    /** Loads only the exact IDs previously supplied by `post_stream.stream`. */
    public suspend fun topicPosts(
        topicId: Long,
        postIds: List<Long>,
        includeSuggested: Boolean = false,
    ): DiscoursePostStream

    public suspend fun search(
        query: String,
        page: DiscourseSearchPage = DiscourseSearchPage.Initial,
        type: DiscourseSearchType? = null,
    ): DiscourseSearchResponse

    public suspend fun user(username: String): DiscourseUserResponse

    public suspend fun userSummary(username: String): DiscourseUserSummaryResponse

    public suspend fun userActions(
        username: String,
        offset: Int = 0,
        filter: String? = null,
    ): DiscourseUserActionsResponse

    public suspend fun notifications(
        offset: DiscourseNotificationOffset = DiscourseNotificationOffset.Initial,
        limit: Int = 60,
    ): DiscourseNotificationResponse

    public suspend fun currentSession(): DiscourseCurrentSessionResponse

    public suspend fun userBookmarks(
        username: String,
        page: DiscourseListPage = DiscourseListPage.Initial,
        limit: Int = 20,
    ): DiscourseUserBookmarkListResponse

    public suspend fun bookmarkedTopics(page: DiscourseListPage = DiscourseListPage.Initial): DiscourseTopicListResponse

    public suspend fun createPost(request: DiscourseCreatePostRequest): DiscoursePostMutationResponse

    public suspend fun updatePost(
        postId: Long,
        request: DiscourseUpdatePostRequest,
    ): DiscoursePostMutationResponse

    public suspend fun markNotificationsRead(notificationId: Long? = null)

    public suspend fun createPostAction(request: DiscoursePostActionRequest): DiscourseActionResponse

    public suspend fun deletePostAction(
        postId: Long,
        actionTypeId: Long,
    ): DiscourseActionResponse

    public suspend fun createBookmark(request: DiscourseCreateBookmarkRequest): DiscourseBookmarkResponse

    public suspend fun deleteBookmark(bookmarkId: Long)

    public suspend fun upload(request: DiscourseUploadRequest): DiscourseUploadResponse
}

private fun requireSafeRouteSegment(
    value: String,
    label: String,
) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value.length <= 256) { "$label is too long" }
    require(value.none(Char::isControlCharacter)) { "$label contains control characters" }
}

private fun requireSafeQueryToken(
    value: String,
    label: String,
) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value.length <= 256) { "$label is too long" }
    require(value.none(Char::isControlCharacter)) { "$label contains control characters" }
}

private fun Char.isControlCharacter(): Boolean = code < 0x20 || code == 0x7f
