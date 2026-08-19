package dev.dimension.flare.data.network.discourse.forum

import dev.dimension.flare.ui.model.UiArticle
import dev.dimension.flare.ui.model.UiTimelineV2
import kotlinx.serialization.Serializable

/** Anonymous Linux.do topic-list selection shared by Compose and SwiftUI hosts. */
@Serializable
public sealed interface DiscourseForumFeed {
    /** Stable non-secret key used for paging state and persistent cache partitioning. */
    public val stableKey: String

    /** Root `/latest` feed. */
    @Serializable
    public data object Latest : DiscourseForumFeed {
        override val stableKey: String = "latest"
    }

    /** Root `/hot` feed. */
    @Serializable
    public data object Hot : DiscourseForumFeed {
        override val stableKey: String = "hot"
    }

    /** Latest topics inside one server-owned category route. */
    @Serializable
    public data class Category(
        val id: Long,
        val slug: String,
        val parentSlug: String? = null,
        val name: String = slug,
    ) : DiscourseForumFeed {
        init {
            require(id > 0L) { "Forum category id must be positive" }
            requireForumRouteValue(slug, "Forum category slug")
            parentSlug?.let { requireForumRouteValue(it, "Forum parent category slug") }
            requireForumDisplayValue(name, "Forum category name")
        }

        override val stableKey: String = "category:$id"
    }

    /** Latest topics carrying one canonical Discourse tag. */
    @Serializable
    public data class Tag(
        val name: String,
        val slug: String = name,
    ) : DiscourseForumFeed {
        init {
            requireForumDisplayValue(name, "Forum tag name")
            requireForumRouteValue(slug, "Forum tag slug")
        }

        override val stableKey: String = "tag:$slug"
    }
}

/** Whether content came from the successful request or an older persistent cache entry. */
@Serializable
public enum class DiscourseForumContentSource {
    Network,
    StaleCache,
}

/** Sanitized failure categories suitable for UI decisions and bounded diagnostics. */
@Serializable
public enum class DiscourseForumFailureKind {
    Network,
    Authentication,
    Permission,
    RateLimited,
    ChallengeRequired,
    Server,
    InvalidResponse,
    Http,
}

/** Category option displayed by the forum navigation surface. */
@Serializable
public data class DiscourseForumCategoryOption(
    val id: Long,
    val name: String,
    val slug: String,
    val parentCategoryId: Long? = null,
    val parentSlug: String? = null,
    val colorHex: String? = null,
    val topicCount: Int = 0,
) {
    init {
        require(id > 0L) { "Forum category option id must be positive" }
        requireForumDisplayValue(name, "Forum category option name")
        requireForumRouteValue(slug, "Forum category option slug")
        require(parentCategoryId == null || parentCategoryId > 0L) {
            "Forum parent category id must be positive"
        }
        parentSlug?.let { requireForumRouteValue(it, "Forum parent category option slug") }
        require(topicCount >= 0) { "Forum category topic count cannot be negative" }
    }
}

/** Tag option displayed by the forum navigation surface. */
@Serializable
public data class DiscourseForumTagOption(
    val id: Long,
    val name: String,
    val slug: String,
    val count: Int = 0,
) {
    init {
        require(id > 0L) { "Forum tag option id must be positive" }
        requireForumDisplayValue(name, "Forum tag option name")
        requireForumRouteValue(slug, "Forum tag option slug")
        require(count >= 0) { "Forum tag count cannot be negative" }
    }
}

/** One de-duplicated page returned by [DiscourseForumRepository.loadFeed]. */
@Serializable
public data class DiscourseForumFeedPage(
    val feed: DiscourseForumFeed,
    val page: Int,
    val topics: List<UiTimelineV2.Topic>,
    val nextPage: Int?,
    val canCreateTopic: Boolean,
    val source: DiscourseForumContentSource,
    val updatedAtEpochMillis: Long,
    val fallbackFailure: DiscourseForumFailureKind? = null,
) {
    init {
        require(page >= 0) { "Forum feed page cannot be negative" }
        require(nextPage == null || nextPage > page) { "Next forum page must advance" }
        require(topics.map(UiTimelineV2.Topic::itemKey).distinct().size == topics.size) {
            "A forum feed page cannot contain duplicate topic keys"
        }
        require(updatedAtEpochMillis >= 0L) { "Forum feed timestamp cannot be negative" }
        require((source == DiscourseForumContentSource.StaleCache) == (fallbackFailure != null)) {
            "Only stale forum pages carry a fallback failure"
        }
    }
}

/** Cached or fresh category collection. */
@Serializable
public data class DiscourseForumCategories(
    val items: List<DiscourseForumCategoryOption>,
    val source: DiscourseForumContentSource,
    val updatedAtEpochMillis: Long,
    val fallbackFailure: DiscourseForumFailureKind? = null,
) {
    init {
        require(updatedAtEpochMillis >= 0L) { "Forum category timestamp cannot be negative" }
        require((source == DiscourseForumContentSource.StaleCache) == (fallbackFailure != null)) {
            "Only stale forum categories carry a fallback failure"
        }
    }
}

/** Cached or fresh tag collection. */
@Serializable
public data class DiscourseForumTags(
    val items: List<DiscourseForumTagOption>,
    val source: DiscourseForumContentSource,
    val updatedAtEpochMillis: Long,
    val fallbackFailure: DiscourseForumFailureKind? = null,
) {
    init {
        require(updatedAtEpochMillis >= 0L) { "Forum tag timestamp cannot be negative" }
        require((source == DiscourseForumContentSource.StaleCache) == (fallbackFailure != null)) {
            "Only stale forum tags carry a fallback failure"
        }
    }
}

/** Fully aggregated topic whose posts follow `post_stream.stream` order exactly. */
@Serializable
public data class DiscourseForumTopic(
    val topicId: Long,
    val title: String,
    val slug: String,
    val categoryId: Long? = null,
    val tags: List<String> = emptyList(),
    val articles: List<UiArticle>,
    val canReply: Boolean,
    val source: DiscourseForumContentSource,
    val updatedAtEpochMillis: Long,
    val fallbackFailure: DiscourseForumFailureKind? = null,
) {
    init {
        require(topicId > 0L) { "Forum topic id must be positive" }
        requireForumDisplayValue(title, "Forum topic title")
        requireForumRouteValue(slug, "Forum topic slug")
        require(categoryId == null || categoryId > 0L) { "Forum topic category id must be positive" }
        require(articles.map(UiArticle::itemKey).distinct().size == articles.size) {
            "A forum topic cannot contain duplicate article keys"
        }
        require(updatedAtEpochMillis >= 0L) { "Forum topic timestamp cannot be negative" }
        require((source == DiscourseForumContentSource.StaleCache) == (fallbackFailure != null)) {
            "Only stale forum topics carry a fallback failure"
        }
    }
}

/** Immutable Molecule state consumed directly by Compose and SwiftUI. */
public data class DiscourseForumState(
    val selection: DiscourseForumFeed = DiscourseForumFeed.Latest,
    val topics: List<UiTimelineV2.Topic> = emptyList(),
    val categories: List<DiscourseForumCategoryOption> = emptyList(),
    val tags: List<DiscourseForumTagOption> = emptyList(),
    val selectedTopicId: Long? = null,
    val selectedTopic: DiscourseForumTopic? = null,
    val nextPage: Int? = null,
    val isFeedLoading: Boolean = true,
    val isAppending: Boolean = false,
    val isTaxonomyLoading: Boolean = true,
    val isTopicLoading: Boolean = false,
    val feedSource: DiscourseForumContentSource? = null,
    val topicSource: DiscourseForumContentSource? = null,
    val feedFailure: DiscourseForumFailureKind? = null,
    /** Paging-only failure; retained cursor can be retried by an explicit [DiscourseForumAction]. */
    val appendFailure: DiscourseForumFailureKind? = null,
    val taxonomyFailure: DiscourseForumFailureKind? = null,
    val topicFailure: DiscourseForumFailureKind? = null,
) {
    /** True when another de-duplicated page can be requested. */
    public val hasMore: Boolean
        get() = nextPage != null && appendFailure == null
}

/** Commands accepted by [DiscourseForumPresenter]. */
public sealed interface DiscourseForumAction {
    public data class SelectFeed(
        val feed: DiscourseForumFeed,
    ) : DiscourseForumAction

    public data object Refresh : DiscourseForumAction

    /** Retries category/tag discovery without discarding a successfully loaded topic list. */
    public data object RetryTaxonomy : DiscourseForumAction

    public data object LoadNextPage : DiscourseForumAction

    public data class OpenTopic(
        val topicId: Long,
    ) : DiscourseForumAction {
        init {
            require(topicId > 0L) { "Opened forum topic id must be positive" }
        }
    }

    public data object CloseTopic : DiscourseForumAction

    public data object RetryTopic : DiscourseForumAction
}

private const val MAX_FORUM_ROUTE_VALUE_LENGTH: Int = 256
private const val MAX_FORUM_DISPLAY_VALUE_LENGTH: Int = 1_000

private fun requireForumRouteValue(
    value: String,
    label: String,
) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value.length <= MAX_FORUM_ROUTE_VALUE_LENGTH) { "$label is too long" }
    require(value.none(Char::isForumControlCharacter)) { "$label contains control characters" }
}

private fun requireForumDisplayValue(
    value: String,
    label: String,
) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value.length <= MAX_FORUM_DISPLAY_VALUE_LENGTH) { "$label is too long" }
    require(value.none(Char::isForumControlCharacter)) { "$label contains control characters" }
}

private fun Char.isForumControlCharacter(): Boolean = code < 0x20 || code == 0x7f
