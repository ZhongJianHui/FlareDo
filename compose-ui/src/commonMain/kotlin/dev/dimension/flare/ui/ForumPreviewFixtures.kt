package dev.dimension.flare.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumCategoryOption
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumContentSource
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFailureKind
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFeed
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumState
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumTagOption
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumTopic
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

    public fun topic(topicId: Long): String = "forum_topic_$topicId"
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

    private const val SELECTED_TOPIC_TITLE: String =
        "Kotlin Multiplatform desktop packaging notes"
}
