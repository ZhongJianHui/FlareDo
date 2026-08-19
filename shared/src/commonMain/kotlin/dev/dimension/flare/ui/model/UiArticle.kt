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
        /** Structured spans; empty for cache records written before safe rich-text support. */
        val inlines: List<UiArticleInline> = emptyList(),
    ) : UiArticleBlock

    @Serializable
    public data class Quote(
        val text: String,
        val attribution: String? = null,
        /** Nested safe blocks; [text] remains the backwards-compatible plain-text projection. */
        val blocks: List<UiArticleBlock> = emptyList(),
    ) : UiArticleBlock

    @Serializable
    public data class Code(
        val code: String,
        val language: String? = null,
    ) : UiArticleBlock

    @Serializable
    public data class Image(
        val url: String,
        val altText: String? = null,
        val title: String? = null,
        /** Optional safe HTTPS destination when the image was wrapped in a link. */
        val linkUrl: String? = null,
    ) : UiArticleBlock

    @Serializable
    public data class ListBlock(
        val ordered: Boolean,
        val startIndex: Int = 1,
        val items: List<UiArticleListItem>,
    ) : UiArticleBlock

    @Serializable
    public data class Table(
        val caption: String? = null,
        val rows: List<UiArticleTableRow>,
    ) : UiArticleBlock

    @Serializable
    public data class Spoiler(
        val text: String,
        val summary: String? = null,
        val blocks: List<UiArticleBlock> = emptyList(),
    ) : UiArticleBlock
}

/** Inline primitives retained after cooked HTML has passed the Discourse sanitizer. */
@Serializable
public sealed interface UiArticleInline {
    @Serializable
    public data class Text(
        val text: String,
    ) : UiArticleInline

    @Serializable
    public data class Link(
        val text: String,
        val url: String,
    ) : UiArticleInline

    @Serializable
    public data class Code(
        val code: String,
    ) : UiArticleInline

    @Serializable
    public data class Image(
        val url: String,
        val altText: String? = null,
        val title: String? = null,
        val linkUrl: String? = null,
    ) : UiArticleInline

    @Serializable
    public data class Spoiler(
        val text: String,
        val inlines: List<UiArticleInline> = emptyList(),
    ) : UiArticleInline
}

/** One list item may contain paragraphs, nested lists, quotes, code, or other safe blocks. */
@Serializable
public data class UiArticleListItem(
    val blocks: List<UiArticleBlock>,
)

/** A row in a sanitized table. */
@Serializable
public data class UiArticleTableRow(
    val cells: List<UiArticleTableCell>,
)

/** A sanitized table cell with bounded span values and no raw HTML attributes. */
@Serializable
public data class UiArticleTableCell(
    val text: String,
    val inlines: List<UiArticleInline> = emptyList(),
    val isHeader: Boolean = false,
    val columnSpan: Int = 1,
    val rowSpan: Int = 1,
)
