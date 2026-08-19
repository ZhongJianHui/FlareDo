package dev.dimension.flare.data.network.discourse.forum

import dev.dimension.flare.data.network.discourse.DiscourseSearchType
import dev.dimension.flare.data.network.discourse.content.DiscourseCookedHtmlParser
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationException
import dev.dimension.flare.data.network.discourse.model.DiscourseGroupedSearchResult
import dev.dimension.flare.data.network.discourse.model.DiscourseSearchPost
import dev.dimension.flare.data.network.discourse.model.DiscourseSearchResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicSummary
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicTag
import dev.dimension.flare.data.network.discourse.paging.DiscourseSearchPage
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.StaleDiscourseSessionException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

internal class DiscourseForumSearchStage6Test {
    private val mapper = DiscourseForumSearchMapper(DiscourseCookedHtmlParser())

    @Test
    fun repositoryAlwaysRequestsPostSearchFromLogicalPageOne() =
        runTest {
            var recordedPage: DiscourseSearchPage? = null
            var recordedType: DiscourseSearchType? = null
            val repository =
                DefaultDiscourseForumSearchRepository(
                    remote =
                        DiscourseForumSearchRemoteDataSource { _, page, type ->
                            recordedPage = page
                            recordedType = type
                            searchResponse()
                        },
                    mapper = mapper,
                    sessionManager = DiscourseSessionManager(),
                )

            repository.search("kotlin")

            assertEquals(DiscourseSearchPage.Initial, recordedPage)
            assertEquals(DiscourseSearchType.Post, recordedType)
        }

    @Test
    fun sessionReplacementCancelsSearchBeforeMappedRowsCanEscape() =
        runTest {
            supervisorScope {
                val requestStarted = CompletableDeferred<Unit>()
                val releaseResponse = CompletableDeferred<Unit>()
                val sessionManager = DiscourseSessionManager()
                val repository =
                    DefaultDiscourseForumSearchRepository(
                        remote =
                            DiscourseForumSearchRemoteDataSource { _, _, _ ->
                                requestStarted.complete(Unit)
                                releaseResponse.await()
                                searchResponse(posts = listOf(searchPost(id = 81L)))
                            },
                        mapper = mapper,
                        sessionManager = sessionManager,
                    )

                val search = async { repository.search("private result") }
                requestStarted.await()
                sessionManager.logout()
                releaseResponse.complete(Unit)

                assertFailsWith<StaleDiscourseSessionException> { search.await() }
            }
        }

    @Test
    fun overlapDoesNotPreventContinuationCursorAdvancing() {
        val response =
            searchResponse(
                posts = listOf(searchPost(id = 81L)),
                continuation = true,
            )

        val page =
            mapper.mapPage(
                query = "paging",
                page = DiscourseSearchPage(3),
                response = response,
                knownPostIds = setOf(81L),
            )

        assertEquals(emptyList(), page.items)
        assertEquals(DiscourseSearchPage(4), page.nextPage)
    }

    @Test
    fun postIdsAreDeduplicatedAndJoinedToSideLoadedTopics() {
        val response =
            searchResponse(
                posts =
                    listOf(
                        searchPost(id = 81L, postNumber = 3),
                        searchPost(id = 81L, postNumber = 3),
                        searchPost(id = 82L, postNumber = 7),
                    ),
            )

        val page = mapper.mapPage("joins", DiscourseSearchPage.Initial, response)

        assertEquals(listOf(81L, 82L), page.items.map { it.postId })
        assertEquals(listOf(3, 7), page.items.map { it.topic.postNumber })
        assertEquals(listOf(42L, 42L), page.items.map { it.topic.topicId })
        assertEquals("safe-topic", page.items.first().topicSlug)
        assertNull(page.nextPage)
    }

    @Test
    fun orphanPostFailsWithFixedProtocolFailure() {
        val response =
            searchResponse(
                posts = listOf(searchPost(id = 81L, topicId = 404L)),
            )

        assertFailsWith<DiscourseSerializationException> {
            mapper.mapPage("orphan", DiscourseSearchPage.Initial, response)
        }
    }

    @Test
    fun serverHtmlIsSanitizedBeforeSearchTextIsExposed() {
        val response =
            searchResponse(
                posts =
                    listOf(
                        searchPost(
                            id = 81L,
                            blurb =
                                "<script>steal()</script><p>Visible " +
                                    "<a href=\"javascript:bad()\">link</a></p>",
                        ),
                    ),
                topicTitle = "<img src=x onerror=steal()><strong>Safe title</strong>",
            )

        val hit = mapper.mapPage("sanitize", DiscourseSearchPage.Initial, response).items.single()

        assertEquals("Safe title", hit.title)
        assertEquals("Visible link", hit.excerpt)
        assertFalse(hit.title.contains('<'))
        assertFalse(hit.excerpt.contains("javascript", ignoreCase = true))
    }
}

private fun searchResponse(
    posts: List<DiscourseSearchPost> = emptyList(),
    continuation: Boolean = false,
    topicTitle: String = "Safe topic",
): DiscourseSearchResponse =
    DiscourseSearchResponse(
        posts = posts,
        topics =
            listOf(
                DiscourseTopicSummary(
                    id = 42L,
                    title = topicTitle,
                    slug = "safe-topic",
                    categoryId = 9L,
                    tags = listOf(DiscourseTopicTag(name = "kotlin")),
                ),
            ),
        groupedSearchResult =
            DiscourseGroupedSearchResult(
                morePosts = continuation,
                moreFullPageResults = false,
            ),
    )

private fun searchPost(
    id: Long,
    topicId: Long = 42L,
    postNumber: Int = 2,
    blurb: String = "Safe excerpt",
): DiscourseSearchPost =
    DiscourseSearchPost(
        id = id,
        topicId = topicId,
        postNumber = postNumber,
        username = "member",
        name = "Member",
        avatarTemplate = "/user_avatar/linux.do/member/{size}/1.png",
        createdAt = "2026-08-19T01:02:03Z",
        likeCount = 4,
        blurb = blurb,
    )
