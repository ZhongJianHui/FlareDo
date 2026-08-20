package dev.dimension.flare.data.network.discourse

import dev.dimension.flare.data.network.discourse.error.DiscourseAuthenticationException
import dev.dimension.flare.data.network.discourse.error.DiscourseCsrfException
import dev.dimension.flare.data.network.discourse.error.DiscoursePostEnqueuedException
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationException
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationPhase
import dev.dimension.flare.data.network.discourse.model.DiscourseActionResponseKind
import dev.dimension.flare.data.network.discourse.model.DiscourseCreateBookmarkRequest
import dev.dimension.flare.data.network.discourse.model.DiscourseCreatePostRequest
import dev.dimension.flare.data.network.discourse.model.DiscoursePostActionRequest
import dev.dimension.flare.data.network.discourse.model.DiscourseUpdatePostRequest
import dev.dimension.flare.data.network.discourse.paging.DiscourseListPage
import dev.dimension.flare.data.network.discourse.paging.DiscourseNotificationOffset
import dev.dimension.flare.data.network.discourse.paging.DiscourseSearchPage
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieStorage
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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
import kotlin.test.assertTrue

internal class DefaultDiscourseApiIntegrationTest {
    @Test
    fun logoutUsesCsrfProtectedSessionDelete() =
        runTest {
            val observed = mutableListOf<Pair<HttpMethod, String>>()
            val engine =
                MockEngine { request ->
                    observed += request.method to request.url.encodedPath
                    when (request.url.encodedPath) {
                        "/session/csrf" -> {
                            respond(
                                content = "{\"csrf\":\"logout-token\"}",
                                headers = apiJsonHeaders(),
                            )
                        }

                        "/session/member" -> {
                            assertEquals("logout-token", request.headers["X-CSRF-Token"])
                            respond(content = "", status = HttpStatusCode.NoContent)
                        }

                        else -> {
                            error("Unexpected logout request")
                        }
                    }
                }
            val cookieStorage = DiscourseCookieStorage()
            val client = createDiscourseHttpClient(engine, cookieStorage)
            val sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage)
            sessionManager.authenticateFixture()
            val api =
                DefaultDiscourseApi(
                    wire = createDiscourseWireTransport(client),
                    sessionManager = sessionManager,
                )

            try {
                api.logout("member")
            } finally {
                client.close()
            }

            assertEquals(
                listOf(
                    HttpMethod.Get to "/session/csrf",
                    HttpMethod.Delete to "/session/member",
                ),
                observed,
            )
        }

    @Test
    fun guestPrivateReadsAndMutationsFailBeforeCsrfOrTargetRequest() =
        runTest {
            var networkCalls = 0
            val engine =
                MockEngine {
                    networkCalls += 1
                    error("Guest private APIs must be rejected before transport")
                }
            val cookieStorage = DiscourseCookieStorage()
            val client = createDiscourseHttpClient(engine, cookieStorage)
            val sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage)
            val api =
                DefaultDiscourseApi(
                    wire = createDiscourseWireTransport(client),
                    sessionManager = sessionManager,
                )

            try {
                assertAuthenticationRequired { api.logout("fixture-member") }
                assertAuthenticationRequired { api.userBookmarks("fixture-member") }
                assertAuthenticationRequired { api.bookmarkedTopics() }
                assertAuthenticationRequired { api.editablePost(postId = 401L) }
                assertAuthenticationRequired {
                    api.createPost(
                        DiscourseCreatePostRequest(
                            raw = "Redacted fixture reply",
                            topicId = 42L,
                        ),
                    )
                }
                assertAuthenticationRequired {
                    api.updatePost(
                        postId = 401L,
                        request = DiscourseUpdatePostRequest(raw = "Redacted fixture edit"),
                    )
                }
                assertAuthenticationRequired { api.markNotificationsRead(notificationId = 8102L) }
                assertAuthenticationRequired {
                    api.createPostAction(
                        DiscoursePostActionRequest(
                            id = 401L,
                            postActionTypeId = 2L,
                        ),
                    )
                }
                assertAuthenticationRequired {
                    api.deletePostAction(
                        postId = 401L,
                        actionTypeId = 2L,
                    )
                }
                assertAuthenticationRequired {
                    api.createBookmark(
                        DiscourseCreateBookmarkRequest(
                            bookmarkableId = 42L,
                            bookmarkableType = "Topic",
                        ),
                    )
                }
                assertAuthenticationRequired { api.deleteBookmark(bookmarkId = 901L) }
                assertAuthenticationRequired {
                    api.upload(
                        DiscourseUploadRequest(
                            bytes = byteArrayOf(1),
                            fileName = "fixture.bin",
                        ),
                    )
                }
                assertFailsWith<IllegalArgumentException> { api.editablePost(postId = 0L) }
            } finally {
                client.close()
            }

            assertEquals(0, networkCalls)
        }

    @Test
    fun uncategorizedNewTopicIsSentForServerPermissionValidation() =
        runTest {
            var postBody = ""
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/session/csrf" -> {
                            respond(
                                content = "{\"csrf\":\"fixture-topic-token\"}",
                                headers = apiJsonHeaders(),
                            )
                        }

                        "/posts.json" -> {
                            postBody = request.body.toByteArray().decodeToString()
                            respond(
                                content = "{\"id\":501,\"topic_id\":42,\"post_number\":1}",
                                headers = apiJsonHeaders(),
                            )
                        }

                        else -> {
                            error("Unexpected uncategorized topic request: ${request.url.encodedPath}")
                        }
                    }
                }
            val cookieStorage = DiscourseCookieStorage()
            val client = createDiscourseHttpClient(engine, cookieStorage)
            val sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage)
            sessionManager.authenticateFixture()
            val api = DefaultDiscourseApi(createDiscourseWireTransport(client), sessionManager)

            try {
                val response =
                    api.createPost(
                        DiscourseCreatePostRequest(
                            raw = "Fixture uncategorized body",
                            title = "Fixture uncategorized topic",
                        ),
                    )

                assertEquals(42L, response.post?.topicId)
                assertTrue("category=" !in postBody)
            } finally {
                client.close()
            }
        }

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
            val sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage)
            sessionManager.startAuthenticatedSession(
                accountId = "fixture-account",
                username = "fixture-member",
            )
            val api =
                DefaultDiscourseApi(
                    wire = createDiscourseWireTransport(client),
                    sessionManager = sessionManager,
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
    fun authenticatedEditablePostUsesPrivateRouteAndPreservesAuthoritativeRaw() =
        runTest {
            var requestCount = 0
            val engine =
                MockEngine { request ->
                    requestCount += 1
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals("/posts/401.json", request.url.encodedPath)
                    respond(
                        content =
                            """{"id":401,"topic_id":42,"post_number":3,"raw":"**Fixture**\nbody"}""",
                        headers = apiJsonHeaders(),
                    )
                }
            val cookieStorage = DiscourseCookieStorage()
            val client = createDiscourseHttpClient(engine, cookieStorage)
            val sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage)
            sessionManager.authenticateFixture()
            val dataSource =
                DiscourseDataSource(
                    api =
                        DefaultDiscourseApi(
                            wire = createDiscourseWireTransport(client),
                            sessionManager = sessionManager,
                        ),
                )

            try {
                val editablePost = dataSource.editablePost(postId = 401L)

                assertEquals(401L, editablePost.id)
                assertEquals(42L, editablePost.topicId)
                assertEquals(3, editablePost.postNumber)
                assertEquals("**Fixture**\nbody", editablePost.raw)
            } finally {
                client.close()
            }

            assertEquals(1, requestCount)
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
            val sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage)
            sessionManager.authenticateFixture()
            val api =
                DefaultDiscourseApi(
                    wire = createDiscourseWireTransport(client),
                    sessionManager = sessionManager,
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
            val sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage)
            sessionManager.authenticateFixture()
            val api =
                DefaultDiscourseApi(
                    wire = createDiscourseWireTransport(client),
                    sessionManager = sessionManager,
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
            sessionManager.authenticateFixture()
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
    fun mutationResponsesCannotReplaceRequestedPostTopicActionOrBookmarkIdentity() =
        runTest {
            val engine =
                MockEngine { request ->
                    val response =
                        when (request.url.encodedPath) {
                            "/session/csrf" -> {
                                "{\"csrf\":\"fixture-token\"}"
                            }

                            "/posts.json" -> {
                                "{\"id\":501,\"topic_id\":43,\"post_number\":1}"
                            }

                            "/posts/401.json" -> {
                                """{"id":402,"topic_id":42,"post_number":2,"raw":"Redacted fixture raw"}"""
                            }

                            "/post_actions" -> {
                                """{"id":402,"topic_id":42,"post_number":3,"actions_summary":[{"id":2,"count":1,"acted":true,"can_act":false,"can_undo":true}]}"""
                            }

                            "/post_actions/401" -> {
                                """{"id":401,"topic_id":42,"post_number":3,"actions_summary":[{"id":2,"count":1,"acted":true,"can_act":false,"can_undo":true}]}"""
                            }

                            "/bookmarks.json" -> {
                                "{\"id\":0}"
                            }

                            "/uploads.json" -> {
                                """{"id":601,"short_url":"javascript:fixture-alert","original_filename":"fixture.bin"}"""
                            }

                            else -> {
                                error("Unexpected identity fixture request: ${request.url.encodedPath}")
                            }
                        }
                    respond(content = response, headers = apiJsonHeaders())
                }
            val cookieStorage = DiscourseCookieStorage()
            val client = createDiscourseHttpClient(engine, cookieStorage)
            val sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage)
            sessionManager.authenticateFixture()
            val api =
                DefaultDiscourseApi(
                    wire = createDiscourseWireTransport(client),
                    sessionManager = sessionManager,
                )

            try {
                assertMutationResponseDecodingFailure {
                    api.editablePost(postId = 401L)
                }
                assertMutationResponseDecodingFailure {
                    api.createPost(
                        DiscourseCreatePostRequest(
                            raw = "Redacted fixture reply",
                            topicId = 42L,
                        ),
                    )
                }
                assertMutationResponseDecodingFailure {
                    api.updatePost(
                        postId = 401L,
                        request = DiscourseUpdatePostRequest(raw = "Redacted fixture edit"),
                    )
                }
                assertMutationResponseDecodingFailure {
                    api.createPostAction(
                        DiscoursePostActionRequest(
                            id = 401L,
                            postActionTypeId = 2L,
                        ),
                    )
                }
                assertMutationResponseDecodingFailure {
                    api.deletePostAction(
                        postId = 401L,
                        actionTypeId = 2L,
                    )
                }
                assertMutationResponseDecodingFailure {
                    api.createBookmark(
                        DiscourseCreateBookmarkRequest(
                            bookmarkableId = 42L,
                            bookmarkableType = "Topic",
                        ),
                    )
                }
                assertMutationResponseDecodingFailure {
                    api.upload(
                        DiscourseUploadRequest(
                            bytes = byteArrayOf(1),
                            fileName = "fixture.bin",
                        ),
                    )
                }
            } finally {
                client.close()
            }
        }

    @Test
    fun postActionsRequireOfficialFullPostStateOrExplicitDeleteNoContent() =
        runTest {
            var createCalls = 0
            var deleteCalls = 0
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/session/csrf" -> {
                            respond(
                                content = "{\"csrf\":\"fixture-action-token\"}",
                                headers = apiJsonHeaders(),
                            )
                        }

                        "/post_actions" -> {
                            createCalls += 1
                            val content =
                                when (createCalls) {
                                    1 -> {
                                        // The real endpoint renders a full Post. This fixture keeps
                                        // sensitive-looking unknown fields to prove that the narrow
                                        // acknowledgement DTO discards them while accepting the
                                        // official extensible shape.
                                        """{"id":401,"topic_id":42,"post_number":3,"raw":"unpublished fixture body","cooked":"<p>private fixture</p>","username":"fixture-member","plugin_secret":{"token":"discard-me"},"actions_summary":[{"id":2,"count":5,"acted":true,"can_act":false,"can_undo":true}]}"""
                                    }

                                    2 -> {
                                        "{}"
                                    }

                                    3 -> {
                                        """{"id":401,"topic_id":42,"post_number":3,"actions_summary":[{"id":2,"count":5,"acted":true,"can_act":false,"can_undo":true},{"id":2,"count":5,"acted":true,"can_act":false,"can_undo":true}]}"""
                                    }

                                    else -> {
                                        error("Unexpected create action call")
                                    }
                                }
                            respond(content = content, headers = apiJsonHeaders())
                        }

                        "/post_actions/401" -> {
                            deleteCalls += 1
                            when (deleteCalls) {
                                1 -> {
                                    respond(
                                        content =
                                            """{"id":401,"topic_id":42,"post_number":3,"actions_summary":[{"id":2,"count":4,"acted":false,"can_act":true,"can_undo":false}]}""",
                                        headers = apiJsonHeaders(),
                                    )
                                }

                                2 -> {
                                    respond(
                                        content =
                                            """{"id":401,"topic_id":42,"post_number":3,"actions_summary":[]}""",
                                        headers = apiJsonHeaders(),
                                    )
                                }

                                3 -> {
                                    respond(content = "", status = HttpStatusCode.NoContent)
                                }

                                4 -> {
                                    respond(
                                        content =
                                            """{"id":401,"topic_id":42,"post_number":3,"actions_summary":[{"id":2,"count":4,"acted":false,"can_act":true,"can_undo":false},{"id":2,"count":4,"acted":false,"can_act":true,"can_undo":false}]}""",
                                        headers = apiJsonHeaders(),
                                    )
                                }

                                else -> {
                                    error("Unexpected delete action call")
                                }
                            }
                        }

                        else -> {
                            error("Unexpected action contract request: ${request.url.encodedPath}")
                        }
                    }
                }
            val cookieStorage = DiscourseCookieStorage()
            val client = createDiscourseHttpClient(engine, cookieStorage)
            val sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage)
            sessionManager.authenticateFixture()
            val api = DefaultDiscourseApi(createDiscourseWireTransport(client), sessionManager)
            val createRequest = DiscoursePostActionRequest(id = 401L, postActionTypeId = 2L)

            try {
                val created = api.createPostAction(createRequest)
                assertEquals(401L, created.postId)
                assertEquals(2L, created.postActionTypeId)
                assertTrue(created.acted)
                assertEquals(DiscourseActionResponseKind.FullPost, created.kind)
                assertEquals(5, created.count)
                assertEquals(false, created.canAct)
                assertEquals(true, created.canUndo)

                assertMutationResponseDecodingFailure { api.createPostAction(createRequest) }
                assertMutationResponseDecodingFailure { api.createPostAction(createRequest) }

                val deletedWithSummary = api.deletePostAction(postId = 401L, actionTypeId = 2L)
                assertEquals(DiscourseActionResponseKind.FullPost, deletedWithSummary.kind)
                assertTrue(!deletedWithSummary.acted)
                assertEquals(4, deletedWithSummary.count)
                assertEquals(true, deletedWithSummary.canAct)
                assertEquals(false, deletedWithSummary.canUndo)

                val deletedWithOmittedSummary = api.deletePostAction(postId = 401L, actionTypeId = 2L)
                assertEquals(DiscourseActionResponseKind.FullPost, deletedWithOmittedSummary.kind)
                assertTrue(!deletedWithOmittedSummary.acted)
                assertEquals(0, deletedWithOmittedSummary.count)
                assertEquals(false, deletedWithOmittedSummary.canAct)
                assertEquals(false, deletedWithOmittedSummary.canUndo)

                val deletedWithoutBody = api.deletePostAction(postId = 401L, actionTypeId = 2L)
                assertEquals(DiscourseActionResponseKind.NoContent, deletedWithoutBody.kind)
                assertTrue(!deletedWithoutBody.acted)
                assertEquals(null, deletedWithoutBody.count)
                assertEquals(null, deletedWithoutBody.canAct)
                assertEquals(null, deletedWithoutBody.canUndo)

                assertMutationResponseDecodingFailure {
                    api.deletePostAction(postId = 401L, actionTypeId = 2L)
                }
            } finally {
                client.close()
            }

            assertEquals(3, createCalls)
            assertEquals(4, deleteCalls)
        }

    @Test
    fun enqueuedCreateAndUpdateBecomeModerationFailures() =
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

                        "/posts.json", "/posts/73.json" -> {
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
            val sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage)
            sessionManager.authenticateFixture()
            val api =
                DefaultDiscourseApi(
                    wire = createDiscourseWireTransport(client),
                    sessionManager = sessionManager,
                )

            try {
                val createFailure =
                    assertFailsWith<DiscoursePostEnqueuedException> {
                        api.createPost(
                            DiscourseCreatePostRequest(
                                raw = "Redacted fixture reply",
                                topicId = 42L,
                            ),
                        )
                    }
                val updateFailure =
                    assertFailsWith<DiscoursePostEnqueuedException> {
                        api.updatePost(
                            postId = 73L,
                            request = DiscourseUpdatePostRequest(raw = "Redacted fixture edit"),
                        )
                    }

                listOf(createFailure, updateFailure).forEach { failure ->
                    assertEquals(2, failure.pendingCount)
                    assertEquals(73L, failure.pendingPostId)
                    assertEquals(42L, failure.topicId)
                    assertIs<DiscoursePostEnqueuedException>(failure)
                }
            } finally {
                client.close()
            }
        }

    @Test
    fun rawUploadUsesProtectedClientBodyProgressAndSharedResponseInvariants() =
        runTest {
            val observedProgress = mutableListOf<Pair<Long, Long?>>()
            val consumedBodySizes = mutableListOf<Int>()
            var uploadCount = 0
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/session/csrf" -> {
                            respond(
                                content = "{\"csrf\":\"fixture-upload-token\"}",
                                headers = apiJsonHeaders(),
                            )
                        }

                        "/uploads.json" -> {
                            uploadCount += 1
                            assertEquals(HttpMethod.Post, request.method)
                            assertEquals("fixture-upload-token", request.headers["X-CSRF-Token"])
                            assertEquals("fixture-bus-client", request.url.parameters["client_id"])
                            assertTrue(
                                request.body.contentType
                                    ?.match(ContentType.MultiPart.FormData) == true,
                            )
                            val sentBody = request.body.toByteArray()
                            consumedBodySizes += sentBody.size
                            assertTrue(sentBody.size > UPLOAD_BYTES.size)
                            assertTrue(sentBody.decodeToString().contains("fixture-upload.bin"))
                            respond(
                                content =
                                    when (uploadCount) {
                                        1 -> {
                                            """{"id":601,"short_url":"upload://fixture-token","original_filename":"fixture-upload.bin","filesize":4}"""
                                        }

                                        2 -> {
                                            """{"id":-1,"short_url":"upload://invalid-id","original_filename":"fixture-upload.bin"}"""
                                        }

                                        3 -> {
                                            """{"id":601,"short_url":"data:text/plain,fixture","original_filename":"fixture-upload.bin"}"""
                                        }

                                        4 -> {
                                            """{"id":601,"url":"//untrusted.invalid/uploads/fixture.bin","original_filename":"fixture-upload.bin"}"""
                                        }

                                        else -> {
                                            """{"id":601,"url":"https://untrusted.invalid/uploads/fixture.bin","original_filename":"fixture-upload.bin"}"""
                                        }
                                    },
                                headers = apiJsonHeaders(),
                            )
                        }

                        else -> {
                            error("Unexpected upload fixture request: ${request.url.encodedPath}")
                        }
                    }
                }
            val cookieStorage = DiscourseCookieStorage()
            val client = createDiscourseHttpClient(engine, cookieStorage)
            val sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage)
            sessionManager.authenticateFixture()
            val api =
                DefaultDiscourseApi(
                    wire = createDiscourseWireTransport(client),
                    sessionManager = sessionManager,
                    client = client,
                )
            val request =
                DiscourseUploadRequest(
                    bytes = UPLOAD_BYTES,
                    fileName = "fixture-upload.bin",
                    contentType = ContentType.Application.OctetStream.toString(),
                    messageBusClientId = "fixture-bus-client",
                )

            try {
                val upload =
                    api.upload(request) { bytesSent, contentLength ->
                        observedProgress += bytesSent to contentLength
                    }

                assertEquals(601L, upload.id)
                assertTrue(observedProgress.isNotEmpty())
                assertEquals(consumedBodySizes.first().toLong(), observedProgress.last().first)
                assertTrue(observedProgress.zipWithNext().all { (first, second) -> second.first >= first.first })
                observedProgress.last().second?.let { total ->
                    assertEquals(consumedBodySizes.first().toLong(), total)
                }

                repeat(4) {
                    assertMutationResponseDecodingFailure {
                        api.upload(request) { _, _ -> }
                    }
                }
            } finally {
                client.close()
            }

            assertEquals(5, uploadCount)
        }
}

private const val TOPIC_LIST_FIXTURE: String = """{"topic_list":{"topics":[]}}"""
private const val SEARCH_FIXTURE: String = """{}"""
private const val NOTIFICATION_FIXTURE: String = """{"notifications":[]}"""
private const val POST_STREAM_FIXTURE: String = """{"posts":[]}"""
private const val ENQUEUED_POST_FIXTURE: String =
    """{"action":"enqueued","pending_count":2,"pending_post":{"id":73,"topic_id":42},"topic_id":42}"""
private val UPLOAD_BYTES: ByteArray = byteArrayOf(1, 2, 3, 4)

private suspend fun DiscourseSessionManager.authenticateFixture() {
    startAuthenticatedSession(
        accountId = "fixture-account",
        username = "fixture-member",
    )
}

private suspend fun assertAuthenticationRequired(block: suspend () -> Unit) {
    assertFailsWith<DiscourseAuthenticationException> { block() }
}

private suspend fun assertMutationResponseDecodingFailure(block: suspend () -> Unit) {
    val failure = assertFailsWith<DiscourseSerializationException> { block() }
    assertEquals(DiscourseSerializationPhase.ResponseDecoding, failure.phase)
}

private fun apiJsonHeaders(): Headers =
    Headers.build {
        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }

private fun apiTextHeaders(): Headers =
    Headers.build {
        append(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
    }
