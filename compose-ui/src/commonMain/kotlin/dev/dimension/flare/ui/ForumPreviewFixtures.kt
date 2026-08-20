package dev.dimension.flare.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumActivity
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumActivityKind
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumBadge
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumCategoryOption
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumContentSource
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumDestination
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFailureKind
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFeed
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumNotification
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumNotificationData
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumNotificationKind
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumNotificationSnapshot
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumNotificationsState
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumProfile
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumProfileState
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumSearchHit
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumSearchState
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumState
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumTagOption
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumTopic
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumUserSummary
import dev.dimension.flare.data.network.discourse.paging.DiscourseNotificationOffset
import dev.dimension.flare.data.network.discourse.paging.DiscourseSearchPage
import dev.dimension.flare.ui.model.DiscoursePostMeta
import dev.dimension.flare.ui.model.DiscourseTopicMeta
import dev.dimension.flare.ui.model.DiscourseTopicRef
import dev.dimension.flare.ui.model.UiArticle
import dev.dimension.flare.ui.model.UiArticleBlock
import dev.dimension.flare.ui.model.UiArticleInline
import dev.dimension.flare.ui.model.UiArticleListItem
import dev.dimension.flare.ui.model.UiArticleTableCell
import dev.dimension.flare.ui.model.UiArticleTableRow
import dev.dimension.flare.ui.model.UiAuthor
import dev.dimension.flare.ui.model.UiTimelineV2

/** Stable layout buckets used by both the production shells and screenshot fixtures. */
public enum class ForumLayoutClass {
    Compact,
    Medium,
    Expanded,
}

/**
 * Maps the actual available window width instead of a device name or orientation.
 *
 * The boundaries intentionally line up with the 400/610/900 dp screenshot matrix. A foldable,
 * tablet, or desktop window therefore receives the same layout whenever its usable width matches.
 */
public fun forumLayoutClassFor(width: Dp): ForumLayoutClass =
    when {
        width < 600.dp -> ForumLayoutClass.Compact
        width < 840.dp -> ForumLayoutClass.Medium
        else -> ForumLayoutClass.Expanded
    }

/** Stable semantics identifiers used by host behavior tests and accessibility inspection. */
public object ForumTestTags {
    public const val WORKSPACE: String = "forum_workspace"
    public const val TOPIC_LIST: String = "forum_topic_list"
    public const val TOPIC_DETAIL: String = "forum_topic_detail"
    public const val SUPPORTING_PANE: String = "forum_supporting_pane"
    public const val FEED_ERROR: String = "forum_feed_error"
    public const val FEED_EMPTY: String = "forum_feed_empty"
    public const val CACHED_NOTICE: String = "forum_cached_notice"
    public const val LOAD_MORE_PROGRESS: String = "forum_load_more_progress"
    public const val SEARCH_RESULTS: String = "forum_search_results"
    public const val NOTIFICATIONS: String = "forum_notifications"
    public const val PROFILE: String = "forum_profile"
    public const val NEW_TOPIC: String = "forum_new_topic"
    public const val COMPOSER: String = "forum_composer"
    public const val COMPOSER_TITLE: String = "forum_composer_title"
    public const val COMPOSER_BODY: String = "forum_composer_body"
    public const val COMPOSER_TAGS: String = "forum_composer_tags"
    public const val COMPOSER_ATTACH: String = "forum_composer_attach"
    public const val COMPOSER_SUBMIT: String = "forum_composer_submit"
    public const val COMPOSER_CLOSE: String = "forum_composer_close"
    public const val COMPOSER_DISCARD: String = "forum_composer_discard"
    public const val COMPOSER_UPLOAD: String = "forum_composer_upload"
    public const val COMPOSER_CANCEL_UPLOAD: String = "forum_composer_cancel_upload"
    public const val COMPOSER_RETRY_UPLOAD: String = "forum_composer_retry_upload"

    public fun topic(topicId: Long): String = "forum_topic_$topicId"

    public fun postAction(
        postId: Long,
        action: String,
    ): String = "forum_post_${postId}_$action"

    public fun topicAction(
        topicId: Long,
        action: String,
    ): String = "forum_topic_${topicId}_$action"
}

/**
 * Deterministic, self-authored data for previews, screenshots, and host tests.
 *
 * These fixtures contain no copied Linux.do payload, user identity, post text, or fluxdo asset.
 * Keeping them in production-independent state also guarantees that screenshot tests never make a
 * network request or exercise a write endpoint.
 */
public object ForumPreviewFixtures {
    private val alex = UiAuthor(username = "alex", displayName = "Alex Chen")
    private val mina = UiAuthor(username = "mina", displayName = "Mina Zhou")
    private val robin = UiAuthor(username = "robin", displayName = "Robin Lin")
    private val previewMember =
        UiAuthor(username = "preview_member", displayName = "Preview Member")

    private val topics: List<UiTimelineV2.Topic> =
        listOf(
            topic(
                id = 4102L,
                title = "Kotlin Multiplatform desktop packaging notes",
                excerpt = "A concise checklist for reproducible Linux and Windows artifacts.",
                author = alex,
                replies = 28,
                views = 1_284,
                category = "Development",
                tags = listOf("kotlin", "desktop"),
                unread = true,
            ),
            topic(
                id = 4101L,
                title = "Home network observability without cloud telemetry",
                excerpt = "Comparing small, local-first dashboards and bounded log retention.",
                author = mina,
                replies = 16,
                views = 742,
                category = "Resources",
                tags = listOf("network", "privacy"),
            ),
            topic(
                id = 4098L,
                title = "Share your practical terminal workflow",
                excerpt = "Aliases, multiplexers, and scripts that remain understandable later.",
                author = robin,
                replies = 63,
                views = 2_906,
                category = "General",
                tags = listOf("linux", "workflow"),
            ),
            topic(
                id = 4094L,
                title = "An approachable introduction to passkeys",
                excerpt = "Threat boundaries and recovery choices explained with concrete cases.",
                author = alex,
                replies = 11,
                views = 618,
                category = "Security",
                tags = listOf("security"),
            ),
            topic(
                id = 4089L,
                title = "Small open-source projects worth studying",
                excerpt = "Well-scoped codebases with clear architecture and useful tests.",
                author = mina,
                replies = 35,
                views = 1_932,
                category = "Open Source",
                tags = listOf("opensource", "learning"),
            ),
        )

    private val categoryOptions: List<DiscourseForumCategoryOption> =
        listOf(
            DiscourseForumCategoryOption(
                id = 7L,
                name = "Development",
                slug = "development",
                colorHex = "087F73",
                topicCount = 318,
            ),
            DiscourseForumCategoryOption(
                id = 9L,
                name = "Resources",
                slug = "resources",
                colorHex = "B24F18",
                topicCount = 204,
            ),
            DiscourseForumCategoryOption(
                id = 12L,
                name = "Security",
                slug = "security",
                colorHex = "4E5F91",
                topicCount = 96,
            ),
            DiscourseForumCategoryOption(
                id = 15L,
                name = "General",
                slug = "general",
                topicCount = 527,
            ),
        )

    private val tagOptions: List<DiscourseForumTagOption> =
        listOf(
            DiscourseForumTagOption(id = 1L, name = "linux", slug = "linux", count = 486),
            DiscourseForumTagOption(id = 2L, name = "kotlin", slug = "kotlin", count = 172),
            DiscourseForumTagOption(id = 3L, name = "privacy", slug = "privacy", count = 138),
            DiscourseForumTagOption(id = 4L, name = "opensource", slug = "opensource", count = 121),
            DiscourseForumTagOption(id = 5L, name = "network", slug = "network", count = 94),
        )

    private val selectedTopic: DiscourseForumTopic =
        DiscourseForumTopic(
            topicId = 4102L,
            title = "Kotlin Multiplatform desktop packaging notes",
            slug = "kmp-desktop-packaging-notes",
            categoryId = 7L,
            tags = listOf("kotlin", "desktop"),
            articles =
                listOf(
                    article(
                        key = "post-8801",
                        postId = 8_801L,
                        postNumber = 1,
                        author = alex,
                        blocks =
                            listOf(
                                UiArticleBlock.Paragraph(
                                    text =
                                        "A reliable package starts with a deliberately small " +
                                            "runtime and a build that can be repeated locally.",
                                ),
                                UiArticleBlock.Quote(
                                    text = "Build outputs are evidence, not the source of truth.",
                                    attribution = "Packaging checklist",
                                ),
                                UiArticleBlock.Code(
                                    code = "./gradlew :desktopApp:packageReleaseDistributionForCurrentOS",
                                    language = "shell",
                                ),
                                UiArticleBlock.ListBlock(
                                    ordered = true,
                                    items =
                                        listOf(
                                            listItem("Pin the toolchain and dependency graph."),
                                            listItem("Keep signing outside the source repository."),
                                            listItem("Inspect the package on a clean machine."),
                                        ),
                                ),
                            ),
                    ),
                    article(
                        key = "post-8816",
                        postId = 8_816L,
                        postNumber = 2,
                        author = mina,
                        blocks =
                            listOf(
                                UiArticleBlock.Paragraph(
                                    text = "The size comparison is especially useful for CI reviews.",
                                    inlines =
                                        listOf(
                                            UiArticleInline.Text("The "),
                                            UiArticleInline.Link(
                                                text = "official packaging guide",
                                                url =
                                                    "https://www.jetbrains.com/help/" +
                                                        "kotlin-multiplatform-dev/" +
                                                        "compose-native-distribution.html",
                                            ),
                                            UiArticleInline.Text(" covers the platform-specific formats."),
                                        ),
                                ),
                                UiArticleBlock.Table(
                                    caption = "Unsigned development artifacts",
                                    rows =
                                        listOf(
                                            UiArticleTableRow(
                                                cells =
                                                    listOf(
                                                        UiArticleTableCell("Target", isHeader = true),
                                                        UiArticleTableCell("Format", isHeader = true),
                                                    ),
                                            ),
                                            UiArticleTableRow(
                                                cells =
                                                    listOf(
                                                        UiArticleTableCell("Linux"),
                                                        UiArticleTableCell("AppImage"),
                                                    ),
                                            ),
                                            UiArticleTableRow(
                                                cells =
                                                    listOf(
                                                        UiArticleTableCell("Windows"),
                                                        UiArticleTableCell("AppX"),
                                                    ),
                                            ),
                                        ),
                                ),
                                UiArticleBlock.Spoiler(
                                    text = "Clean-machine verification caught one missing JDK module.",
                                    summary = "Build note",
                                ),
                            ),
                    ),
                ),
            canReply = false,
            source = DiscourseForumContentSource.Network,
            updatedAtEpochMillis = 1_786_910_400_000L,
        )

    private val searchResults: List<DiscourseForumSearchHit> =
        listOf(
            searchHit(
                postId = 9_201L,
                topicId = 5_201L,
                postNumber = 3,
                title = "Compose workspace patterns for narrow windows",
                excerpt = "A synthetic example comparing bottom navigation with a compact result list.",
                author = mina,
                likes = 24,
                tags = listOf("compose", "adaptive"),
            ),
            searchHit(
                postId = 9_202L,
                topicId = 5_202L,
                postNumber = 7,
                title = "Testing a desktop package without production credentials",
                excerpt = "Notes from a fully local fixture server and an unsigned test artifact.",
                author = alex,
                likes = 11,
                tags = listOf("testing", "desktop"),
            ),
            searchHit(
                postId = 9_203L,
                topicId = 5_203L,
                postNumber = 2,
                title = "A small checklist for privacy-aware diagnostics",
                excerpt = "Keep logs bounded, redact identifiers, and make export an explicit action.",
                author = robin,
                likes = 8,
                tags = listOf("privacy", "logging"),
            ),
        )

    private val previewProfile: DiscourseForumProfile =
        DiscourseForumProfile(
            userId = 91L,
            username = previewMember.username,
            displayName = previewMember.displayName,
            avatarUrl = null,
            title = "Fixture maintainer",
            trustLevel = 2,
            moderator = false,
            admin = false,
            staff = false,
            active = true,
            suspended = false,
            canSendPrivateMessages = true,
            canEdit = false,
            createdAtEpochMillis = 1_767_225_600_000L,
            lastPostedAtEpochMillis = 1_786_906_800_000L,
            lastSeenAtEpochMillis = 1_786_910_400_000L,
            websiteName = "Preview notebook",
            websiteUrl = "https://preview.invalid/notebook",
            location = "Local test environment",
            primaryGroupName = "Preview contributors",
            bio =
                listOf(
                    UiArticleBlock.Paragraph(
                        "This profile is generated locally to exercise the account workspace.",
                    ),
                ),
            badges =
                listOf(
                    DiscourseForumBadge(
                        id = 701L,
                        name = "Fixture author",
                        description = "Created deterministic preview data",
                        icon = null,
                        imageUrl = null,
                        count = 2,
                    ),
                    DiscourseForumBadge(
                        id = 702L,
                        name = "Careful reviewer",
                        description = "Reviewed a synthetic test journey",
                        icon = null,
                        imageUrl = null,
                        count = 1,
                    ),
                ),
            summary =
                DiscourseForumUserSummary(
                    likesGiven = 37,
                    likesReceived = 128,
                    topicsEntered = 84,
                    postsReadCount = 642,
                    daysVisited = 56,
                    topicCount = 9,
                    postCount = 73,
                    timeReadSeconds = 28_800L,
                    recentTimeReadSeconds = 3_600L,
                    solvedCount = 4,
                ),
        )

    private val profileActivity: List<DiscourseForumActivity> =
        listOf(
            activity(
                id = 801L,
                kind = DiscourseForumActivityKind.TopicCreated,
                topicId = 5_301L,
                postId = 9_301L,
                postNumber = 1,
                title = "Building deterministic UI fixtures",
                excerpt = "A local-only example for repeatable visual tests.",
            ),
            activity(
                id = 802L,
                kind = DiscourseForumActivityKind.Replied,
                topicId = 5_302L,
                postId = 9_302L,
                postNumber = 4,
                title = "Window-size checks for a forum workspace",
                excerpt = "Compared compact, medium, and expanded pane behavior.",
            ),
            activity(
                id = 803L,
                kind = DiscourseForumActivityKind.Bookmarked,
                topicId = 5_303L,
                postId = 9_303L,
                postNumber = 6,
                title = "Keeping screenshot baselines reviewable",
                excerpt = "Small focused images make layout regressions easier to inspect.",
            ),
        )

    private val previewNotifications: List<DiscourseForumNotification> =
        listOf(
            notification(
                id = 9_403L,
                kind = DiscourseForumNotificationKind.Reply,
                read = false,
                title = "A reply arrived in the packaging checklist",
                actor = mina,
                topicId = 4_102L,
                postNumber = 2,
            ),
            notification(
                id = 9_402L,
                kind = DiscourseForumNotificationKind.Like,
                read = false,
                title = "Your synthetic diagnostics note was liked",
                actor = alex,
                topicId = 5_203L,
                postNumber = 2,
            ),
            notification(
                id = 9_401L,
                kind = DiscourseForumNotificationKind.Badge,
                read = true,
                title = "Fixture author badge awarded",
                actor = null,
                topicId = null,
                postNumber = null,
            ),
        )

    /** Loaded latest feed with a selected topic, suitable for list-detail screenshots. */
    public fun loaded(withSelectedTopic: Boolean = true): DiscourseForumState =
        DiscourseForumState(
            topics = topics,
            categories = categoryOptions,
            tags = tagOptions,
            selectedTopicId = if (withSelectedTopic) selectedTopic.topicId else null,
            selectedTopic = if (withSelectedTopic) selectedTopic else null,
            nextPage = 1,
            isFeedLoading = false,
            isTaxonomyLoading = false,
            feedSource = DiscourseForumContentSource.Network,
            topicSource =
                if (withSelectedTopic) DiscourseForumContentSource.Network else null,
        )

    /** Compact, public search result state with a one-based continuation cursor. */
    public fun search(): DiscourseForumState =
        loaded(withSelectedTopic = false).copy(
            destination = DiscourseForumDestination.Search,
            search =
                DiscourseForumSearchState(
                    query = "adaptive workspace",
                    submittedQuery = "adaptive workspace",
                    items = searchResults,
                    nextPage = DiscourseSearchPage(2),
                ),
        )

    /** Authenticated notification state with both unread and already-read synthetic rows. */
    public fun notifications(): DiscourseForumState =
        loaded().copy(
            destination = DiscourseForumDestination.Notifications,
            sessionGeneration = 6L,
            isAuthenticated = true,
            accountUsername = previewMember.username,
            notifications =
                DiscourseForumNotificationsState(
                    snapshot =
                        DiscourseForumNotificationSnapshot(
                            items = previewNotifications,
                            totalRows = 7,
                            seenNotificationId = 9_401L,
                        ),
                    nextOffset = DiscourseNotificationOffset(3),
                ),
        )

    /** Authenticated profile and activity state containing only local presentation-safe values. */
    public fun profile(): DiscourseForumState =
        loaded().copy(
            destination = DiscourseForumDestination.Profile,
            sessionGeneration = 6L,
            isAuthenticated = true,
            accountUsername = previewMember.username,
            profile =
                DiscourseForumProfileState(
                    username = previewMember.username,
                    value = previewProfile,
                    activity = profileActivity,
                    nextOffset = 30,
                ),
        )

    /** Initial loading state with stable taxonomy options for layout coverage. */
    public fun loading(): DiscourseForumState =
        DiscourseForumState(
            categories = categoryOptions,
            tags = tagOptions,
            isFeedLoading = true,
            isTaxonomyLoading = false,
        )

    /** Completed request that returned no topics. */
    public fun empty(): DiscourseForumState =
        DiscourseForumState(
            categories = categoryOptions,
            tags = tagOptions,
            isFeedLoading = false,
            isTaxonomyLoading = false,
            feedSource = DiscourseForumContentSource.Network,
        )

    /** Sanitized transport failure; no exception or server body reaches UI fixtures. */
    public fun error(): DiscourseForumState =
        DiscourseForumState(
            categories = categoryOptions,
            tags = tagOptions,
            isFeedLoading = false,
            isTaxonomyLoading = false,
            feedFailure = DiscourseForumFailureKind.Network,
        )

    /** Stale-but-readable content after a failed refresh. */
    public fun cached(): DiscourseForumState =
        loaded().copy(
            feedSource = DiscourseForumContentSource.StaleCache,
            topicSource = DiscourseForumContentSource.StaleCache,
            feedFailure = DiscourseForumFailureKind.Network,
            topicFailure = DiscourseForumFailureKind.Network,
            selectedTopic =
                selectedTopic.copy(
                    source = DiscourseForumContentSource.StaleCache,
                    fallbackFailure = DiscourseForumFailureKind.Network,
                ),
        )

    private fun topic(
        id: Long,
        title: String,
        excerpt: String,
        author: UiAuthor,
        replies: Int,
        views: Int,
        category: String,
        tags: List<String>,
        unread: Boolean = false,
    ): UiTimelineV2.Topic =
        UiTimelineV2.Topic(
            itemKey = "topic-$id",
            title = title,
            excerpt = excerpt,
            author = author,
            replyCount = replies,
            viewCount = views,
            lastActivityEpochMillis = 1_786_910_400_000L - id * 1_000L,
            unread = unread,
            categoryName = category,
            tags = tags,
            discourse =
                DiscourseTopicMeta(
                    ref = DiscourseTopicRef(topicId = id),
                    slug = "fixture-topic-$id",
                    unreadPostCount = if (unread) 3 else 0,
                ),
        )

    private fun article(
        key: String,
        postId: Long,
        postNumber: Int,
        author: UiAuthor,
        blocks: List<UiArticleBlock>,
    ): UiArticle =
        UiArticle(
            itemKey = key,
            title = SELECTED_TOPIC_TITLE,
            author = author,
            createdAtEpochMillis = 1_786_910_400_000L + postNumber * 60_000L,
            blocks = blocks,
            discourse =
                DiscoursePostMeta(
                    topicId = 4_102L,
                    postId = postId,
                    postNumber = postNumber,
                ),
        )

    private fun listItem(text: String): UiArticleListItem =
        UiArticleListItem(
            blocks = listOf(UiArticleBlock.Paragraph(text)),
        )

    private fun searchHit(
        postId: Long,
        topicId: Long,
        postNumber: Int,
        title: String,
        excerpt: String,
        author: UiAuthor,
        likes: Int,
        tags: List<String>,
    ): DiscourseForumSearchHit =
        DiscourseForumSearchHit(
            itemKey = "discourse-search-post:$postId",
            postId = postId,
            topic = DiscourseTopicRef(topicId = topicId, postNumber = postNumber),
            topicSlug = "fixture-search-topic-$topicId",
            title = title,
            excerpt = excerpt,
            author = author,
            createdAtEpochMillis = 1_786_910_400_000L - postId * 1_000L,
            likeCount = likes,
            categoryId = 7L,
            tags = tags,
        )

    private fun activity(
        id: Long,
        kind: DiscourseForumActivityKind,
        topicId: Long,
        postId: Long,
        postNumber: Int,
        title: String,
        excerpt: String,
    ): DiscourseForumActivity =
        DiscourseForumActivity(
            itemKey = "fixture-activity-$id",
            actionType = id.toInt(),
            kind = kind,
            createdAtEpochMillis = 1_786_910_400_000L - id * 60_000L,
            user = previewMember,
            actingUser = null,
            topic = DiscourseTopicRef(topicId = topicId, postNumber = postNumber),
            postId = postId,
            topicSlug = "fixture-activity-topic-$topicId",
            title = title,
            excerpt = excerpt,
            categoryId = 7L,
            closed = false,
            archived = false,
            hidden = false,
            deleted = false,
        )

    private fun notification(
        id: Long,
        kind: DiscourseForumNotificationKind,
        read: Boolean,
        title: String,
        actor: UiAuthor?,
        topicId: Long?,
        postNumber: Int?,
    ): DiscourseForumNotification =
        DiscourseForumNotification(
            id = id,
            recipientUserId = previewProfile.userId,
            kind = kind,
            read = read,
            highPriority = !read,
            createdAtEpochMillis = 1_786_910_400_000L - id * 60_000L,
            topic = topicId?.let { DiscourseTopicRef(topicId = it, postNumber = postNumber) },
            topicSlug = topicId?.let { "fixture-notification-topic-$it" },
            title = title,
            actingUser = actor,
            data =
                DiscourseForumNotificationData(
                    topicTitle = title,
                    displayUsername = actor?.displayName,
                    username = actor?.username,
                    badgeName = title.takeIf { kind == DiscourseForumNotificationKind.Badge },
                ),
        )

    private const val SELECTED_TOPIC_TITLE: String =
        "Kotlin Multiplatform desktop packaging notes"
}
