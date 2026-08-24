package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.network.discourse.DISCOURSE_ORIGIN
import dev.dimension.flare.data.network.discourse.error.DiscourseCloudflareChallengeException
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieSnapshot
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class DiscourseManualChallengeCoordinatorTest {
    @Test
    fun completionPublishesOnlyTheFixedOriginAndResumesTheCaller() =
        runTest {
            val coordinator = DiscourseManualChallengeCoordinator()
            val result = async { coordinator.present(DISCOURSE_ORIGIN) }
            runCurrent()

            val request = requireNotNull(coordinator.request.value)
            assertEquals(DISCOURSE_ORIGIN, request.origin)
            assertTrue(coordinator.complete(request.requestId))
            assertTrue(result.await())
            assertNull(coordinator.request.value)
        }

    @Test
    fun cancellationFromTheForegroundUiResumesWithFalse() =
        runTest {
            val coordinator = DiscourseManualChallengeCoordinator()
            val result = async { coordinator.present(DISCOURSE_ORIGIN) }
            runCurrent()

            val request = requireNotNull(coordinator.request.value)
            assertTrue(coordinator.cancel(request.requestId))
            assertFalse(result.await())
            assertNull(coordinator.request.value)
        }

    @Test
    fun concurrentPresentationFailsClosedWithoutJoiningTheActiveRequest() =
        runTest {
            val coordinator = DiscourseManualChallengeCoordinator()
            val first = async { coordinator.present(DISCOURSE_ORIGIN) }
            runCurrent()

            assertFalse(coordinator.present(DISCOURSE_ORIGIN))
            val request = requireNotNull(coordinator.request.value)
            assertTrue(coordinator.complete(request.requestId))
            assertTrue(first.await())
        }

    @Test
    fun callerCancellationPropagatesAndRemovesItsVisibleRequest() =
        runTest {
            val coordinator = DiscourseManualChallengeCoordinator()
            val result = async { coordinator.present(DISCOURSE_ORIGIN) }
            runCurrent()
            requireNotNull(coordinator.request.value)

            result.cancel()
            assertFailsWith<CancellationException> { result.await() }
            assertNull(coordinator.request.value)
        }

    @Test
    fun staleCompletionCannotResolveAReplacementRequest() =
        runTest {
            val coordinator = DiscourseManualChallengeCoordinator()
            val first = async { coordinator.present(DISCOURSE_ORIGIN) }
            runCurrent()
            val firstRequest = requireNotNull(coordinator.request.value)
            assertTrue(coordinator.cancel(firstRequest.requestId))
            assertFalse(first.await())

            val second = async { coordinator.present(DISCOURSE_ORIGIN) }
            runCurrent()
            val secondRequest = requireNotNull(coordinator.request.value)
            assertNotEquals(firstRequest.requestId, secondRequest.requestId)
            assertFalse(coordinator.complete(firstRequest.requestId))
            assertEquals(secondRequest, coordinator.request.value)
            assertTrue(coordinator.complete(secondRequest.requestId))
            assertTrue(second.await())
        }

    @Test
    fun arbitraryOriginIsRejectedWithoutPublishingUiState() =
        runTest {
            val coordinator = DiscourseManualChallengeCoordinator()

            assertFalse(coordinator.present("https://example.invalid"))
            assertNull(coordinator.request.value)
        }

    @Test
    fun missingForegroundHostTimesOutAndRemovesTheRequest() =
        runTest {
            val coordinator = DiscourseManualChallengeCoordinator(timeoutMillis = 1_000L)
            val result = async { coordinator.present(DISCOURSE_ORIGIN) }
            runCurrent()
            requireNotNull(coordinator.request.value)

            advanceTimeBy(1_000L)
            runCurrent()

            assertFalse(result.await())
            assertNull(coordinator.request.value)
        }

    @Test
    fun cancellationDuringPresentationStillClearsBrowserHandoff() =
        runTest {
            val presentationStarted = CompletableDeferred<Unit>()
            var clearCalls = 0
            val handler =
                DiscourseManualChallengeCookieHandler(
                    presenter =
                        DiscourseManualChallengePresenter {
                            presentationStarted.complete(Unit)
                            awaitCancellation()
                        },
                    cookieBridge =
                        object : DiscourseWebSessionCookieBridge {
                            override suspend fun snapshotLinuxDoCookies(): List<DiscourseCookieSnapshot> =
                                error("Cancellation must stop before snapshot")

                            override suspend fun clearLinuxDoCookies() {
                                clearCalls += 1
                            }
                        },
                    sessionManager = DiscourseSessionManager(),
                )
            val operation =
                async {
                    handler.handle(DiscourseCloudflareChallengeException(statusCode = 403))
                }
            presentationStarted.await()

            operation.cancel()

            assertFailsWith<CancellationException> { operation.await() }
            assertEquals(1, clearCalls)
        }

    @Test
    fun concurrentHandlerCannotClearTheAcceptedRequestsCookieBuffer() =
        runTest {
            val presentationStarted = CompletableDeferred<Unit>()
            val releasePresentation = CompletableDeferred<Unit>()
            var clearCalls = 0
            val handler =
                DiscourseManualChallengeCookieHandler(
                    presenter =
                        DiscourseManualChallengePresenter {
                            presentationStarted.complete(Unit)
                            releasePresentation.await()
                            false
                        },
                    cookieBridge =
                        object : DiscourseWebSessionCookieBridge {
                            override suspend fun snapshotLinuxDoCookies(): List<DiscourseCookieSnapshot> = emptyList()

                            override suspend fun clearLinuxDoCookies() {
                                clearCalls += 1
                            }
                        },
                    sessionManager = DiscourseSessionManager(),
                )
            val first =
                async {
                    handler.handle(DiscourseCloudflareChallengeException(statusCode = 403))
                }
            presentationStarted.await()

            assertFalse(handler.handle(DiscourseCloudflareChallengeException(statusCode = 403)))
            assertEquals(0, clearCalls)

            releasePresentation.complete(Unit)
            assertFalse(first.await())
            assertEquals(1, clearCalls)
        }

    @Test
    fun appleCompletionWaitsUntilRequestConsumesAndClearsCookieHandoff() =
        runTest {
            val coordinator = DiscourseManualChallengeCoordinator()
            val sessionManager = DiscourseSessionManager()
            val clearStarted = CompletableDeferred<Unit>()
            val releaseClear = CompletableDeferred<Unit>()
            val handler =
                DiscourseManualChallengeCookieHandler(
                    presenter = coordinator,
                    cookieBridge =
                        object : DiscourseWebSessionCookieBridge {
                            override suspend fun snapshotLinuxDoCookies(): List<DiscourseCookieSnapshot> =
                                listOf(
                                    DiscourseCookieSnapshot(
                                        name = "cf_clearance",
                                        value = "bounded-challenge-cookie",
                                        httpOnly = true,
                                    ),
                                )

                            override suspend fun clearLinuxDoCookies() {
                                clearStarted.complete(Unit)
                                releaseClear.await()
                            }
                        },
                    sessionManager = sessionManager,
                )
            val handling =
                async {
                    sessionManager.runForCurrentSession {
                        handler.handle(DiscourseCloudflareChallengeException(statusCode = 403))
                    }
                }
            runCurrent()
            val request = requireNotNull(coordinator.request.value)
            val completion = async { coordinator.completeAfterCookieConsumption(request.requestId) }
            runCurrent()

            assertTrue(clearStarted.isCompleted)
            assertFalse(completion.isCompleted)

            releaseClear.complete(Unit)
            assertTrue(handling.await())
            assertTrue(completion.await())
            assertNull(coordinator.request.value)
        }
}
