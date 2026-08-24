package dev.dimension.flare.apple.shared

import dev.dimension.flare.data.network.discourse.composer.DiscourseActionNotAllowedReason
import dev.dimension.flare.data.network.discourse.composer.DiscourseActionTarget
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerDraftStatus
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerMode
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerState
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerSubmitStatus
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerTarget
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerUploadStatus
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerValidationFailure
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumActivity
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumActivityKind
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumContentSource
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumDestination
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFailureKind
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFeed
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumNotification
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumNotificationKind
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumProfile
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumSearchHit
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumState
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumTopic
import dev.dimension.flare.data.network.discourse.realtime.DiscourseSessionRecoveryReason
import dev.dimension.flare.ui.model.UiArticle
import dev.dimension.flare.ui.model.UiArticleBlock
import dev.dimension.flare.ui.model.UiArticleInline
import dev.dimension.flare.ui.model.UiAuthor
import dev.dimension.flare.ui.model.UiTimelineV2

/** Stable top-level destinations exported without Kotlin enum naming conventions leaking into Swift. */
public enum class AppleForumDestination {
    LATEST,
    HOT,
    SEARCH,
    NOTIFICATIONS,
    PROFILE,
}

public enum class AppleForumFeedKind {
    LATEST,
    HOT,
    CATEGORY,
    TAG,
}

public enum class AppleForumContentSource {
    NETWORK,
    STALE_CACHE,
}

/** Fixed, content-free presentation failures. No exception message crosses the Objective-C boundary. */
public enum class AppleForumFailure {
    NETWORK,
    AUTHENTICATION,
    PERMISSION,
    RATE_LIMITED,
    CHALLENGE_REQUIRED,
    SERVER,
    INVALID_RESPONSE,
    HTTP,
}

public enum class AppleSessionRecoveryReason {
    AUTHENTICATION_REQUIRED,
    PERMISSION_DENIED,
    MANUAL_CHALLENGE_REQUIRED,
}

public enum class AppleRichTextBlockKind {
    PARAGRAPH,
    QUOTE,
    CODE,
    IMAGE,
    LIST,
    LIST_ITEM,
    TABLE,
    TABLE_ROW,
    TABLE_CELL,
    SPOILER,
}

public enum class AppleRichTextInlineKind {
    TEXT,
    LINK,
    CODE,
    IMAGE,
    SPOILER,
}

public enum class AppleForumActivityKind {
    LIKED,
    WAS_LIKED,
    BOOKMARKED,
    TOPIC_CREATED,
    REPLIED,
    MENTIONED,
    QUOTED,
    EDITED,
    PRIVATE_MESSAGE,
    SOLVED,
    GENERIC,
}

public enum class AppleForumNotificationKind {
    MENTION,
    REPLY,
    QUOTE,
    EDIT,
    LIKE,
    PRIVATE_MESSAGE,
    INVITATION,
    POSTED,
    MOVED_POST,
    LINK,
    BADGE,
    GROUP,
    REMINDER,
    APPROVAL,
    REACTION,
    GENERIC,
}

public enum class AppleComposerMode {
    CLOSED,
    NEW_TOPIC,
    REPLY,
    EDIT,
}

public enum class AppleComposerDraftStatus {
    NONE,
    LOADING,
    CLEAN,
    DIRTY,
    SAVING,
    SAVED,
    FAILED,
}

public enum class AppleComposerSubmitStatus {
    IDLE,
    SUBMITTING,
    PUBLISHED,
    PENDING_MODERATION,
    FAILED,
}

public enum class AppleComposerUploadStatus {
    NONE,
    READY,
    UPLOADING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

public enum class AppleComposerTargetKind {
    NEW_TOPIC,
    REPLY,
    EDIT,
}

public enum class AppleActionTargetKind {
    POST,
    TOPIC,
}

public enum class AppleComposerValidationFailure {
    DRAFT_NOT_FOUND,
    EMPTY_RAW,
    MISSING_TITLE,
    UNEXPECTED_TITLE,
    CATEGORY_UNAVAILABLE,
    TOO_FEW_TAGS,
    REQUIRED_TAG_GROUP_MINIMUM,
    REQUIRED_TAG_GROUP_MAXIMUM,
    EDITABLE_IDENTITY_MISMATCH,
}

public enum class AppleActionNotAllowedReason {
    MISSING_SERVER_STATE,
    PERMISSION_DENIED,
    MISSING_BOOKMARK_ID,
}

public data class AppleForumAuthorSnapshot(
    public val username: String,
    public val displayName: String,
    public val avatarUrl: String?,
)

/** Concrete inline representation so Swift never downcasts a Kotlin sealed protocol. */
public data class AppleRichTextInlineSnapshot(
    public val kind: AppleRichTextInlineKind,
    public val text: String,
    public val url: String?,
    public val auxiliaryText: String?,
    public val children: List<AppleRichTextInlineSnapshot>,
)

/**
 * One safe rich-text node with a stable path-derived id.
 *
 * Every source variant maps into the same concrete shape. [children] preserves semantic nesting for
 * quotes, lists, tables, and spoilers without exposing raw cooked HTML or a sealed Objective-C type.
 */
public data class AppleRichTextBlockSnapshot(
    public val id: String,
    public val kind: AppleRichTextBlockKind,
    public val text: String,
    public val auxiliaryText: String?,
    public val url: String?,
    public val linkUrl: String?,
    public val inlines: List<AppleRichTextInlineSnapshot>,
    public val children: List<AppleRichTextBlockSnapshot>,
    public val ordered: Boolean,
    public val startIndex: Int,
    public val itemIndex: Int?,
    public val isHeader: Boolean,
    public val columnSpan: Int,
    public val rowSpan: Int,
)

public data class AppleForumFeedSnapshot(
    public val kind: AppleForumFeedKind,
    public val stableKey: String,
    public val id: Long?,
    public val name: String?,
    public val slug: String?,
    public val parentSlug: String?,
)

public data class AppleForumTopicRowSnapshot(
    public val id: String,
    public val topicId: Long?,
    public val postNumber: Int?,
    public val slug: String?,
    public val title: String,
    public val excerpt: String,
    public val author: AppleForumAuthorSnapshot,
    public val replyCount: Int,
    public val viewCount: Int,
    public val lastActivityEpochMillis: Long,
    public val unread: Boolean,
    public val categoryName: String?,
    public val categoryId: Long?,
    public val tags: List<String>,
    public val unreadPostCount: Int,
    public val newPostCount: Int,
    public val highestPostNumber: Int?,
    public val lastReadPostNumber: Int?,
    public val canCreatePost: Boolean,
    public val canBookmark: Boolean,
    public val liked: Boolean,
    public val bookmarked: Boolean,
    public val bookmarkId: Long?,
)

public data class AppleForumCategorySnapshot(
    public val id: Long,
    public val name: String,
    public val slug: String,
    public val parentCategoryId: Long?,
    public val parentSlug: String?,
    public val colorHex: String?,
    public val topicCount: Int,
)

public data class AppleForumTagSnapshot(
    public val id: Long,
    public val name: String,
    public val slug: String,
    public val count: Int,
)

public data class AppleForumArticleSnapshot(
    public val id: String,
    public val title: String,
    public val author: AppleForumAuthorSnapshot,
    public val createdAtEpochMillis: Long,
    public val blocks: List<AppleRichTextBlockSnapshot>,
    public val topicId: Long?,
    public val postId: Long?,
    public val postNumber: Int?,
    public val replyToPostNumber: Int?,
    public val canReply: Boolean,
    public val canEdit: Boolean,
    public val canDelete: Boolean,
    public val canLike: Boolean,
    public val liked: Boolean,
    public val likeCount: Int,
    public val canBookmark: Boolean,
    public val bookmarked: Boolean,
    public val bookmarkId: Long?,
    public val currentReaction: String?,
)

public data class AppleForumTopicSnapshot(
    public val topicId: Long,
    public val title: String,
    public val slug: String,
    public val categoryId: Long?,
    public val tags: List<String>,
    public val articles: List<AppleForumArticleSnapshot>,
    public val canReply: Boolean,
    public val canBookmark: Boolean,
    public val bookmarked: Boolean,
    public val bookmarkId: Long?,
    public val source: AppleForumContentSource,
    public val updatedAtEpochMillis: Long,
    public val fallbackFailure: AppleForumFailure?,
)

public data class AppleForumSearchHitSnapshot(
    public val id: String,
    public val postId: Long,
    public val topicId: Long,
    public val postNumber: Int,
    public val topicSlug: String,
    public val title: String,
    public val excerpt: String,
    public val author: AppleForumAuthorSnapshot,
    public val createdAtEpochMillis: Long?,
    public val likeCount: Int,
    public val categoryId: Long?,
    public val tags: List<String>,
)

public data class AppleForumSearchSnapshot(
    public val query: String,
    public val submittedQuery: String,
    public val items: List<AppleForumSearchHitSnapshot>,
    public val nextPage: Int?,
    public val isLoading: Boolean,
    public val isAppending: Boolean,
    public val failure: AppleForumFailure?,
    public val appendFailure: AppleForumFailure?,
)

public data class AppleForumUserSummarySnapshot(
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
)

public data class AppleForumBadgeSnapshot(
    public val id: Long,
    public val name: String,
    public val detailText: String?,
    public val icon: String?,
    public val imageUrl: String?,
    public val count: Int,
)

public data class AppleForumProfileValueSnapshot(
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
    public val bio: List<AppleRichTextBlockSnapshot>,
    public val badges: List<AppleForumBadgeSnapshot>,
    public val summary: AppleForumUserSummarySnapshot,
)

public data class AppleForumActivitySnapshot(
    public val id: String,
    public val actionType: Int,
    public val kind: AppleForumActivityKind,
    public val createdAtEpochMillis: Long,
    public val user: AppleForumAuthorSnapshot?,
    public val actingUser: AppleForumAuthorSnapshot?,
    public val topicId: Long?,
    public val postNumber: Int?,
    public val postId: Long?,
    public val topicSlug: String?,
    public val title: String?,
    public val excerpt: String,
    public val categoryId: Long?,
    public val closed: Boolean,
    public val archived: Boolean,
    public val hidden: Boolean,
    public val deleted: Boolean,
)

public data class AppleForumProfileSnapshot(
    public val username: String?,
    public val value: AppleForumProfileValueSnapshot?,
    public val activity: List<AppleForumActivitySnapshot>,
    public val nextOffset: Int?,
    public val isLoading: Boolean,
    public val isActivityLoading: Boolean,
    public val isAppendingActivity: Boolean,
    public val failure: AppleForumFailure?,
    public val activityFailure: AppleForumFailure?,
    public val activityAppendFailure: AppleForumFailure?,
)

public data class AppleForumNotificationDataSnapshot(
    public val topicTitle: String?,
    public val displayUsername: String?,
    public val username: String?,
    public val originalUsername: String?,
    public val badgeName: String?,
    public val groupName: String?,
    public val count: Int?,
)

public data class AppleForumNotificationSnapshot(
    public val id: Long,
    public val recipientUserId: Long,
    public val kind: AppleForumNotificationKind,
    public val read: Boolean,
    public val highPriority: Boolean,
    public val createdAtEpochMillis: Long?,
    public val topicId: Long?,
    public val postNumber: Int?,
    public val topicSlug: String?,
    public val title: String?,
    public val actingUser: AppleForumAuthorSnapshot?,
    public val data: AppleForumNotificationDataSnapshot,
)

public data class AppleForumNotificationsSnapshot(
    public val items: List<AppleForumNotificationSnapshot>,
    public val unreadCount: Int,
    public val totalRows: Int,
    public val seenNotificationId: Long,
    public val nextOffset: Int?,
    public val isLoading: Boolean,
    public val isAppending: Boolean,
    public val isMarkingRead: Boolean,
    public val failure: AppleForumFailure?,
    public val appendFailure: AppleForumFailure?,
    public val markFailure: AppleForumFailure?,
)

/** Complete immutable forum snapshot consumed by the SwiftUI store on the main actor. */
public data class AppleForumSnapshot(
    public val destination: AppleForumDestination,
    public val selection: AppleForumFeedSnapshot,
    public val topics: List<AppleForumTopicRowSnapshot>,
    public val categories: List<AppleForumCategorySnapshot>,
    public val tags: List<AppleForumTagSnapshot>,
    public val selectedTopicId: Long?,
    public val selectedPostNumber: Int?,
    public val selectedTopic: AppleForumTopicSnapshot?,
    public val sessionGeneration: Long,
    public val accountId: String?,
    public val isAuthenticated: Boolean,
    public val canCreateTopic: Boolean,
    public val accountUsername: String?,
    public val search: AppleForumSearchSnapshot,
    public val profile: AppleForumProfileSnapshot,
    public val notifications: AppleForumNotificationsSnapshot,
    public val nextPage: Int?,
    public val isFeedLoading: Boolean,
    public val isAppending: Boolean,
    public val isTaxonomyLoading: Boolean,
    public val isTopicLoading: Boolean,
    public val feedSource: AppleForumContentSource?,
    public val topicSource: AppleForumContentSource?,
    public val feedFailure: AppleForumFailure?,
    public val appendFailure: AppleForumFailure?,
    public val taxonomyFailure: AppleForumFailure?,
    public val topicFailure: AppleForumFailure?,
    public val realtimeRecoveryReason: AppleSessionRecoveryReason?,
)

public data class AppleComposerTargetSnapshot(
    public val kind: AppleComposerTargetKind,
    public val stableKey: String,
    public val categoryId: Long?,
    public val topicId: Long?,
    public val postId: Long?,
    public val postNumber: Int?,
    public val replyToPostNumber: Int?,
)

public data class AppleRequiredTagGroupSnapshot(
    public val name: String,
    public val minimumCount: Int,
    public val maximumCount: Int?,
    public val acceptedTags: List<String>,
    public val membershipAvailable: Boolean,
)

public data class AppleNewTopicConstraintsSnapshot(
    public val categoryId: Long?,
    public val minimumRequiredTags: Int,
    public val requiredTagGroups: List<AppleRequiredTagGroupSnapshot>,
)

public data class ApplePublishedPostSnapshot(
    public val postId: Long,
    public val topicId: Long,
    public val postNumber: Int,
)

public data class ApplePendingModerationSnapshot(
    public val pendingCount: Int,
    public val pendingPostId: Long?,
    public val topicId: Long?,
)

public data class AppleUploadedAttachmentSnapshot(
    public val uploadId: Long?,
    public val markdownReference: String,
    public val composerMarkdown: String,
    public val originalFilename: String,
    public val width: Int?,
    public val height: Int?,
    public val fileSizeBytes: Long?,
    public val fileExtension: String?,
)

public data class AppleComposerUploadSnapshot(
    public val status: AppleComposerUploadStatus,
    public val taskEpoch: Long,
    public val attempt: Long?,
    public val bytesSent: Long,
    public val totalBytes: Long?,
    public val attachment: AppleUploadedAttachmentSnapshot?,
    public val failure: AppleForumFailure?,
    public val isComposerInsertionPending: Boolean,
)

public data class ApplePostActionSnapshot(
    public val targetKind: AppleActionTargetKind,
    public val targetId: Long,
    public val liked: Boolean,
    public val likeCount: Int,
    public val canLike: Boolean,
    public val bookmarked: Boolean,
    public val bookmarkId: Long?,
    public val canBookmark: Boolean,
    public val isLikeInFlight: Boolean,
    public val isBookmarkInFlight: Boolean,
    public val likeFailure: AppleForumFailure?,
    public val bookmarkFailure: AppleForumFailure?,
    public val likeNotAllowedReason: AppleActionNotAllowedReason?,
    public val bookmarkNotAllowedReason: AppleActionNotAllowedReason?,
)

/** Complete immutable composer owner and mutation snapshot for SwiftUI. */
public data class AppleComposerSnapshot(
    public val mode: AppleComposerMode,
    public val sessionGeneration: Long,
    public val contentVersion: Long,
    public val accountId: String?,
    public val target: AppleComposerTargetSnapshot?,
    public val title: String?,
    public val raw: String,
    public val tags: List<String>,
    public val constraints: AppleNewTopicConstraintsSnapshot?,
    public val isInitializing: Boolean,
    public val initializationFailure: AppleForumFailure?,
    public val draftStatus: AppleComposerDraftStatus,
    public val draftRevision: Long?,
    public val draftUpdatedAtEpochMillis: Long?,
    public val draftFailure: AppleForumFailure?,
    public val submitStatus: AppleComposerSubmitStatus,
    public val publishedPost: ApplePublishedPostSnapshot?,
    public val pendingModeration: ApplePendingModerationSnapshot?,
    public val submitFailure: AppleForumFailure?,
    public val validationFailure: AppleComposerValidationFailure?,
    public val upload: AppleComposerUploadSnapshot,
    public val postActions: List<ApplePostActionSnapshot>,
    public val canEdit: Boolean,
    public val canSubmit: Boolean,
)

internal fun DiscourseForumState.toAppleSnapshot(): AppleForumSnapshot =
    AppleForumSnapshot(
        destination = destination.toAppleSnapshot(),
        selection = selection.toAppleSnapshot(),
        topics = topics.map(UiTimelineV2.Topic::toAppleSnapshot),
        categories =
            categories.map {
                AppleForumCategorySnapshot(
                    id = it.id,
                    name = it.name,
                    slug = it.slug,
                    parentCategoryId = it.parentCategoryId,
                    parentSlug = it.parentSlug,
                    colorHex = it.colorHex,
                    topicCount = it.topicCount,
                )
            },
        tags = tags.map { AppleForumTagSnapshot(it.id, it.name, it.slug, it.count) },
        selectedTopicId = selectedTopicId,
        selectedPostNumber = selectedPostNumber,
        selectedTopic = selectedTopic?.toAppleSnapshot(),
        sessionGeneration = sessionGeneration,
        accountId = accountId,
        isAuthenticated = isAuthenticated,
        canCreateTopic = canCreateTopic,
        accountUsername = accountUsername,
        search =
            AppleForumSearchSnapshot(
                query = search.query,
                submittedQuery = search.submittedQuery,
                items = search.items.map(DiscourseForumSearchHit::toAppleSnapshot),
                nextPage = search.nextPage?.value,
                isLoading = search.isLoading,
                isAppending = search.isAppending,
                failure = search.failure?.toAppleSnapshot(),
                appendFailure = search.appendFailure?.toAppleSnapshot(),
            ),
        profile =
            AppleForumProfileSnapshot(
                username = profile.username,
                value = profile.value?.toAppleSnapshot(),
                activity = profile.activity.map(DiscourseForumActivity::toAppleSnapshot),
                nextOffset = profile.nextOffset,
                isLoading = profile.isLoading,
                isActivityLoading = profile.isActivityLoading,
                isAppendingActivity = profile.isAppendingActivity,
                failure = profile.failure?.toAppleSnapshot(),
                activityFailure = profile.activityFailure?.toAppleSnapshot(),
                activityAppendFailure = profile.activityAppendFailure?.toAppleSnapshot(),
            ),
        notifications =
            AppleForumNotificationsSnapshot(
                items =
                    notifications.snapshot
                        ?.items
                        .orEmpty()
                        .map(DiscourseForumNotification::toAppleSnapshot),
                unreadCount = notifications.snapshot?.unreadCount ?: 0,
                totalRows = notifications.snapshot?.totalRows ?: 0,
                seenNotificationId = notifications.snapshot?.seenNotificationId ?: 0L,
                nextOffset = notifications.nextOffset?.value,
                isLoading = notifications.isLoading,
                isAppending = notifications.isAppending,
                isMarkingRead = notifications.isMarkingRead,
                failure = notifications.failure?.toAppleSnapshot(),
                appendFailure = notifications.appendFailure?.toAppleSnapshot(),
                markFailure = notifications.markFailure?.toAppleSnapshot(),
            ),
        nextPage = nextPage,
        isFeedLoading = isFeedLoading,
        isAppending = isAppending,
        isTaxonomyLoading = isTaxonomyLoading,
        isTopicLoading = isTopicLoading,
        feedSource = feedSource?.toAppleSnapshot(),
        topicSource = topicSource?.toAppleSnapshot(),
        feedFailure = feedFailure?.toAppleSnapshot(),
        appendFailure = appendFailure?.toAppleSnapshot(),
        taxonomyFailure = taxonomyFailure?.toAppleSnapshot(),
        topicFailure = topicFailure?.toAppleSnapshot(),
        realtimeRecoveryReason = realtimeRecoveryReason?.toAppleSnapshot(),
    )

internal fun DiscourseComposerState.toAppleSnapshot(): AppleComposerSnapshot =
    AppleComposerSnapshot(
        mode = mode.toAppleSnapshot(),
        sessionGeneration = sessionGeneration,
        contentVersion = contentVersion,
        accountId = accountId,
        target = target?.toAppleSnapshot(),
        title = title,
        raw = raw,
        tags = tags.toList(),
        constraints =
            constraints?.let { value ->
                AppleNewTopicConstraintsSnapshot(
                    categoryId = value.categoryId,
                    minimumRequiredTags = value.minimumRequiredTags,
                    requiredTagGroups =
                        value.requiredTagGroups.map { group ->
                            AppleRequiredTagGroupSnapshot(
                                name = group.name,
                                minimumCount = group.minimumCount,
                                maximumCount = group.maximumCount,
                                acceptedTags = group.acceptedTags.sorted(),
                                membershipAvailable = group.membershipAvailable,
                            )
                        },
                )
            },
        isInitializing = isInitializing,
        initializationFailure = initializationFailure?.toAppleSnapshot(),
        draftStatus = draftStatus.toAppleSnapshot(),
        draftRevision = draftRevision,
        draftUpdatedAtEpochMillis = draftUpdatedAtEpochMillis,
        draftFailure = draftFailure?.toAppleSnapshot(),
        submitStatus = submitStatus.toAppleSnapshot(),
        publishedPost = publishedPost?.let { ApplePublishedPostSnapshot(it.postId, it.topicId, it.postNumber) },
        pendingModeration =
            pendingModeration?.let {
                ApplePendingModerationSnapshot(it.pendingCount, it.pendingPostId, it.topicId)
            },
        submitFailure = submitFailure?.toAppleSnapshot(),
        validationFailure = validationFailure?.toAppleSnapshot(),
        upload =
            AppleComposerUploadSnapshot(
                status = upload.status.toAppleSnapshot(),
                taskEpoch = upload.taskEpoch,
                attempt = upload.attempt,
                bytesSent = upload.bytesSent,
                totalBytes = upload.totalBytes,
                attachment =
                    upload.attachment?.let {
                        AppleUploadedAttachmentSnapshot(
                            uploadId = it.uploadId,
                            markdownReference = it.markdownReference,
                            composerMarkdown = it.composerMarkdown,
                            originalFilename = it.originalFilename,
                            width = it.width,
                            height = it.height,
                            fileSizeBytes = it.fileSizeBytes,
                            fileExtension = it.extension,
                        )
                    },
                failure = upload.failure?.toAppleSnapshot(),
                isComposerInsertionPending = upload.isComposerInsertionPending,
            ),
        postActions =
            postActions.map { value ->
                val (kind, id) =
                    when (val targetValue = value.target) {
                        is DiscourseActionTarget.Post -> AppleActionTargetKind.POST to targetValue.postId
                        is DiscourseActionTarget.Topic -> AppleActionTargetKind.TOPIC to targetValue.topicId
                    }
                ApplePostActionSnapshot(
                    targetKind = kind,
                    targetId = id,
                    liked = value.liked,
                    likeCount = value.likeCount,
                    canLike = value.canLike,
                    bookmarked = value.bookmarked,
                    bookmarkId = value.bookmarkId,
                    canBookmark = value.canBookmark,
                    isLikeInFlight = value.isLikeInFlight,
                    isBookmarkInFlight = value.isBookmarkInFlight,
                    likeFailure = value.likeFailure?.toAppleSnapshot(),
                    bookmarkFailure = value.bookmarkFailure?.toAppleSnapshot(),
                    likeNotAllowedReason = value.likeNotAllowedReason?.toAppleSnapshot(),
                    bookmarkNotAllowedReason = value.bookmarkNotAllowedReason?.toAppleSnapshot(),
                )
            },
        canEdit = canEdit,
        canSubmit = canSubmit,
    )

private fun DiscourseForumDestination.toAppleSnapshot(): AppleForumDestination =
    when (this) {
        DiscourseForumDestination.Latest -> AppleForumDestination.LATEST
        DiscourseForumDestination.Hot -> AppleForumDestination.HOT
        DiscourseForumDestination.Search -> AppleForumDestination.SEARCH
        DiscourseForumDestination.Notifications -> AppleForumDestination.NOTIFICATIONS
        DiscourseForumDestination.Profile -> AppleForumDestination.PROFILE
    }

private fun DiscourseForumFeed.toAppleSnapshot(): AppleForumFeedSnapshot =
    when (this) {
        DiscourseForumFeed.Latest -> {
            AppleForumFeedSnapshot(AppleForumFeedKind.LATEST, stableKey, null, null, null, null)
        }

        DiscourseForumFeed.Hot -> {
            AppleForumFeedSnapshot(AppleForumFeedKind.HOT, stableKey, null, null, null, null)
        }

        is DiscourseForumFeed.Category -> {
            AppleForumFeedSnapshot(
                kind = AppleForumFeedKind.CATEGORY,
                stableKey = stableKey,
                id = id,
                name = name,
                slug = slug,
                parentSlug = parentSlug,
            )
        }

        is DiscourseForumFeed.Tag -> {
            AppleForumFeedSnapshot(
                kind = AppleForumFeedKind.TAG,
                stableKey = stableKey,
                id = null,
                name = name,
                slug = slug,
                parentSlug = null,
            )
        }
    }

private fun UiTimelineV2.Topic.toAppleSnapshot(): AppleForumTopicRowSnapshot {
    val meta = discourse
    return AppleForumTopicRowSnapshot(
        id = itemKey,
        topicId = meta?.ref?.topicId,
        postNumber = meta?.ref?.postNumber,
        slug = meta?.slug,
        title = title,
        excerpt = excerpt,
        author = author.toAppleSnapshot(),
        replyCount = replyCount,
        viewCount = viewCount,
        lastActivityEpochMillis = lastActivityEpochMillis,
        unread = unread,
        categoryName = categoryName,
        categoryId = meta?.categoryId,
        tags = tags.toList(),
        unreadPostCount = meta?.unreadPostCount ?: 0,
        newPostCount = meta?.newPostCount ?: 0,
        highestPostNumber = meta?.highestPostNumber,
        lastReadPostNumber = meta?.lastReadPostNumber,
        canCreatePost = meta?.canCreatePost == true,
        canBookmark = meta?.canBookmark == true,
        liked = meta?.liked == true,
        bookmarked = meta?.bookmarked == true,
        bookmarkId = meta?.bookmarkId,
    )
}

private fun DiscourseForumTopic.toAppleSnapshot(): AppleForumTopicSnapshot =
    AppleForumTopicSnapshot(
        topicId = topicId,
        title = title,
        slug = slug,
        categoryId = categoryId,
        tags = tags.toList(),
        articles = articles.map(UiArticle::toAppleSnapshot),
        canReply = canReply,
        canBookmark = discourse?.canBookmark == true,
        bookmarked = discourse?.bookmarked == true,
        bookmarkId = discourse?.bookmarkId,
        source = source.toAppleSnapshot(),
        updatedAtEpochMillis = updatedAtEpochMillis,
        fallbackFailure = fallbackFailure?.toAppleSnapshot(),
    )

private fun UiArticle.toAppleSnapshot(): AppleForumArticleSnapshot {
    val meta = discourse
    return AppleForumArticleSnapshot(
        id = itemKey,
        title = title,
        author = author.toAppleSnapshot(),
        createdAtEpochMillis = createdAtEpochMillis,
        blocks = blocks.toAppleSnapshots(itemKey),
        topicId = meta?.topicId,
        postId = meta?.postId,
        postNumber = meta?.postNumber,
        replyToPostNumber = meta?.replyToPostNumber,
        canReply = canReply,
        canEdit = meta?.canEdit == true,
        canDelete = meta?.canDelete == true,
        canLike = meta?.canLike == true,
        liked = meta?.liked == true,
        likeCount = meta?.likeCount ?: 0,
        canBookmark = meta?.canBookmark == true,
        bookmarked = meta?.bookmarked == true,
        bookmarkId = meta?.bookmarkId,
        currentReaction = meta?.currentReaction,
    )
}

internal fun List<UiArticleBlock>.toAppleSnapshots(ownerKey: String): List<AppleRichTextBlockSnapshot> =
    mapIndexed { index, block -> block.toAppleSnapshot("$ownerKey:block:$index") }

private fun UiArticleBlock.toAppleSnapshot(id: String): AppleRichTextBlockSnapshot =
    when (this) {
        is UiArticleBlock.Paragraph -> {
            appleBlock(
                id = id,
                kind = AppleRichTextBlockKind.PARAGRAPH,
                text = text,
                inlines = inlines.map(UiArticleInline::toAppleSnapshot),
            )
        }

        is UiArticleBlock.Quote -> {
            appleBlock(
                id = id,
                kind = AppleRichTextBlockKind.QUOTE,
                text = text,
                auxiliaryText = attribution,
                children = blocks.mapIndexed { index, child -> child.toAppleSnapshot("$id:quote:$index") },
            )
        }

        is UiArticleBlock.Code -> {
            appleBlock(
                id = id,
                kind = AppleRichTextBlockKind.CODE,
                text = code,
                auxiliaryText = language,
            )
        }

        is UiArticleBlock.Image -> {
            appleBlock(
                id = id,
                kind = AppleRichTextBlockKind.IMAGE,
                text = altText.orEmpty(),
                auxiliaryText = title,
                url = url,
                linkUrl = linkUrl,
            )
        }

        is UiArticleBlock.ListBlock -> {
            appleBlock(
                id = id,
                kind = AppleRichTextBlockKind.LIST,
                ordered = ordered,
                startIndex = startIndex,
                children =
                    items.mapIndexed { itemIndex, item ->
                        appleBlock(
                            id = "$id:item:$itemIndex",
                            kind = AppleRichTextBlockKind.LIST_ITEM,
                            itemIndex = itemIndex,
                            children =
                                item.blocks.mapIndexed { blockIndex, child ->
                                    child.toAppleSnapshot("$id:item:$itemIndex:block:$blockIndex")
                                },
                        )
                    },
            )
        }

        is UiArticleBlock.Table -> {
            appleBlock(
                id = id,
                kind = AppleRichTextBlockKind.TABLE,
                auxiliaryText = caption,
                children =
                    rows.mapIndexed { rowIndex, row ->
                        appleBlock(
                            id = "$id:row:$rowIndex",
                            kind = AppleRichTextBlockKind.TABLE_ROW,
                            itemIndex = rowIndex,
                            children =
                                row.cells.mapIndexed { cellIndex, cell ->
                                    appleBlock(
                                        id = "$id:row:$rowIndex:cell:$cellIndex",
                                        kind = AppleRichTextBlockKind.TABLE_CELL,
                                        text = cell.text,
                                        inlines = cell.inlines.map(UiArticleInline::toAppleSnapshot),
                                        itemIndex = cellIndex,
                                        isHeader = cell.isHeader,
                                        columnSpan = cell.columnSpan,
                                        rowSpan = cell.rowSpan,
                                    )
                                },
                        )
                    },
            )
        }

        is UiArticleBlock.Spoiler -> {
            appleBlock(
                id = id,
                kind = AppleRichTextBlockKind.SPOILER,
                text = text,
                auxiliaryText = summary,
                children = blocks.mapIndexed { index, child -> child.toAppleSnapshot("$id:spoiler:$index") },
            )
        }
    }

private fun appleBlock(
    id: String,
    kind: AppleRichTextBlockKind,
    text: String = "",
    auxiliaryText: String? = null,
    url: String? = null,
    linkUrl: String? = null,
    inlines: List<AppleRichTextInlineSnapshot> = emptyList(),
    children: List<AppleRichTextBlockSnapshot> = emptyList(),
    ordered: Boolean = false,
    startIndex: Int = 1,
    itemIndex: Int? = null,
    isHeader: Boolean = false,
    columnSpan: Int = 1,
    rowSpan: Int = 1,
): AppleRichTextBlockSnapshot =
    AppleRichTextBlockSnapshot(
        id = id,
        kind = kind,
        text = text,
        auxiliaryText = auxiliaryText,
        url = url,
        linkUrl = linkUrl,
        inlines = inlines,
        children = children,
        ordered = ordered,
        startIndex = startIndex,
        itemIndex = itemIndex,
        isHeader = isHeader,
        columnSpan = columnSpan,
        rowSpan = rowSpan,
    )

private fun UiArticleInline.toAppleSnapshot(): AppleRichTextInlineSnapshot =
    when (this) {
        is UiArticleInline.Text -> {
            AppleRichTextInlineSnapshot(AppleRichTextInlineKind.TEXT, text, null, null, emptyList())
        }

        is UiArticleInline.Link -> {
            AppleRichTextInlineSnapshot(AppleRichTextInlineKind.LINK, text, url, null, emptyList())
        }

        is UiArticleInline.Code -> {
            AppleRichTextInlineSnapshot(AppleRichTextInlineKind.CODE, code, null, null, emptyList())
        }

        is UiArticleInline.Image -> {
            AppleRichTextInlineSnapshot(AppleRichTextInlineKind.IMAGE, altText.orEmpty(), url, title, emptyList())
        }

        is UiArticleInline.Spoiler -> {
            AppleRichTextInlineSnapshot(
                AppleRichTextInlineKind.SPOILER,
                text,
                null,
                null,
                inlines.map(UiArticleInline::toAppleSnapshot),
            )
        }
    }

private fun DiscourseForumSearchHit.toAppleSnapshot(): AppleForumSearchHitSnapshot =
    AppleForumSearchHitSnapshot(
        id = itemKey,
        postId = postId,
        topicId = topic.topicId,
        postNumber = checkNotNull(topic.postNumber),
        topicSlug = topicSlug,
        title = title,
        excerpt = excerpt,
        author = author.toAppleSnapshot(),
        createdAtEpochMillis = createdAtEpochMillis,
        likeCount = likeCount,
        categoryId = categoryId,
        tags = tags.toList(),
    )

private fun DiscourseForumProfile.toAppleSnapshot(): AppleForumProfileValueSnapshot =
    AppleForumProfileValueSnapshot(
        userId = userId,
        username = username,
        displayName = displayName,
        avatarUrl = avatarUrl,
        title = title,
        trustLevel = trustLevel,
        moderator = moderator,
        admin = admin,
        staff = staff,
        active = active,
        suspended = suspended,
        canSendPrivateMessages = canSendPrivateMessages,
        canEdit = canEdit,
        createdAtEpochMillis = createdAtEpochMillis,
        lastPostedAtEpochMillis = lastPostedAtEpochMillis,
        lastSeenAtEpochMillis = lastSeenAtEpochMillis,
        websiteName = websiteName,
        websiteUrl = websiteUrl,
        location = location,
        primaryGroupName = primaryGroupName,
        bio = bio.toAppleSnapshots("profile:$userId:bio"),
        badges = badges.map { AppleForumBadgeSnapshot(it.id, it.name, it.description, it.icon, it.imageUrl, it.count) },
        summary =
            AppleForumUserSummarySnapshot(
                likesGiven = summary.likesGiven,
                likesReceived = summary.likesReceived,
                topicsEntered = summary.topicsEntered,
                postsReadCount = summary.postsReadCount,
                daysVisited = summary.daysVisited,
                topicCount = summary.topicCount,
                postCount = summary.postCount,
                timeReadSeconds = summary.timeReadSeconds,
                recentTimeReadSeconds = summary.recentTimeReadSeconds,
                solvedCount = summary.solvedCount,
            ),
    )

private fun DiscourseForumActivity.toAppleSnapshot(): AppleForumActivitySnapshot =
    AppleForumActivitySnapshot(
        id = itemKey,
        actionType = actionType,
        kind = kind.toAppleSnapshot(),
        createdAtEpochMillis = createdAtEpochMillis,
        user = user?.toAppleSnapshot(),
        actingUser = actingUser?.toAppleSnapshot(),
        topicId = topic?.topicId,
        postNumber = topic?.postNumber,
        postId = postId,
        topicSlug = topicSlug,
        title = title,
        excerpt = excerpt,
        categoryId = categoryId,
        closed = closed,
        archived = archived,
        hidden = hidden,
        deleted = deleted,
    )

private fun DiscourseForumNotification.toAppleSnapshot(): AppleForumNotificationSnapshot =
    AppleForumNotificationSnapshot(
        id = id,
        recipientUserId = recipientUserId,
        kind = kind.toAppleSnapshot(),
        read = read,
        highPriority = highPriority,
        createdAtEpochMillis = createdAtEpochMillis,
        topicId = topic?.topicId,
        postNumber = topic?.postNumber,
        topicSlug = topicSlug,
        title = title,
        actingUser = actingUser?.toAppleSnapshot(),
        data =
            AppleForumNotificationDataSnapshot(
                topicTitle = data.topicTitle,
                displayUsername = data.displayUsername,
                username = data.username,
                originalUsername = data.originalUsername,
                badgeName = data.badgeName,
                groupName = data.groupName,
                count = data.count,
            ),
    )

private fun UiAuthor.toAppleSnapshot(): AppleForumAuthorSnapshot = AppleForumAuthorSnapshot(username, displayName, avatarUrl)

private fun DiscourseForumContentSource.toAppleSnapshot(): AppleForumContentSource =
    when (this) {
        DiscourseForumContentSource.Network -> AppleForumContentSource.NETWORK
        DiscourseForumContentSource.StaleCache -> AppleForumContentSource.STALE_CACHE
    }

private fun DiscourseForumFailureKind.toAppleSnapshot(): AppleForumFailure =
    when (this) {
        DiscourseForumFailureKind.Network -> AppleForumFailure.NETWORK
        DiscourseForumFailureKind.Authentication -> AppleForumFailure.AUTHENTICATION
        DiscourseForumFailureKind.Permission -> AppleForumFailure.PERMISSION
        DiscourseForumFailureKind.RateLimited -> AppleForumFailure.RATE_LIMITED
        DiscourseForumFailureKind.ChallengeRequired -> AppleForumFailure.CHALLENGE_REQUIRED
        DiscourseForumFailureKind.Server -> AppleForumFailure.SERVER
        DiscourseForumFailureKind.InvalidResponse -> AppleForumFailure.INVALID_RESPONSE
        DiscourseForumFailureKind.Http -> AppleForumFailure.HTTP
    }

private fun DiscourseSessionRecoveryReason.toAppleSnapshot(): AppleSessionRecoveryReason =
    when (this) {
        DiscourseSessionRecoveryReason.AuthenticationRequired -> AppleSessionRecoveryReason.AUTHENTICATION_REQUIRED
        DiscourseSessionRecoveryReason.PermissionDenied -> AppleSessionRecoveryReason.PERMISSION_DENIED
        DiscourseSessionRecoveryReason.ManualChallengeRequired -> AppleSessionRecoveryReason.MANUAL_CHALLENGE_REQUIRED
    }

private fun DiscourseForumActivityKind.toAppleSnapshot(): AppleForumActivityKind =
    when (this) {
        DiscourseForumActivityKind.Liked -> AppleForumActivityKind.LIKED
        DiscourseForumActivityKind.WasLiked -> AppleForumActivityKind.WAS_LIKED
        DiscourseForumActivityKind.Bookmarked -> AppleForumActivityKind.BOOKMARKED
        DiscourseForumActivityKind.TopicCreated -> AppleForumActivityKind.TOPIC_CREATED
        DiscourseForumActivityKind.Replied -> AppleForumActivityKind.REPLIED
        DiscourseForumActivityKind.Mentioned -> AppleForumActivityKind.MENTIONED
        DiscourseForumActivityKind.Quoted -> AppleForumActivityKind.QUOTED
        DiscourseForumActivityKind.Edited -> AppleForumActivityKind.EDITED
        DiscourseForumActivityKind.PrivateMessage -> AppleForumActivityKind.PRIVATE_MESSAGE
        DiscourseForumActivityKind.Solved -> AppleForumActivityKind.SOLVED
        DiscourseForumActivityKind.Generic -> AppleForumActivityKind.GENERIC
    }

private fun DiscourseForumNotificationKind.toAppleSnapshot(): AppleForumNotificationKind =
    when (this) {
        DiscourseForumNotificationKind.Mention -> AppleForumNotificationKind.MENTION
        DiscourseForumNotificationKind.Reply -> AppleForumNotificationKind.REPLY
        DiscourseForumNotificationKind.Quote -> AppleForumNotificationKind.QUOTE
        DiscourseForumNotificationKind.Edit -> AppleForumNotificationKind.EDIT
        DiscourseForumNotificationKind.Like -> AppleForumNotificationKind.LIKE
        DiscourseForumNotificationKind.PrivateMessage -> AppleForumNotificationKind.PRIVATE_MESSAGE
        DiscourseForumNotificationKind.Invitation -> AppleForumNotificationKind.INVITATION
        DiscourseForumNotificationKind.Posted -> AppleForumNotificationKind.POSTED
        DiscourseForumNotificationKind.MovedPost -> AppleForumNotificationKind.MOVED_POST
        DiscourseForumNotificationKind.Link -> AppleForumNotificationKind.LINK
        DiscourseForumNotificationKind.Badge -> AppleForumNotificationKind.BADGE
        DiscourseForumNotificationKind.Group -> AppleForumNotificationKind.GROUP
        DiscourseForumNotificationKind.Reminder -> AppleForumNotificationKind.REMINDER
        DiscourseForumNotificationKind.Approval -> AppleForumNotificationKind.APPROVAL
        DiscourseForumNotificationKind.Reaction -> AppleForumNotificationKind.REACTION
        DiscourseForumNotificationKind.Generic -> AppleForumNotificationKind.GENERIC
    }

private fun DiscourseComposerMode.toAppleSnapshot(): AppleComposerMode =
    when (this) {
        DiscourseComposerMode.Closed -> AppleComposerMode.CLOSED
        DiscourseComposerMode.NewTopic -> AppleComposerMode.NEW_TOPIC
        DiscourseComposerMode.Reply -> AppleComposerMode.REPLY
        DiscourseComposerMode.Edit -> AppleComposerMode.EDIT
    }

private fun DiscourseComposerDraftStatus.toAppleSnapshot(): AppleComposerDraftStatus =
    when (this) {
        DiscourseComposerDraftStatus.None -> AppleComposerDraftStatus.NONE
        DiscourseComposerDraftStatus.Loading -> AppleComposerDraftStatus.LOADING
        DiscourseComposerDraftStatus.Clean -> AppleComposerDraftStatus.CLEAN
        DiscourseComposerDraftStatus.Dirty -> AppleComposerDraftStatus.DIRTY
        DiscourseComposerDraftStatus.Saving -> AppleComposerDraftStatus.SAVING
        DiscourseComposerDraftStatus.Saved -> AppleComposerDraftStatus.SAVED
        DiscourseComposerDraftStatus.Failed -> AppleComposerDraftStatus.FAILED
    }

private fun DiscourseComposerSubmitStatus.toAppleSnapshot(): AppleComposerSubmitStatus =
    when (this) {
        DiscourseComposerSubmitStatus.Idle -> AppleComposerSubmitStatus.IDLE
        DiscourseComposerSubmitStatus.Submitting -> AppleComposerSubmitStatus.SUBMITTING
        DiscourseComposerSubmitStatus.Published -> AppleComposerSubmitStatus.PUBLISHED
        DiscourseComposerSubmitStatus.PendingModeration -> AppleComposerSubmitStatus.PENDING_MODERATION
        DiscourseComposerSubmitStatus.Failed -> AppleComposerSubmitStatus.FAILED
    }

private fun DiscourseComposerUploadStatus.toAppleSnapshot(): AppleComposerUploadStatus =
    when (this) {
        DiscourseComposerUploadStatus.None -> AppleComposerUploadStatus.NONE
        DiscourseComposerUploadStatus.Ready -> AppleComposerUploadStatus.READY
        DiscourseComposerUploadStatus.Uploading -> AppleComposerUploadStatus.UPLOADING
        DiscourseComposerUploadStatus.Succeeded -> AppleComposerUploadStatus.SUCCEEDED
        DiscourseComposerUploadStatus.Failed -> AppleComposerUploadStatus.FAILED
        DiscourseComposerUploadStatus.Cancelled -> AppleComposerUploadStatus.CANCELLED
    }

private fun DiscourseComposerTarget.toAppleSnapshot(): AppleComposerTargetSnapshot =
    when (this) {
        is DiscourseComposerTarget.NewTopic -> {
            AppleComposerTargetSnapshot(
                AppleComposerTargetKind.NEW_TOPIC,
                stableKey,
                categoryId,
                null,
                null,
                null,
                null,
            )
        }

        is DiscourseComposerTarget.Reply -> {
            AppleComposerTargetSnapshot(
                AppleComposerTargetKind.REPLY,
                stableKey,
                null,
                topicId,
                null,
                null,
                replyToPostNumber,
            )
        }

        is DiscourseComposerTarget.Edit -> {
            AppleComposerTargetSnapshot(
                AppleComposerTargetKind.EDIT,
                stableKey,
                null,
                topicId,
                postId,
                postNumber,
                null,
            )
        }
    }

internal fun AppleComposerTargetSnapshot.toDiscourseTargetOrNull(): DiscourseComposerTarget? =
    try {
        when (kind) {
            AppleComposerTargetKind.NEW_TOPIC -> {
                DiscourseComposerTarget.NewTopic(categoryId)
            }

            AppleComposerTargetKind.REPLY -> {
                DiscourseComposerTarget.Reply(checkNotNull(topicId), replyToPostNumber)
            }

            AppleComposerTargetKind.EDIT -> {
                DiscourseComposerTarget.Edit(
                    topicId = checkNotNull(topicId),
                    postId = checkNotNull(postId),
                    postNumber = checkNotNull(postNumber),
                )
            }
        }.takeIf { it.stableKey == stableKey }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

private fun DiscourseComposerValidationFailure.toAppleSnapshot(): AppleComposerValidationFailure =
    when (this) {
        DiscourseComposerValidationFailure.DraftNotFound -> AppleComposerValidationFailure.DRAFT_NOT_FOUND
        DiscourseComposerValidationFailure.EmptyRaw -> AppleComposerValidationFailure.EMPTY_RAW
        DiscourseComposerValidationFailure.MissingTitle -> AppleComposerValidationFailure.MISSING_TITLE
        DiscourseComposerValidationFailure.UnexpectedTitle -> AppleComposerValidationFailure.UNEXPECTED_TITLE
        DiscourseComposerValidationFailure.CategoryUnavailable -> AppleComposerValidationFailure.CATEGORY_UNAVAILABLE
        DiscourseComposerValidationFailure.TooFewTags -> AppleComposerValidationFailure.TOO_FEW_TAGS
        DiscourseComposerValidationFailure.RequiredTagGroupMinimum -> AppleComposerValidationFailure.REQUIRED_TAG_GROUP_MINIMUM
        DiscourseComposerValidationFailure.RequiredTagGroupMaximum -> AppleComposerValidationFailure.REQUIRED_TAG_GROUP_MAXIMUM
        DiscourseComposerValidationFailure.EditableIdentityMismatch -> AppleComposerValidationFailure.EDITABLE_IDENTITY_MISMATCH
    }

private fun DiscourseActionNotAllowedReason.toAppleSnapshot(): AppleActionNotAllowedReason =
    when (this) {
        DiscourseActionNotAllowedReason.MissingServerState -> AppleActionNotAllowedReason.MISSING_SERVER_STATE
        DiscourseActionNotAllowedReason.PermissionDenied -> AppleActionNotAllowedReason.PERMISSION_DENIED
        DiscourseActionNotAllowedReason.MissingBookmarkId -> AppleActionNotAllowedReason.MISSING_BOOKMARK_ID
    }
