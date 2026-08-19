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
