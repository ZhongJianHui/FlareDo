package dev.dimension.flare.ui.model

import kotlinx.serialization.Serializable

/**
 * Shared timeline rows consumed by Compose and SwiftUI.
 *
 * Transport DTOs never cross into this model. Keeping the UI model small and serializable lets
 * stage 3 add a Discourse mapper while preserving deterministic cache and screenshot fixtures.
 */
@Serializable
public sealed interface UiTimelineV2 {
    /** Stable key suitable for paging de-duplication and list state restoration. */
    public val itemKey: String

    /** Compact topic row used by latest, hot, category, and tag timelines. */
    @Serializable
    public data class Topic(
        override val itemKey: String,
        val title: String,
        val excerpt: String,
        val author: UiAuthor,
        val replyCount: Int,
        val viewCount: Int,
        val lastActivityEpochMillis: Long,
        val unread: Boolean = false,
        val categoryName: String? = null,
        val tags: List<String> = emptyList(),
        val discourse: DiscourseTopicMeta? = null,
    ) : UiTimelineV2

    /** Inline status row for loading failures or intentionally empty forum sections. */
    @Serializable
    public data class Message(
        override val itemKey: String,
        val text: String,
    ) : UiTimelineV2
}

/** Public, non-sensitive user identity displayed next to forum content. */
@Serializable
public data class UiAuthor(
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
)

/**
 * Stable navigation target for a Discourse topic or a particular numbered post.
 *
 * Discourse distinguishes a database post id from the user-visible post number. Deep links use
 * the latter, while post-stream pagination uses the former. Keeping only the navigation pair in
 * this type prevents callers from accidentally treating those identifiers as interchangeable.
 */
@Serializable
public data class DiscourseTopicRef(
    val topicId: Long,
    val postNumber: Int? = null,
)

/**
 * Forum-specific state attached to a compact topic row.
 *
 * Counts are copied from a single server response and are therefore a snapshot, not local
 * counters. Mutations in later stages replace the complete value after server confirmation.
 */
@Serializable
public data class DiscourseTopicMeta(
    val ref: DiscourseTopicRef,
    val slug: String,
    val categoryId: Long? = null,
    val unreadPostCount: Int = 0,
    val newPostCount: Int = 0,
    val highestPostNumber: Int? = null,
    val lastReadPostNumber: Int? = null,
    val canCreatePost: Boolean = false,
    val liked: Boolean = false,
    val bookmarked: Boolean = false,
)
