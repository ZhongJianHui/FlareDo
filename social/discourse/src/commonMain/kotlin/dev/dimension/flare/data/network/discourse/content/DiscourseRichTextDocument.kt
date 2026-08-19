package dev.dimension.flare.data.network.discourse.content

import dev.dimension.flare.ui.model.UiArticleBlock

/**
 * Platform-neutral result of parsing one Discourse `cooked` HTML fragment.
 *
 * Raw HTML is intentionally not retained. Android/Desktop Compose and Apple SwiftUI receive only
 * the safe blocks below, so no platform renderer can accidentally bypass the same policy. A
 * truncated or malformed fragment is still represented safely; callers may show the accepted
 * prefix and optionally expose a retry affordance without falling back to a WebView.
 */
public data class DiscourseRichTextDocument(
    public val blocks: List<UiArticleBlock>,
    public val wasTruncated: Boolean,
    public val removedUnsafeContent: Boolean,
)

/**
 * Allocation and traversal limits for untrusted cooked HTML.
 *
 * The input is bounded before KSoup builds a DOM. The remaining limits stop pathological nesting,
 * node floods, very large text runs, URL abuse, list expansion, and table-cell expansion while the
 * structured tree is converted to UI values. Tests use smaller values to exercise every boundary
 * deterministically.
 */
public data class DiscourseRichTextLimits(
    public val maxInputChars: Int = DEFAULT_MAX_INPUT_CHARS,
    public val maxInputBytes: Int = DEFAULT_MAX_INPUT_BYTES,
    public val maxNodes: Int = DEFAULT_MAX_NODES,
    public val maxDepth: Int = DEFAULT_MAX_DEPTH,
    public val maxBlocks: Int = DEFAULT_MAX_BLOCKS,
    public val maxTextChars: Int = DEFAULT_MAX_TEXT_CHARS,
    public val maxUrlChars: Int = DEFAULT_MAX_URL_CHARS,
    public val maxAttributesPerElement: Int = DEFAULT_MAX_ATTRIBUTES_PER_ELEMENT,
    public val maxListItems: Int = DEFAULT_MAX_LIST_ITEMS,
    public val maxTableCells: Int = DEFAULT_MAX_TABLE_CELLS,
) {
    init {
        require(maxInputChars > 0) { "maxInputChars must be positive" }
        require(maxInputBytes > 0) { "maxInputBytes must be positive" }
        require(maxNodes > 0) { "maxNodes must be positive" }
        require(maxDepth > 0) { "maxDepth must be positive" }
        require(maxBlocks > 0) { "maxBlocks must be positive" }
        require(maxTextChars > 0) { "maxTextChars must be positive" }
        require(maxUrlChars > 0) { "maxUrlChars must be positive" }
        require(maxAttributesPerElement > 0) { "maxAttributesPerElement must be positive" }
        require(maxListItems > 0) { "maxListItems must be positive" }
        require(maxTableCells > 0) { "maxTableCells must be positive" }
    }

    public companion object {
        public const val DEFAULT_MAX_INPUT_CHARS: Int = 256 * 1024
        public const val DEFAULT_MAX_INPUT_BYTES: Int = 512 * 1024
        public const val DEFAULT_MAX_NODES: Int = 10_000
        public const val DEFAULT_MAX_DEPTH: Int = 32
        public const val DEFAULT_MAX_BLOCKS: Int = 2_000
        public const val DEFAULT_MAX_TEXT_CHARS: Int = 256 * 1024
        public const val DEFAULT_MAX_URL_CHARS: Int = 2_048
        public const val DEFAULT_MAX_ATTRIBUTES_PER_ELEMENT: Int = 64
        public const val DEFAULT_MAX_LIST_ITEMS: Int = 1_000
        public const val DEFAULT_MAX_TABLE_CELLS: Int = 2_000
    }
}
