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

/**
 * Envelope shared by latest, hot, top, new, category, and tag topic lists.
 *
 * Discourse side-loads users next to the list. Topic poster entries refer to these users by numeric
 * ID, so consumers should build the user lookup once per response rather than guessing usernames.
 */
@Serializable
public data class DiscourseTopicListResponse(
    public val users: List<DiscourseBasicUser> = emptyList(),
    @SerialName("topic_list")
    public val topicList: DiscourseTopicList,
)

/** A page of topics and the server-provided continuation URL. */
@Serializable
public data class DiscourseTopicList(
    public val topics: List<DiscourseTopicSummary>,
    @SerialName("more_topics_url")
    public val moreTopicsUrl: String? = null,
    @SerialName("can_create_topic")
    public val canCreateTopic: Boolean = false,
    @SerialName("per_page")
    public val perPage: Int? = null,
    public val draft: String? = null,
    @SerialName("draft_key")
    public val draftKey: String? = null,
    @SerialName("draft_sequence")
    public val draftSequence: Int? = null,
)

/**
 * Topic data used by list, search, suggested-topic, and related-topic serializers.
 *
 * Only [id], [title], and [slug] are required because they are the durable navigation identity.
 * Count, read-state, and permission fields differ between anonymous and authenticated serializers,
 * so they use conservative defaults instead of making otherwise valid pages undecodable.
 */
@Serializable
public data class DiscourseTopicSummary(
    public val id: Long,
    public val title: String,
    public val slug: String,
    @SerialName("fancy_title")
    public val fancyTitle: String? = null,
    @SerialName("posts_count")
    public val postsCount: Int = 0,
    @SerialName("reply_count")
    public val replyCount: Int = 0,
    @SerialName("highest_post_number")
    public val highestPostNumber: Int = 0,
    @SerialName("image_url")
    public val imageUrl: String? = null,
    public val excerpt: String? = null,
    @SerialName("created_at")
    public val createdAt: String? = null,
    @SerialName("last_posted_at")
    public val lastPostedAt: String? = null,
    @SerialName("bumped_at")
    public val bumpedAt: String? = null,
    public val bumped: Boolean = false,
    @SerialName("category_id")
    public val categoryId: Long? = null,
    public val tags: List<DiscourseTopicTag> = emptyList(),
    public val views: Int = 0,
    @SerialName("like_count")
    public val likeCount: Int = 0,
    @SerialName("has_summary")
    public val hasSummary: Boolean = false,
    public val pinned: Boolean = false,
    @SerialName("pinned_globally")
    public val pinnedGlobally: Boolean = false,
    public val visible: Boolean = true,
    public val closed: Boolean = false,
    public val archived: Boolean = false,
    public val bookmarked: Boolean? = null,
    public val liked: Boolean? = null,
    public val unpinned: Boolean? = null,
    @SerialName("unread_posts")
    public val unreadPosts: Int = 0,
    @SerialName("new_posts")
    public val newPosts: Int = 0,
    public val unseen: Boolean = false,
    @SerialName("last_read_post_number")
    public val lastReadPostNumber: Int? = null,
    @SerialName("notification_level")
    public val notificationLevel: Int? = null,
    public val archetype: String? = null,
    public val posters: List<DiscourseTopicPoster> = emptyList(),
)

/** A role-to-user link embedded in a topic summary. */
@Serializable
public data class DiscourseTopicPoster(
    public val extras: String? = null,
    public val description: String? = null,
    @SerialName("user_id")
    public val userId: Long,
    @SerialName("primary_group_id")
    public val primaryGroupId: Long? = null,
)

/**
 * Full topic payload returned by `GET /t/{id}.json`.
 *
 * [postStream] is mandatory because rendering a topic without the authoritative ordered stream can
 * silently skip posts. The initial [posts][DiscoursePostStream.posts] array is only a window; clients
 * must follow [DiscoursePostStream.stream] and request the remaining IDs in server order.
 */
@Serializable
public data class DiscourseTopicDetail(
    public val id: Long,
    public val title: String,
    public val slug: String,
    @SerialName("post_stream")
    public val postStream: DiscoursePostStream,
    @SerialName("fancy_title")
    public val fancyTitle: String? = null,
    @SerialName("posts_count")
    public val postsCount: Int = 0,
    @SerialName("highest_post_number")
    public val highestPostNumber: Int = 0,
    @SerialName("created_at")
    public val createdAt: String? = null,
    @SerialName("last_posted_at")
    public val lastPostedAt: String? = null,
    public val views: Int = 0,
    @SerialName("reply_count")
    public val replyCount: Int = 0,
    @SerialName("like_count")
    public val likeCount: Int = 0,
    @SerialName("category_id")
    public val categoryId: Long? = null,
    public val tags: List<DiscourseTopicTag> = emptyList(),
    public val archetype: String? = null,
    public val visible: Boolean = true,
    public val closed: Boolean = false,
    public val archived: Boolean = false,
    public val pinned: Boolean = false,
    public val bookmarked: Boolean? = null,
    @SerialName("bookmark_id")
    public val bookmarkId: Long? = null,
    @SerialName("bookmark_name")
    public val bookmarkName: String? = null,
    @SerialName("bookmark_reminder_at")
    public val bookmarkReminderAt: String? = null,
    @SerialName("can_create_post")
    public val canCreatePost: Boolean = false,
    @SerialName("can_reply_as_new_topic")
    public val canReplyAsNewTopic: Boolean = false,
    @SerialName("can_edit")
    public val canEdit: Boolean = false,
    @SerialName("can_delete")
    public val canDelete: Boolean = false,
    @SerialName("can_flag_topic")
    public val canFlagTopic: Boolean = false,
    @SerialName("details")
    public val details: DiscourseTopicDetails? = null,
    @SerialName("suggested_topics")
    public val suggestedTopics: List<DiscourseTopicSummary> = emptyList(),
    @SerialName("related_topics")
    public val relatedTopics: List<DiscourseTopicSummary> = emptyList(),
)

/**
 * A tag attached to a topic summary or detail response.
 *
 * Current Discourse serializers emit an object containing [id], [name], and [slug], while older
 * endpoints and cached payloads may still emit only the tag name as a JSON string. The optional
 * fields deliberately remain absent for that legacy shape: inventing an ID or slug would make an
 * unstable display value look like server-owned routing identity. Use [routeSegment] when a caller
 * needs the best available URL component, while retaining [id] and [slug] independently for modern
 * responses. Components are capped at 256 UTF-16 code units and reject blank or control-character
 * content before they can become a navigation segment.
 */
@Serializable(with = DiscourseTopicTagSerializer::class)
public data class DiscourseTopicTag(
    public val name: String,
    public val id: Long? = null,
    public val slug: String? = null,
) {
    init {
        require(isValidTopicTagComponent(name)) { "Topic tag name is not a safe bounded value" }
        require(id == null || id > 0L) { "Topic tag ID must be positive" }
        require(slug == null || isValidTopicTagComponent(slug)) {
            "Topic tag slug is not a safe bounded value"
        }
        require((id == null) == (slug == null)) {
            "Topic tag ID and slug must either both be present or both be absent"
        }
    }

    /** Server slug when supplied, otherwise the legacy tag name used by old topic payloads. */
    public val routeSegment: String
        get() = slug ?: name
}

@Serializable
private data class DiscourseTopicTagWire(
    val id: Long? = null,
    val name: String? = null,
    val slug: String? = null,
)

private const val MAX_TOPIC_TAG_COMPONENT_LENGTH: Int = 256

private fun isValidTopicTagComponent(value: String): Boolean =
    value.isNotBlank() &&
        value.length <= MAX_TOPIC_TAG_COMPONENT_LENGTH &&
        value.none { it.isISOControl() }

private fun requireSerializedTopicTagComponent(
    value: String?,
    field: String,
): String =
    value?.takeIf(::isValidTopicTagComponent)
        ?: throw SerializationException(
            "Discourse topic tag $field must be non-blank, bounded, and contain no control characters",
        )

/** JSON-only serializer for the modern object and legacy string tag representations. */
public object DiscourseTopicTagSerializer : KSerializer<DiscourseTopicTag> {
    override val descriptor: SerialDescriptor = DiscourseTopicTagWire.serializer().descriptor

    override fun deserialize(decoder: Decoder): DiscourseTopicTag {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("Discourse topic tags can only be decoded from JSON")
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (!element.isString) {
                    throw SerializationException("A legacy Discourse topic tag must be a JSON string")
                }
                DiscourseTopicTag(
                    name = requireSerializedTopicTagComponent(element.content, "name"),
                )
            }

            is JsonObject -> {
                val wire = jsonDecoder.json.decodeFromJsonElement<DiscourseTopicTagWire>(element)
                val id =
                    wire.id?.takeIf { it > 0L }
                        ?: throw SerializationException("A modern Discourse topic tag requires a positive ID")
                DiscourseTopicTag(
                    id = id,
                    name = requireSerializedTopicTagComponent(wire.name, "name"),
                    slug = requireSerializedTopicTagComponent(wire.slug, "slug"),
                )
            }

            else -> {
                throw SerializationException("A Discourse topic tag must be a string or object")
            }
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: DiscourseTopicTag,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("Discourse topic tags can only be encoded as JSON")
        val element =
            if (value.id == null && value.slug == null) {
                JsonPrimitive(value.name)
            } else {
                jsonEncoder.json.encodeToJsonElement(
                    DiscourseTopicTagWire(id = value.id, name = value.name, slug = value.slug),
                )
            }
        jsonEncoder.encodeJsonElement(element)
    }
}

/** Participant and permission metadata accompanying a topic. */
@Serializable
public data class DiscourseTopicDetails(
    @SerialName("created_by")
    public val createdBy: DiscourseBasicUser? = null,
    @SerialName("last_poster")
    public val lastPoster: DiscourseBasicUser? = null,
    public val participants: List<DiscourseTopicParticipant> = emptyList(),
    @SerialName("notification_level")
    public val notificationLevel: Int? = null,
    @SerialName("can_edit")
    public val canEdit: Boolean = false,
    @SerialName("can_delete")
    public val canDelete: Boolean = false,
    @SerialName("can_flag_topic")
    public val canFlagTopic: Boolean = false,
    @SerialName("can_invite_to")
    public val canInviteTo: Boolean = false,
    @SerialName("can_remove_allowed_users")
    public val canRemoveAllowedUsers: Boolean = false,
    @SerialName("can_create_post")
    public val canCreatePost: Boolean = false,
)

/** A topic participant with optional post and read-state counters. */
@Serializable
public data class DiscourseTopicParticipant(
    public val id: Long,
    public val username: String,
    public val name: String? = null,
    @SerialName("avatar_template")
    public val avatarTemplate: String = "",
    @SerialName("post_count")
    public val postCount: Int = 0,
    @SerialName("primary_group_name")
    public val primaryGroupName: String? = null,
)

/**
 * Ordered topic post stream.
 *
 * Some batch endpoints return this object directly while others wrap it in `post_stream`. The custom
 * serializer accepts both wire shapes without weakening post identity validation. [stream] defaults
 * only because batched post responses may omit it; topic-detail callers must treat an empty stream as
 * an invalid or incomplete ordering contract unless the topic itself has zero posts.
 */
@Serializable(with = DiscoursePostStreamSerializer::class)
public data class DiscoursePostStream(
    public val posts: List<DiscoursePost>,
    public val stream: List<Long> = emptyList(),
    public val gaps: JsonObject? = null,
)

@Serializable
private data class DiscoursePostStreamWire(
    val posts: List<DiscoursePost>,
    val stream: List<Long> = emptyList(),
    val gaps: JsonObject? = null,
)

/** Serializer implementing the two documented post-stream envelope variants. */
public object DiscoursePostStreamSerializer : KSerializer<DiscoursePostStream> {
    override val descriptor: SerialDescriptor = DiscoursePostStreamWire.serializer().descriptor

    override fun deserialize(decoder: Decoder): DiscoursePostStream {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("Discourse post streams can only be decoded from JSON")
        val element = jsonDecoder.decodeJsonElement()
        val root =
            element as? JsonObject
                ?: throw SerializationException("Discourse post stream must be a JSON object")
        val payload = root["post_stream"] ?: root
        if (payload !is JsonObject) {
            throw SerializationException("Discourse post_stream envelope must contain a JSON object")
        }
        val wire = jsonDecoder.json.decodeFromJsonElement<DiscoursePostStreamWire>(payload)
        return DiscoursePostStream(posts = wire.posts, stream = wire.stream, gaps = wire.gaps)
    }

    override fun serialize(
        encoder: Encoder,
        value: DiscoursePostStream,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("Discourse post streams can only be encoded as JSON")
        val wire = DiscoursePostStreamWire(value.posts, value.stream, value.gaps)
        jsonEncoder.encodeJsonElement(jsonEncoder.json.encodeToJsonElement(wire))
    }
}

/**
 * One post in a topic.
 *
 * [id], [topicId], and [postNumber] are all required. They serve different purposes: API mutation,
 * topic association, and human-facing reply position respectively. Losing any one of them makes
 * deduplication or reply routing unsafe, so malformed payloads fail instead of receiving sentinel IDs.
 */
@Serializable
public data class DiscoursePost(
    public val id: Long,
    @SerialName("topic_id")
    public val topicId: Long,
    @SerialName("post_number")
    public val postNumber: Int,
    public val username: String = "",
    public val name: String? = null,
    @SerialName("display_username")
    public val displayUsername: String? = null,
    @SerialName("avatar_template")
    public val avatarTemplate: String = "",
    public val cooked: String = "",
    public val raw: String? = null,
    @SerialName("created_at")
    public val createdAt: String? = null,
    @SerialName("updated_at")
    public val updatedAt: String? = null,
    @SerialName("reply_count")
    public val replyCount: Int = 0,
    @SerialName("reply_to_post_number")
    public val replyToPostNumber: Int? = null,
    @SerialName("quote_count")
    public val quoteCount: Int = 0,
    @SerialName("reads")
    public val reads: Int = 0,
    @SerialName("readers_count")
    public val readersCount: Int = 0,
    public val score: Double? = null,
    @SerialName("yours")
    public val yours: Boolean = false,
    @SerialName("topic_slug")
    public val topicSlug: String? = null,
    @SerialName("user_id")
    public val userId: Long? = null,
    @SerialName("trust_level")
    public val trustLevel: Int? = null,
    @SerialName("post_type")
    public val postType: Int? = null,
    @SerialName("user_deleted")
    public val userDeleted: Boolean = false,
    public val hidden: Boolean = false,
    public val deleted: Boolean = false,
    @SerialName("can_edit")
    public val canEdit: Boolean = false,
    @SerialName("can_delete")
    public val canDelete: Boolean = false,
    @SerialName("can_recover")
    public val canRecover: Boolean = false,
    @SerialName("can_wiki")
    public val canWiki: Boolean = false,
    @SerialName("can_view_edit_history")
    public val canViewEditHistory: Boolean = false,
    @SerialName("wiki")
    public val wiki: Boolean = false,
    @SerialName("moderator")
    public val moderator: Boolean = false,
    @SerialName("admin")
    public val admin: Boolean = false,
    @SerialName("staff")
    public val staff: Boolean = false,
    @SerialName("version")
    public val version: Int = 1,
    @SerialName("link_counts")
    public val linkCounts: List<DiscoursePostLink> = emptyList(),
    @SerialName("actions_summary")
    public val actionsSummary: List<DiscoursePostActionSummary> = emptyList(),
    public val reactions: List<DiscourseReaction> = emptyList(),
    @SerialName("current_user_reaction")
    public val currentUserReaction: DiscourseReaction? = null,
    @SerialName("bookmarked")
    public val bookmarked: Boolean? = null,
    @SerialName("bookmark_id")
    public val bookmarkId: Long? = null,
    @SerialName("bookmark_name")
    public val bookmarkName: String? = null,
    @SerialName("bookmark_reminder_at")
    public val bookmarkReminderAt: String? = null,
)

/** Link usage information used to annotate links without reparsing cooked HTML. */
@Serializable
public data class DiscoursePostLink(
    public val url: String,
    public val title: String? = null,
    public val clicks: Int = 0,
    public val internal: Boolean = false,
    public val reflection: Boolean = false,
)

/** Current user's state for one Discourse post action type. */
@Serializable
public data class DiscoursePostActionSummary(
    public val id: Long,
    public val count: Int = 0,
    public val acted: Boolean = false,
    @SerialName("can_act")
    public val canAct: Boolean = false,
)

/** Reaction aggregate returned by the reactions plugin. */
@Serializable
public data class DiscourseReaction(
    public val id: String,
    public val type: String = "emoji",
    public val count: Int = 0,
    @SerialName("can_undo")
    public val canUndo: Boolean = false,
    public val chosen: Boolean = false,
)
