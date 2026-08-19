package dev.dimension.flare.data.network.discourse

import dev.dimension.flare.data.network.discourse.error.DiscourseCsrfException
import dev.dimension.flare.data.network.discourse.error.DiscoursePostEnqueuedException
import dev.dimension.flare.data.network.discourse.model.DiscourseCreateBookmarkRequest
import dev.dimension.flare.data.network.discourse.model.DiscourseCreatePostRequest
import dev.dimension.flare.data.network.discourse.paging.DiscourseListPage
import dev.dimension.flare.data.network.discourse.paging.DiscourseNotificationOffset
import dev.dimension.flare.data.network.discourse.paging.DiscourseSearchPage
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieStorage
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

internal class DefaultDiscourseApiIntegrationTest {
    @Test
    fun cursorOriginsAreEncodedWithoutOffByOne() =
        runTest {
            val observedUrls = mutableListOf<Url>()
            val engine =
                MockEngine { request ->
                    observedUrls += request.url
                    val body =
                        when (request.url.encodedPath) {
                            "/latest.json" -> TOPIC_LIST_FIXTURE
                            "/search.json" -> SEARCH_FIXTURE
                            "/notifications" -> NOTIFICATION_FIXTURE
                            else -> error("Unexpected fixture request: ${request.url.encodedPath}")
                        }
                    respond(content = body, headers = apiJsonHeaders())
                }
            val cookieStorage = DiscourseCookieStorage()
            val client = createDiscourseHttpClient(engine, cookieStorage)
            val api =
                DefaultDiscourseApi(
                    wire = createDiscourseWireTransport(client),
                    sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage),
                )

            try {
                api.topics()
                api.topics(DiscourseTopicListRequest(page = DiscourseListPage(2)))
                api.search(query = "safe fixture", type = DiscourseSearchType.Topic)
                api.search(
                    query = "safe fixture",
                    page = DiscourseSearchPage(3),
                    type = DiscourseSearchType.Topic,
                )
                api.notifications()
                api.notifications(offset = DiscourseNotificationOffset(60), limit = 30)
            } finally {
                client.close()
            }

            val topicUrls = observedUrls.filter { it.encodedPath == "/latest.json" }
            assertEquals(listOf(null, "2"), topicUrls.map { it.parameters["page"] })

            val searchUrls = observedUrls.filter { it.encodedPath == "/search.json" }
            assertEquals(listOf(null, "3"), searchUrls.map { it.parameters["page"] })
            assertEquals(listOf("topic", "topic"), searchUrls.map { it.parameters["type_filter"] })

            val notificationUrls = observedUrls.filter { it.encodedPath == "/notifications" }
            assertEquals(listOf(null, "60"), notificationUrls.map { it.parameters["offset"] })
            assertEquals(listOf("60", "30"), notificationUrls.map { it.parameters["limit"] })
        }

    @Test
    fun postIdsPreserveAuthoritativeStreamOrder() =
        runTest {
            var observedPostIds: List<String>? = null
            val engine =
                MockEngine { request ->
                    assertEquals("/t/42/posts.json", request.url.encodedPath)
                    observedPostIds = request.url.parameters.getAll("post_ids[]")
                    respond(content = POST_STREAM_FIXTURE, headers = apiJsonHeaders())
                }
            val cookieStorage = DiscourseCookieStorage()
            val client = createDiscourseHttpClient(engine, cookieStorage)
            val api =
                DefaultDiscourseApi(
                    wire = createDiscourseWireTransport(client),
                    sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage),
                )

            try {
                api.topicPosts(topicId = 42L, postIds = listOf(91L, 12L, 44L))
            } finally {
                client.close()
            }

            assertEquals(listOf("91", "12", "44"), observedPostIds)
        }

    @Test
    fun explicitCsrfFailureReplaysOnce() =
        runTest {
            var csrfRequestCount = 0
            val mutationTokens = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/session/csrf" -> {
                            csrfRequestCount += 1
                            respond(
                                content = "{\"csrf\":\"fixture-token-$csrfRequestCount\"}",
                                headers = apiJsonHeaders(),
                            )
                        }

                        "/bookmarks.json" -> {
                            mutationTokens += requireNotNull(request.headers["X-CSRF-Token"])
                            if (mutationTokens.size == 1) {
                                respond(
                                    content = "BAD CSRF",
                                    status = HttpStatusCode.Forbidden,
                                    headers = apiTextHeaders(),
                                )
                            } else {
                                respond(content = "{\"id\":901}", headers = apiJsonHeaders())
                            }
                        }

                        else -> {
                            error("Unexpected fixture request: ${request.url.encodedPath}")
                        }
                    }
                }
            val cookieStorage = DiscourseCookieStorage()
            val client = createDiscourseHttpClient(engine, cookieStorage)
            val api =
                DefaultDiscourseApi(
                    wire = createDiscourseWireTransport(client),
                    sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage),
                )

            try {
                val response =
                    api.createBookmark(
                        DiscourseCreateBookmarkRequest(
                            bookmarkableId = 42L,
                            bookmarkableType = "Topic",
                        ),
                    )
                assertEquals(901L, response.id)
            } finally {
                client.close()
            }

            assertEquals(2, csrfRequestCount)
            assertEquals(listOf("fixture-token-1", "fixture-token-2"), mutationTokens)
        }

    @Test
    fun secondCsrfFailureStopsReplay() =
        runTest {
            var csrfRequestCount = 0
            val mutationTokens = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/session/csrf" -> {
                            csrfRequestCount += 1
                            respond(
                                content = "{\"csrf\":\"fixture-token-$csrfRequestCount\"}",
                                headers = apiJsonHeaders(),
                            )
                        }

                        "/bookmarks.json" -> {
                            mutationTokens += requireNotNull(request.headers["X-CSRF-Token"])
                            respond(
                                content = "BAD CSRF",
                                status = HttpStatusCode.Forbidden,
                                headers = apiTextHeaders(),
                            )
                        }

                        else -> {
                            error("Unexpected fixture request: ${request.url.encodedPath}")
                        }
                    }
                }
            val cookieStorage = DiscourseCookieStorage()
            val client = createDiscourseHttpClient(engine, cookieStorage)
            val api =
                DefaultDiscourseApi(
                    wire = createDiscourseWireTransport(client),
                    sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage),
                )

            try {
                assertFailsWith<DiscourseCsrfException> {
                    api.createBookmark(
                        DiscourseCreateBookmarkRequest(
                            bookmarkableId = 42L,
                            bookmarkableType = "Topic",
                        ),
                    )
                }
            } finally {
                client.close()
            }

            assertEquals(2, csrfRequestCount)
            assertEquals(listOf("fixture-token-1", "fixture-token-2"), mutationTokens)
        }

    @Test
    fun lateConcurrentCsrfFailureCannotClearRefreshedToken() =
        runTest {
            val oldRequestsEntered = List(2) { CompletableDeferred<Unit>() }
            val releaseOldResponses = List(2) { CompletableDeferred<Unit>() }
            val firstFreshRequestEntered = CompletableDeferred<Unit>()
            val mutationTokens = mutableListOf<String>()
            var oldRequestCount = 0
            var csrfRequestCount = 0
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/session/csrf" -> {
                            csrfRequestCount += 1
                            respond(
                                content = "{\"csrf\":\"fresh-token\"}",
                                headers = apiJsonHeaders(),
                            )
                        }

                        "/bookmarks.json" -> {
                            val token = requireNotNull(request.headers["X-CSRF-Token"])
                            mutationTokens += token
                            if (token == "old-token") {
                                val requestIndex = oldRequestCount++
                                oldRequestsEntered[requestIndex].complete(Unit)
                                releaseOldResponses[requestIndex].await()
                                respond(
                                    content = "BAD CSRF",
                                    status = HttpStatusCode.Forbidden,
                                    headers = apiTextHeaders(),
                                )
                            } else {
                                firstFreshRequestEntered.complete(Unit)
                                respond(content = "{\"id\":901}", headers = apiJsonHeaders())
                            }
                        }

                        else -> {
                            error("Unexpected fixture request: ${request.url.encodedPath}")
                        }
                    }
                }
            val cookieStorage = DiscourseCookieStorage()
            val sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage)
            sessionManager.csrfToken { "old-token" }
            val client = createDiscourseHttpClient(engine, cookieStorage)
            val api =
                DefaultDiscourseApi(
                    wire = createDiscourseWireTransport(client),
                    sessionManager = sessionManager,
                )

            try {
                val requests =
                    List(2) {
                        async {
                            api.createBookmark(
                                DiscourseCreateBookmarkRequest(
                                    bookmarkableId = 42L,
                                    bookmarkableType = "Topic",
                                ),
                            )
                        }
                    }

                oldRequestsEntered.forEach { it.await() }
                releaseOldResponses.first().complete(Unit)
                firstFreshRequestEntered.await()
                releaseOldResponses.last().complete(Unit)

                assertEquals(listOf(901L, 901L), requests.awaitAll().map { it.id })
            } finally {
                client.close()
            }

            assertEquals(1, csrfRequestCount)
            assertEquals(2, mutationTokens.count { it == "old-token" })
            assertEquals(2, mutationTokens.count { it == "fresh-token" })
        }

    @Test
    fun enqueuedPostBecomesModerationFailure() =
        runTest {
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/session/csrf" -> {
                            respond(
                                content = "{\"csrf\":\"fixture-token\"}",
                                headers = apiJsonHeaders(),
                            )
                        }

                        "/posts.json" -> {
                            respond(
                                content = ENQUEUED_POST_FIXTURE,
                                headers = apiJsonHeaders(),
                            )
                        }

                        else -> {
                            error("Unexpected fixture request: ${request.url.encodedPath}")
                        }
                    }
                }
            val cookieStorage = DiscourseCookieStorage()
            val client = createDiscourseHttpClient(engine, cookieStorage)
            val api =
                DefaultDiscourseApi(
                    wire = createDiscourseWireTransport(client),
                    sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage),
                )

            try {
                val failure =
                    assertFailsWith<DiscoursePostEnqueuedException> {
                        api.createPost(
                            DiscourseCreatePostRequest(
                                raw = "Redacted fixture reply",
                                topicId = 42L,
                            ),
                        )
                    }

                assertEquals(2, failure.pendingCount)
                assertEquals(73L, failure.pendingPostId)
                assertEquals(42L, failure.topicId)
                assertIs<DiscoursePostEnqueuedException>(failure)
            } finally {
                client.close()
            }
        }
}

private const val TOPIC_LIST_FIXTURE: String = """{"topic_list":{"topics":[]}}"""
private const val SEARCH_FIXTURE: String = """{}"""
private const val NOTIFICATION_FIXTURE: String = """{"notifications":[]}"""
private const val POST_STREAM_FIXTURE: String = """{"posts":[]}"""
private const val ENQUEUED_POST_FIXTURE: String =
    """{"action":"enqueued","pending_count":2,"pending_post":{"id":73,"topic_id":42},"topic_id":42}"""

private fun apiJsonHeaders(): Headers =
    Headers.build {
        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }

private fun apiTextHeaders(): Headers =
    Headers.build {
        append(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
    }
