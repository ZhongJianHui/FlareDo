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

/**
 * Compact user representation side-loaded into topic, search, and notification responses.
 *
 * A user ID and username are both required: the numeric ID joins side-loaded records while the
 * username is the stable public route segment. Avatar and display metadata vary by serializer.
 */
@Serializable
public data class DiscourseBasicUser(
    public val id: Long,
    public val username: String,
    public val name: String? = null,
    @SerialName("avatar_template")
    public val avatarTemplate: String = "",
    @SerialName("trust_level")
    public val trustLevel: Int? = null,
    public val moderator: Boolean = false,
    public val admin: Boolean = false,
    @SerialName("primary_group_name")
    public val primaryGroupName: String? = null,
    @SerialName("flair_name")
    public val flairName: String? = null,
)

/**
 * User profile endpoint response.
 *
 * Discourse installations and endpoint serializers disagree on whether the user is wrapped as
 * `{ "user": ... }` or returned directly. The custom serializer accepts both official shapes while
 * still requiring [DiscourseUser.id] and [DiscourseUser.username].
 */
@Serializable(with = DiscourseUserResponseSerializer::class)
public data class DiscourseUserResponse(
    public val user: DiscourseUser,
    public val badges: List<DiscourseBadge> = emptyList(),
    @SerialName("user_badges")
    public val userBadges: List<DiscourseUserBadge> = emptyList(),
    @SerialName("badge_types")
    public val badgeTypes: List<DiscourseBadgeType> = emptyList(),
    public val users: List<DiscourseBasicUser> = emptyList(),
)

@Serializable
private data class DiscourseUserEnvelope(
    val user: DiscourseUser,
    val badges: List<DiscourseBadge> = emptyList(),
    @SerialName("user_badges")
    val userBadges: List<DiscourseUserBadge> = emptyList(),
    @SerialName("badge_types")
    val badgeTypes: List<DiscourseBadgeType> = emptyList(),
    val users: List<DiscourseBasicUser> = emptyList(),
)

/** Serializer for wrapped and direct user profile payloads. */
public object DiscourseUserResponseSerializer : KSerializer<DiscourseUserResponse> {
    override val descriptor: SerialDescriptor = DiscourseUserEnvelope.serializer().descriptor

    override fun deserialize(decoder: Decoder): DiscourseUserResponse {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("Discourse user responses can only be decoded from JSON")
        val element = jsonDecoder.decodeJsonElement()
        val objectPayload =
            element as? JsonObject
                ?: throw SerializationException("Discourse user response must be a JSON object")
        val response =
            if ("user" in objectPayload) {
                val envelope = jsonDecoder.json.decodeFromJsonElement<DiscourseUserEnvelope>(element)
                DiscourseUserResponse(
                    user = envelope.user,
                    badges = envelope.badges,
                    userBadges = envelope.userBadges,
                    badgeTypes = envelope.badgeTypes,
                    users = envelope.users,
                )
            } else {
                DiscourseUserResponse(jsonDecoder.json.decodeFromJsonElement<DiscourseUser>(element))
            }
        return response
    }

    override fun serialize(
        encoder: Encoder,
        value: DiscourseUserResponse,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("Discourse user responses can only be encoded as JSON")
        jsonEncoder.encodeJsonElement(
            jsonEncoder.json.encodeToJsonElement(
                DiscourseUserEnvelope(
                    user = value.user,
                    badges = value.badges,
                    userBadges = value.userBadges,
                    badgeTypes = value.badgeTypes,
                    users = value.users,
                ),
            ),
        )
    }
}

/** Full user profile required by the account screen and composer permission checks. */
@Serializable
public data class DiscourseUser(
    public val id: Long,
    public val username: String,
    public val name: String? = null,
    @SerialName("avatar_template")
    public val avatarTemplate: String = "",
    public val title: String? = null,
    @SerialName("trust_level")
    public val trustLevel: Int = 0,
    public val moderator: Boolean = false,
    public val admin: Boolean = false,
    public val staff: Boolean = false,
    public val suspended: Boolean = false,
    public val staged: Boolean = false,
    public val active: Boolean = true,
    @SerialName("can_send_private_messages")
    public val canSendPrivateMessages: Boolean = false,
    @SerialName("can_send_private_message_to_user")
    public val canSendPrivateMessageToUser: Boolean = false,
    @SerialName("can_edit")
    public val canEdit: Boolean = false,
    @SerialName("can_edit_username")
    public val canEditUsername: Boolean = false,
    @SerialName("can_edit_email")
    public val canEditEmail: Boolean = false,
    @SerialName("can_edit_name")
    public val canEditName: Boolean = false,
    @SerialName("can_upload_profile_header")
    public val canUploadProfileHeader: Boolean = false,
    @SerialName("can_upload_user_card_background")
    public val canUploadUserCardBackground: Boolean = false,
    @SerialName("created_at")
    public val createdAt: String? = null,
    @SerialName("last_posted_at")
    public val lastPostedAt: String? = null,
    @SerialName("last_seen_at")
    public val lastSeenAt: String? = null,
    @SerialName("website_name")
    public val websiteName: String? = null,
    @SerialName("website")
    public val website: String? = null,
    public val location: String? = null,
    @SerialName("bio_raw")
    public val bioRaw: String? = null,
    @SerialName("bio_cooked")
    public val bioCooked: String? = null,
    @SerialName("profile_background_upload_url")
    public val profileBackgroundUploadUrl: String? = null,
    @SerialName("card_background_upload_url")
    public val cardBackgroundUploadUrl: String? = null,
    @SerialName("primary_group_name")
    public val primaryGroupName: String? = null,
    @SerialName("primary_group_flair_url")
    public val primaryGroupFlairUrl: String? = null,
    @SerialName("primary_group_flair_bg_color")
    public val primaryGroupFlairBackgroundColor: String? = null,
    @SerialName("primary_group_flair_color")
    public val primaryGroupFlairColor: String? = null,
    @SerialName("featured_user_badge_ids")
    public val featuredUserBadgeIds: List<Long> = emptyList(),
    public val groups: List<DiscourseUserGroup> = emptyList(),
    @SerialName("user_fields")
    public val userFields: JsonObject = JsonObject(emptyMap()),
)

/** A group shown on a profile. Membership and messageability are authorization hints. */
@Serializable
public data class DiscourseUserGroup(
    public val id: Long,
    public val name: String,
    @SerialName("display_name")
    public val displayName: String? = null,
    public val title: String? = null,
    @SerialName("user_count")
    public val userCount: Int = 0,
    public val automatic: Boolean = false,
    @SerialName("messageable_level")
    public val messageableLevel: Int? = null,
    @SerialName("mentionable_level")
    public val mentionableLevel: Int? = null,
)

/** Profile summary envelope returned by `/u/{username}/summary.json`. */
@Serializable
public data class DiscourseUserSummaryResponse(
    @SerialName("user_summary")
    public val userSummary: DiscourseUserSummary,
    public val topics: List<DiscourseTopicSummary> = emptyList(),
    public val badges: List<DiscourseBadge> = emptyList(),
    public val users: List<DiscourseBasicUser> = emptyList(),
)

/** Aggregate profile activity counters and frequently interacting users. */
@Serializable
public data class DiscourseUserSummary(
    @SerialName("likes_given")
    public val likesGiven: Int = 0,
    @SerialName("likes_received")
    public val likesReceived: Int = 0,
    @SerialName("topics_entered")
    public val topicsEntered: Int = 0,
    @SerialName("posts_read_count")
    public val postsReadCount: Int = 0,
    @SerialName("days_visited")
    public val daysVisited: Int = 0,
    @SerialName("topic_count")
    public val topicCount: Int = 0,
    @SerialName("post_count")
    public val postCount: Int = 0,
    @SerialName("time_read")
    public val timeReadSeconds: Long = 0,
    @SerialName("recent_time_read")
    public val recentTimeReadSeconds: Long = 0,
    @SerialName("solved_count")
    public val solvedCount: Int = 0,
    @SerialName("top_replies")
    public val topReplies: List<DiscourseSummaryPostRef> = emptyList(),
    @SerialName("top_topics")
    public val topTopics: List<DiscourseSummaryTopicRef> = emptyList(),
    @SerialName("most_liked_by_users")
    public val mostLikedByUsers: List<DiscourseSummaryUserRef> = emptyList(),
    @SerialName("most_liked_users")
    public val mostLikedUsers: List<DiscourseSummaryUserRef> = emptyList(),
    @SerialName("most_replied_to_users")
    public val mostRepliedToUsers: List<DiscourseSummaryUserRef> = emptyList(),
)

/** A post reference in a user summary; [id] is the post ID, not its display number. */
@Serializable
public data class DiscourseSummaryPostRef(
    public val id: Long,
    @SerialName("topic_id")
    public val topicId: Long,
    @SerialName("post_number")
    public val postNumber: Int,
    public val title: String? = null,
    public val slug: String? = null,
    @SerialName("like_count")
    public val likeCount: Int = 0,
)

/** Topic reference in user summary statistics. */
@Serializable
public data class DiscourseSummaryTopicRef(
    public val id: Long,
    public val title: String,
    public val slug: String,
    @SerialName("like_count")
    public val likeCount: Int = 0,
)

/** User/count pair in profile interaction statistics. */
@Serializable
public data class DiscourseSummaryUserRef(
    public val id: Long,
    public val count: Int = 0,
)

/** Badge descriptor side-loaded with user summaries. */
@Serializable
public data class DiscourseBadge(
    public val id: Long,
    public val name: String,
    public val slug: String,
    public val description: String? = null,
    @SerialName("icon")
    public val icon: String? = null,
    @SerialName("image_url")
    public val imageUrl: String? = null,
    @SerialName("badge_type_id")
    public val badgeTypeId: Long? = null,
)

/** A grant linking a badge to a user profile. */
@Serializable
public data class DiscourseUserBadge(
    public val id: Long,
    @SerialName("badge_id")
    public val badgeId: Long,
    @SerialName("user_id")
    public val userId: Long,
    @SerialName("granted_by_id")
    public val grantedById: Long? = null,
    @SerialName("granted_at")
    public val grantedAt: String? = null,
    public val count: Int = 1,
)

/** Badge tier metadata side-loaded by profile serializers. */
@Serializable
public data class DiscourseBadgeType(
    public val id: Long,
    public val name: String,
    @SerialName("sort_order")
    public val sortOrder: Int = 0,
)

/** Envelope returned by `/user_actions.json`. */
@Serializable
public data class DiscourseUserActionsResponse(
    @SerialName("user_actions")
    public val userActions: List<DiscourseUserAction>,
)

/**
 * One profile activity entry.
 *
 * Activity variants do not share a single entity ID: some refer to topics, some to posts, and some
 * to user relationships. [actionType] and [createdAt] are therefore the required discriminating
 * identity, with referenced IDs required only by the consumer of the corresponding action type.
 */
@Serializable
public data class DiscourseUserAction(
    @SerialName("action_type")
    public val actionType: Int,
    @SerialName("created_at")
    public val createdAt: String,
    @SerialName("user_id")
    public val userId: Long? = null,
    public val username: String? = null,
    public val name: String? = null,
    @SerialName("avatar_template")
    public val avatarTemplate: String? = null,
    @SerialName("acting_user_id")
    public val actingUserId: Long? = null,
    @SerialName("acting_username")
    public val actingUsername: String? = null,
    @SerialName("acting_name")
    public val actingName: String? = null,
    @SerialName("acting_avatar_template")
    public val actingAvatarTemplate: String? = null,
    @SerialName("topic_id")
    public val topicId: Long? = null,
    @SerialName("post_id")
    public val postId: Long? = null,
    @SerialName("post_number")
    public val postNumber: Int? = null,
    public val slug: String? = null,
    public val title: String? = null,
    public val excerpt: String? = null,
    @SerialName("category_id")
    public val categoryId: Long? = null,
    public val closed: Boolean = false,
    public val archived: Boolean = false,
    public val hidden: Boolean = false,
    public val deleted: Boolean = false,
)
