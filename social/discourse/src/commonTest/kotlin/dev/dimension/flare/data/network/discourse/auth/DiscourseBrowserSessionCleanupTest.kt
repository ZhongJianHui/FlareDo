package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.network.discourse.DefaultDiscourseApi
import dev.dimension.flare.data.network.discourse.configureDiscourseHttpClient
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
import dev.dimension.flare.data.network.discourse.session.StaleDiscourseSessionException
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class DiscourseBrowserSessionCleanupTest {
    @Test
    fun presenterKeepsSharedObserversGuestUntilOneRealFallbackActivation() =
        runTest {
            val probeStarted = CompletableDeferred<Unit>()
            val releaseProbe = CompletableDeferred<Unit>()
            val finalCleanupStarted = CompletableDeferred<Unit>()
            val releaseFinalCleanup = CompletableDeferred<Unit>()
            var clearCalls = 0
            val bridge =
                RecordingCookieBridge(
                    cookies = listOf(sessionCookie("browser-session")),
                    beforeClear = {
                        clearCalls += 1
                        if (clearCalls == 2) {
                            finalCleanupStarted.complete(Unit)
                            releaseFinalCleanup.await()
                        }
                    },
                )
            val fixture =
                browserFixture(bridge) { request ->
                    assertEquals("/session/current.json", request.url.encodedPath)
                    assertEquals("_t=browser-session", request.headers[HttpHeaders.Cookie])
                    probeStarted.complete(Unit)
                    releaseProbe.await()
                    respondJson(
                        content = CURRENT_SESSION_JSON,
                        setCookie = "_t=rotated-session; Path=/; Secure; HttpOnly",
                    )
                }
            val presenter =
                DiscourseAuthenticationPresenter(
                    loginService = fixture.loginService,
                    webSessionLogin = fixture.webLogin,
                    cookieBridge = bridge,
                    sessionManager = fixture.sessionManager,
                    challengeCoordinator = DiscourseManualChallengeCoordinator(),
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models
            val forumObservedStates = mutableListOf<DiscourseSessionState>()
            val composerObservedStates = mutableListOf<DiscourseSessionState>()
            val realtimeObservedStates = mutableListOf<DiscourseSessionState>()
            listOf(forumObservedStates, composerObservedStates, realtimeObservedStates).forEach { observed ->
                backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
                    fixture.sessionManager.state.collect { state -> observed += state }
                }
            }

            try {
                runCurrent()
                assertTrue(presenter.dispatch(DiscourseAuthenticationAction.BeginFallbackLogin))
                advanceUntilIdle()
                val request = requireNotNull(models.value.restrictedBrowser)

                assertTrue(
                    presenter.dispatch(
                        DiscourseAuthenticationAction.CompleteRestrictedBrowser(
                            requestId = request.requestId,
                            mode = request.mode,
                        ),
                    ),
                )
                probeStarted.await()

                val initialGuest = DiscourseSessionState.Guest(generation = 0L)
                val expectedGuestStates: List<DiscourseSessionState> = listOf(initialGuest)
                assertEquals(initialGuest, fixture.sessionManager.state.value)
                assertEquals(expectedGuestStates, forumObservedStates)
                assertEquals(expectedGuestStates, composerObservedStates)
                assertEquals(expectedGuestStates, realtimeObservedStates)
                assertTrue(
                    fixture.sessionManager.cookieStorage
                        .snapshot()
                        .isEmpty(),
                )
                assertNull(fixture.sessionStore.current)
                assertTrue(models.value.isBusy)
                assertEquals(request, models.value.restrictedBrowser)

                releaseProbe.complete(Unit)
                finalCleanupStarted.await()
                runCurrent()

                val final = assertIs<DiscourseSessionState.Authenticated>(fixture.sessionManager.state.value)
                assertEquals(1L, final.generation)
                assertEquals("42", final.accountId)
                assertEquals(
                    "rotated-session",
                    fixture.sessionStore.current
                        ?.cookies
                        ?.single()
                        ?.value,
                )
                val expectedStates: List<DiscourseSessionState> = listOf(initialGuest, final)
                assertEquals(expectedStates, forumObservedStates)
                assertEquals(expectedStates, composerObservedStates)
                assertEquals(expectedStates, realtimeObservedStates)
                listOf(forumObservedStates, composerObservedStates, realtimeObservedStates)
                    .flatten()
                    .filterIsInstance<DiscourseSessionState.Authenticated>()
                    .forEach { observed -> assertEquals("42", observed.accountId) }
                assertTrue(models.value.isBusy)
                assertEquals(request, models.value.restrictedBrowser)

                releaseFinalCleanup.complete(Unit)
                advanceUntilIdle()

                assertEquals(DiscourseAuthenticationState(), models.value)
                assertEquals("42", assertIs<DiscourseSessionState.Authenticated>(fixture.sessionManager.state.value).accountId)
            } finally {
                releaseProbe.complete(Unit)
                releaseFinalCleanup.complete(Unit)
                presenter.close()
                runCurrent()
                fixture.close()
            }
        }

    @Test
    fun isolatedProbePersistsResponseCookiesAndClosesTemporaryResources() =
        runTest {
            lateinit var temporaryStorage: DiscourseCookieStorage
            lateinit var temporaryClient: HttpClient
            val probe =
                DefaultDiscourseWebSessionProbe(
                    cookieStorageFactory = {
                        DiscourseCookieStorage().also { temporaryStorage = it }
                    },
                    clientFactory = { storage ->
                        managedMockDiscourseClient(storage) { request ->
                            assertEquals("/session/current.json", request.url.encodedPath)
                            assertEquals("_t=browser-session", request.headers[HttpHeaders.Cookie])
                            respondJson(
                                content = CURRENT_SESSION_JSON,
                                setCookie = "_t=server-rotated; Path=/; Secure; HttpOnly",
                            )
                        }.also { temporaryClient = it }
                    },
                )

            val result = probe.probe(listOf(sessionCookie("browser-session")))

            assertEquals("42", result.accountId)
            assertEquals("member", result.username)
            assertEquals("server-rotated", result.cookies.single { it.name == "_t" }.value)
            assertTemporaryProbeResourcesClosed(temporaryStorage, temporaryClient)
        }

    @Test
    fun isolatedProbeClosesTemporaryResourcesWhenVerificationFails() =
        runTest {
            lateinit var temporaryStorage: DiscourseCookieStorage
            lateinit var temporaryClient: HttpClient
            val probe =
                DefaultDiscourseWebSessionProbe(
                    cookieStorageFactory = {
                        DiscourseCookieStorage().also { temporaryStorage = it }
                    },
                    clientFactory = { storage ->
                        managedMockDiscourseClient(storage) { request ->
                            assertEquals("/session/current.json", request.url.encodedPath)
                            respond("failure", HttpStatusCode.InternalServerError)
                        }.also { temporaryClient = it }
                    },
                )

            assertFailsWith<DiscourseServerException> {
                probe.probe(listOf(sessionCookie("browser-session")))
            }

            assertTemporaryProbeResourcesClosed(temporaryStorage, temporaryClient)
        }

    @Test
    fun isolatedProbeCancellationPropagatesAfterTemporaryResourceCleanup() =
        runTest {
            val requestStarted = CompletableDeferred<Unit>()
            lateinit var temporaryStorage: DiscourseCookieStorage
            lateinit var temporaryClient: HttpClient
            val probe =
                DefaultDiscourseWebSessionProbe(
                    cookieStorageFactory = {
                        DiscourseCookieStorage().also { temporaryStorage = it }
                    },
                    clientFactory = { storage ->
                        managedMockDiscourseClient(storage) { request ->
                            assertEquals("/session/current.json", request.url.encodedPath)
                            requestStarted.complete(Unit)
                            awaitCancellation()
                        }.also { temporaryClient = it }
                    },
                )
            val operation = async { probe.probe(listOf(sessionCookie("browser-session"))) }
            requestStarted.await()

            operation.cancel()

            assertFailsWith<CancellationException> { operation.await() }
            assertTemporaryProbeResourcesClosed(temporaryStorage, temporaryClient)
        }

    @Test
    fun ownerReplacementDuringPendingAuthorizationCleanupSkipsRemoteLogout() =
        runTest {
            val bridge = RecordingCookieBridge()
            val attemptStore = BlockingCancelAttemptStore()
            val requests = mutableListOf<HttpRequestData>()
            val fixture =
                authenticatedLoginFixture(
                    bridge = bridge,
                    attemptStore = attemptStore,
                ) { request ->
                    requests += request
                    error("A stale logout must not reach the network")
                }

            try {
                val originalOwner = assertIs<DiscourseSessionState.Authenticated>(fixture.sessionManager.state.value)
                val logout =
                    async {
                        fixture.loginService.logout(
                            expectedGeneration = originalOwner.generation,
                            expectedAccountId = originalOwner.accountId,
                        )
                    }
                attemptStore.consumeStarted.await()

                fixture.sessionStore.lifecycle.activate(
                    expectedGeneration = originalOwner.generation,
                    accountId = "84",
                    username = "replacement",
                    cookies = listOf(sessionCookie("replacement-session")),
                )
                val replacement = assertIs<DiscourseSessionState.Authenticated>(fixture.sessionManager.state.value)
                val replacementReference = replacement.credentialRef

                attemptStore.releaseConsume.complete(Unit)
                assertFalse(logout.await())

                assertEquals(replacement, fixture.sessionManager.state.value)
                assertEquals("84", fixture.sessionStore.current?.accountId)
                assertEquals(replacementReference, fixture.sessionStore.current?.credentialRef)
                assertEquals(
                    "replacement-session",
                    fixture.sessionManager.cookieStorage
                        .snapshot()
                        .single { it.name == "_t" }
                        .value,
                )
                assertTrue(requests.isEmpty())
                assertEquals(0, bridge.clearCalls)
            } finally {
                attemptStore.releaseConsume.complete(Unit)
                fixture.close()
            }
        }

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
    fun concurrentFallbackCompletionCannotClearTheAcceptedHandoff() =
        runTest {
            val snapshotStarted = CompletableDeferred<Unit>()
            val releaseSnapshot = CompletableDeferred<Unit>()
            val bridge =
                RecordingCookieBridge(
                    cookies = listOf(sessionCookie("browser-session")),
                    beforeSnapshot = {
                        snapshotStarted.complete(Unit)
                        releaseSnapshot.await()
                    },
                )
            val fixture =
                browserFixture(bridge) { request ->
                    assertEquals("/session/current.json", request.url.encodedPath)
                    respondJson(CURRENT_SESSION_JSON)
                }

            try {
                val accepted = async { fixture.webLogin.complete() }
                snapshotStarted.await()

                val rejected =
                    assertFailsWith<DiscourseAuthExchangeException> {
                        fixture.webLogin.complete()
                    }
                assertEquals(DiscourseAuthExchangeFailure.ActiveSession, rejected.reason)
                assertEquals(0, bridge.clearCalls)

                releaseSnapshot.complete(Unit)
                assertEquals("42", accepted.await().accountId)
                assertEquals(1, bridge.clearCalls)
            } finally {
                releaseSnapshot.complete(Unit)
                fixture.close()
            }
        }

    @Test
    fun fallbackActivationKeepsLifecycleReplacementQueuedUntilBrowserCookiesAreCleared() =
        runTest {
            val clearStarted = CompletableDeferred<Unit>()
            val releaseClear = CompletableDeferred<Unit>()
            val bridge =
                RecordingCookieBridge(
                    cookies = listOf(sessionCookie("browser-session")),
                    beforeClear = {
                        clearStarted.complete(Unit)
                        releaseClear.await()
                    },
                )
            val fixture =
                browserFixture(bridge) { request ->
                    assertEquals("/session/current.json", request.url.encodedPath)
                    respondJson(CURRENT_SESSION_JSON)
                }

            try {
                val login = async { fixture.webLogin.complete() }
                clearStarted.await()
                val original = assertIs<DiscourseSessionState.Authenticated>(fixture.sessionManager.state.value)
                val replacement =
                    async {
                        fixture.sessionStore.lifecycle.activate(
                            expectedGeneration = original.generation,
                            accountId = "84",
                            username = "replacement",
                            cookies = listOf(sessionCookie("replacement-session")),
                        )
                    }
                runCurrent()

                // Activation and request-owned browser cleanup share one lifecycle critical section.
                // A replacement cannot create a second owner in the old two-acquisition gap.
                assertFalse(replacement.isCompleted)
                assertEquals(1, bridge.clearCalls)

                releaseClear.complete(Unit)
                assertEquals("42", login.await().accountId)
                replacement.await()

                val active = assertIs<DiscourseSessionState.Authenticated>(fixture.sessionManager.state.value)
                assertEquals("84", active.accountId)
                assertEquals(1, bridge.clearCalls)
            } finally {
                releaseClear.complete(Unit)
                fixture.close()
            }
        }

    @Test
    fun cancellationDuringMandatoryBrowserClearRollsBackTheActivatedGeneration() =
        runTest {
            val clearStarted = CompletableDeferred<Unit>()
            val releaseClear = CompletableDeferred<Unit>()
            val bridge =
                RecordingCookieBridge(
                    cookies = listOf(sessionCookie("browser-session")),
                    beforeClear = {
                        clearStarted.complete(Unit)
                        releaseClear.await()
                    },
                )
            val fixture =
                browserFixture(bridge) { request ->
                    assertEquals("/session/current.json", request.url.encodedPath)
                    respondJson(CURRENT_SESSION_JSON)
                }

            try {
                val login = async { fixture.webLogin.complete() }
                clearStarted.await()
                assertIs<DiscourseSessionState.Authenticated>(fixture.sessionManager.state.value)

                login.cancel(CancellationException("caller cancelled during browser cleanup"))
                runCurrent()
                assertFalse(login.isCompleted)
                releaseClear.complete(Unit)

                assertFailsWith<CancellationException> { login.await() }
                fixture.assertLocalSessionDestroyed()
                // The first mandatory clear completed, so rollback must not repeat it.
                assertEquals(1, bridge.clearCalls)
            } finally {
                releaseClear.complete(Unit)
                fixture.close()
            }
        }

    @Test
    fun fallbackClearDetectsDirectOwnerReplacementAndDoesNotClearItAgain() =
        runTest {
            val clearStarted = CompletableDeferred<Unit>()
            val releaseClear = CompletableDeferred<Unit>()
            val bridge =
                RecordingCookieBridge(
                    cookies = listOf(sessionCookie("browser-session")),
                    beforeClear = {
                        clearStarted.complete(Unit)
                        releaseClear.await()
                    },
                )
            val fixture =
                browserFixture(bridge) { request ->
                    assertEquals("/session/current.json", request.url.encodedPath)
                    respondJson(CURRENT_SESSION_JSON)
                }

            try {
                val login = async { runCatching { fixture.webLogin.complete() } }
                clearStarted.await()
                val oldOwner = assertIs<DiscourseSessionState.Authenticated>(fixture.sessionManager.state.value)

                // Lifecycle-managed replacement is serialized by the handoff lock. This direct
                // manager transition models an already-started replacement that wins independently;
                // the post-clear owner CAS must reject stale success without logging it out or
                // performing a second broad browser clear.
                fixture.sessionManager.startAuthenticatedSession(
                    accountId = oldOwner.accountId,
                    username = "replacement",
                    credentialRef = oldOwner.credentialRef,
                    cookieSnapshot = listOf(sessionCookie("replacement-session")),
                    expectedGeneration = oldOwner.generation,
                )
                val replacement = assertIs<DiscourseSessionState.Authenticated>(fixture.sessionManager.state.value)
                releaseClear.complete(Unit)

                val failure = login.await().exceptionOrNull()
                assertIs<StaleDiscourseSessionException>(failure)
                assertEquals(replacement, fixture.sessionManager.state.value)
                assertEquals(
                    "replacement-session",
                    fixture.sessionManager.cookieStorage
                        .snapshot()
                        .single { it.name == "_t" }
                        .value,
                )
                assertEquals(1, bridge.clearCalls)
            } finally {
                releaseClear.complete(Unit)
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
    fun remoteLogoutFailureRemainsPrimaryWhenBrowserCleanupThrowsCancellation() =
        runTest {
            val browserCancellation = CancellationException("browser backend cancelled cleanup")
            val bridge = RecordingCookieBridge(clearFailure = browserCancellation)
            val fixture =
                authenticatedLoginFixture(bridge) { request ->
                    when (request.url.encodedPath) {
                        "/session/csrf" -> respondJson("{\"csrf\":\"logout-csrf\"}")
                        "/session/member" -> respond("failure", HttpStatusCode.InternalServerError)
                        else -> error("Unexpected logout request")
                    }
                }

            try {
                val failure = assertFailsWith<DiscourseServerException> { fixture.loginService.logout() }

                val suppressed = assertIs<CancellationException>(failure.suppressedExceptions.single())
                assertEquals(browserCancellation.message, suppressed.message)
                fixture.assertLocalSessionDestroyed()
                assertEquals(1, bridge.clearCalls)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun lifecycleLogoutPersistenceFailureKeepsTheRecoverableOwnerAuthenticated() =
        runTest {
            val fixture =
                authenticatedLoginFixture(RecordingCookieBridge()) { request ->
                    successfulLogoutResponse(request)
                }
            val owner = assertIs<DiscourseSessionState.Authenticated>(fixture.sessionManager.state.value)
            val persisted = requireNotNull(fixture.sessionStore.current)
            val vaultFailure = IllegalStateException("vault reference retained")
            fixture.sessionStore.clearFailure = vaultFailure

            try {
                val failure =
                    assertFailsWith<IllegalStateException> {
                        fixture.sessionStore.lifecycle.logout()
                    }

                assertEquals(vaultFailure.message, failure.message)
                assertEquals(owner, fixture.sessionManager.state.value)
                assertEquals(persisted, fixture.sessionStore.current)
                assertEquals(
                    "active-session",
                    fixture.sessionManager.cookieStorage
                        .snapshot()
                        .single { it.name == "_t" }
                        .value,
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun ordinaryLogoutPersistenceFailureKeepsLocalOwnerButClearsItsBrowserCookie() =
        runTest {
            val bridge = RecordingCookieBridge()
            val fixture =
                authenticatedLoginFixture(bridge) { request ->
                    successfulLogoutResponse(request)
                }
            val owner = assertIs<DiscourseSessionState.Authenticated>(fixture.sessionManager.state.value)
            val persisted = requireNotNull(fixture.sessionStore.current)
            val vaultFailure = IllegalStateException("vault reference retained")
            fixture.sessionStore.clearFailure = vaultFailure

            try {
                val failure = assertFailsWith<IllegalStateException> { fixture.loginService.logout() }

                assertEquals(vaultFailure.message, failure.message)
                assertEquals(owner, fixture.sessionManager.state.value)
                assertEquals(persisted, fixture.sessionStore.current)
                assertEquals(1, bridge.clearCalls)
                assertTrue(bridge.clearObservedActiveContext)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun ordinaryLogoutDoesNotClearBrowserCookieAfterQueuedOwnerReplacement() =
        runTest {
            val clearStarted = CompletableDeferred<Unit>()
            val releaseClear = CompletableDeferred<Unit>()
            val bridge = RecordingCookieBridge()
            val fixture =
                authenticatedLoginFixture(bridge) { request ->
                    successfulLogoutResponse(request)
                }
            val owner = assertIs<DiscourseSessionState.Authenticated>(fixture.sessionManager.state.value)
            val vaultFailure = IllegalStateException("vault reference retained")
            fixture.sessionStore.beforeClear = {
                clearStarted.complete(Unit)
                releaseClear.await()
            }
            fixture.sessionStore.clearFailure = vaultFailure

            try {
                val logout = async { runCatching { fixture.loginService.logout() } }
                clearStarted.await()
                val replacement =
                    async {
                        fixture.sessionStore.lifecycle.activate(
                            expectedGeneration = owner.generation,
                            accountId = "84",
                            username = "replacement",
                            cookies = listOf(sessionCookie("replacement-session")),
                        )
                    }
                runCurrent()
                assertFalse(replacement.isCompleted)

                releaseClear.complete(Unit)
                replacement.await()
                val failure = logout.await().exceptionOrNull()

                assertEquals(vaultFailure.message, assertIs<IllegalStateException>(failure).message)
                val active = assertIs<DiscourseSessionState.Authenticated>(fixture.sessionManager.state.value)
                assertEquals("84", active.accountId)
                assertEquals(active.credentialRef, fixture.sessionStore.current?.credentialRef)
                assertEquals(0, bridge.clearCalls)
            } finally {
                releaseClear.complete(Unit)
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
    fun fallbackVaultCleanupFailureStillRetriesBrowserCookieCleanup() =
        runTest {
            val browserFailure = IllegalStateException("browser cleanup failed")
            val vaultFailure = IllegalStateException("vault cleanup failed")
            val bridge =
                RecordingCookieBridge(
                    cookies = listOf(sessionCookie("browser-session")),
                    clearFailure = browserFailure,
                )
            val fixture =
                browserFixture(bridge) { request ->
                    assertEquals("/session/current.json", request.url.encodedPath)
                    respondJson(CURRENT_SESSION_JSON)
                }
            fixture.sessionStore.clearFailure = vaultFailure

            try {
                val failure = assertFailsWith<IllegalStateException> { fixture.webLogin.complete() }

                assertEquals(browserFailure.message, failure.message)
                val suppressed = assertIs<IllegalStateException>(failure.suppressedExceptions.single())
                assertEquals(vaultFailure.message, suppressed.message)
                val active = assertIs<DiscourseSessionState.Authenticated>(fixture.sessionManager.state.value)
                assertEquals(active.credentialRef, fixture.sessionStore.current?.credentialRef)
                // The strict browser clear failed once; persistence failure cannot skip the retry.
                assertEquals(2, bridge.clearCalls)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun fallbackCancellationRemainsPrimaryWhenVaultAndBrowserCleanupFail() =
        runTest {
            val cancellation = CancellationException("browser cleanup cancelled")
            val vaultFailure = IllegalStateException("vault cleanup failed")
            val bridge =
                RecordingCookieBridge(
                    cookies = listOf(sessionCookie("browser-session")),
                    clearFailure = cancellation,
                )
            val fixture =
                browserFixture(bridge) { request ->
                    assertEquals("/session/current.json", request.url.encodedPath)
                    respondJson(CURRENT_SESSION_JSON)
                }
            fixture.sessionStore.clearFailure = vaultFailure

            try {
                val failure = assertFailsWith<CancellationException> { fixture.webLogin.complete() }

                assertEquals(cancellation.message, failure.message)
                val suppressed = assertIs<IllegalStateException>(failure.suppressedExceptions.single())
                assertEquals(vaultFailure.message, suppressed.message)
                val active = assertIs<DiscourseSessionState.Authenticated>(fixture.sessionManager.state.value)
                assertEquals(active.credentialRef, fixture.sessionStore.current?.credentialRef)
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
    attemptStore: DiscourseAuthAttemptStore = MemoryDiscourseAuthAttemptStore(),
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): BrowserFixture =
    browserFixture(bridge, attemptStore, handler).also { fixture ->
        fixture.sessionStore.lifecycle.activate(
            expectedGeneration = 0L,
            accountId = "42",
            username = "member",
            cookies = listOf(sessionCookie("active-session")),
        )
    }

private fun browserFixture(
    bridge: RecordingCookieBridge,
    attemptStore: DiscourseAuthAttemptStore = MemoryDiscourseAuthAttemptStore(),
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): BrowserFixture {
    val cookieStorage = DiscourseCookieStorage()
    val sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage)
    val sessionStore = RecordingSessionStore(sessionManager)
    val client = createDiscourseHttpClient(MockEngine(handler), cookieStorage)
    val api = DefaultDiscourseApi(createDiscourseWireTransport(client), sessionManager)
    val webSessionProbe =
        DefaultDiscourseWebSessionProbe(
            clientFactory = { probeStorage ->
                managedMockDiscourseClient(probeStorage, handler)
            },
        )
    val credentialStore = SessionOnlySecureCredentialStore()
    val authorizationCoordinator =
        DiscourseAuthorizationCoordinator(
            keyPairGenerator = DiscourseRsaPkcs1KeyPairGenerator { _ -> error("RSA generation is not expected") },
            tokenGenerator = DiscourseAuthTokenGenerator { _ -> error("Token generation is not expected") },
            credentialStore = credentialStore,
            attemptStore = attemptStore,
        )
    val redirectProcessor =
        DiscourseAuthRedirectProcessor(
            attemptStore = attemptStore,
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
                probe = webSessionProbe,
            ),
        loginService = loginService,
        sessionManager = sessionManager,
        sessionStore = sessionStore,
        client = client,
        credentialStore = credentialStore,
    )
}

private class BlockingCancelAttemptStore : DiscourseAuthAttemptStore {
    val consumeStarted = CompletableDeferred<Unit>()
    val releaseConsume = CompletableDeferred<Unit>()
    private var attempt: DiscourseAuthAttempt? =
        DiscourseAuthAttempt(
            id = "pending-attempt",
            privateKeyRef = SecureCredentialRef("pending-private-key"),
            nonce = "pending-nonce",
            clientId = "pending-client",
            createdAtEpochMillis = 1L,
            expiresAtEpochMillis = 60_001L,
        )

    override suspend fun replace(attempt: DiscourseAuthAttempt): DiscourseAuthAttempt? {
        val previous = this.attempt
        this.attempt = attempt
        return previous
    }

    override suspend fun peek(): DiscourseAuthAttempt? = attempt

    override suspend fun consume(expectedId: String): DiscourseAuthAttempt? {
        consumeStarted.complete(Unit)
        releaseConsume.await()
        val current = attempt
        if (current?.id != expectedId) return null
        attempt = null
        return current
    }
}

private class RecordingSessionStore(
    sessionManager: DiscourseSessionManager,
) : DiscourseSessionStore {
    val lifecycle = DiscourseSessionLifecycle(sessionManager, this)
    var beforeClear: suspend () -> Unit = {}
    var clearFailure: Throwable? = null
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
        beforeClear()
        clearFailure?.let { throw it }
        if (expectedCredentialRef == null || current?.credentialRef == expectedCredentialRef) current = null
    }
}

private class RecordingCookieBridge(
    private val cookies: List<DiscourseCookieSnapshot> = emptyList(),
    private val clearFailure: Throwable? = null,
    private val beforeSnapshot: suspend () -> Unit = {},
    private val beforeClear: suspend () -> Unit = {},
) : DiscourseWebSessionCookieBridge {
    var clearCalls: Int = 0
        private set
    var clearObservedActiveContext: Boolean = false
        private set

    override suspend fun snapshotLinuxDoCookies(): List<DiscourseCookieSnapshot> {
        beforeSnapshot()
        return cookies.toList()
    }

    override suspend fun clearLinuxDoCookies() {
        clearCalls += 1
        clearObservedActiveContext = currentCoroutineContext().isActive
        beforeClear()
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

private fun managedMockDiscourseClient(
    storage: DiscourseCookieStorage,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): HttpClient =
    HttpClient(MockEngine) {
        engine {
            addHandler(handler)
        }
        configureDiscourseHttpClient(storage)
    }

private suspend fun assertTemporaryProbeResourcesClosed(
    storage: DiscourseCookieStorage,
    client: HttpClient,
) {
    assertTrue(storage.snapshot().isEmpty())
    assertTrue(requireNotNull(client.coroutineContext[Job]).isCompleted)
    assertFailsWith<IllegalStateException> {
        storage.importSnapshot(listOf(sessionCookie("must-not-be-restored")))
    }
}

private fun MockRequestHandleScope.respondJson(
    content: String,
    setCookie: String? = null,
): HttpResponseData =
    respond(
        content = content,
        headers =
            Headers.build {
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setCookie?.let { append(HttpHeaders.SetCookie, it) }
            },
    )

private const val CURRENT_SESSION_JSON: String =
    """{"current_user":{"id":42,"username":"member","name":"Fixture Member"}}"""
