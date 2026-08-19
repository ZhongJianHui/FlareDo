package dev.dimension.flare.data.network.discourse.forum

import dev.dimension.flare.data.network.discourse.paging.DiscourseNotificationOffset
import dev.dimension.flare.ui.model.DiscourseTopicRef
import dev.dimension.flare.ui.model.UiArticleBlock
import dev.dimension.flare.ui.model.UiAuthor

/** Bounded aggregate counters from the public Discourse user-summary endpoint. */
public data class DiscourseForumUserSummary(
    public val likesGiven: Int,
    public val likesReceived: Int,
    public val topicsEntered: Int,
    public val postsReadCount: Int,
    public val daysVisited: Int,
    public val topicCount: Int,
    public val postCount: Int,
    public val timeReadSeconds: Long,
    public val recentTimeReadSeconds: Long,
    public val solvedCount: Int,
) {
    init {
        require(
            listOf(
                likesGiven,
                likesReceived,
                topicsEntered,
                postsReadCount,
                daysVisited,
                topicCount,
                postCount,
                solvedCount,
            ).all { it >= 0 },
        ) { "Profile summary counters cannot be negative" }
        require(timeReadSeconds >= 0L && recentTimeReadSeconds >= 0L) {
            "Profile read times cannot be negative"
        }
    }
}

/** Presentation-safe badge metadata; raw badge HTML and plugin fields are intentionally absent. */
public data class DiscourseForumBadge(
    public val id: Long,
    public val name: String,
    public val description: String?,
    public val icon: String?,
    public val imageUrl: String?,
    public val count: Int,
) {
    init {
        require(id > 0L) { "Profile badge id must be positive" }
        require(name.isNotBlank()) { "Profile badge name must not be blank" }
        require(count >= 0) { "Profile badge count cannot be negative" }
    }
}

/**
 * Public profile plus its independently loaded summary.
 *
 * `bio_raw`, `bio_cooked`, `user_fields`, and every raw JSON/HTML value are intentionally excluded.
 * [bio] is produced only by [dev.dimension.flare.data.network.discourse.content.DiscourseCookedHtmlParser].
 */
public data class DiscourseForumProfile(
    public val userId: Long,
    public val username: String,
    public val displayName: String,
    public val avatarUrl: String?,
    public val title: String?,
    public val trustLevel: Int,
    public val moderator: Boolean,
    public val admin: Boolean,
    public val staff: Boolean,
    public val active: Boolean,
    public val suspended: Boolean,
    public val canSendPrivateMessages: Boolean,
    public val canEdit: Boolean,
    public val createdAtEpochMillis: Long?,
    public val lastPostedAtEpochMillis: Long?,
    public val lastSeenAtEpochMillis: Long?,
    public val websiteName: String?,
    public val websiteUrl: String?,
    public val location: String?,
    public val primaryGroupName: String?,
    public val bio: List<UiArticleBlock>,
    public val badges: List<DiscourseForumBadge>,
    public val summary: DiscourseForumUserSummary,
) {
    init {
        require(userId > 0L) { "Profile user id must be positive" }
        require(username.isNotBlank() && username.length <= MAX_ACCOUNT_USERNAME_CHARS) {
            "Profile username is invalid"
        }
        require(displayName.isNotBlank() && displayName.length <= MAX_ACCOUNT_DISPLAY_CHARS) {
            "Profile display name is invalid"
        }
        require(trustLevel >= 0) { "Profile trust level cannot be negative" }
        require(badges.size <= MAX_ACCOUNT_BADGES) { "Profile contains too many badges" }
    }
}

/** Coarse activity families; unknown numeric action types remain visible as [Generic]. */
public enum class DiscourseForumActivityKind {
    Liked,
    WasLiked,
    Bookmarked,
    TopicCreated,
    Replied,
    Mentioned,
    Quoted,
    Edited,
    PrivateMessage,
    Solved,
    Generic,
}

/** One de-duplicated public user-action row. */
public data class DiscourseForumActivity(
    public val itemKey: String,
    public val actionType: Int,
    public val kind: DiscourseForumActivityKind,
    public val createdAtEpochMillis: Long,
    public val user: UiAuthor?,
    public val actingUser: UiAuthor?,
    public val topic: DiscourseTopicRef?,
    public val postId: Long?,
    public val topicSlug: String?,
    public val title: String?,
    public val excerpt: String,
    public val categoryId: Long?,
    public val closed: Boolean,
    public val archived: Boolean,
    public val hidden: Boolean,
    public val deleted: Boolean,
) {
    init {
        require(itemKey.isNotBlank()) { "Activity item key must not be blank" }
        require(actionType > 0) { "Activity action type must be positive" }
        require(createdAtEpochMillis >= 0L) { "Activity timestamp cannot be negative" }
        require(postId == null || postId > 0L) { "Activity post id must be positive" }
        require(categoryId == null || categoryId > 0L) { "Activity category id must be positive" }
        require(topic?.topicId?.let { it > 0L } != false) { "Activity topic id must be positive" }
        require(topic?.postNumber?.let { it > 0 } != false) {
            "Activity post number must be positive"
        }
    }
}

/** Activity cursor advances by raw server rows even when all accepted rows overlap prior pages. */
public data class DiscourseForumActivityPage(
    public val offset: Int,
    public val items: List<DiscourseForumActivity>,
    public val nextOffset: Int?,
) {
    init {
        require(offset >= 0) { "Activity offset cannot be negative" }
        require(nextOffset == null || nextOffset > offset) { "Next activity offset must advance" }
        require(items.map(DiscourseForumActivity::itemKey).distinct().size == items.size) {
            "Activity page cannot contain duplicate item keys"
        }
    }
}

/** Known core notification families; plugin and future values map to [Generic]. */
public enum class DiscourseForumNotificationKind {
    Mention,
    Reply,
    Quote,
    Edit,
    Like,
    PrivateMessage,
    Invitation,
    Posted,
    MovedPost,
    Link,
    Badge,
    Group,
    Reminder,
    Approval,
    Reaction,
    Generic,
}

/**
 * Whitelisted notification payload.
 *
 * Discourse plugins can append arbitrary JSON to `data`. The domain layer retains only these
 * bounded scalar fields, so logging or rendering a notification can never expose a raw JsonElement.
 */
public data class DiscourseForumNotificationData(
    public val topicTitle: String? = null,
    public val displayUsername: String? = null,
    public val username: String? = null,
    public val originalUsername: String? = null,
    public val badgeName: String? = null,
    public val groupName: String? = null,
    public val count: Int? = null,
) {
    init {
        require(count == null || count >= 0) { "Notification count cannot be negative" }
    }
}

/** Authenticated notification belonging to the exact numeric active account. */
public data class DiscourseForumNotification(
    public val id: Long,
    public val recipientUserId: Long,
    public val kind: DiscourseForumNotificationKind,
    public val read: Boolean,
    public val highPriority: Boolean,
    public val createdAtEpochMillis: Long?,
    public val topic: DiscourseTopicRef?,
    public val topicSlug: String?,
    public val title: String?,
    public val actingUser: UiAuthor?,
    public val data: DiscourseForumNotificationData,
) {
    init {
        require(id > 0L) { "Notification id must be positive" }
        require(recipientUserId > 0L) { "Notification recipient id must be positive" }
        require(topic?.topicId?.let { it > 0L } != false) { "Notification topic id must be positive" }
        require(topic?.postNumber?.let { it > 0 } != false) {
            "Notification post number must be positive"
        }
    }
}

/** One notification response page and its locally derived monotonic continuation cursor. */
public data class DiscourseForumNotificationPage(
    public val offset: DiscourseNotificationOffset,
    public val items: List<DiscourseForumNotification>,
    public val nextOffset: DiscourseNotificationOffset?,
    public val totalRows: Int,
    public val seenNotificationId: Long,
) {
    init {
        require(nextOffset == null || nextOffset.value > offset.value) {
            "Next notification offset must advance"
        }
        require(totalRows >= 0) { "Notification total row count cannot be negative" }
        require(seenNotificationId >= 0L) { "Seen notification id cannot be negative" }
        require(items.map(DiscourseForumNotification::id).distinct().size == items.size) {
            "Notification page cannot contain duplicate ids"
        }
    }

    public fun toSnapshot(): DiscourseForumNotificationSnapshot =
        DiscourseForumNotificationSnapshot(
            items = items,
            totalRows = totalRows,
            seenNotificationId = seenNotificationId,
        )
}

/** Immutable local notification state replaced only after a successful mark-read mutation. */
public data class DiscourseForumNotificationSnapshot(
    public val items: List<DiscourseForumNotification>,
    public val totalRows: Int,
    public val seenNotificationId: Long,
) {
    init {
        require(totalRows >= 0) { "Notification total row count cannot be negative" }
        require(seenNotificationId >= 0L) { "Seen notification id cannot be negative" }
        require(items.map(DiscourseForumNotification::id).distinct().size == items.size) {
            "Notification snapshot cannot contain duplicate ids"
        }
    }

    public val unreadCount: Int
        get() = items.count { !it.read }
}

private const val MAX_ACCOUNT_USERNAME_CHARS: Int = 256
private const val MAX_ACCOUNT_DISPLAY_CHARS: Int = 512
private const val MAX_ACCOUNT_BADGES: Int = 100
