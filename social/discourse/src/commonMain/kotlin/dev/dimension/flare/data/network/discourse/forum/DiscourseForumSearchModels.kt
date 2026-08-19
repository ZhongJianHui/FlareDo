package dev.dimension.flare.data.network.discourse.forum

import dev.dimension.flare.data.network.discourse.paging.DiscourseSearchPage
import dev.dimension.flare.ui.model.DiscourseTopicRef
import dev.dimension.flare.ui.model.UiAuthor

/** One post hit joined to its side-loaded topic before it reaches presentation code. */
public data class DiscourseForumSearchHit(
    public val itemKey: String,
    public val postId: Long,
    public val topic: DiscourseTopicRef,
    public val topicSlug: String,
    public val title: String,
    public val excerpt: String,
    public val author: UiAuthor,
    public val createdAtEpochMillis: Long?,
    public val likeCount: Int,
    public val categoryId: Long?,
    public val tags: List<String>,
) {
    init {
        require(itemKey == searchItemKey(postId)) { "Search item key must match its post id" }
        require(postId > 0L) { "Search post id must be positive" }
        require(topic.topicId > 0L) { "Search topic id must be positive" }
        require(topic.postNumber?.let { it > 0 } == true) {
            "Search hits must retain a positive post number"
        }
        requireForumSearchDisplayValue(title, "Search topic title")
        require(excerpt.length <= MAX_SEARCH_EXCERPT_CHARS) { "Search excerpt is too long" }
        require(likeCount >= 0) { "Search like count cannot be negative" }
        require(categoryId == null || categoryId > 0L) { "Search category id must be positive" }
        require(tags.size <= MAX_SEARCH_TAGS) { "Search hit contains too many tags" }
    }
}

/**
 * A one-based search page.
 *
 * [nextPage] is derived only from Discourse continuation flags. It may advance when [items] is
 * empty because all rows overlapped a previous page; coupling the cursor to accepted rows would
 * otherwise strand the pager on the same response forever.
 */
public data class DiscourseForumSearchPage(
    public val query: String,
    public val page: DiscourseSearchPage,
    public val items: List<DiscourseForumSearchHit>,
    public val nextPage: DiscourseSearchPage?,
) {
    init {
        require(query.isNotBlank()) { "Search query must not be blank" }
        require(query.length <= MAX_SEARCH_QUERY_CHARS) { "Search query is too long" }
        require(query.none(Char::isForumSearchControlCharacter)) {
            "Search query contains control characters"
        }
        require(nextPage == null || nextPage.value > page.value) {
            "Next search page must advance"
        }
        require(items.map(DiscourseForumSearchHit::postId).distinct().size == items.size) {
            "Search page cannot contain duplicate posts"
        }
    }
}

internal fun searchItemKey(postId: Long): String = "discourse-search-post:$postId"

private fun requireForumSearchDisplayValue(
    value: String,
    label: String,
) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value.length <= MAX_SEARCH_TITLE_CHARS) { "$label is too long" }
    require(value.none(Char::isForumSearchControlCharacter)) { "$label contains control characters" }
}

private fun Char.isForumSearchControlCharacter(): Boolean = code < 0x20 || code == 0x7f

private const val MAX_SEARCH_QUERY_CHARS: Int = 2_000
private const val MAX_SEARCH_TITLE_CHARS: Int = 1_000
private const val MAX_SEARCH_EXCERPT_CHARS: Int = 4_000
private const val MAX_SEARCH_TAGS: Int = 100
