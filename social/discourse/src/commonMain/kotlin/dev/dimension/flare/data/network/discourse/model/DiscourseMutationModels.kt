package dev.dimension.flare.data.network.discourse.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Typed request body shared by new-topic and reply creation. */
@Serializable
public data class DiscourseCreatePostRequest(
    public val raw: String,
    public val title: String? = null,
    @SerialName("topic_id")
    public val topicId: Long? = null,
    public val category: Long? = null,
    public val archetype: String? = null,
    @SerialName("reply_to_post_number")
    public val replyToPostNumber: Int? = null,
    public val tags: List<String> = emptyList(),
)

/** Request body for editing an existing post. */
@Serializable
public data class DiscourseUpdatePostRequest(
    @SerialName("post[raw]")
    public val raw: String,
    @SerialName("post[edit_reason]")
    public val editReason: String? = null,
)

/**
 * Authenticated source used to edit one existing post.
 *
 * Topic streams commonly omit [raw], so an editor must request `/posts/{id}.json` instead of trying
 * to reconstruct Markdown from sanitized cooked HTML. This narrow DTO keeps only durable routing
 * identity and the authoritative Markdown source; callers must not place [raw] in shared public
 * caches, diagnostics, or exception messages.
 */
@Serializable
public data class DiscourseEditablePost(
    public val id: Long,
    @SerialName("topic_id")
    public val topicId: Long,
    @SerialName("post_number")
    public val postNumber: Int,
    public val raw: String,
)

/**
 * Result of topic creation, reply creation, or post editing.
 *
 * A normal response contains [post]. Sites with approval enabled may instead return
 * `action = "enqueued"` with pending metadata and no durable post ID. Consumers must not apply an
 * optimistic successful-post state in that case. The serializer also accepts Discourse's direct-post
 * and `{ "post": ... }` success variants.
 */
@Serializable(with = DiscoursePostMutationResponseSerializer::class)
public data class DiscoursePostMutationResponse(
    public val post: DiscoursePost? = null,
    public val action: String? = null,
    @SerialName("pending_count")
    public val pendingCount: Int? = null,
    @SerialName("pending_post")
    public val pendingPost: DiscoursePendingPost? = null,
    @SerialName("topic_id")
    public val topicId: Long? = null,
) {
    /** True when the server accepted the content for review instead of publishing it. */
    public val isEnqueued: Boolean
        get() = action == "enqueued"
}

@Serializable
private data class DiscoursePostMutationWire(
    val post: DiscoursePost? = null,
    val action: String? = null,
    @SerialName("pending_count")
    val pendingCount: Int? = null,
    @SerialName("pending_post")
    val pendingPost: DiscoursePendingPost? = null,
    @SerialName("topic_id")
    val topicId: Long? = null,
)

/**
 * Numeric-only approval queue envelope selected before decoding a possible top-level `post`.
 *
 * Some plugins may add a preview-shaped `post` beside `action = "enqueued"`. It is deliberately an
 * unknown field for this wire type, so private raw/cooked content and user metadata are discarded
 * instead of constructing a full [DiscoursePost] that can escape the moderation branch.
 */
@Serializable
private data class DiscourseEnqueuedPostMutationWire(
    val action: String = DISCOURSE_ENQUEUED_ACTION,
    @SerialName("pending_count")
    val pendingCount: Int? = null,
    @SerialName("pending_post")
    val pendingPost: DiscoursePendingPost? = null,
    @SerialName("topic_id")
    val topicId: Long? = null,
)

/** Serializer for normal post and moderation-queue mutation variants. */
public object DiscoursePostMutationResponseSerializer : KSerializer<DiscoursePostMutationResponse> {
    override val descriptor: SerialDescriptor = DiscoursePostMutationWire.serializer().descriptor

    override fun deserialize(decoder: Decoder): DiscoursePostMutationResponse {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException(
                    "Discourse post mutation responses can only be decoded from JSON",
                )
        val element = jsonDecoder.decodeJsonElement()
        val payload =
            element as? JsonObject
                ?: throw SerializationException("Discourse post mutation response must be a JSON object")

        val actionDiscriminator =
            (payload["action"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
        return if (actionDiscriminator == DISCOURSE_ENQUEUED_ACTION) {
            val wire = jsonDecoder.json.decodeFromJsonElement<DiscourseEnqueuedPostMutationWire>(element)
            DiscoursePostMutationResponse(
                post = null,
                action = DISCOURSE_ENQUEUED_ACTION,
                pendingCount = wire.pendingCount,
                pendingPost = wire.pendingPost,
                topicId = wire.topicId,
            )
        } else if ("post" in payload || "action" in payload) {
            val wire = jsonDecoder.json.decodeFromJsonElement<DiscoursePostMutationWire>(element)
            if (wire.post == null) {
                throw SerializationException("Discourse post mutation response has no published post")
            }
            DiscoursePostMutationResponse(
                post = wire.post,
                action = wire.action,
                pendingCount = wire.pendingCount,
                pendingPost = wire.pendingPost,
                topicId = wire.topicId ?: wire.post.topicId,
            )
        } else {
            val post = jsonDecoder.json.decodeFromJsonElement<DiscoursePost>(element)
            DiscoursePostMutationResponse(post = post, topicId = post.topicId)
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: DiscoursePostMutationResponse,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException(
                    "Discourse post mutation responses can only be encoded as JSON",
                )
        val element =
            if (value.isEnqueued) {
                jsonEncoder.json.encodeToJsonElement(
                    DiscourseEnqueuedPostMutationWire(
                        pendingCount = value.pendingCount,
                        pendingPost = value.pendingPost,
                        topicId = value.topicId,
                    ),
                )
            } else {
                jsonEncoder.json.encodeToJsonElement(
                    DiscoursePostMutationWire(
                        post = value.post,
                        action = value.action,
                        pendingCount = value.pendingCount,
                        pendingPost = value.pendingPost,
                        topicId = value.topicId,
                    ),
                )
            }
        jsonEncoder.encodeJsonElement(element)
    }
}

private const val DISCOURSE_ENQUEUED_ACTION: String = "enqueued"

/**
 * Minimal non-durable identity included with an approval queue response.
 *
 * Every field is optional because some Discourse versions expose only preview content and queue
 * count. Free-form `raw`, `cooked`, title, and user fields are intentionally not modeled: unknown
 * JSON is discarded during decoding so unapproved private content cannot survive in this response
 * object, logs, or caches. These numeric hints are used only for bounded identity validation and
 * must never be inserted into the durable post cache until a later response supplies a published
 * [DiscoursePost].
 */
@Serializable
public data class DiscoursePendingPost(
    public val id: Long? = null,
    @SerialName("topic_id")
    public val topicId: Long? = null,
    @SerialName("post_number")
    public val postNumber: Int? = null,
)

/**
 * Successful upload descriptor returned by `POST /uploads.json`.
 *
 * [shortUrl] is normally the Markdown-safe reference used in composer content, but legacy or plugin
 * serializers may return only [url]. [resolvedReference] applies that fallback. The custom serializer
 * rejects a response missing both references, because such an upload cannot be inserted into a post.
 * The numeric upload ID is optional; FlareDo never substitutes a sentinel ID.
 */
@Serializable(with = DiscourseUploadResponseSerializer::class)
public data class DiscourseUploadResponse(
    public val id: Long? = null,
    @SerialName("short_url")
    public val shortUrl: String? = null,
    public val url: String? = null,
    @SerialName("original_filename")
    public val originalFilename: String = "",
    public val width: Int? = null,
    public val height: Int? = null,
    @SerialName("thumbnail_width")
    public val thumbnailWidth: Int? = null,
    @SerialName("thumbnail_height")
    public val thumbnailHeight: Int? = null,
    public val filesize: Long? = null,
    @SerialName("human_filesize")
    public val humanFilesize: String? = null,
    public val extension: String? = null,
) {
    /** Preferred server reference for composer Markdown. */
    public val resolvedReference: String
        get() = shortUrl?.takeIf(String::isNotBlank) ?: requireNotNull(url?.takeIf(String::isNotBlank))
}

@Serializable
private data class DiscourseUploadWire(
    val id: Long? = null,
    @SerialName("short_url")
    val shortUrl: String? = null,
    val url: String? = null,
    @SerialName("original_filename")
    val originalFilename: String = "",
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("thumbnail_width")
    val thumbnailWidth: Int? = null,
    @SerialName("thumbnail_height")
    val thumbnailHeight: Int? = null,
    val filesize: Long? = null,
    @SerialName("human_filesize")
    val humanFilesize: String? = null,
    val extension: String? = null,
)

/** Serializer enforcing that a successful upload has a usable server reference. */
public object DiscourseUploadResponseSerializer : KSerializer<DiscourseUploadResponse> {
    override val descriptor: SerialDescriptor = DiscourseUploadWire.serializer().descriptor

    override fun deserialize(decoder: Decoder): DiscourseUploadResponse {
        val wire = decoder.decodeSerializableValue(DiscourseUploadWire.serializer())
        if (wire.shortUrl.isNullOrBlank() && wire.url.isNullOrBlank()) {
            throw SerializationException("Discourse upload response has neither short_url nor url")
        }
        return DiscourseUploadResponse(
            id = wire.id,
            shortUrl = wire.shortUrl,
            url = wire.url,
            originalFilename = wire.originalFilename,
            width = wire.width,
            height = wire.height,
            thumbnailWidth = wire.thumbnailWidth,
            thumbnailHeight = wire.thumbnailHeight,
            filesize = wire.filesize,
            humanFilesize = wire.humanFilesize,
            extension = wire.extension,
        )
    }

    override fun serialize(
        encoder: Encoder,
        value: DiscourseUploadResponse,
    ) {
        encoder.encodeSerializableValue(
            DiscourseUploadWire.serializer(),
            DiscourseUploadWire(
                id = value.id,
                shortUrl = value.shortUrl,
                url = value.url,
                originalFilename = value.originalFilename,
                width = value.width,
                height = value.height,
                thumbnailWidth = value.thumbnailWidth,
                thumbnailHeight = value.thumbnailHeight,
                filesize = value.filesize,
                humanFilesize = value.humanFilesize,
                extension = value.extension,
            ),
        )
    }
}

/** Request body for adding a like, flag, or other post action. */
@Serializable
public data class DiscoursePostActionRequest(
    public val id: Long,
    @SerialName("post_action_type_id")
    public val postActionTypeId: Long,
    public val flagTopic: Boolean = false,
    public val message: String? = null,
)

/**
 * Narrow wire projection of the official full-Post acknowledgement returned by action routes.
 *
 * Discourse serializes the entire post after creating or deleting an action. The acknowledgement
 * needs only route identity and `actions_summary`; decoding it as [DiscoursePost] would needlessly
 * keep cooked/raw content, usernames, and plugin fields alive until the mutation completes. Unknown
 * full-Post fields are still accepted by [discourseJson], but are discarded during decoding.
 */
@Serializable
internal data class DiscoursePostActionWireResponse(
    val id: Long,
    @SerialName("topic_id")
    val topicId: Long,
    @SerialName("post_number")
    val postNumber: Int,
    @SerialName("actions_summary")
    val actionsSummary: List<DiscoursePostActionSummary> = emptyList(),
)

/** Which official success shape proved a post action mutation. */
public enum class DiscourseActionResponseKind {
    /** The endpoint returned a full PostSerializer payload with authoritative `actions_summary`. */
    FullPost,

    /** Action deletion returned an explicit HTTP 204 with no response body. */
    NoContent,
}

/**
 * Sanitized acknowledgement constructed only after validating the official endpoint response.
 *
 * `POST /post_actions` must return a full post; `DELETE /post_actions/{id}` returns either the full
 * post or HTTP 204. The transport never deserializes legacy `{post_action: ...}` or empty JSON into
 * this type, so repositories cannot accidentally treat an unrelated or body-less create as success.
 */
public data class DiscourseActionResponse(
    public val postId: Long,
    public val postActionTypeId: Long,
    public val acted: Boolean,
    public val kind: DiscourseActionResponseKind,
    /** Authoritative aggregate from a full Post response; null only for HTTP 204. */
    public val count: Int?,
    /** Whether the server permits creating this action next; null only for HTTP 204. */
    public val canAct: Boolean?,
    /** Whether the server permits undoing this action next; null only for HTTP 204. */
    public val canUndo: Boolean?,
) {
    init {
        require(postId > 0L) { "Action response post id must be positive" }
        require(postActionTypeId > 0L) { "Action response type id must be positive" }
        require(count == null || count >= 0) { "Action response count cannot be negative" }
        require(kind != DiscourseActionResponseKind.NoContent || !acted) {
            "A no-content response can only confirm action deletion"
        }
        require(
            when (kind) {
                DiscourseActionResponseKind.FullPost -> count != null && canAct != null && canUndo != null
                DiscourseActionResponseKind.NoContent -> count == null && canAct == null && canUndo == null
            },
        ) {
            "Only a full Post response can carry authoritative action state"
        }
    }
}

/** Request body for creating a topic or post bookmark. */
@Serializable
public data class DiscourseCreateBookmarkRequest(
    @SerialName("bookmarkable_id")
    public val bookmarkableId: Long,
    @SerialName("bookmarkable_type")
    public val bookmarkableType: String,
    public val name: String? = null,
    @SerialName("reminder_at")
    public val reminderAt: String? = null,
    @SerialName("auto_delete_preference")
    public val autoDeletePreference: Int? = null,
)

/** Bookmark creation response; a missing ID is a protocol failure. */
@Serializable
public data class DiscourseBookmarkResponse(
    public val id: Long,
)

/** Durable bookmark record shown on a topic, post, or user bookmark list. */
@Serializable
public data class DiscourseBookmark(
    public val id: Long,
    @SerialName("bookmarkable_type")
    public val bookmarkableType: String,
    @SerialName("bookmarkable_id")
    public val bookmarkableId: Long,
    public val name: String? = null,
    @SerialName("reminder_at")
    public val reminderAt: String? = null,
    @SerialName("created_at")
    public val createdAt: String? = null,
    @SerialName("updated_at")
    public val updatedAt: String? = null,
    @SerialName("auto_delete_preference")
    public val autoDeletePreference: Int? = null,
    public val topic: DiscourseTopicSummary? = null,
    public val post: DiscoursePost? = null,
)

/** User bookmark list envelope. */
@Serializable
public data class DiscourseUserBookmarkListResponse(
    @SerialName("user_bookmark_list")
    public val userBookmarkList: DiscourseUserBookmarkList? = null,
    public val bookmarks: List<DiscourseBookmark> = emptyList(),
)

/** Paginated bookmark collection returned by profile endpoints. */
@Serializable
public data class DiscourseUserBookmarkList(
    public val bookmarks: List<DiscourseBookmark>,
    @SerialName("more_bookmarks_url")
    public val moreBookmarksUrl: String? = null,
)
