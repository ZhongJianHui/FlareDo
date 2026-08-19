package dev.dimension.flare.ui.model

import kotlinx.serialization.Serializable

/** A platform-neutral topic/post document rendered by Compose or SwiftUI. */
@Serializable
public data class UiArticle(
    val itemKey: String,
    val title: String,
    val author: UiAuthor,
    val createdAtEpochMillis: Long,
    val blocks: List<UiArticleBlock>,
    val canReply: Boolean = false,
    val discourse: DiscoursePostMeta? = null,
)

/**
 * Discourse permissions and action state belonging to one rendered post.
 *
 * [postId] is used by mutation endpoints, whereas [postNumber] and [replyToPostNumber] describe
 * the visible reply graph. They intentionally remain separate to enforce Discourse's API
 * contract when loading or editing content.
 */
@Serializable
public data class DiscoursePostMeta(
    val topicId: Long,
    val postId: Long,
    val postNumber: Int,
    val replyToPostNumber: Int? = null,
    val canEdit: Boolean = false,
    val canDelete: Boolean = false,
    val liked: Boolean = false,
    val likeCount: Int = 0,
    val bookmarked: Boolean = false,
    val bookmarkId: Long? = null,
    val currentReaction: String? = null,
)

/**
 * Safe rich-text primitives. Raw HTML is deliberately absent from the public UI contract; the
 * Discourse module must sanitize cooked HTML before mapping it to these values.
 */
@Serializable
public sealed interface UiArticleBlock {
    @Serializable
    public data class Paragraph(
        val text: String,
    ) : UiArticleBlock

    @Serializable
    public data class Quote(
        val text: String,
        val attribution: String? = null,
    ) : UiArticleBlock

    @Serializable
    public data class Code(
        val code: String,
        val language: String? = null,
    ) : UiArticleBlock
}
