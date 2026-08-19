package dev.dimension.flare.data.network.discourse

import dev.dimension.flare.data.network.discourse.error.DiscourseAuthenticationException
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationException
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationPhase
import dev.dimension.flare.data.network.discourse.paging.DiscourseNotificationOffset
import dev.dimension.flare.data.network.discourse.paging.DiscourseSearchPage
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieStorage
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.parseQueryString
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class Stage6DiscourseApiContractIntegrationTest {
    @Test
    fun searchUsesLogicalPageOneAndPostFilterWhilePreservingOverlappingRows() =
        runTest {
            val observedUrls = mutableListOf<Url>()
            val fixture =
                stage6ApiFixture { request ->
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals("/search.json", request.url.encodedPath)
                    observedUrls += request.url
                    val response =
                        when (request.url.parameters["page"]) {
                            null -> Stage6ApiContractFixtures.SEARCH_PAGE_ONE
                            "2" -> Stage6ApiContractFixtures.SEARCH_PAGE_TWO_WITH_OVERLAP
                            else -> error("Unexpected synthetic search page")
                        }
                    respondFixtureJson(response)
                }

            try {
                val first =
                    fixture.api.search(
                        query = "contract query",
                        page = DiscourseSearchPage(1),
                        type = DiscourseSearchType.Post,
                    )
                val second =
                    fixture.api.search(
                        query = "contract query",
                        page = DiscourseSearchPage(2),
                        type = DiscourseSearchType.Post,
                    )

                assertEquals(listOf(6101L, 6102L), first.posts.map { it.id })
                assertEquals(listOf(6102L, 6103L), second.posts.map { it.id })
                assertEquals(
                    setOf(6102L),
                    first.posts
                        .map { it.id }
                        .toSet()
                        .intersect(second.posts.map { it.id }.toSet()),
                )
                assertTrue(requireNotNull(first.groupedSearchResult).moreFullPageResults)
                assertFalse(requireNotNull(second.groupedSearchResult).moreFullPageResults)
                assertTrue(first.users.isEmpty())
                assertNull(first.posts.first().name)
                assertEquals(0, first.posts.first().likeCount)
            } finally {
                fixture.close()
            }

            assertEquals(listOf(null, "2"), observedUrls.map { it.parameters["page"] })
            assertEquals(listOf("post", "post"), observedUrls.map { it.parameters["type_filter"] })
            assertEquals(listOf("contract query", "contract query"), observedUrls.map { it.parameters["q"] })
        }

    @Test
    fun profileSummaryAndActivityRoutesDecodeForwardCompatibleFixtures() =
        runTest {
            val observedUrls = mutableListOf<Url>()
            val fixture =
                stage6ApiFixture { request ->
                    assertEquals(HttpMethod.Get, request.method)
                    observedUrls += request.url
                    val response =
                        when (request.url.encodedPath) {
                            "/u/fixture-member.json" -> Stage6ApiContractFixtures.USER_PROFILE
                            "/u/fixture-member/summary.json" -> Stage6ApiContractFixtures.USER_SUMMARY
                            "/user_actions.json" -> Stage6ApiContractFixtures.USER_ACTIVITY
                            else -> error("Unexpected synthetic profile request")
                        }
                    respondFixtureJson(response)
                }

            try {
                val profile = fixture.api.user("fixture-member")
                val summary = fixture.api.userSummary("fixture-member")
                val activity =
                    fixture.api.userActions(
                        username = "fixture-member",
                        offset = 20,
                        filter = "5,6",
                    )

                assertEquals(7101L, profile.user.id)
                assertEquals("fixture-member", profile.user.username)
                assertEquals(0, profile.user.trustLevel)
                assertEquals("", profile.user.avatarTemplate)
                assertEquals(4, summary.userSummary.likesGiven)
                assertEquals(12, summary.userSummary.postCount)
                assertEquals(
                    7201L,
                    summary.userSummary.topReplies
                        .single()
                        .id,
                )
                assertEquals(0, summary.userSummary.daysVisited)
                assertEquals(5, activity.userActions.single().actionType)
                assertEquals(7201L, activity.userActions.single().postId)
                assertFalse(activity.userActions.single().closed)
            } finally {
                fixture.close()
            }

            assertEquals(
                listOf(
                    "/u/fixture-member.json",
                    "/u/fixture-member/summary.json",
                    "/user_actions.json",
                ),
                observedUrls.map { it.encodedPath },
            )
            val activityUrl = observedUrls.last()
            assertEquals("fixture-member", activityUrl.parameters["username"])
            assertEquals("20", activityUrl.parameters["offset"])
            assertEquals("5,6", activityUrl.parameters["filter"])
        }

    @Test
    fun missingRequiredAccountAndNotificationFieldsFailAtTransportBoundary() =
        runTest {
            var networkCalls = 0
            val fixture =
                stage6ApiFixture { request ->
                    networkCalls += 1
                    val response =
                        when (request.url.encodedPath) {
                            "/u/missing-user.json" -> {
                                Stage6ApiContractFixtures.USER_MISSING_USERNAME
                            }

                            "/u/missing-summary/summary.json" -> {
                                Stage6ApiContractFixtures.SUMMARY_MISSING_REQUIRED_ENVELOPE
                            }

                            "/user_actions.json" -> {
                                Stage6ApiContractFixtures.ACTIVITY_MISSING_CREATED_AT
                            }

                            "/notifications" -> {
                                Stage6ApiContractFixtures.NOTIFICATION_MISSING_TYPE
                            }

                            else -> {
                                error("Unexpected malformed fixture request")
                            }
                        }
                    respondFixtureJson(response)
                }
            fixture.authenticate()

            try {
                assertResponseDecodingFailure { fixture.api.user("missing-user") }
                assertResponseDecodingFailure { fixture.api.userSummary("missing-summary") }
                assertResponseDecodingFailure { fixture.api.userActions("missing-activity") }
                assertResponseDecodingFailure { fixture.api.notifications() }
            } finally {
                fixture.close()
            }

            assertEquals(4, networkCalls)
        }

    @Test
    fun guestNotificationsAndMarkReadAreRejectedBeforeAnyNetworkAccess() =
        runTest {
            var networkCalls = 0
            val fixture =
                stage6ApiFixture {
                    networkCalls += 1
                    error("Guest notification APIs must be rejected before transport")
                }

            try {
                assertFailsWith<DiscourseAuthenticationException> {
                    fixture.api.notifications()
                }
                assertFailsWith<DiscourseAuthenticationException> {
                    fixture.api.markNotificationsRead(notificationId = 8102L)
                }
            } finally {
                fixture.close()
            }

            assertEquals(0, networkCalls)
        }

    @Test
    fun authenticatedNotificationsUseOffsetsAndMarkReadUsesCsrf() =
        runTest {
            val notificationUrls = mutableListOf<Url>()
            var csrfRequests = 0
            var markReadRequests = 0
            val fixture =
                stage6ApiFixture { request ->
                    when (request.url.encodedPath) {
                        "/notifications" -> {
                            assertEquals(HttpMethod.Get, request.method)
                            notificationUrls += request.url
                            val response =
                                when (request.url.parameters["offset"]) {
                                    null -> Stage6ApiContractFixtures.NOTIFICATIONS_FIRST_PAGE
                                    "2" -> Stage6ApiContractFixtures.NOTIFICATIONS_SECOND_PAGE_WITH_OVERLAP
                                    else -> error("Unexpected synthetic notification offset")
                                }
                            respondFixtureJson(response)
                        }

                        "/session/csrf" -> {
                            csrfRequests += 1
                            respondFixtureJson(Stage6ApiContractFixtures.CSRF)
                        }

                        "/notifications/mark-read" -> {
                            markReadRequests += 1
                            assertEquals(HttpMethod.Put, request.method)
                            assertEquals("fixture-csrf-token", request.headers["X-CSRF-Token"])
                            val encodedBody =
                                (request.body as OutgoingContent.ByteArrayContent)
                                    .bytes()
                                    .decodeToString()
                            assertEquals("8102", parseQueryString(encodedBody)["id"])
                            respond(content = "", status = HttpStatusCode.NoContent)
                        }

                        else -> {
                            error("Unexpected authenticated notification request")
                        }
                    }
                }
            fixture.authenticate()

            try {
                val first = fixture.api.notifications(limit = 2)
                val second =
                    fixture.api.notifications(
                        offset = DiscourseNotificationOffset(2),
                        limit = 2,
                    )
                fixture.api.markNotificationsRead(notificationId = 8102L)

                assertEquals(listOf(8103L, 8102L), first.notifications.map { it.id })
                assertEquals(listOf(8102L, 8101L), second.notifications.map { it.id })
                assertEquals(
                    setOf(8102L),
                    first.notifications
                        .map { it.id }
                        .toSet()
                        .intersect(second.notifications.map { it.id }.toSet()),
                )
                assertEquals(3, first.totalRowsNotifications)
                assertEquals(8101L, first.seenNotificationId)
                assertFalse(first.notifications.first().read)
                assertTrue(second.notifications.last().read)
            } finally {
                fixture.close()
            }

            assertEquals(listOf(null, "2"), notificationUrls.map { it.parameters["offset"] })
            assertEquals(listOf("2", "2"), notificationUrls.map { it.parameters["limit"] })
            assertEquals(1, csrfRequests)
            assertEquals(1, markReadRequests)
        }
}

private data class Stage6ApiFixture(
    val api: DiscourseApi,
    val sessionManager: DiscourseSessionManager,
    val client: HttpClient,
) {
    suspend fun authenticate() {
        sessionManager.startAuthenticatedSession(
            accountId = "7101",
            username = "fixture-member",
        )
    }

    fun close() {
        client.close()
    }
}

private fun stage6ApiFixture(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): Stage6ApiFixture {
    val cookieStorage = DiscourseCookieStorage()
    val sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage)
    val client = createDiscourseHttpClient(MockEngine(handler), cookieStorage)
    return Stage6ApiFixture(
        api =
            DefaultDiscourseApi(
                wire = createDiscourseWireTransport(client),
                sessionManager = sessionManager,
            ),
        sessionManager = sessionManager,
        client = client,
    )
}

private suspend fun assertResponseDecodingFailure(block: suspend () -> Unit) {
    val failure = assertFailsWith<DiscourseSerializationException> { block() }
    assertEquals(DiscourseSerializationPhase.ResponseDecoding, failure.phase)
}

private fun MockRequestHandleScope.respondFixtureJson(content: String): HttpResponseData =
    respond(
        content = content,
        headers =
            Headers.build {
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            },
    )
