package dev.dimension.flare.data.network.discourse.forum

import dev.dimension.flare.data.network.discourse.content.DiscourseCookedHtmlParser
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationException
import dev.dimension.flare.data.network.discourse.model.DiscourseBasicUser
import dev.dimension.flare.data.network.discourse.model.DiscourseCategory
import dev.dimension.flare.data.network.discourse.model.DiscourseCategoryList
import dev.dimension.flare.data.network.discourse.model.DiscourseCategoryListResponse
import dev.dimension.flare.data.network.discourse.model.DiscoursePostActionSummary
import dev.dimension.flare.data.network.discourse.model.DiscoursePostStream
import dev.dimension.flare.data.network.discourse.model.DiscourseReaction
import dev.dimension.flare.data.network.discourse.model.DiscourseTag
import dev.dimension.flare.data.network.discourse.model.DiscourseTagsResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicDetail
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicList
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicListResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicPoster
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicSummary
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicTag
import dev.dimension.flare.ui.model.UiArticleBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class DiscourseForumMapperTest {
    private val mapper = DiscourseForumMapper(DiscourseCookedHtmlParser())

    @Test
    fun feedMappingUsesSideLoadedAuthorSanitizesExcerptAndDeduplicatesTopics() {
        val summary =
            DiscourseTopicSummary(
                id = 42L,
                title = "Kotlin Multiplatform",
                slug = "kotlin-multiplatform",
                excerpt = "<p>Hello <a href=\"https://linux.do/t/42\">forum</a></p><script>secret()</script>",
                replyCount = 7,
                views = 99,
                bumpedAt = "2026-08-19T01:02:03Z",
                categoryId = 5L,
                tags = listOf(DiscourseTopicTag("kotlin")),
                unreadPosts = 2,
                highestPostNumber = 8,
                posters = listOf(DiscourseTopicPoster(userId = 9L)),
            )
        val response =
            DiscourseTopicListResponse(
                users =
                    listOf(
                        DiscourseBasicUser(
                            id = 9L,
                            username = "alice",
                            name = "Alice",
                            avatarTemplate = "/user_avatar/linux.do/alice/{size}/1.png",
                        ),
                    ),
                topicList =
                    DiscourseTopicList(
                        topics = listOf(summary, summary.copy(views = 100)),
                        moreTopicsUrl = "/latest?page=1",
                    ),
            )

        val page =
            mapper.mapFeedPage(
                response = response,
                feed = DiscourseForumFeed.Latest,
                page = 0,
                updatedAtEpochMillis = 123L,
                categoryNames = mapOf(5L to "Development"),
            )

        assertEquals(1, page.topics.size)
        assertEquals(1, page.nextPage)
        with(page.topics.single()) {
            assertEquals("discourse-topic:42", itemKey)
            assertEquals("Hello forum", excerpt)
            assertFalse(excerpt.contains("secret"))
            assertEquals("alice", author.username)
            assertEquals("https://linux.do/user_avatar/linux.do/alice/96/1.png", author.avatarUrl)
            assertEquals("Development", categoryName)
            assertEquals(listOf("kotlin"), tags)
            assertTrue(unread)
            assertEquals(2, discourse?.unreadPostCount)
            assertEquals(8, discourse?.highestPostNumber)
        }
    }

    @Test
    fun topicMappingPreservesStreamOrderAndMapsPostActionState() {
        val second =
            discoursePost(
                id = 22L,
                postNumber = 2,
                cooked = "<p>Safe reply</p><iframe src=\"https://evil.invalid\"></iframe>",
            ).copy(
                replyToPostNumber = 1,
                canEdit = true,
                actionsSummary = listOf(DiscoursePostActionSummary(id = 2L, count = 4, acted = true)),
                bookmarked = true,
                bookmarkId = 88L,
                currentUserReaction = DiscourseReaction(id = "heart", chosen = true),
            )
        val first = discoursePost(id = 11L, postNumber = 1)
        val detail =
            DiscourseTopicDetail(
                id = 42L,
                title = "Strict stream topic",
                slug = "strict-stream-topic",
                postStream = DiscoursePostStream(posts = emptyList(), stream = listOf(22L, 11L)),
                postsCount = 2,
                canCreatePost = true,
            )

        val topic = mapper.mapTopic(detail, listOf(second, first), 456L)

        assertEquals(listOf("discourse-post:22", "discourse-post:11"), topic.articles.map { it.itemKey })
        assertTrue(topic.canReply)
        val article = topic.articles.first()
        assertIs<UiArticleBlock.Paragraph>(article.blocks.single())
        assertEquals("Safe reply", (article.blocks.single() as UiArticleBlock.Paragraph).text)
        with(requireNotNull(article.discourse)) {
            assertEquals(1, replyToPostNumber)
            assertTrue(canEdit)
            assertTrue(liked)
            assertEquals(4, likeCount)
            assertTrue(bookmarked)
            assertEquals(88L, bookmarkId)
            assertEquals("heart", currentReaction)
        }

        assertFailsWith<DiscourseSerializationException> {
            mapper.mapTopic(
                detail.copy(
                    postStream =
                        DiscoursePostStream(
                            posts = emptyList(),
                            stream = listOf(22L, 11L, 22L),
                        ),
                ),
                orderedPosts = listOf(second, first),
                updatedAtEpochMillis = 456L,
            )
        }
    }

    @Test
    fun taxonomyMappingRetainsHierarchyAndRejectsInvalidDurableIdentity() {
        val categories =
            mapper.mapCategories(
                DiscourseCategoryListResponse(
                    DiscourseCategoryList(
                        categories =
                            listOf(
                                DiscourseCategory(1L, "Parent", "parent", color = "00aaCC"),
                                DiscourseCategory(
                                    2L,
                                    "Child",
                                    "child",
                                    parentCategoryId = 1L,
                                    topicCount = 3,
                                ),
                            ),
                    ),
                ),
                updatedAtEpochMillis = 1L,
            )
        val tags =
            mapper.mapTags(
                DiscourseTagsResponse(
                    tags =
                        listOf(
                            DiscourseTag(id = 3L, text = "kmp", name = "KMP", slug = "kmp", count = 9),
                        ),
                ),
                updatedAtEpochMillis = 2L,
            )

        assertEquals("#00AACC", categories.items.first().colorHex)
        assertEquals("parent", categories.items.last().parentSlug)
        assertEquals(3, categories.items.last().topicCount)
        assertEquals("kmp", tags.items.single().slug)

        assertFailsWith<DiscourseSerializationException> {
            mapper.mapFeedPage(
                response =
                    DiscourseTopicListResponse(
                        topicList =
                            DiscourseTopicList(
                                topics =
                                    listOf(
                                        DiscourseTopicSummary(
                                            id = -1L,
                                            title = "Invalid",
                                            slug = "invalid",
                                        ),
                                    ),
                            ),
                    ),
                feed = DiscourseForumFeed.Latest,
                page = 0,
                updatedAtEpochMillis = 1L,
            )
        }
    }
}
