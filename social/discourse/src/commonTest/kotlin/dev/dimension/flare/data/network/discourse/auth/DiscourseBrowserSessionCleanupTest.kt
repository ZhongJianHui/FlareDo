package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.network.discourse.DefaultDiscourseApi
import dev.dimension.flare.data.network.discourse.createDiscourseHttpClient
import dev.dimension.flare.data.network.discourse.createDiscourseWireTransport
import dev.dimension.flare.data.network.discourse.error.DiscourseServerException
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
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class DiscourseBrowserSessionCleanupTest {
    @Test
    fun fallbackHandoffPersistsSessionBeforeClearingBrowserCookies() =
        runTest {
            val bridge = RecordingCookieBridge(listOf(sessionCookie("browser-session")))
            val fixture =
                browserFixture(bridge) { request ->
                    assertEquals("/session/current.json", request.url.encodedPath)
                    assertEquals("_t=browser-session", request.headers[HttpHeaders.Cookie])
                    respondJson(CURRENT_SESSION_JSON)
                }

            try {
                val result = fixture.webLogin.complete()

                assertEquals("42", result.accountId)
                assertEquals(1, bridge.clearCalls)
                assertTrue(bridge.clearObservedActiveContext)
                val active = assertIs<DiscourseSessionState.Authenticated>(fixture.sessionManager.state.value)
                assertEquals("42", active.accountId)
                assertEquals(active.credentialRef, fixture.sessionStore.current?.credentialRef)
                assertEquals(
                    "browser-session",
                    fixture.sessionStore.current
                        ?.cookies
                        ?.single()
                        ?.value,
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun remoteLogoutFailureStillDestroysLocalAndBrowserSessions() =
        runTest {
            val bridge = RecordingCookieBridge()
            val fixture =
                authenticatedLoginFixture(bridge) { request ->
                    when (request.url.encodedPath) {
                        "/session/csrf" -> respondJson("{\"csrf\":\"logout-csrf\"}")
                        "/session/member" -> respond("failure", HttpStatusCode.InternalServerError)
                        else -> error("Unexpected logout request")
                    }
                }

            try {
                assertFailsWith<DiscourseServerException> { fixture.loginService.logout() }

                fixture.assertLocalSessionDestroyed()
                assertEquals(1, bridge.clearCalls)
                assertTrue(bridge.clearObservedActiveContext)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun fallbackBrowserCleanupFailureDestroysTheNewlyPersistedSession() =
        runTest {
            val bridge =
                RecordingCookieBridge(
                    cookies = listOf(sessionCookie("browser-session")),
                    clearFailure = IllegalStateException("browser unavailable"),
                )
            val fixture =
                browserFixture(bridge) { request ->
                    assertEquals("/session/current.json", request.url.encodedPath)
                    respondJson(CURRENT_SESSION_JSON)
                }

            try {
                assertFailsWith<IllegalStateException> { fixture.webLogin.complete() }

                fixture.assertLocalSessionDestroyed()
                // The strict handoff clear fails, then fail-closed cleanup retries once.
                assertEquals(2, bridge.clearCalls)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun browserCleanupFailureCannotPreventLocalLogout() =
        runTest {
            val bridge = RecordingCookieBridge(clearFailure = IllegalStateException("browser unavailable"))
            val fixture =
                authenticatedLoginFixture(bridge) { request ->
                    successfulLogoutResponse(request)
                }

            try {
                fixture.loginService.logout()

                fixture.assertLocalSessionDestroyed()
                assertEquals(1, bridge.clearCalls)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun callerCancellationPropagatesAfterNonCancellableLocalAndBrowserCleanup() =
        runTest {
            val deleteStarted = CompletableDeferred<Unit>()
            val bridge = RecordingCookieBridge()
            val fixture =
                authenticatedLoginFixture(bridge) { request ->
                    when (request.url.encodedPath) {
                        "/session/csrf" -> {
                            respondJson("{\"csrf\":\"logout-csrf\"}")
                        }

                        "/session/member" -> {
                            deleteStarted.complete(Unit)
                            awaitCancellation()
                        }

                        else -> {
                            error("Unexpected logout request")
                        }
                    }
                }

            try {
                val logout = async { fixture.loginService.logout() }
                deleteStarted.await()
                logout.cancel()

                assertFailsWith<CancellationException> { logout.await() }
                fixture.assertLocalSessionDestroyed()
                assertEquals(1, bridge.clearCalls)
                assertTrue(bridge.clearObservedActiveContext)
            } finally {
                fixture.close()
            }
        }
}

private data class BrowserFixture(
    val webLogin: DiscourseWebSessionLogin,
    val loginService: DiscourseLoginService,
    val sessionManager: DiscourseSessionManager,
    val sessionStore: RecordingSessionStore,
    val client: HttpClient,
    val credentialStore: SessionOnlySecureCredentialStore,
) {
    suspend fun assertLocalSessionDestroyed() {
        assertIs<DiscourseSessionState.Guest>(sessionManager.state.value)
        assertTrue(sessionManager.cookieStorage.snapshot().isEmpty())
        assertNull(sessionStore.current)
    }

    fun close() {
        client.close()
        credentialStore.close()
    }
}

private suspend fun authenticatedLoginFixture(
    bridge: RecordingCookieBridge,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): BrowserFixture =
    browserFixture(bridge, handler).also { fixture ->
        fixture.sessionStore.lifecycle.activate(
            expectedGeneration = 0L,
            accountId = "42",
            username = "member",
            cookies = listOf(sessionCookie("active-session")),
        )
    }

private fun browserFixture(
    bridge: RecordingCookieBridge,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): BrowserFixture {
    val cookieStorage = DiscourseCookieStorage()
    val sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage)
    val sessionStore = RecordingSessionStore(sessionManager)
    val client = createDiscourseHttpClient(MockEngine(handler), cookieStorage)
    val api = DefaultDiscourseApi(createDiscourseWireTransport(client), sessionManager)
    val credentialStore = SessionOnlySecureCredentialStore()
    val attempts = MemoryDiscourseAuthAttemptStore()
    val authorizationCoordinator =
        DiscourseAuthorizationCoordinator(
            keyPairGenerator = DiscourseRsaPkcs1KeyPairGenerator { _ -> error("RSA generation is not expected") },
            tokenGenerator = DiscourseAuthTokenGenerator { _ -> error("Token generation is not expected") },
            credentialStore = credentialStore,
            attemptStore = attempts,
        )
    val redirectProcessor =
        DiscourseAuthRedirectProcessor(
            attemptStore = attempts,
            credentialStore = credentialStore,
            decryptor = DiscourseRsaPkcs1Decryptor { _, _ -> error("RSA decryption is not expected") },
            nowEpochMillis = { 1_000L },
        )
    val exchangeTransport =
        DiscourseOtpSessionExchangeTransport(
            client = client,
            sessionManager = sessionManager,
            challengeHandler = DiscourseCloudflareChallengeHandler { false },
        )
    val loginService =
        DiscourseLoginService(
            authorizationCoordinator = authorizationCoordinator,
            redirectProcessor = redirectProcessor,
            exchangeTransport = exchangeTransport,
            sessionLifecycle = sessionStore.lifecycle,
            sessionManager = sessionManager,
            cookieBridge = bridge,
            api = api,
        )
    return BrowserFixture(
        webLogin =
            DiscourseWebSessionLogin(
                cookieBridge = bridge,
                sessionManager = sessionManager,
                sessionLifecycle = sessionStore.lifecycle,
                api = api,
            ),
        loginService = loginService,
        sessionManager = sessionManager,
        sessionStore = sessionStore,
        client = client,
        credentialStore = credentialStore,
    )
}

private class RecordingSessionStore(
    sessionManager: DiscourseSessionManager,
) : DiscourseSessionStore {
    val lifecycle = DiscourseSessionLifecycle(sessionManager, this)
    var current: PersistedDiscourseSession? = null
        private set
    private var nextReference = 1

    override suspend fun replace(
        accountId: String,
        username: String?,
        cookies: List<DiscourseCookieSnapshot>,
    ): SecureCredentialRef {
        val reference = SecureCredentialRef("browser-cleanup-fixture-${nextReference++}")
        current = PersistedDiscourseSession(reference, accountId, username, cookies.toList())
        return reference
    }

    override suspend fun restore(): PersistedDiscourseSession? = current

    override suspend fun clear(expectedCredentialRef: SecureCredentialRef?) {
        if (expectedCredentialRef == null || current?.credentialRef == expectedCredentialRef) current = null
    }
}

private class RecordingCookieBridge(
    private val cookies: List<DiscourseCookieSnapshot> = emptyList(),
    private val clearFailure: Throwable? = null,
) : DiscourseWebSessionCookieBridge {
    var clearCalls: Int = 0
        private set
    var clearObservedActiveContext: Boolean = false
        private set

    override suspend fun snapshotLinuxDoCookies(): List<DiscourseCookieSnapshot> = cookies.toList()

    override suspend fun clearLinuxDoCookies() {
        clearCalls += 1
        clearObservedActiveContext = currentCoroutineContext().isActive
        clearFailure?.let { throw it }
    }
}

private fun MockRequestHandleScope.successfulLogoutResponse(request: HttpRequestData): HttpResponseData =
    when (request.url.encodedPath) {
        "/session/csrf" -> respondJson("{\"csrf\":\"logout-csrf\"}")
        "/session/member" -> respond("", HttpStatusCode.NoContent)
        else -> error("Unexpected logout request")
    }

private fun sessionCookie(value: String): DiscourseCookieSnapshot =
    DiscourseCookieSnapshot(
        name = "_t",
        value = value,
        httpOnly = true,
    )

private fun MockRequestHandleScope.respondJson(content: String): HttpResponseData =
    respond(
        content = content,
        headers =
            Headers.build {
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            },
    )

private const val CURRENT_SESSION_JSON: String =
    """{"current_user":{"id":42,"username":"member","name":"Fixture Member"}}"""
