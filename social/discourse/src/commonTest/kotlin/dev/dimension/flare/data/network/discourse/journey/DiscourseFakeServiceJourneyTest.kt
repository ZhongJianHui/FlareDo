package dev.dimension.flare.data.network.discourse.journey

import dev.dimension.flare.data.network.discourse.DefaultDiscourseApi
import dev.dimension.flare.data.network.discourse.DiscourseApi
import dev.dimension.flare.data.network.discourse.DiscourseDataSource
import dev.dimension.flare.data.network.discourse.DiscourseUploadRequest
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthRedirectProcessor
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthTokenGenerator
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthorizationCoordinator
import dev.dimension.flare.data.network.discourse.auth.DiscourseCloudflareChallengeHandler
import dev.dimension.flare.data.network.discourse.auth.DiscourseLoginResult
import dev.dimension.flare.data.network.discourse.auth.DiscourseLoginService
import dev.dimension.flare.data.network.discourse.auth.DiscourseOtpSessionExchangeTransport
import dev.dimension.flare.data.network.discourse.auth.DiscourseRsaPkcs1Decryptor
import dev.dimension.flare.data.network.discourse.auth.DiscourseRsaPkcs1KeyPair
import dev.dimension.flare.data.network.discourse.auth.DiscourseRsaPkcs1KeyPairGenerator
import dev.dimension.flare.data.network.discourse.auth.DiscourseWebSessionCookieBridge
import dev.dimension.flare.data.network.discourse.auth.MemoryDiscourseAuthAttemptStore
import dev.dimension.flare.data.network.discourse.composer.DefaultDiscourseComposerRepository
import dev.dimension.flare.data.network.discourse.composer.DefaultDiscoursePostActionRepository
import dev.dimension.flare.data.network.discourse.composer.DiscourseActionTarget
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerPresenter
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerSubmitStatus
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerUploadStatus
import dev.dimension.flare.data.network.discourse.composer.MemoryDiscourseDraftStore
import dev.dimension.flare.data.network.discourse.content.DiscourseCookedHtmlParser
import dev.dimension.flare.data.network.discourse.createDiscourseHttpClient
import dev.dimension.flare.data.network.discourse.createDiscourseWireTransport
import dev.dimension.flare.data.network.discourse.forum.DefaultDiscourseForumAccountRepository
import dev.dimension.flare.data.network.discourse.forum.DefaultDiscourseForumRepository
import dev.dimension.flare.data.network.discourse.forum.DefaultDiscourseForumSearchRepository
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumAccountMapper
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumAction
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumDestination
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumMapper
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumPresenter
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumSearchMapper
import dev.dimension.flare.data.network.discourse.forum.MemoryDiscourseForumCache
import dev.dimension.flare.data.network.discourse.realtime.DefaultDiscourseMessageBusTransport
import dev.dimension.flare.data.network.discourse.realtime.DiscourseMessageBus
import dev.dimension.flare.data.network.discourse.realtime.DiscourseMessageBusClientIdFactory
import dev.dimension.flare.data.network.discourse.realtime.DiscourseMessageBusDelay
import dev.dimension.flare.data.network.discourse.realtime.DiscourseMessageBusMonotonicClock
import dev.dimension.flare.data.network.discourse.realtime.DiscourseRealtimeCoordinator
import dev.dimension.flare.data.network.discourse.realtime.MemoryDiscourseMessageBusCursorStore
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieSnapshot
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieStorage
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionLifecycle
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionState
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionStore
import dev.dimension.flare.data.network.discourse.session.PersistedDiscourseSession
import dev.dimension.flare.data.network.discourse.session.SecureCredentialRef
import dev.dimension.flare.data.network.discourse.session.SessionOnlySecureCredentialStore
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
import io.ktor.http.URLBuilder
import io.ktor.http.content.OutgoingContent
import io.ktor.http.parseQueryString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end contract journeys against a self-authored, process-local Linux.do service.
 *
 * These tests deliberately avoid narrow fake repositories. Each journey enters through a shared
 * presenter or login facade, crosses production repositories, [DiscourseDataSource], the protected
 * Ktor client, and [DefaultDiscourseApi], then returns through the same mapping/session boundaries
 * used by all five hosts. The service rejects every unknown method/path pair, which makes a route
 * change or an accidental production request fail at the exact protocol boundary.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalEncodingApi::class)
internal class DiscourseFakeServiceJourneyTest {
    @Test
    fun guestLatestListOpensAuthoritativeTopicStream() =
        runTest {
            val service = FakeLinuxDoService()
            val graph = service.forumGraph()
            val presenter = graph.presenter(StandardTestDispatcher(testScheduler))
            val models = presenter.models

            try {
                val initialState =
                    models.first { state ->
                        !state.isFeedLoading && !state.isTaxonomyLoading
                    }

                // A terminal loading state can also represent a failed request. Keep those
                // assertions explicit so this synchronization cannot turn a service regression
                // into a passing empty-state journey.
                assertNull(initialState.feedFailure)
                assertNull(initialState.taxonomyFailure)
                assertFalse(initialState.isAuthenticated)
                assertEquals(
                    listOf(42L),
                    initialState.topics.map { topic -> assertNotNull(topic.discourse).ref.topicId },
                )
                assertEquals(
                    "Development",
                    initialState.categories
                        .single()
                        .name,
                )
                assertEquals(
                    "Kotlin",
                    initialState.tags
                        .single()
                        .name,
                )

                assertTrue(presenter.dispatch(DiscourseForumAction.OpenTopic(topicId = 42L)))
                val topicState =
                    models.first { state ->
                        !state.isTopicLoading &&
                            (state.selectedTopic != null || state.topicFailure != null)
                    }

                assertNull(topicState.topicFailure)
                val topic = assertNotNull(topicState.selectedTopic)
                assertEquals(42L, topic.topicId)
                assertEquals(
                    listOf(401L, 402L),
                    topic.articles.map { article -> assertNotNull(article.discourse).postId },
                )
                assertEquals(
                    2,
                    topic.articles
                        .single { it.discourse?.postId == 402L }
                        .discourse
                        ?.postNumber,
                )

                val streamRequest =
                    service
                        .snapshot()
                        .requestsFor(HttpMethod.Get, "/t/42/posts.json")
                        .single()
                assertEquals(listOf("401", "402"), streamRequest.query["post_ids[]"])
                assertNull(streamRequest.query["include_suggested"])
            } finally {
                presenter.closeAndJoin()
                service.close()
            }
        }

    @Test
    fun searchResultNavigatesToItsExactTopicPost() =
        runTest {
            val service = FakeLinuxDoService()
            val graph = service.forumGraph()
            val presenter = graph.presenter(StandardTestDispatcher(testScheduler))
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertTrue(
                    presenter.dispatch(
                        DiscourseForumAction.SelectDestination(DiscourseForumDestination.Search),
                    ),
                )
                assertTrue(presenter.dispatch(DiscourseForumAction.UpdateSearchQuery("journey")))
                assertTrue(presenter.dispatch(DiscourseForumAction.SubmitSearch))
                val searchState =
                    models.first { state ->
                        !state.search.isLoading &&
                            (state.search.items.isNotEmpty() || state.search.failure != null)
                    }

                assertNull(searchState.search.failure)
                val hit = searchState.search.items.single()
                assertEquals(402L, hit.postId)
                assertEquals(42L, hit.topic.topicId)
                assertEquals(2, hit.topic.postNumber)
                assertTrue(
                    presenter.dispatch(
                        DiscourseForumAction.OpenTopic(
                            topicId = hit.topic.topicId,
                            postNumber = hit.topic.postNumber,
                        ),
                    ),
                )
                val topicState =
                    models.first { state ->
                        !state.isTopicLoading &&
                            (state.selectedTopic != null || state.topicFailure != null)
                    }

                assertNull(topicState.topicFailure)
                assertEquals(42L, topicState.selectedTopicId)
                assertEquals(2, topicState.selectedPostNumber)
                assertEquals(
                    402L,
                    topicState.selectedTopic
                        ?.articles
                        ?.single { it.discourse?.postNumber == 2 }
                        ?.discourse
                        ?.postId,
                )

                val search =
                    service
                        .snapshot()
                        .requestsFor(HttpMethod.Get, "/search.json")
                        .single()
                assertEquals(listOf("journey"), search.query["q"])
                assertEquals(listOf("post"), search.query["type_filter"])
                assertNull(search.query["page"], "Logical search page one must be omitted on the wire")
            } finally {
                presenter.closeAndJoin()
                service.close()
            }
        }

    @Test
    fun authorizationRedirectExchangesOtpAndActivatesPersistedSession() =
        runTest {
            val service = FakeLinuxDoService()
            val login = service.loginFixture()

            try {
                val pending = login.service.beginAuthorization()
                val attempt = assertNotNull(login.attemptStore.peek())
                assertEquals("https", pending.url.protocol.name)
                assertEquals("linux.do", pending.url.host)
                assertEquals("/user-api-key/new", pending.url.encodedPath)
                assertEquals("token-2", attempt.nonce)
                assertEquals("token-3", attempt.clientId)

                val callback = authorizationRedirect(attempt.nonce)
                val result = assertIs<DiscourseLoginResult.Authenticated>(login.service.completeRedirect(callback))

                assertEquals("7101", result.accountId)
                assertEquals("fixture-member", result.username)
                val session = assertIs<DiscourseSessionState.Authenticated>(service.sessionManager.state.value)
                assertEquals("7101", session.accountId)
                assertEquals(1L, session.generation)
                assertEquals(
                    "journey-session",
                    service.cookieStorage
                        .snapshot()
                        .single { it.name == "_t" }
                        .value,
                )
                assertEquals("7101", assertNotNull(login.sessionStore.persisted).accountId)
                assertNull(login.attemptStore.peek())
                assertNull(login.credentialStore.load(attempt.privateKeyRef))

                assertEquals(
                    listOf(
                        "/session/csrf",
                        "/session/otp/$JOURNEY_OTP",
                        "/user-api-key/revoke",
                        "/session/current.json",
                    ),
                    service.snapshot().requestPaths,
                )
            } finally {
                login.close()
                service.close()
            }
        }

    @Test
    fun replyUploadSubmitLikeAndBookmarkCrossProductionComposerPath() =
        runTest {
            val service = FakeLinuxDoService()
            service.authenticateFixture()
            val topic = service.forumGraph().repository.loadTopic(42L)
            val sourceArticle = topic.articles.first { it.discourse?.postId == 401L }
            val draftStore = MemoryDiscourseDraftStore()
            val actionRepository =
                DefaultDiscoursePostActionRepository(
                    dataSource = service.dataSource,
                    sessionManager = service.sessionManager,
                )
            val presenter =
                DiscourseComposerPresenter(
                    repository =
                        DefaultDiscourseComposerRepository(
                            dataSource = service.dataSource,
                            draftStore = draftStore,
                            sessionManager = service.sessionManager,
                        ),
                    draftStore = draftStore,
                    postActionRepository = actionRepository,
                    sessionManager = service.sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    nowEpochMillis = { 2_000L },
                    autosaveDelayMillis = 0L,
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertTrue(presenter.openReply(topicId = 42L, replyToPostNumber = 1))
                models.first { state -> state.canEdit && state.target != null }
                assertTrue(presenter.updateDraft(title = null, raw = "Journey reply"))
                models.first { state -> state.raw == "Journey reply" && state.draftRevision != null }

                assertTrue(
                    presenter.startUpload(
                        DiscourseUploadRequest(
                            bytes = byteArrayOf(1, 2, 3, 4),
                            fileName = "journey.png",
                            contentType = "image/png",
                            messageBusClientId = MESSAGE_BUS_CLIENT_ID,
                        ),
                    ),
                )
                val uploadState =
                    models.first { state ->
                        state.upload.status == DiscourseComposerUploadStatus.Succeeded ||
                            state.upload.status == DiscourseComposerUploadStatus.Failed
                    }

                assertEquals(DiscourseComposerUploadStatus.Succeeded, uploadState.upload.status)
                assertTrue(uploadState.raw.contains("upload://journey.png"))
                assertTrue(presenter.submit())
                val submitState =
                    models.first { state ->
                        state.submitStatus == DiscourseComposerSubmitStatus.Published ||
                            state.submitStatus == DiscourseComposerSubmitStatus.Failed
                    }

                assertEquals(DiscourseComposerSubmitStatus.Published, submitState.submitStatus)
                assertEquals(501L, submitState.publishedPost?.postId)
                assertNull(draftStore.load("7101", assertNotNull(submitState.target)))

                assertTrue(presenter.synchronizePostActions(sourceArticle))
                models.first { state -> state.postActions.isNotEmpty() }
                assertTrue(presenter.toggleLike(postId = 401L))
                models.first { state ->
                    state.postActions.singleOrNull()?.let { it.liked && !it.isLikeInFlight } == true
                }
                assertTrue(presenter.togglePostBookmark(postId = 401L))
                val actionState =
                    models.first { state ->
                        state.postActions.singleOrNull()?.let {
                            it.bookmarked && !it.isBookmarkInFlight
                        } == true
                    }

                val actions = actionState.postActions.single()
                assertEquals(DiscourseActionTarget.Post(401L), actions.target)
                assertTrue(actions.liked)
                assertEquals(1, actions.likeCount)
                assertTrue(actions.bookmarked)
                assertEquals(901L, actions.bookmarkId)
                assertEquals(
                    listOf(
                        "/session/csrf",
                        "/uploads.json",
                        "/posts.json",
                        "/post_actions",
                        "/bookmarks.json",
                    ),
                    service
                        .snapshot()
                        .requestPaths
                        .filter { it in WRITE_JOURNEY_PATHS },
                )
            } finally {
                presenter.closeAndFlush()
                runCurrent()
                service.close()
            }
        }

    @Test
    fun messageBusNotificationRefreshesRestThenLogoutClearsEverySessionOwner() =
        runTest {
            val service = FakeLinuxDoService()
            val sessionStore = JourneySessionStore()
            val lifecycle = DiscourseSessionLifecycle(service.sessionManager, sessionStore)
            lifecycle.activate(
                expectedGeneration = 0L,
                accountId = "7101",
                username = "fixture-member",
                cookies = authenticatedCookies(),
            )
            val login = service.loginFixture(sessionStore = sessionStore, sessionLifecycle = lifecycle)
            val transport = DefaultDiscourseMessageBusTransport(service.client)
            val neverReconnect = DiscourseMessageBusDelay { awaitCancellation() }
            val messageBus =
                DiscourseMessageBus(
                    transport = transport,
                    cursorStore = MemoryDiscourseMessageBusCursorStore(),
                    clientIdFactory = DiscourseMessageBusClientIdFactory { MESSAGE_BUS_CLIENT_ID },
                    retryDelay = neverReconnect,
                    monotonicClock = DiscourseMessageBusMonotonicClock { 0L },
                )
            val coordinator =
                DiscourseRealtimeCoordinator(
                    sessionManager = service.sessionManager,
                    messageBus = messageBus,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    retryDelay = neverReconnect,
                )
            val graph = service.forumGraph()
            val presenter = graph.presenter(StandardTestDispatcher(testScheduler), coordinator)
            val models = presenter.models

            try {
                presenter.setForeground(true)
                val realtimeState =
                    models.first { state ->
                        state.notifications.snapshot?.unreadCount == 1
                    }

                val notifications = assertNotNull(realtimeState.notifications.snapshot)
                assertEquals(1, notifications.unreadCount)
                assertEquals(8101L, notifications.items.single().id)
                val realtimeFixture = service.snapshot()
                assertEquals(1, realtimeFixture.authenticatedMessageBusPollCount)
                assertTrue(realtimeFixture.requestsFor(HttpMethod.Get, "/notifications").size >= 2)

                // Backgrounding first cancels the authenticated long-poll branch and prevents a
                // guest replacement subscription from obscuring the logout cleanup assertion.
                presenter.setForeground(false)
                runCurrent()
                val pollsBeforeLogout = service.snapshot().authenticatedMessageBusPollCount

                val owner = assertIs<DiscourseSessionState.Authenticated>(service.sessionManager.state.value)
                assertTrue(login.service.logout(owner.generation, owner.accountId))
                val loggedOutState =
                    models.first { state ->
                        !state.isAuthenticated && state.notifications.snapshot == null
                    }

                assertIs<DiscourseSessionState.Guest>(service.sessionManager.state.value)
                assertTrue(service.cookieStorage.snapshot().isEmpty())
                assertNull(sessionStore.persisted)
                assertEquals(1, login.cookieBridge.clearCount)
                assertNull(loggedOutState.notifications.snapshot)
                val loggedOutFixture = service.snapshot()
                assertEquals(pollsBeforeLogout, loggedOutFixture.authenticatedMessageBusPollCount)
                assertEquals(1, loggedOutFixture.requestsFor(HttpMethod.Delete, "/session/fixture-member").size)
            } finally {
                presenter.closeAndJoin()
                transport.close()
                login.close()
                service.close()
            }
        }
}

private data class JourneyForumGraph(
    val repository: DefaultDiscourseForumRepository,
    val searchRepository: DefaultDiscourseForumSearchRepository,
    val accountRepository: DefaultDiscourseForumAccountRepository,
    val sessionManager: DiscourseSessionManager,
) {
    fun presenter(
        dispatcher: CoroutineDispatcher,
        realtimeCoordinator: DiscourseRealtimeCoordinator? = null,
    ): DiscourseForumPresenter =
        DiscourseForumPresenter(
            repository = repository,
            searchRepository = searchRepository,
            accountRepository = accountRepository,
            sessionManager = sessionManager,
            realtimeCoordinator = realtimeCoordinator,
            dispatcher = dispatcher,
        )
}

private fun FakeLinuxDoService.forumGraph(): JourneyForumGraph {
    val parser = DiscourseCookedHtmlParser()
    return JourneyForumGraph(
        repository =
            DefaultDiscourseForumRepository(
                dataSource = dataSource,
                mapper = DiscourseForumMapper(parser),
                cache = MemoryDiscourseForumCache(),
                sessionManager = sessionManager,
                nowEpochMillis = { 1_000L },
            ),
        searchRepository =
            DefaultDiscourseForumSearchRepository(
                dataSource = dataSource,
                mapper = DiscourseForumSearchMapper(parser),
                sessionManager = sessionManager,
            ),
        accountRepository =
            DefaultDiscourseForumAccountRepository(
                dataSource = dataSource,
                mapper = DiscourseForumAccountMapper(parser),
                sessionManager = sessionManager,
            ),
        sessionManager = sessionManager,
    )
}

/** One strict synthetic service shared by REST, OTP exchange, upload, and MessageBus transports. */
private class FakeLinuxDoService : AutoCloseable {
    val cookieStorage = DiscourseCookieStorage(nowEpochMillis = { 10_000L })
    val sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage)

    // Ktor's MockEngine executes handlers on its engine dispatcher, so initial REST loads,
    // MessageBus refreshes, and test assertions can touch fixture state from different threads.
    // A common coroutine mutex keeps every mutable observation race-free across all KMP targets.
    private val fixtureStateMutex = Mutex()
    private val requests: MutableList<JourneyRequest> = mutableListOf()
    private var authenticatedMessageBusPollCount: Int = 0
    private var notificationAvailable: Boolean = false
    private val engine = MockEngine { request -> handle(request) }
    val client: HttpClient = createDiscourseHttpClient(engine, cookieStorage)
    val api: DiscourseApi =
        DefaultDiscourseApi(
            wire = createDiscourseWireTransport(client),
            sessionManager = sessionManager,
            client = client,
        )
    val dataSource: DiscourseDataSource = DiscourseDataSource(api)

    suspend fun authenticateFixture() {
        sessionManager.startAuthenticatedSession(
            accountId = "7101",
            username = "fixture-member",
            cookieSnapshot = authenticatedCookies(),
        )
    }

    suspend fun snapshot(): JourneyServiceSnapshot =
        fixtureStateMutex.withLock {
            JourneyServiceSnapshot(
                requests = requests.toList(),
                authenticatedMessageBusPollCount = authenticatedMessageBusPollCount,
            )
        }

    override fun close() {
        client.close()
    }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        check(request.url.protocol.name == "https") { "Journey request escaped HTTPS" }
        check(request.url.host == "linux.do") { "Journey request escaped linux.do" }
        check(request.url.port == 443) { "Journey request escaped the fixed Linux.do port" }

        val body = request.bodyTextOrNull()
        val recorded =
            JourneyRequest(
                method = request.method,
                path = request.url.encodedPath,
                query =
                    request.url.parameters
                        .entries()
                        .associate { it.key to it.value.toList() },
                headers =
                    request.headers
                        .entries()
                        .associate { it.key to it.value.toList() },
                body = body,
            )
        fixtureStateMutex.withLock {
            requests += recorded
        }

        return when (request.method to recorded.path) {
            HttpMethod.Get to "/categories.json" -> {
                respondJson(CATEGORIES_FIXTURE)
            }

            HttpMethod.Get to "/tags.json" -> {
                respondJson(TAGS_FIXTURE)
            }

            HttpMethod.Get to "/latest.json" -> {
                check(recorded.query["page"] == null) { "Journey latest starts at page zero" }
                respondJson(TOPIC_LIST_FIXTURE)
            }

            HttpMethod.Get to "/hot.json" -> {
                respondJson(TOPIC_LIST_FIXTURE)
            }

            HttpMethod.Get to "/t/42.json" -> {
                respondJson(TOPIC_DETAIL_FIXTURE)
            }

            HttpMethod.Get to "/t/42/posts.json" -> {
                check(recorded.query["post_ids[]"] == listOf("401", "402")) {
                    "Topic batch must follow post_stream.stream exactly"
                }
                check(recorded.query["include_suggested"] == null)
                respondJson(TOPIC_POSTS_FIXTURE)
            }

            HttpMethod.Get to "/search.json" -> {
                check(recorded.query["q"] == listOf("journey"))
                check(recorded.query["type_filter"] == listOf("post"))
                check(recorded.query["page"] == null) { "Search page one must be omitted" }
                respondJson(SEARCH_FIXTURE)
            }

            HttpMethod.Get to "/notifications" -> {
                recorded.requireAuthenticated()
                check(recorded.query["offset"] == null)
                check(recorded.query["limit"] == listOf("60"))
                val notificationFixture =
                    fixtureStateMutex.withLock {
                        if (notificationAvailable) NOTIFICATION_FIXTURE else EMPTY_NOTIFICATIONS_FIXTURE
                    }
                respondJson(notificationFixture)
            }

            HttpMethod.Get to "/session/csrf" -> {
                respondJson(CSRF_FIXTURE)
            }

            HttpMethod.Post to "/session/otp/$JOURNEY_OTP" -> {
                check(recorded.header("X-CSRF-Token") == "journey-csrf")
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers =
                        Headers.build {
                            append(HttpHeaders.SetCookie, "_t=journey-session; Path=/; Secure; HttpOnly")
                        },
                )
            }

            HttpMethod.Post to "/user-api-key/revoke" -> {
                recorded.requireAuthenticated()
                check(recorded.header("User-Api-Key") == JOURNEY_API_KEY)
                respondJson("{}")
            }

            HttpMethod.Get to "/session/current.json" -> {
                recorded.requireAuthenticated()
                respondJson(CURRENT_SESSION_FIXTURE)
            }

            HttpMethod.Post to "/uploads.json" -> {
                recorded.requireAuthenticatedMutation()
                check(recorded.query["client_id"] == listOf(MESSAGE_BUS_CLIENT_ID))
                respondJson(UPLOAD_FIXTURE)
            }

            HttpMethod.Post to "/posts.json" -> {
                recorded.requireAuthenticatedMutation()
                val form = parseQueryString(checkNotNull(recorded.body))
                check(form["topic_id"] == "42")
                check(form["reply_to_post_number"] == "1")
                check(form["raw"]?.contains("upload://journey.png") == true)
                respondJson(REPLY_FIXTURE)
            }

            HttpMethod.Post to "/post_actions" -> {
                recorded.requireAuthenticatedMutation()
                val form = parseQueryString(checkNotNull(recorded.body))
                check(form["id"] == "401")
                check(form["post_action_type_id"] == "2")
                respondJson(LIKED_POST_FIXTURE)
            }

            HttpMethod.Post to "/bookmarks.json" -> {
                recorded.requireAuthenticatedMutation()
                val form = parseQueryString(checkNotNull(recorded.body))
                check(form["bookmarkable_id"] == "401")
                check(form["bookmarkable_type"] == "Post")
                respondJson(BOOKMARK_FIXTURE)
            }

            HttpMethod.Post to "/message-bus/$MESSAGE_BUS_CLIENT_ID/poll" -> {
                recorded.requireAuthenticated()
                val pollBody = checkNotNull(recorded.body)
                check(pollBody.contains("\"/notification/7101\""))
                fixtureStateMutex.withLock {
                    authenticatedMessageBusPollCount += 1
                    notificationAvailable = true
                }
                respondJson(MESSAGE_BUS_FIXTURE)
            }

            HttpMethod.Delete to "/session/fixture-member" -> {
                recorded.requireAuthenticatedMutation()
                respond(content = "", status = HttpStatusCode.NoContent)
            }

            else -> {
                error("Unexpected journey request: ${request.method.value} ${recorded.path}")
            }
        }
    }
}

private data class JourneyRequest(
    val method: HttpMethod,
    val path: String,
    val query: Map<String, List<String>>,
    val headers: Map<String, List<String>>,
    val body: String?,
) {
    fun header(name: String): String? {
        val values =
            headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
        return values?.singleOrNull()
    }

    fun requireAuthenticated() {
        check(header(HttpHeaders.Cookie)?.contains("_t=journey-session") == true) {
            "Authenticated journey request omitted the Linux.do session cookie"
        }
    }

    fun requireAuthenticatedMutation() {
        requireAuthenticated()
        check(header("X-CSRF-Token") == "journey-csrf") {
            "Unsafe journey request omitted the in-memory CSRF token"
        }
    }
}

private data class JourneyServiceSnapshot(
    val requests: List<JourneyRequest>,
    val authenticatedMessageBusPollCount: Int,
) {
    val requestPaths: List<String> = requests.map(JourneyRequest::path)

    fun requestsFor(
        method: HttpMethod,
        path: String,
    ): List<JourneyRequest> = requests.filter { it.method == method && it.path == path }
}

private fun HttpRequestData.bodyTextOrNull(): String? =
    (body as? OutgoingContent.ByteArrayContent)
        ?.bytes()
        ?.decodeToString()

private fun MockRequestHandleScope.respondJson(
    content: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData =
    respond(
        content = content,
        status = status,
        headers =
            Headers.build {
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            },
    )

private data class JourneyLoginFixture(
    val service: DiscourseLoginService,
    val attemptStore: MemoryDiscourseAuthAttemptStore,
    val credentialStore: SessionOnlySecureCredentialStore,
    val sessionStore: JourneySessionStore,
    val cookieBridge: RecordingCookieBridge,
) : AutoCloseable {
    override fun close() {
        credentialStore.close()
    }
}

private fun FakeLinuxDoService.loginFixture(
    sessionStore: JourneySessionStore = JourneySessionStore(),
    sessionLifecycle: DiscourseSessionLifecycle = DiscourseSessionLifecycle(sessionManager, sessionStore),
): JourneyLoginFixture {
    val credentialStore = SessionOnlySecureCredentialStore()
    val attemptStore = MemoryDiscourseAuthAttemptStore()
    var tokenIndex = 0
    val coordinator =
        DiscourseAuthorizationCoordinator(
            keyPairGenerator =
                DiscourseRsaPkcs1KeyPairGenerator { minimumKeySizeBits ->
                    check(minimumKeySizeBits == 2_048)
                    DiscourseRsaPkcs1KeyPair(
                        publicKeySpkiPem = fakePublicKeyPem(),
                        privateKeyPkcs8 = ByteArray(256) { 0x5a },
                    )
                },
            tokenGenerator =
                DiscourseAuthTokenGenerator { byteCount ->
                    check(byteCount == 32)
                    "token-${++tokenIndex}"
                },
            credentialStore = credentialStore,
            attemptStore = attemptStore,
            nowEpochMillis = { 10_000L },
        )
    val processor =
        DiscourseAuthRedirectProcessor(
            attemptStore = attemptStore,
            credentialStore = credentialStore,
            decryptor = DiscourseRsaPkcs1Decryptor { _, ciphertext -> ciphertext.copyOf() },
            nowEpochMillis = { 10_001L },
        )
    val cookieBridge = RecordingCookieBridge()
    val loginService =
        DiscourseLoginService(
            authorizationCoordinator = coordinator,
            redirectProcessor = processor,
            exchangeTransport =
                DiscourseOtpSessionExchangeTransport(
                    client = client,
                    sessionManager = sessionManager,
                    challengeHandler = DiscourseCloudflareChallengeHandler { false },
                ),
            sessionLifecycle = sessionLifecycle,
            sessionManager = sessionManager,
            cookieBridge = cookieBridge,
            api = api,
        )
    return JourneyLoginFixture(
        service = loginService,
        attemptStore = attemptStore,
        credentialStore = credentialStore,
        sessionStore = sessionStore,
        cookieBridge = cookieBridge,
    )
}

private class JourneySessionStore : DiscourseSessionStore {
    var persisted: PersistedDiscourseSession? = null
        private set
    private var nextReference: Long = 1L

    override suspend fun replace(
        accountId: String,
        username: String?,
        cookies: List<DiscourseCookieSnapshot>,
    ): SecureCredentialRef {
        val reference = SecureCredentialRef("journey-session-ref-${nextReference++}")
        persisted =
            PersistedDiscourseSession(
                credentialRef = reference,
                accountId = accountId,
                username = username,
                cookies = cookies.toList(),
            )
        return reference
    }

    override suspend fun restore(): PersistedDiscourseSession? = persisted

    override suspend fun clear(expectedCredentialRef: SecureCredentialRef?) {
        if (expectedCredentialRef == null || persisted?.credentialRef == expectedCredentialRef) {
            persisted = null
        }
    }
}

private class RecordingCookieBridge : DiscourseWebSessionCookieBridge {
    var clearCount: Int = 0
        private set

    override suspend fun snapshotLinuxDoCookies(): List<DiscourseCookieSnapshot> = emptyList()

    override suspend fun clearLinuxDoCookies() {
        clearCount += 1
    }
}

private fun authenticatedCookies(): List<DiscourseCookieSnapshot> =
    listOf(
        DiscourseCookieSnapshot(
            name = "_t",
            value = "journey-session",
            httpOnly = true,
        ),
    )

private fun authorizationRedirect(nonce: String): String {
    val payload = "{\"key\":\"$JOURNEY_API_KEY\",\"nonce\":\"$nonce\",\"api\":4}"
    return URLBuilder("discourse://auth_redirect")
        .apply {
            parameters.append("payload", Base64.Default.encode(payload.encodeToByteArray()))
            parameters.append("oneTimePassword", Base64.Default.encode(JOURNEY_OTP.encodeToByteArray()))
        }.build()
        .toString()
}

private fun fakePublicKeyPem(): String =
    buildString {
        append("-----BEGIN PUBLIC KEY-----\n")
        append(Base64.Default.encode(ByteArray(256) { 0x2a }))
        append("\n-----END PUBLIC KEY-----")
    }

private const val JOURNEY_API_KEY: String =
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
private const val JOURNEY_OTP: String = "0123456789abcdef"
private const val MESSAGE_BUS_CLIENT_ID: String = "00000000000040008000000000000000"

private val WRITE_JOURNEY_PATHS: Set<String> =
    setOf(
        "/session/csrf",
        "/uploads.json",
        "/posts.json",
        "/post_actions",
        "/bookmarks.json",
    )

private const val CATEGORIES_FIXTURE: String =
    """{"category_list":{"categories":[{"id":7,"name":"Development","slug":"development"}]}}"""

private const val TAGS_FIXTURE: String =
    """{"tags":[{"id":8,"text":"kotlin","name":"Kotlin","slug":"kotlin","count":1}]}"""

private const val TOPIC_LIST_FIXTURE: String =
    """
    {
      "users":[{"id":7101,"username":"fixture-member","name":"Fixture Member"}],
      "topic_list":{
        "topics":[{
          "id":42,
          "title":"Fixture journey topic",
          "slug":"fixture-journey-topic",
          "posts_count":2,
          "highest_post_number":2,
          "category_id":7,
          "posters":[{"user_id":7101}]
        }]
      }
    }
    """

private const val POST_401_FIXTURE: String =
    """
    {
      "id":401,
      "topic_id":42,
      "post_number":1,
      "username":"fixture-member",
      "name":"Fixture Member",
      "cooked":"<p>First fixture post.</p>",
      "actions_summary":[{"id":2,"count":0,"acted":false,"can_act":true,"can_undo":false}],
      "bookmarked":false
    }
    """

private const val POST_402_FIXTURE: String =
    """
    {
      "id":402,
      "topic_id":42,
      "post_number":2,
      "reply_to_post_number":1,
      "username":"fixture-member",
      "name":"Fixture Member",
      "cooked":"<p>Second fixture post with <code>Kotlin</code>.</p>"
    }
    """

private val TOPIC_DETAIL_FIXTURE: String =
    """
    {
      "id":42,
      "title":"Fixture journey topic",
      "slug":"fixture-journey-topic",
      "posts_count":2,
      "highest_post_number":2,
      "category_id":7,
      "can_create_post":true,
      "bookmarked":false,
      "post_stream":{"stream":[401,402],"posts":[$POST_401_FIXTURE]}
    }
    """

private val TOPIC_POSTS_FIXTURE: String =
    """{"posts":[$POST_401_FIXTURE,$POST_402_FIXTURE]}"""

private const val SEARCH_FIXTURE: String =
    """
    {
      "posts":[{
        "id":402,
        "topic_id":42,
        "post_number":2,
        "username":"fixture-member",
        "blurb":"Search fixture"
      }],
      "topics":[{"id":42,"title":"Fixture journey topic","slug":"fixture-journey-topic"}],
      "grouped_search_result":{
        "term":"journey",
        "more_full_page_results":false,
        "post_ids":[402]
      }
    }
    """

private const val EMPTY_NOTIFICATIONS_FIXTURE: String =
    """{"notifications":[],"total_rows_notifications":0,"seen_notification_id":0}"""

private const val NOTIFICATION_FIXTURE: String =
    """
    {
      "notifications":[{
        "id":8101,
        "user_id":7101,
        "notification_type":5,
        "topic_id":42,
        "post_number":2,
        "data":{"topic_title":"Fixture journey topic"}
      }],
      "total_rows_notifications":1,
      "seen_notification_id":0
    }
    """

private const val CSRF_FIXTURE: String = """{"csrf":"journey-csrf"}"""
private const val CURRENT_SESSION_FIXTURE: String =
    """{"current_user":{"id":7101,"username":"fixture-member","name":"Fixture Member"}}"""
private const val UPLOAD_FIXTURE: String =
    """{"id":601,"short_url":"upload://journey.png","original_filename":"journey.png","width":10,"height":10}"""
private const val REPLY_FIXTURE: String = """{"id":501,"topic_id":42,"post_number":3}"""
private const val LIKED_POST_FIXTURE: String =
    """
    {
      "id":401,
      "topic_id":42,
      "post_number":1,
      "actions_summary":[{"id":2,"count":1,"acted":true,"can_act":false,"can_undo":true}]
    }
    """
private const val BOOKMARK_FIXTURE: String = """{"id":901}"""
private const val MESSAGE_BUS_FIXTURE: String =
    """[{"global_id":1,"message_id":1,"channel":"/notification/7101","data":{}}]"""
