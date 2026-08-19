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

        return if ("post" in payload || "action" in payload) {
            val wire = jsonDecoder.json.decodeFromJsonElement<DiscoursePostMutationWire>(element)
            if (wire.post == null && wire.action != "enqueued") {
                throw SerializationException("Discourse post mutation response has no published post")
            }
            DiscoursePostMutationResponse(
                post = wire.post,
                action = wire.action,
                pendingCount = wire.pendingCount,
                pendingPost = wire.pendingPost,
                topicId = wire.topicId ?: wire.post?.topicId,
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
        val wire =
            DiscoursePostMutationWire(
                post = value.post,
                action = value.action,
                pendingCount = value.pendingCount,
                pendingPost = value.pendingPost,
                topicId = value.topicId,
            )
        jsonEncoder.encodeJsonElement(jsonEncoder.json.encodeToJsonElement(wire))
    }
}

/**
 * Non-durable post information included with an approval queue response.
 *
 * Its ID is optional because some Discourse versions expose only preview content and queue count.
 * This type must never be inserted into the durable post cache until a later response supplies a
 * real [DiscoursePost] identity.
 */
@Serializable
public data class DiscoursePendingPost(
    public val id: Long? = null,
    @SerialName("topic_id")
    public val topicId: Long? = null,
    @SerialName("post_number")
    public val postNumber: Int? = null,
    public val raw: String? = null,
    public val cooked: String? = null,
    public val title: String? = null,
    public val username: String? = null,
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
 * Generic action response supporting core post actions and plugin reactions.
 *
 * Delete-action endpoints may return an empty object, so every result field is optional. HTTP status
 * remains authoritative for those endpoints.
 */
@Serializable(with = DiscourseActionResponseSerializer::class)
public data class DiscourseActionResponse(
    @SerialName("post_action")
    public val postAction: DiscoursePostAction? = null,
    public val reactions: List<DiscourseReaction> = emptyList(),
    @SerialName("current_user_reaction")
    public val currentUserReaction: DiscourseReaction? = null,
    public val success: String? = null,
)

@Serializable
private data class DiscourseActionWire(
    @SerialName("post_action")
    val postAction: DiscoursePostAction? = null,
    val reactions: List<DiscourseReaction> = emptyList(),
    @SerialName("current_user_reaction")
    val currentUserReaction: DiscourseReaction? = null,
    val success: String? = null,
)

/** Serializer accepting wrapped and direct core post-action records. */
public object DiscourseActionResponseSerializer : KSerializer<DiscourseActionResponse> {
    override val descriptor: SerialDescriptor = DiscourseActionWire.serializer().descriptor

    override fun deserialize(decoder: Decoder): DiscourseActionResponse {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("Discourse action responses can only be decoded from JSON")
        val element = jsonDecoder.decodeJsonElement()
        val payload =
            element as? JsonObject
                ?: throw SerializationException("Discourse action response must be a JSON object")
        return if (
            "post_action" in payload ||
            "reactions" in payload ||
            "current_user_reaction" in payload ||
            "success" in payload ||
            payload.isEmpty()
        ) {
            val wire = jsonDecoder.json.decodeFromJsonElement<DiscourseActionWire>(element)
            DiscourseActionResponse(
                postAction = wire.postAction,
                reactions = wire.reactions,
                currentUserReaction = wire.currentUserReaction,
                success = wire.success,
            )
        } else {
            DiscourseActionResponse(
                postAction = jsonDecoder.json.decodeFromJsonElement<DiscoursePostAction>(element),
            )
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: DiscourseActionResponse,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("Discourse action responses can only be encoded as JSON")
        val wire =
            DiscourseActionWire(
                postAction = value.postAction,
                reactions = value.reactions,
                currentUserReaction = value.currentUserReaction,
                success = value.success,
            )
        jsonEncoder.encodeJsonElement(jsonEncoder.json.encodeToJsonElement(wire))
    }
}

/** Durable post-action record returned by core action creation. */
@Serializable
public data class DiscoursePostAction(
    public val id: Long,
    @SerialName("post_id")
    public val postId: Long,
    @SerialName("post_action_type_id")
    public val postActionTypeId: Long,
    @SerialName("user_id")
    public val userId: Long? = null,
    @SerialName("created_at")
    public val createdAt: String? = null,
)

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
