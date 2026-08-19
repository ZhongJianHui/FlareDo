package dev.dimension.flare.data.network.discourse.forum

import dev.dimension.flare.data.network.discourse.content.DiscourseCookedHtmlParser
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationException
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationPhase
import dev.dimension.flare.data.network.discourse.model.DiscourseBasicUser
import dev.dimension.flare.data.network.discourse.model.DiscourseCategoryListResponse
import dev.dimension.flare.data.network.discourse.model.DiscoursePost
import dev.dimension.flare.data.network.discourse.model.DiscourseTag
import dev.dimension.flare.data.network.discourse.model.DiscourseTagsResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicDetail
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicListResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicSummary
import dev.dimension.flare.ui.model.DiscoursePostMeta
import dev.dimension.flare.ui.model.DiscourseTopicMeta
import dev.dimension.flare.ui.model.DiscourseTopicRef
import dev.dimension.flare.ui.model.UiArticle
import dev.dimension.flare.ui.model.UiArticleBlock
import dev.dimension.flare.ui.model.UiAuthor
import dev.dimension.flare.ui.model.UiTimelineV2
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.takeFrom
import kotlin.time.Instant

/**
 * Maps validated Discourse transport records into the bounded, platform-neutral forum UI models.
 *
 * This is the only bridge from `cooked` HTML to an article. Every post is passed through
 * [DiscourseCookedHtmlParser], and neither raw HTML nor a string-stripping fallback can cross this
 * boundary. Invalid durable identities fail with a fixed serialization error instead of producing
 * cache keys or navigation routes that the rest of the application cannot safely use.
 */
public class DiscourseForumMapper(
    private val cookedHtmlParser: DiscourseCookedHtmlParser,
) {
    /** Maps one root/category/tag list page and removes overlapping topic identities in-place. */
    public fun mapFeedPage(
        response: DiscourseTopicListResponse,
        feed: DiscourseForumFeed,
        page: Int,
        updatedAtEpochMillis: Long,
        categoryNames: Map<Long, String> = emptyMap(),
    ): DiscourseForumFeedPage =
        mapOrProtocolFailure {
            require(page >= 0)
            require(updatedAtEpochMillis >= 0L)
            val usersById = response.users.validUserLookup()
            val topics =
                response.topicList.topics
                    .map { summary ->
                        summary.toUiTopic(
                            usersById = usersById,
                            categoryName =
                                summary.categoryId?.let(categoryNames::get)
                                    ?: (feed as? DiscourseForumFeed.Category)
                                        ?.takeIf { it.id == summary.categoryId }
                                        ?.name,
                        )
                    }.distinctBy(UiTimelineV2.Topic::itemKey)
            val nextPage =
                response.topicList.moreTopicsUrl
                    ?.takeIf(String::isNotBlank)
                    ?.takeIf { topics.isNotEmpty() }
                    ?.let {
                        check(page < Int.MAX_VALUE)
                        page + 1
                    }
            DiscourseForumFeedPage(
                feed = feed,
                page = page,
                topics = topics,
                nextPage = nextPage,
                canCreateTopic = response.topicList.canCreateTopic,
                source = DiscourseForumContentSource.Network,
                updatedAtEpochMillis = updatedAtEpochMillis,
            )
        }

    /** Maps category identity, hierarchy, display color, and anonymous topic counts. */
    public fun mapCategories(
        response: DiscourseCategoryListResponse,
        updatedAtEpochMillis: Long,
    ): DiscourseForumCategories =
        mapOrProtocolFailure {
            require(updatedAtEpochMillis >= 0L)
            val categoriesById = response.categoryList.categories.associateBy { it.id }
            val items =
                response.categoryList.categories
                    .map { category ->
                        require(category.id > 0L)
                        val parent = category.parentCategoryId?.let(categoriesById::get)
                        DiscourseForumCategoryOption(
                            id = category.id,
                            name = category.name.requiredDisplayValue(MAX_TITLE_CHARS),
                            slug = category.slug.requiredRouteValue(),
                            parentCategoryId = category.parentCategoryId,
                            parentSlug = parent?.slug?.requiredRouteValue(),
                            colorHex = category.color.toSafeColorHex(),
                            topicCount = category.topicCount.coerceAtLeast(0),
                        )
                    }.distinctBy(DiscourseForumCategoryOption::id)
            DiscourseForumCategories(
                items = items,
                source = DiscourseForumContentSource.Network,
                updatedAtEpochMillis = updatedAtEpochMillis,
            )
        }

    /** Maps the canonical `/tags.json` collection and removes duplicate server IDs. */
    public fun mapTags(
        response: DiscourseTagsResponse,
        updatedAtEpochMillis: Long,
    ): DiscourseForumTags =
        mapOrProtocolFailure {
            require(updatedAtEpochMillis >= 0L)
            DiscourseForumTags(
                items =
                    response.tags
                        .map(DiscourseTag::toForumTag)
                        .distinctBy(DiscourseForumTagOption::id),
                source = DiscourseForumContentSource.Network,
                updatedAtEpochMillis = updatedAtEpochMillis,
            )
        }

    /**
     * Maps a complete topic. [orderedPosts] must already follow the authoritative stream exactly;
     * this method verifies that identity again before any snapshot is persisted.
     */
    public fun mapTopic(
        detail: DiscourseTopicDetail,
        orderedPosts: List<DiscoursePost>,
        updatedAtEpochMillis: Long,
    ): DiscourseForumTopic =
        mapOrProtocolFailure {
            require(detail.id > 0L)
            require(updatedAtEpochMillis >= 0L)
            val streamIds = detail.postStream.stream
            require(streamIds.all { it > 0L })
            require(streamIds.distinct().size == streamIds.size)
            require(detail.postsCount <= 0 || streamIds.isNotEmpty())
            require(orderedPosts.map(DiscoursePost::id) == streamIds)
            require(orderedPosts.all { it.topicId == detail.id })
            val canReply =
                !detail.closed &&
                    !detail.archived &&
                    (detail.canCreatePost || detail.details?.canCreatePost == true)
            val title = detail.title.requiredDisplayValue(MAX_TITLE_CHARS)
            DiscourseForumTopic(
                topicId = detail.id,
                title = title,
                slug = detail.slug.requiredRouteValue(),
                categoryId = detail.categoryId?.also { require(it > 0L) },
                tags =
                    detail.tags
                        .map { it.name.requiredDisplayValue(MAX_TAG_CHARS) }
                        .distinct(),
                articles =
                    orderedPosts.map { post ->
                        post.toUiArticle(
                            topicTitle = title,
                            canReply = canReply,
                        )
                    },
                canReply = canReply,
                source = DiscourseForumContentSource.Network,
                updatedAtEpochMillis = updatedAtEpochMillis,
            )
        }

    private fun DiscourseTopicSummary.toUiTopic(
        usersById: Map<Long, DiscourseBasicUser>,
        categoryName: String?,
    ): UiTimelineV2.Topic {
        require(id > 0L)
        categoryId?.let { require(it > 0L) }
        val author = posters.firstNotNullOfOrNull { usersById[it.userId] }.toUiAuthor()
        val safeTags = tags.map { it.name.requiredDisplayValue(MAX_TAG_CHARS) }.distinct()
        return UiTimelineV2.Topic(
            itemKey = topicItemKey(id),
            title = title.requiredDisplayValue(MAX_TITLE_CHARS),
            excerpt = cookedHtmlParser.parse(excerpt.orEmpty()).toPlainText(MAX_EXCERPT_CHARS),
            author = author,
            replyCount = replyCount.coerceAtLeast(0),
            viewCount = views.coerceAtLeast(0),
            lastActivityEpochMillis =
                sequenceOf(bumpedAt, lastPostedAt, createdAt)
                    .firstNotNullOfOrNull(::parseEpochMillis)
                    ?: 0L,
            unread = unseen || unreadPosts > 0 || newPosts > 0,
            categoryName = categoryName?.safeOptionalDisplayValue(MAX_TITLE_CHARS),
            tags = safeTags,
            discourse =
                DiscourseTopicMeta(
                    ref = DiscourseTopicRef(topicId = id),
                    slug = slug.requiredRouteValue(),
                    categoryId = categoryId,
                    unreadPostCount = unreadPosts.coerceAtLeast(0),
                    newPostCount = newPosts.coerceAtLeast(0),
                    highestPostNumber = highestPostNumber.takeIf { it > 0 },
                    lastReadPostNumber = lastReadPostNumber?.takeIf { it > 0 },
                    liked = liked == true,
                    bookmarked = bookmarked == true,
                ),
        )
    }

    private fun DiscoursePost.toUiArticle(
        topicTitle: String,
        canReply: Boolean,
    ): UiArticle {
        require(id > 0L)
        require(topicId > 0L)
        require(postNumber > 0)
        replyToPostNumber?.let { require(it > 0) }
        bookmarkId?.let { require(it > 0L) }
        val likeAction = actionsSummary.firstOrNull { it.id == LIKE_ACTION_ID }
        return UiArticle(
            itemKey = postItemKey(id),
            title = topicTitle,
            author =
                UiAuthor(
                    username = username.safeUsername(),
                    displayName =
                        sequenceOf(name, displayUsername, username)
                            .firstNotNullOfOrNull { it?.safeOptionalDisplayValue(MAX_AUTHOR_CHARS) }
                            ?: UNKNOWN_AUTHOR_DISPLAY_NAME,
                    avatarUrl = avatarTemplate.toSafeAvatarUrl(),
                ),
            createdAtEpochMillis = parseEpochMillis(createdAt) ?: 0L,
            blocks = cookedHtmlParser.parse(cooked),
            canReply = canReply,
            discourse =
                DiscoursePostMeta(
                    topicId = topicId,
                    postId = id,
                    postNumber = postNumber,
                    replyToPostNumber = replyToPostNumber,
                    canEdit = canEdit,
                    canDelete = canDelete,
                    liked = likeAction?.acted == true,
                    likeCount = likeAction?.count?.coerceAtLeast(0) ?: 0,
                    bookmarked = bookmarked == true || bookmarkId != null,
                    bookmarkId = bookmarkId,
                    currentReaction =
                        currentUserReaction
                            ?.id
                            ?.safeOptionalDisplayValue(MAX_REACTION_CHARS),
                ),
        )
    }
}

private fun List<DiscourseBasicUser>.validUserLookup(): Map<Long, DiscourseBasicUser> =
    buildMap {
        for (user in this@validUserLookup) {
            if (user.id <= 0L || user.username.safeOptionalDisplayValue(MAX_AUTHOR_CHARS) == null) continue
            if (user.id !in this) this[user.id] = user
        }
    }

private fun DiscourseBasicUser?.toUiAuthor(): UiAuthor {
    val user = this
    if (user == null) {
        return UiAuthor(
            username = UNKNOWN_AUTHOR_USERNAME,
            displayName = UNKNOWN_AUTHOR_DISPLAY_NAME,
        )
    }
    val username = user.username.safeUsername()
    return UiAuthor(
        username = username,
        displayName = user.name.safeOptionalDisplayValue(MAX_AUTHOR_CHARS) ?: username,
        avatarUrl = user.avatarTemplate.toSafeAvatarUrl(),
    )
}

private fun DiscourseTag.toForumTag(): DiscourseForumTagOption {
    require(id > 0L)
    return DiscourseForumTagOption(
        id = id,
        name = name.requiredDisplayValue(MAX_TAG_CHARS),
        slug = slug.requiredRouteValue(),
        count = count.coerceAtLeast(0),
    )
}

private fun List<UiArticleBlock>.toPlainText(maxChars: Int): String {
    if (isEmpty()) return ""
    val text = joinToString(separator = "\n") { it.plainText() }
    return text.take(maxChars).trim()
}

private fun UiArticleBlock.plainText(): String =
    when (this) {
        is UiArticleBlock.Paragraph -> {
            text
        }

        is UiArticleBlock.Quote -> {
            text
        }

        is UiArticleBlock.Code -> {
            code
        }

        is UiArticleBlock.Image -> {
            altText.orEmpty()
        }

        is UiArticleBlock.ListBlock -> {
            items.joinToString(separator = "\n") { item ->
                item.blocks.joinToString(separator = " ") { it.plainText() }
            }
        }

        is UiArticleBlock.Table -> {
            rows.joinToString(separator = "\n") { row ->
                row.cells.joinToString(separator = " ") { it.text }
            }
        }

        is UiArticleBlock.Spoiler -> {
            text
        }
    }

private fun parseEpochMillis(value: String?): Long? {
    val candidate = value?.takeIf { it.length <= MAX_TIMESTAMP_CHARS } ?: return null
    return runCatching { Instant.parse(candidate).toEpochMilliseconds() }
        .getOrNull()
        ?.takeIf { it >= 0L }
}

private fun String?.toSafeAvatarUrl(): String? {
    val template = this ?: return null
    if (
        template.isBlank() ||
        template.length > MAX_AVATAR_URL_CHARS ||
        template != template.trim() ||
        template.any { it.code < 0x20 || it.code == 0x7f }
    ) {
        return null
    }
    val expanded = template.replace("{size}", AVATAR_SIZE.toString())
    if ('{' in expanded || '}' in expanded) return null
    val normalized = if (expanded.startsWith("//")) "https:$expanded" else expanded
    return try {
        val url =
            if (normalized.startsWith("https://", ignoreCase = true)) {
                Url(normalized)
            } else {
                URLBuilder(FORUM_ORIGIN).takeFrom(normalized).build()
            }
        if (
            url.protocol != URLProtocol.HTTPS ||
            url.host.isBlank() ||
            url.user != null ||
            url.password != null
        ) {
            null
        } else {
            url.toString()
        }
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun String.toSafeColorHex(): String? {
    val normalized = removePrefix("#")
    return normalized
        .takeIf { it.length == 6 && it.all(Char::isHexDigit) }
        ?.uppercase()
        ?.let { "#$it" }
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun String.safeUsername(): String = safeOptionalDisplayValue(MAX_AUTHOR_CHARS) ?: UNKNOWN_AUTHOR_USERNAME

private fun String.requiredRouteValue(): String {
    require(isNotBlank())
    require(length <= MAX_ROUTE_CHARS)
    require(none(Char::isControlCharacter))
    return this
}

private fun String.requiredDisplayValue(maxChars: Int): String =
    safeOptionalDisplayValue(maxChars) ?: throw IllegalArgumentException("Invalid forum display value")

private fun String?.safeOptionalDisplayValue(maxChars: Int): String? {
    val value = this?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (value.length > maxChars || value.any(Char::isControlCharacter)) return null
    return value
}

private fun Char.isControlCharacter(): Boolean = code < 0x20 || code == 0x7f

private inline fun <T> mapOrProtocolFailure(block: () -> T): T =
    try {
        block()
    } catch (known: DiscourseSerializationException) {
        throw known
    } catch (_: IllegalArgumentException) {
        throw protocolFailure()
    } catch (_: IllegalStateException) {
        throw protocolFailure()
    }

private fun protocolFailure(): DiscourseSerializationException =
    DiscourseSerializationException(DiscourseSerializationPhase.ResponseDecoding)

private fun topicItemKey(topicId: Long): String = "discourse-topic:$topicId"

private fun postItemKey(postId: Long): String = "discourse-post:$postId"

private const val FORUM_ORIGIN: String = "https://linux.do/"
private const val UNKNOWN_AUTHOR_USERNAME: String = "unknown"
private const val UNKNOWN_AUTHOR_DISPLAY_NAME: String = "Unknown"
private const val LIKE_ACTION_ID: Long = 2L
private const val AVATAR_SIZE: Int = 96
private const val MAX_ROUTE_CHARS: Int = 256
private const val MAX_TITLE_CHARS: Int = 1_000
private const val MAX_EXCERPT_CHARS: Int = 4_000
private const val MAX_AUTHOR_CHARS: Int = 256
private const val MAX_TAG_CHARS: Int = 256
private const val MAX_REACTION_CHARS: Int = 128
private const val MAX_AVATAR_URL_CHARS: Int = 2_048
private const val MAX_TIMESTAMP_CHARS: Int = 128
