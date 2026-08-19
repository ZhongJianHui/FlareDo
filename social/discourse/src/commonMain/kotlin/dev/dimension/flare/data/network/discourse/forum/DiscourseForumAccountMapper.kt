package dev.dimension.flare.data.network.discourse.forum

import dev.dimension.flare.data.network.discourse.content.DiscourseCookedHtmlParser
import dev.dimension.flare.data.network.discourse.model.DiscourseBadge
import dev.dimension.flare.data.network.discourse.model.DiscourseNotification
import dev.dimension.flare.data.network.discourse.model.DiscourseNotificationResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserAction
import dev.dimension.flare.data.network.discourse.model.DiscourseUserActionsResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserSummary
import dev.dimension.flare.data.network.discourse.model.DiscourseUserSummaryResponse
import dev.dimension.flare.data.network.discourse.model.discourseJson
import dev.dimension.flare.data.network.discourse.paging.DiscourseNotificationOffset
import dev.dimension.flare.ui.model.DiscourseTopicRef
import dev.dimension.flare.ui.model.UiAuthor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Maps profile, activity, and authenticated notification DTOs into bounded domain values. */
public class DiscourseForumAccountMapper(
    private val cookedHtmlParser: DiscourseCookedHtmlParser,
) {
    /** Joins profile and summary responses and parses `bio_cooked` through the shared sanitizer. */
    public fun mapProfile(
        requestedUsername: String,
        profileResponse: DiscourseUserResponse,
        summaryResponse: DiscourseUserSummaryResponse,
    ): DiscourseForumProfile =
        mapForumResponse {
            requestedUsername.requireForumRoute()
            val user = profileResponse.user
            require(user.id > 0L)
            val username = user.username.requireForumRoute()
            require(username.equals(requestedUsername, ignoreCase = true))

            val badgeGrantCounts =
                profileResponse.userBadges
                    .onEach { grant ->
                        require(grant.id > 0L)
                        require(grant.badgeId > 0L)
                        require(grant.userId > 0L)
                        require(grant.count >= 0)
                    }.filter { it.userId == user.id }
                    .groupingBy { it.badgeId }
                    .fold(0) { count, grant ->
                        (count.toLong() + grant.count.toLong())
                            .coerceAtMost(Int.MAX_VALUE.toLong())
                            .toInt()
                    }
            val grantedOnly = profileResponse.userBadges.isNotEmpty()
            val badges =
                profileResponse.badges
                    .asSequence()
                    .filter { badge -> !grantedOnly || badge.id in badgeGrantCounts }
                    .distinctBy(DiscourseBadge::id)
                    .take(MAX_PROFILE_BADGES)
                    .map { badge -> badge.toForumBadge(badgeGrantCounts[badge.id] ?: 1) }
                    .toList()

            DiscourseForumProfile(
                userId = user.id,
                username = username,
                displayName =
                    user.name.safeForumDisplayValue(MAX_PROFILE_DISPLAY_CHARS)
                        ?: username,
                avatarUrl = user.avatarTemplate.toSafeForumAvatarUrl(),
                title = user.title.safeForumDisplayValue(MAX_FORUM_SMALL_TEXT_CHARS),
                trustLevel = user.trustLevel.coerceAtLeast(0),
                moderator = user.moderator,
                admin = user.admin,
                staff = user.staff,
                active = user.active,
                suspended = user.suspended,
                canSendPrivateMessages =
                    user.canSendPrivateMessages || user.canSendPrivateMessageToUser,
                canEdit = user.canEdit,
                createdAtEpochMillis = parseForumEpochMillis(user.createdAt),
                lastPostedAtEpochMillis = parseForumEpochMillis(user.lastPostedAt),
                lastSeenAtEpochMillis = parseForumEpochMillis(user.lastSeenAt),
                websiteName =
                    user.websiteName.safeForumDisplayValue(MAX_FORUM_SMALL_TEXT_CHARS),
                websiteUrl = user.website.toSafeForumUrl(),
                location = user.location.safeForumDisplayValue(MAX_FORUM_SMALL_TEXT_CHARS),
                primaryGroupName =
                    user.primaryGroupName.safeForumDisplayValue(MAX_FORUM_SMALL_TEXT_CHARS),
                bio = cookedHtmlParser.parse(user.bioCooked.orEmpty()),
                badges = badges,
                summary = summaryResponse.userSummary.toForumSummary(),
            )
        }

    /**
     * Maps one activity response and advances by its raw row count.
     *
     * De-duplication happens after every raw row has been validated and mapped. Consequently an
     * overlapping page still advances its offset and cannot trap the caller in a repeat loop.
     */
    public fun mapActivityPage(
        offset: Int,
        response: DiscourseUserActionsResponse,
        knownItemKeys: Set<String> = emptySet(),
    ): DiscourseForumActivityPage =
        mapForumResponse {
            require(offset >= 0)
            require(knownItemKeys.all { it.isNotBlank() && it.length <= MAX_ACTIVITY_KEY_CHARS })
            val seenKeys = knownItemKeys.toMutableSet()
            val items =
                buildList {
                    response.userActions.forEach { row ->
                        val mapped = row.toForumActivity()
                        if (seenKeys.add(mapped.itemKey)) add(mapped)
                    }
                }
            val nextOffset =
                response.userActions
                    .takeIf(List<*>::isNotEmpty)
                    ?.let { rows ->
                        check(rows.size <= Int.MAX_VALUE - offset)
                        offset + rows.size
                    }
            DiscourseForumActivityPage(
                offset = offset,
                items = items,
                nextOffset = nextOffset,
            )
        }

    /**
     * Verifies recipient identity, normalizes notification `data`, and derives a safe cursor.
     * The server continuation string is treated as a boolean only and is never parsed or followed.
     */
    public fun mapNotificationPage(
        offset: DiscourseNotificationOffset,
        response: DiscourseNotificationResponse,
        expectedRecipientUserId: Long,
        knownIds: Set<Long> = emptySet(),
    ): DiscourseForumNotificationPage =
        mapForumResponse {
            require(expectedRecipientUserId > 0L)
            require(knownIds.all { it > 0L })
            val seenIds = knownIds.toMutableSet()
            val items =
                buildList {
                    response.notifications.forEach { row ->
                        // Recipient validation precedes de-duplication so an overlap cannot hide a
                        // row delivered for a different authenticated account.
                        require(row.userId == expectedRecipientUserId)
                        val mapped = row.toForumNotification()
                        if (seenIds.add(mapped.id)) add(mapped)
                    }
                }
            val hasContinuation = response.loadMoreNotifications != null
            if (hasContinuation && items.isEmpty()) {
                // Advancing by accepted identities is required by the local offset contract. A
                // continuation with no progress would repeat forever, so fail with a fixed error.
                throw forumProtocolFailure()
            }
            require(response.seenNotificationId >= 0L)
            DiscourseForumNotificationPage(
                offset = offset,
                items = items,
                nextOffset = if (hasContinuation) offset.advanceBy(items.size) else null,
                totalRows = response.totalRowsNotifications.coerceAtLeast(0),
                seenNotificationId = response.seenNotificationId,
            )
        }

    private fun DiscourseBadge.toForumBadge(count: Int): DiscourseForumBadge {
        require(id > 0L)
        return DiscourseForumBadge(
            id = id,
            name =
                cookedHtmlParser.sanitizeForumText(name, MAX_FORUM_SMALL_TEXT_CHARS)
                    ?: throw forumProtocolFailure(),
            description =
                cookedHtmlParser.sanitizeForumText(description, MAX_PROFILE_BADGE_DESCRIPTION_CHARS),
            icon = icon.safeForumDisplayValue(MAX_PROFILE_BADGE_ICON_CHARS),
            imageUrl = imageUrl.toSafeForumUrl(allowRelative = true),
            count = count.coerceAtLeast(0),
        )
    }

    private fun DiscourseUserSummary.toForumSummary(): DiscourseForumUserSummary =
        DiscourseForumUserSummary(
            likesGiven = likesGiven.coerceAtLeast(0),
            likesReceived = likesReceived.coerceAtLeast(0),
            topicsEntered = topicsEntered.coerceAtLeast(0),
            postsReadCount = postsReadCount.coerceAtLeast(0),
            daysVisited = daysVisited.coerceAtLeast(0),
            topicCount = topicCount.coerceAtLeast(0),
            postCount = postCount.coerceAtLeast(0),
            timeReadSeconds = timeReadSeconds.coerceAtLeast(0L),
            recentTimeReadSeconds = recentTimeReadSeconds.coerceAtLeast(0L),
            solvedCount = solvedCount.coerceAtLeast(0),
        )

    private fun DiscourseUserAction.toForumActivity(): DiscourseForumActivity {
        require(actionType > 0)
        userId?.let { require(it > 0L) }
        actingUserId?.let { require(it > 0L) }
        topicId?.let { require(it > 0L) }
        postId?.let { require(it > 0L) }
        postNumber?.let { require(it > 0 && topicId != null) }
        categoryId?.let { require(it > 0L) }
        val timestamp = parseForumEpochMillis(createdAt) ?: throw forumProtocolFailure()
        val topicRef = topicId?.let { DiscourseTopicRef(topicId = it, postNumber = postNumber) }
        val key =
            activityItemKey(
                actionType = actionType,
                timestamp = timestamp,
                topicId = topicId,
                postId = postId,
                postNumber = postNumber,
                userId = userId,
                actingUserId = actingUserId,
            )
        return DiscourseForumActivity(
            itemKey = key,
            actionType = actionType,
            kind = actionType.toForumActivityKind(),
            createdAtEpochMillis = timestamp,
            user = toForumAuthor(userId, username, name, avatarTemplate),
            actingUser =
                toForumAuthor(
                    actingUserId,
                    actingUsername,
                    actingName,
                    actingAvatarTemplate,
                ),
            topic = topicRef,
            postId = postId,
            topicSlug = topicId?.let { slug?.requireForumRoute() },
            title = cookedHtmlParser.sanitizeForumText(title, MAX_FORUM_TITLE_CHARS),
            excerpt =
                cookedHtmlParser
                    .sanitizeForumText(excerpt, MAX_FORUM_EXCERPT_CHARS)
                    .orEmpty(),
            categoryId = categoryId,
            closed = closed,
            archived = archived,
            hidden = hidden,
            deleted = deleted,
        )
    }

    private fun DiscourseNotification.toForumNotification(): DiscourseForumNotification {
        require(id > 0L)
        require(userId > 0L)
        require(notificationType > 0)
        topicId?.let { require(it > 0L) }
        postNumber?.let { require(it > 0 && topicId != null) }
        val normalizedData = normalizeNotificationData(data)
        val actorUsername = normalizedData.username ?: normalizedData.originalUsername
        val actorDisplayName = normalizedData.displayUsername ?: actorUsername
        val actor =
            if (actorUsername == null && actorDisplayName == null) {
                null
            } else {
                UiAuthor(
                    username = actorUsername ?: UNKNOWN_AUTHOR_USERNAME,
                    displayName = actorDisplayName ?: UNKNOWN_AUTHOR_USERNAME,
                    avatarUrl = actingUserAvatarTemplate.toSafeForumAvatarUrl(),
                )
            }
        return DiscourseForumNotification(
            id = id,
            recipientUserId = userId,
            kind = notificationType.toForumNotificationKind(),
            read = read,
            highPriority = highPriority,
            createdAtEpochMillis = parseForumEpochMillis(createdAt),
            topic = topicId?.let { DiscourseTopicRef(topicId = it, postNumber = postNumber) },
            topicSlug = topicId?.let { slug?.requireForumRoute() },
            title =
                normalizedData.topicTitle
                    ?: cookedHtmlParser.sanitizeForumText(fancyTitle, MAX_FORUM_TITLE_CHARS),
            actingUser = actor,
            data = normalizedData,
        )
    }

    private fun normalizeNotificationData(element: JsonElement?): DiscourseForumNotificationData {
        val payload = element.toNotificationObject()

        fun safeText(
            key: String,
            maxChars: Int,
        ): String? =
            payload.stringValue(key)?.let { value ->
                cookedHtmlParser.sanitizeForumText(value, maxChars)
            }

        return DiscourseForumNotificationData(
            topicTitle = safeText("topic_title", MAX_FORUM_TITLE_CHARS),
            displayUsername = safeText("display_username", MAX_FORUM_USERNAME_CHARS),
            username = safeText("username", MAX_FORUM_USERNAME_CHARS),
            originalUsername = safeText("original_username", MAX_FORUM_USERNAME_CHARS),
            badgeName = safeText("badge_name", MAX_FORUM_SMALL_TEXT_CHARS),
            groupName = safeText("group_name", MAX_FORUM_SMALL_TEXT_CHARS),
            count = payload.nonNegativeIntValue("count"),
        )
    }
}

private fun JsonElement?.toNotificationObject(): JsonObject =
    when (this) {
        null, JsonNull -> {
            JsonObject(emptyMap())
        }

        is JsonObject -> {
            this
        }

        is JsonPrimitive -> {
            require(isString)
            require(content.length <= MAX_NOTIFICATION_DATA_JSON_CHARS)
            discourseJson.parseToJsonElement(content) as? JsonObject
                ?: throw forumProtocolFailure()
        }

        else -> {
            throw forumProtocolFailure()
        }
    }

private fun JsonObject.stringValue(key: String): String? {
    val value = get(key) as? JsonPrimitive ?: return null
    return value.takeIf(JsonPrimitive::isString)?.content
}

private fun JsonObject.nonNegativeIntValue(key: String): Int? {
    val value = (get(key) as? JsonPrimitive)?.intOrNull ?: return null
    return value.takeIf { it >= 0 }
}

private fun toForumAuthor(
    id: Long?,
    username: String?,
    name: String?,
    avatarTemplate: String?,
): UiAuthor? {
    if (id == null && username == null && name == null) return null
    val safeUsername =
        username.safeForumDisplayValue(MAX_FORUM_USERNAME_CHARS)
            ?: UNKNOWN_AUTHOR_USERNAME
    return UiAuthor(
        username = safeUsername,
        displayName = name.safeForumDisplayValue(MAX_FORUM_USERNAME_CHARS) ?: safeUsername,
        avatarUrl = avatarTemplate.toSafeForumAvatarUrl(),
    )
}

private fun Int.toForumActivityKind(): DiscourseForumActivityKind =
    when (this) {
        1 -> DiscourseForumActivityKind.Liked
        2 -> DiscourseForumActivityKind.WasLiked
        3 -> DiscourseForumActivityKind.Bookmarked
        4 -> DiscourseForumActivityKind.TopicCreated
        5, 6 -> DiscourseForumActivityKind.Replied
        7 -> DiscourseForumActivityKind.Mentioned
        9 -> DiscourseForumActivityKind.Quoted
        11 -> DiscourseForumActivityKind.Edited
        12, 13 -> DiscourseForumActivityKind.PrivateMessage
        15 -> DiscourseForumActivityKind.Solved
        else -> DiscourseForumActivityKind.Generic
    }

private fun Int.toForumNotificationKind(): DiscourseForumNotificationKind =
    when (this) {
        1 -> DiscourseForumNotificationKind.Mention
        2 -> DiscourseForumNotificationKind.Reply
        3 -> DiscourseForumNotificationKind.Quote
        4 -> DiscourseForumNotificationKind.Edit
        5, 19 -> DiscourseForumNotificationKind.Like
        6, 7 -> DiscourseForumNotificationKind.PrivateMessage
        8, 13 -> DiscourseForumNotificationKind.Invitation
        9 -> DiscourseForumNotificationKind.Posted
        10 -> DiscourseForumNotificationKind.MovedPost
        11 -> DiscourseForumNotificationKind.Link
        12 -> DiscourseForumNotificationKind.Badge
        15, 16, 22, 23 -> DiscourseForumNotificationKind.Group
        18, 24, 27 -> DiscourseForumNotificationKind.Reminder
        20, 21 -> DiscourseForumNotificationKind.Approval
        25 -> DiscourseForumNotificationKind.Reaction
        28 -> DiscourseForumNotificationKind.Invitation
        else -> DiscourseForumNotificationKind.Generic
    }

private fun activityItemKey(
    actionType: Int,
    timestamp: Long,
    topicId: Long?,
    postId: Long?,
    postNumber: Int?,
    userId: Long?,
    actingUserId: Long?,
): String =
    "discourse-activity:$actionType:$timestamp:${topicId ?: 0L}:${postId ?: 0L}:" +
        "${postNumber ?: 0}:${userId ?: 0L}:${actingUserId ?: 0L}"

private const val UNKNOWN_AUTHOR_USERNAME: String = "unknown"
private const val MAX_PROFILE_DISPLAY_CHARS: Int = 512
private const val MAX_PROFILE_BADGES: Int = 100
private const val MAX_PROFILE_BADGE_DESCRIPTION_CHARS: Int = 2_000
private const val MAX_PROFILE_BADGE_ICON_CHARS: Int = 256
private const val MAX_ACTIVITY_KEY_CHARS: Int = 512
private const val MAX_NOTIFICATION_DATA_JSON_CHARS: Int = 16 * 1024
