package dev.dimension.flare.data.network.discourse

import dev.dimension.flare.data.network.discourse.model.DiscourseActionResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseBookmarkResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseCategoryListResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseCreateBookmarkRequest
import dev.dimension.flare.data.network.discourse.model.DiscourseCreatePostRequest
import dev.dimension.flare.data.network.discourse.model.DiscourseCurrentSessionResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseEditablePost
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

/**
 * Owned bytes and non-secret metadata for one composer upload.
 *
 * Both the constructor and [bytes] accessor copy the array. Callers therefore cannot mutate an
 * already validated or in-flight upload through either their source array or a returned view.
 * [copy], equality, and hashing retain data-class-like content semantics without exposing the
 * private backing array.
 */
public class DiscourseUploadRequest(
    bytes: ByteArray,
    public val fileName: String,
    public val contentType: String? = null,
    public val messageBusClientId: String? = null,
) {
    private val ownedBytes: ByteArray = bytes.toOwnedUploadSnapshot()

    /** Returns an independent snapshot; changing it cannot affect this request. */
    public val bytes: ByteArray
        get() = ownedBytes.copyOf()

    init {
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
            ownedBytes.contentEquals(other.ownedBytes) &&
            fileName == other.fileName &&
            contentType == other.contentType &&
            messageBusClientId == other.messageBusClientId

    override fun hashCode(): Int {
        var result = ownedBytes.contentHashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + (contentType?.hashCode() ?: 0)
        result = 31 * result + (messageBusClientId?.hashCode() ?: 0)
        return result
    }

    /** Data-class-compatible copy that independently owns its byte argument. */
    public fun copy(
        bytes: ByteArray = ownedBytes,
        fileName: String = this.fileName,
        contentType: String? = this.contentType,
        messageBusClientId: String? = this.messageBusClientId,
    ): DiscourseUploadRequest =
        DiscourseUploadRequest(
            bytes = bytes,
            fileName = fileName,
            contentType = contentType,
            messageBusClientId = messageBusClientId,
        )

    public operator fun component1(): ByteArray = bytes

    public operator fun component2(): String = fileName

    public operator fun component3(): String? = contentType

    public operator fun component4(): String? = messageBusClientId

    override fun toString(): String =
        "DiscourseUploadRequest(bytes=<${ownedBytes.size} bytes>, " +
            "fileName=<redacted>, contentType=$contentType, " +
            "messageBusClientId=${if (messageBusClientId == null) "absent" else "present"})"

    /**
     * Borrows the validated backing array for the trusted multipart transport only.
     *
     * The returned array must never be mutated or exposed outside the request body. Unlike the
     * public [bytes] accessor, this internal path deliberately avoids a second full-file copy: the
     * request already owns an immutable snapshot, and uploads are capped because the multipart
     * writer retains that snapshot until the request completes.
     */
    internal fun borrowOwnedBytesForTransport(): ByteArray = ownedBytes
}

private fun ByteArray.toOwnedUploadSnapshot(): ByteArray {
    require(isNotEmpty()) { "Upload must not be empty" }
    require(size <= MAX_DISCOURSE_UPLOAD_BYTES) { "Upload exceeds the client memory bound" }
    return copyOf()
}

/**
 * Monotonic progress reported while Ktor writes one multipart request body.
 *
 * The byte counts cover the complete multipart body, including its small protocol envelope. The
 * listener executes in the request coroutine: throwing or cancelling it aborts the upload instead
 * of leaving an unstructured writer running after the composer has closed.
 */
public fun interface DiscourseUploadProgressListener {
    public suspend fun onProgress(
        bytesSent: Long,
        contentLength: Long?,
    )
}

/**
 * Allocation bound applied before a platform-selected file enters the all-in-memory upload path.
 *
 * The request owns one snapshot and Ktor retains it while building and writing multipart content;
 * 16 MiB keeps that bounded on phones while leaving the server free to enforce a smaller limit.
 */
public const val MAX_DISCOURSE_UPLOAD_BYTES: Int = 16 * 1024 * 1024

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

    /** Invalidates the active web session on Linux.do before local fail-closed cleanup. */
    public suspend fun logout(username: String)

    /** Invalidates only the exact authenticated owner captured by a delayed host callback. */
    public suspend fun logout(
        username: String,
        expectedSessionGeneration: Long,
        expectedAccountId: String,
    )

    public suspend fun userBookmarks(
        username: String,
        page: DiscourseListPage = DiscourseListPage.Initial,
        limit: Int = 20,
    ): DiscourseUserBookmarkListResponse

    public suspend fun bookmarkedTopics(page: DiscourseListPage = DiscourseListPage.Initial): DiscourseTopicListResponse

    public suspend fun createPost(request: DiscourseCreatePostRequest): DiscoursePostMutationResponse

    /** Loads authenticated, authoritative Markdown for an editor without using the public cache. */
    public suspend fun editablePost(postId: Long): DiscourseEditablePost

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

    public suspend fun upload(
        request: DiscourseUploadRequest,
        progressListener: DiscourseUploadProgressListener? = null,
    ): DiscourseUploadResponse
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
