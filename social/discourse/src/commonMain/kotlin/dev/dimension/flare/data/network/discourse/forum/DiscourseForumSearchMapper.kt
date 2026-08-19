package dev.dimension.flare.data.network.discourse.forum

import dev.dimension.flare.data.network.discourse.content.DiscourseCookedHtmlParser
import dev.dimension.flare.data.network.discourse.model.DiscourseSearchPost
import dev.dimension.flare.data.network.discourse.model.DiscourseSearchResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicSummary
import dev.dimension.flare.data.network.discourse.paging.DiscourseSearchPage
import dev.dimension.flare.ui.model.DiscourseTopicRef
import dev.dimension.flare.ui.model.UiAuthor

/** Maps post-only search responses while enforcing all side-loaded joins and paging invariants. */
public class DiscourseForumSearchMapper(
    private val cookedHtmlParser: DiscourseCookedHtmlParser,
) {
    public fun mapPage(
        query: String,
        page: DiscourseSearchPage,
        response: DiscourseSearchResponse,
        knownPostIds: Set<Long> = emptySet(),
    ): DiscourseForumSearchPage =
        mapForumResponse {
            require(query.isNotBlank())
            require(query.length <= MAX_SEARCH_QUERY_INPUT_CHARS)
            require(query.none(Char::isForumMappingControlCharacter))
            require(knownPostIds.all { it > 0L })

            val topicsById = response.topics.validSearchTopicLookup()
            val seenPostIds = knownPostIds.toMutableSet()
            val items =
                buildList {
                    response.posts.forEach { post ->
                        // Validate the join even for an overlapping post. A known ID must not let a
                        // malformed later page smuggle an orphan or cross-topic navigation target.
                        val topic = topicsById[post.topicId] ?: throw forumProtocolFailure()
                        post.validateSearchIdentity(topic)
                        if (seenPostIds.add(post.id)) add(post.toSearchHit(topic))
                    }
                }

            val continuation =
                response.groupedSearchResult?.let { grouped ->
                    grouped.morePosts == true || grouped.moreFullPageResults
                } == true
            DiscourseForumSearchPage(
                query = query,
                page = page,
                items = items,
                nextPage = if (continuation) page.next() else null,
            )
        }

    private fun DiscourseSearchPost.toSearchHit(topic: DiscourseTopicSummary): DiscourseForumSearchHit {
        val safeUsername =
            username.safeForumDisplayValue(MAX_FORUM_USERNAME_CHARS)
                ?: UNKNOWN_AUTHOR_USERNAME
        val title =
            cookedHtmlParser.sanitizeForumText(
                value = topic.fancyTitle ?: topic.title,
                maxChars = MAX_FORUM_TITLE_CHARS,
            ) ?: throw forumProtocolFailure()
        return DiscourseForumSearchHit(
            itemKey = searchItemKey(id),
            postId = id,
            topic = DiscourseTopicRef(topicId = topicId, postNumber = postNumber),
            topicSlug = topic.slug.requireForumRoute(),
            title = title,
            excerpt =
                cookedHtmlParser
                    .sanitizeForumText(blurb, MAX_FORUM_EXCERPT_CHARS)
                    .orEmpty(),
            author =
                UiAuthor(
                    username = safeUsername,
                    displayName =
                        name.safeForumDisplayValue(MAX_FORUM_USERNAME_CHARS)
                            ?: safeUsername,
                    avatarUrl = avatarTemplate.toSafeForumAvatarUrl(),
                ),
            createdAtEpochMillis = parseForumEpochMillis(createdAt),
            likeCount = likeCount.coerceAtLeast(0),
            categoryId = topic.categoryId,
            tags =
                topic.tags
                    .take(MAX_SEARCH_TAGS)
                    .map { tag ->
                        cookedHtmlParser.sanitizeForumText(tag.name, MAX_FORUM_TAG_CHARS)
                            ?: throw forumProtocolFailure()
                    }.distinct(),
        )
    }
}

private fun List<DiscourseTopicSummary>.validSearchTopicLookup(): Map<Long, DiscourseTopicSummary> {
    require(all { it.id > 0L })
    require(map(DiscourseTopicSummary::id).distinct().size == size)
    return associateBy(DiscourseTopicSummary::id)
}

private fun DiscourseSearchPost.validateSearchIdentity(topic: DiscourseTopicSummary) {
    require(id > 0L)
    require(topicId > 0L && topic.id == topicId)
    require(postNumber > 0)
    topic.categoryId?.let { require(it > 0L) }
}

private const val UNKNOWN_AUTHOR_USERNAME: String = "unknown"
private const val MAX_SEARCH_QUERY_INPUT_CHARS: Int = 2_000
private const val MAX_SEARCH_TAGS: Int = 100
