package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.network.discourse.DISCOURSE_ORIGIN
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionState
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class DiscourseAuthenticationPresenterTest {
    @Test
    fun externalAuthorizationIsConsumedOnceAndNeverRenderedInDiagnostics() =
        runTest {
            val secret = "private-nonce-value"
            val backend = RecordingAuthenticationBackend()
            backend.authorization =
                DiscoursePendingAuthorization(
                    url = Url("$DISCOURSE_ORIGIN/user-api-key/new?nonce=$secret"),
                    expiresAtEpochMillis = 10_000L,
                )
            val presenter = presenter(backend)
            val models = presenter.models

            try {
                runCurrent()
                assertTrue(presenter.dispatch(DiscourseAuthenticationAction.BeginAuthorization))
                advanceUntilIdle()

                val request = requireNotNull(models.value.externalAuthorization)
                assertEquals(1L, request.requestId)
                assertFalse(request.toString().contains(secret))
                assertFalse(models.value.toString().contains(secret))

                assertTrue(
                    presenter.dispatch(
                        DiscourseAuthenticationAction.AuthorizationOpened(request.requestId),
                    ),
                )
                advanceUntilIdle()
                assertNull(models.value.externalAuthorization)

                // A stale acknowledgement cannot consume a later request.
                assertTrue(
                    presenter.dispatch(
                        DiscourseAuthenticationAction.AuthorizationOpened(request.requestId),
                    ),
                )
                advanceUntilIdle()
                assertNull(models.value.externalAuthorization)
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun browserLaunchFailureCancelsPendingAttempt() =
        runTest {
            val backend = RecordingAuthenticationBackend()
            val presenter = presenter(backend)
            val models = presenter.models

            try {
                runCurrent()
                presenter.dispatch(DiscourseAuthenticationAction.BeginAuthorization)
                advanceUntilIdle()
                val requestId = requireNotNull(models.value.externalAuthorization).requestId

                presenter.dispatch(DiscourseAuthenticationAction.AuthorizationLaunchFailed(requestId))
                advanceUntilIdle()

                assertEquals(1, backend.cancelAuthorizationCalls)
                assertNull(models.value.externalAuthorization)
                assertEquals(
                    DiscourseAuthenticationFailureKind.BrowserUnavailable,
                    models.value.failure,
                )
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun fallbackClearsPreviousAuthorizationAndCookiesBeforePresentation() =
        runTest {
            val backend = RecordingAuthenticationBackend()
            val presenter = presenter(backend)
            val models = presenter.models

            try {
                runCurrent()
                presenter.dispatch(DiscourseAuthenticationAction.BeginFallbackLogin)
                advanceUntilIdle()

                assertEquals(listOf("cancel-authorization", "clear-browser-cookies"), backend.calls)
                val request = requireNotNull(models.value.restrictedBrowser)
                assertEquals(DiscourseRestrictedBrowserMode.FallbackLogin, request.mode)
                assertEquals("$DISCOURSE_ORIGIN/login", request.initialUrl)
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun fallbackCancelCancelsItsInFlightCompletionBeforeItCanAuthenticate() =
        runTest {
            assertFallbackCompletionCancelled(
                terminalAction = { request ->
                    DiscourseAuthenticationAction.CancelRestrictedBrowser(
                        requestId = request.requestId,
                        mode = request.mode,
                    )
                },
                expectedFailure = null,
            )
        }

    @Test
    fun fallbackBrowserFailureCancelsItsInFlightCompletionBeforeItCanAuthenticate() =
        runTest {
            assertFallbackCompletionCancelled(
                terminalAction = { request ->
                    DiscourseAuthenticationAction.RestrictedBrowserFailed(
                        requestId = request.requestId,
                        mode = request.mode,
                    )
                },
                expectedFailure = DiscourseAuthenticationFailureKind.BrowserUnavailable,
            )
        }

    @Test
    fun cancellingVisibleFallbackDoesNotCancelAnUnboundRawRedirect() =
        runTest {
            val redirectStarted = CompletableDeferred<Unit>()
            val releaseRedirect = CompletableDeferred<Unit>()
            var redirectCancelled = false
            val backend = RecordingAuthenticationBackend()
            backend.completeRedirectHandler = {
                redirectStarted.complete(Unit)
                try {
                    releaseRedirect.await()
                    DiscourseLoginResult.Stale
                } catch (cancellation: CancellationException) {
                    redirectCancelled = true
                    throw cancellation
                }
            }
            val presenter = presenter(backend)
            val models = presenter.models

            try {
                runCurrent()
                assertTrue(presenter.dispatch(DiscourseAuthenticationAction.BeginFallbackLogin))
                advanceUntilIdle()
                val request = requireNotNull(models.value.restrictedBrowser)

                assertTrue(
                    presenter.completeRedirect(
                        "discourse://auth_redirect?payload=secret&oneTimePassword=secret",
                    ),
                )
                runCurrent()
                assertTrue(redirectStarted.isCompleted)

                val cancelAction =
                    DiscourseAuthenticationAction.CancelRestrictedBrowser(
                        requestId = request.requestId,
                        mode = request.mode,
                    )
                assertTrue(presenter.dispatch(cancelAction))
                runCurrent()

                assertTrue(cancelAction.receipt.awaitOwnership(1_000L))
                assertFalse(redirectCancelled)
                assertFalse(releaseRedirect.isCompleted)
                assertNull(models.value.restrictedBrowser)

                releaseRedirect.complete(Unit)
                advanceUntilIdle()
                assertEquals(listOf("discourse://auth_redirect?payload=secret&oneTimePassword=secret"), backend.redirects)
            } finally {
                releaseRedirect.complete(Unit)
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun busyRawRedirectRejectsFallbackCompletionReceiptWithoutTakingTheCookieHandoff() =
        runTest {
            val redirectStarted = CompletableDeferred<Unit>()
            val releaseRedirect = CompletableDeferred<Unit>()
            val backend = RecordingAuthenticationBackend()
            backend.completeRedirectHandler = {
                redirectStarted.complete(Unit)
                releaseRedirect.await()
                DiscourseLoginResult.Stale
            }
            val presenter = presenter(backend)
            val models = presenter.models

            try {
                runCurrent()
                assertTrue(presenter.dispatch(DiscourseAuthenticationAction.BeginFallbackLogin))
                advanceUntilIdle()
                val request = requireNotNull(models.value.restrictedBrowser)

                assertTrue(
                    presenter.completeRedirect(
                        "discourse://auth_redirect?payload=secret&oneTimePassword=secret",
                    ),
                )
                runCurrent()
                assertTrue(redirectStarted.isCompleted)

                val completeAction =
                    DiscourseAuthenticationAction.CompleteRestrictedBrowser(
                        requestId = request.requestId,
                        mode = request.mode,
                    )
                assertTrue(presenter.dispatch(completeAction))
                runCurrent()

                assertFalse(completeAction.receipt.awaitOwnership(1_000L))
                assertEquals(0, backend.completeWebSessionCalls)
                assertEquals(request, models.value.restrictedBrowser)
                assertFalse(models.value.restrictedBrowserHandoffInProgress)
            } finally {
                releaseRedirect.complete(Unit)
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun fallbackCompletionKeepsGuestUntilItsSingleAuthenticatedGeneration() =
        runTest {
            val allowActivation = CompletableDeferred<Unit>()
            val allowCompletion = CompletableDeferred<Unit>()
            var completionCancelled = false
            val backend = RecordingAuthenticationBackend()
            backend.completeWebSessionHandler = {
                try {
                    allowActivation.await()
                    backend.session.value =
                        DiscourseSessionState.Authenticated(
                            generation = 1L,
                            accountId = "42",
                            username = "member",
                        )
                    allowCompletion.await()
                    DiscourseLoginResult.Authenticated("42", "member", null)
                } catch (cancellation: CancellationException) {
                    completionCancelled = true
                    throw cancellation
                }
            }
            val presenter = presenter(backend)
            val models = presenter.models

            try {
                runCurrent()
                assertTrue(presenter.dispatch(DiscourseAuthenticationAction.BeginFallbackLogin))
                advanceUntilIdle()
                val request = requireNotNull(models.value.restrictedBrowser)

                val completeAction =
                    DiscourseAuthenticationAction.CompleteRestrictedBrowser(
                        requestId = request.requestId,
                        mode = request.mode,
                    )
                assertTrue(presenter.dispatch(completeAction))
                runCurrent()

                assertTrue(completeAction.receipt.awaitOwnership(1_000L))
                assertEquals(DiscourseSessionState.Guest(generation = 0L), backend.session.value)
                assertFalse(completionCancelled)
                assertTrue(models.value.isBusy)
                assertEquals(request, models.value.restrictedBrowser)
                assertTrue(models.value.restrictedBrowserHandoffInProgress)

                allowActivation.complete(Unit)
                runCurrent()

                val authenticated = assertIs<DiscourseSessionState.Authenticated>(backend.session.value)
                assertEquals(1L, authenticated.generation)
                assertEquals("42", authenticated.accountId)
                assertFalse(completionCancelled)
                assertTrue(models.value.isBusy)
                assertEquals(request, models.value.restrictedBrowser)
                assertTrue(models.value.restrictedBrowserHandoffInProgress)

                allowCompletion.complete(Unit)
                advanceUntilIdle()

                assertFalse(completionCancelled)
                assertEquals(DiscourseAuthenticationState(), models.value)
            } finally {
                allowActivation.complete(Unit)
                allowCompletion.complete(Unit)
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun logoutPinsTheOwnerObservedWhenTheUiDispatches() =
        runTest {
            val backend = RecordingAuthenticationBackend()
            backend.session.value =
                DiscourseSessionState.Authenticated(
                    generation = 7L,
                    accountId = "42",
                    username = "member",
                )
            val presenter = presenter(backend)
            presenter.models

            try {
                runCurrent()
                assertTrue(presenter.dispatch(DiscourseAuthenticationAction.Logout))
                advanceUntilIdle()

                assertEquals(listOf(7L to "42"), backend.logoutOwners)
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun generationChangeCancelsAStaleAuthorizationResult() =
        runTest {
            val pendingAuthorization = CompletableDeferred<DiscoursePendingAuthorization>()
            val backend = RecordingAuthenticationBackend()
            backend.beginAuthorizationHandler = { pendingAuthorization.await() }
            val presenter = presenter(backend)
            val models = presenter.models

            try {
                runCurrent()
                presenter.dispatch(DiscourseAuthenticationAction.BeginAuthorization)
                runCurrent()
                assertTrue(models.value.isBusy)

                backend.session.value = DiscourseSessionState.Guest(generation = 1L)
                runCurrent()
                pendingAuthorization.complete(backend.authorization)
                advanceUntilIdle()

                assertEquals(DiscourseAuthenticationState(), models.value)
            } finally {
                pendingAuthorization.complete(backend.authorization)
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun manualChallengeAcceptsOnlyTheCurrentlyVisibleRequestId() =
        runTest {
            val backend = RecordingAuthenticationBackend()
            val presenter = presenter(backend)
            val models = presenter.models

            try {
                runCurrent()
                backend.challenge.value =
                    DiscourseManualChallengeRequest(
                        requestId = 9L,
                        origin = DISCOURSE_ORIGIN,
                    )
                runCurrent()
                assertEquals(9L, requireNotNull(models.value.restrictedBrowser).requestId)

                val staleAction =
                    DiscourseAuthenticationAction.CompleteRestrictedBrowser(
                        requestId = 8L,
                        mode = DiscourseRestrictedBrowserMode.ManualChallenge,
                    )
                assertTrue(presenter.dispatch(staleAction))
                runCurrent()
                assertFalse(staleAction.receipt.awaitOwnership(1_000L))
                assertTrue(backend.completedChallenges.isEmpty())

                val matchingAction =
                    DiscourseAuthenticationAction.CompleteRestrictedBrowser(
                        requestId = 9L,
                        mode = DiscourseRestrictedBrowserMode.ManualChallenge,
                    )
                assertTrue(presenter.dispatch(matchingAction))
                advanceUntilIdle()
                assertTrue(matchingAction.receipt.awaitOwnership(1_000L))
                assertEquals(listOf(9L), backend.completedChallenges)
                assertNull(models.value.restrictedBrowser)
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun staleRestrictedBrowserTerminalActionsAreRejectedByTheActor() =
        runTest {
            val backend = RecordingAuthenticationBackend()
            val presenter = presenter(backend)
            val models = presenter.models

            try {
                runCurrent()
                assertTrue(presenter.dispatch(DiscourseAuthenticationAction.BeginFallbackLogin))
                advanceUntilIdle()
                val request = requireNotNull(models.value.restrictedBrowser)
                val staleActions: List<DiscourseRestrictedBrowserTerminalAction> =
                    listOf(
                        DiscourseAuthenticationAction.CompleteRestrictedBrowser(
                            requestId = request.requestId + 1L,
                            mode = request.mode,
                        ),
                        DiscourseAuthenticationAction.CancelRestrictedBrowser(
                            requestId = request.requestId + 1L,
                            mode = request.mode,
                        ),
                        DiscourseAuthenticationAction.RestrictedBrowserFailed(
                            requestId = request.requestId + 1L,
                            mode = request.mode,
                        ),
                    )

                staleActions.forEach { action -> assertTrue(presenter.dispatch(action)) }
                runCurrent()

                staleActions.forEach { action ->
                    assertFalse(action.receipt.awaitOwnership(1_000L))
                }
                assertEquals(request, models.value.restrictedBrowser)
                assertNull(models.value.failure)
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun manualChallengeRequestRemainsVisibleUntilCookieCleanupCompletes() =
        runTest {
            val cookieCleanupStarted = CompletableDeferred<Unit>()
            val allowCookieCleanup = CompletableDeferred<Unit>()
            val backend = RecordingAuthenticationBackend()
            backend.completeManualChallengeHandler = { requestId ->
                backend.completedChallenges += requestId
                cookieCleanupStarted.complete(Unit)
                allowCookieCleanup.await()
                backend.challenge.value = null
                true
            }
            val presenter = presenter(backend)
            val models = presenter.models

            try {
                runCurrent()
                backend.challenge.value =
                    DiscourseManualChallengeRequest(
                        requestId = 9L,
                        origin = DISCOURSE_ORIGIN,
                    )
                runCurrent()
                val request = requireNotNull(models.value.restrictedBrowser)

                val completeAction =
                    DiscourseAuthenticationAction.CompleteRestrictedBrowser(
                        requestId = request.requestId,
                        mode = request.mode,
                    )
                assertTrue(presenter.dispatch(completeAction))
                runCurrent()

                assertTrue(completeAction.receipt.awaitOwnership(1_000L))
                assertTrue(cookieCleanupStarted.isCompleted)
                assertFalse(allowCookieCleanup.isCompleted)
                assertTrue(models.value.isBusy)
                assertEquals(request, models.value.restrictedBrowser)
                assertTrue(models.value.restrictedBrowserHandoffInProgress)

                // Recomposition or a duplicated platform click cannot start a second snapshot.
                val duplicateComplete =
                    DiscourseAuthenticationAction.CompleteRestrictedBrowser(
                        requestId = request.requestId,
                        mode = request.mode,
                    )
                assertTrue(presenter.dispatch(duplicateComplete))

                // A late dismiss/failure signal from the browser must not dispose the surface while
                // its one-use Cookie snapshot is still being consumed and cleared by shared code.
                val duplicateCancel =
                    DiscourseAuthenticationAction.CancelRestrictedBrowser(
                        requestId = request.requestId,
                        mode = request.mode,
                    )
                val duplicateFailure =
                    DiscourseAuthenticationAction.RestrictedBrowserFailed(
                        requestId = request.requestId,
                        mode = request.mode,
                    )
                assertTrue(presenter.dispatch(duplicateCancel))
                assertTrue(presenter.dispatch(duplicateFailure))
                runCurrent()
                assertFalse(duplicateComplete.receipt.awaitOwnership(1_000L))
                assertFalse(duplicateCancel.receipt.awaitOwnership(1_000L))
                assertFalse(duplicateFailure.receipt.awaitOwnership(1_000L))
                assertEquals(listOf(9L), backend.completedChallenges)
                assertEquals(request, models.value.restrictedBrowser)
                assertTrue(models.value.restrictedBrowserHandoffInProgress)
                assertNull(models.value.failure)

                allowCookieCleanup.complete(Unit)
                advanceUntilIdle()

                assertEquals(listOf(9L), backend.completedChallenges)
                assertNull(models.value.restrictedBrowser)
                assertFalse(models.value.isBusy)
                assertFalse(models.value.restrictedBrowserHandoffInProgress)
                assertNull(models.value.failure)
            } finally {
                allowCookieCleanup.complete(Unit)
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun rawRedirectIsBoundedAndNeverCopiedIntoState() =
        runTest {
            val sensitiveCallback = "discourse://auth_redirect?payload=secret&oneTimePassword=secret"
            val backend = RecordingAuthenticationBackend()
            val presenter = presenter(backend)
            val models = presenter.models

            try {
                runCurrent()
                assertTrue(presenter.completeRedirect(sensitiveCallback))
                advanceUntilIdle()

                assertEquals(listOf(sensitiveCallback), backend.redirects)
                assertFalse(models.value.toString().contains("payload="))
                assertEquals(DiscourseAuthenticationFailureKind.Authentication, models.value.failure)
                assertFalse(presenter.completeRedirect("x".repeat(16 * 1024 + 1)))
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun redirectReceiptIsAcceptedOnlyAfterTheActorLaunchesItsOperation() =
        runTest {
            val operationStarted = CompletableDeferred<Unit>()
            val releaseOperation = CompletableDeferred<Unit>()
            val backend = RecordingAuthenticationBackend()
            backend.completeRedirectHandler = {
                operationStarted.complete(Unit)
                releaseOperation.await()
                DiscourseLoginResult.Stale
            }
            val presenter = presenter(backend)
            presenter.models

            try {
                runCurrent()
                val receipt =
                    requireNotNull(
                        presenter.completeRedirectWithReceipt(
                            "discourse://auth_redirect?payload=secret&oneTimePassword=secret",
                        ),
                    )
                runCurrent()

                assertTrue(receipt.awaitAcceptance(1_000L))
                assertTrue(operationStarted.isCompleted)
            } finally {
                releaseOperation.complete(Unit)
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun promptCancellationAfterTerminalReceiptOwnershipKeepsOwnershipAndPropagates() =
        runTest {
            val action =
                DiscourseAuthenticationAction.CompleteRestrictedBrowser(
                    requestId = 1L,
                    mode = DiscourseRestrictedBrowserMode.FallbackLogin,
                )
            val waiter =
                async(start = CoroutineStart.UNDISPATCHED) {
                    action.receipt.awaitOwnership(1_000L)
                }

            assertTrue(action.receipt.resolve { true })
            waiter.cancel(CancellationException("caller cancelled"))
            val failure = assertFailsWith<CancellationException> { waiter.await() }

            assertEquals("caller cancelled", failure.message)
            assertTrue(action.receipt.ownershipTransferred)
        }

    @Test
    fun terminalReceiptTimeoutPreventsLateActorSideEffects() =
        runTest {
            var invoked = false
            val action =
                DiscourseAuthenticationAction.CancelRestrictedBrowser(
                    requestId = 1L,
                    mode = DiscourseRestrictedBrowserMode.FallbackLogin,
                )

            assertFalse(action.receipt.awaitOwnership(1L))
            assertFalse(
                action.receipt.resolve {
                    invoked = true
                    true
                },
            )
            assertFalse(invoked)
            assertFalse(action.receipt.ownershipTransferred)
        }

    @Test
    fun terminalReceiptCancellationExpiresPendingActionAndPropagates() =
        runTest {
            var invoked = false
            val action =
                DiscourseAuthenticationAction.RestrictedBrowserFailed(
                    requestId = 1L,
                    mode = DiscourseRestrictedBrowserMode.ManualChallenge,
                )
            val waiter =
                async(start = CoroutineStart.UNDISPATCHED) {
                    action.receipt.awaitOwnership(1_000L)
                }

            waiter.cancel(CancellationException("caller cancelled"))
            val failure = assertFailsWith<CancellationException> { waiter.await() }

            assertEquals("caller cancelled", failure.message)
            assertFalse(action.receipt.ownershipTransferred)
            assertFalse(
                action.receipt.resolve {
                    invoked = true
                    true
                },
            )
            assertFalse(invoked)
        }

    @Test
    fun timedOutTerminalCommandCannotCompleteFallbackWhenActorResumesLater() =
        runTest {
            val backend = RecordingAuthenticationBackend()
            val dispatcher = GatedCoroutineDispatcher(StandardTestDispatcher(testScheduler))
            val presenter =
                DiscourseAuthenticationPresenter(
                    backend = backend,
                    dispatcher = dispatcher,
                    testMarker = Unit,
                )
            val models = presenter.models

            try {
                runCurrent()
                assertTrue(presenter.dispatch(DiscourseAuthenticationAction.BeginFallbackLogin))
                advanceUntilIdle()
                val request = requireNotNull(models.value.restrictedBrowser)

                dispatcher.hold()
                val completeAction =
                    DiscourseAuthenticationAction.CompleteRestrictedBrowser(
                        requestId = request.requestId,
                        mode = request.mode,
                    )
                assertTrue(presenter.dispatch(completeAction))
                assertFalse(completeAction.receipt.awaitOwnership(1L))

                dispatcher.releaseAll()
                advanceUntilIdle()

                assertEquals(0, backend.completeWebSessionCalls)
                assertEquals(request, models.value.restrictedBrowser)
                assertFalse(models.value.restrictedBrowserHandoffInProgress)
            } finally {
                dispatcher.releaseAll()
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun busyActorRejectsRedirectReceiptWithoutInvokingTheBackend() =
        runTest {
            val authorizationStarted = CompletableDeferred<Unit>()
            val releaseAuthorization = CompletableDeferred<Unit>()
            val backend = RecordingAuthenticationBackend()
            backend.beginAuthorizationHandler = {
                authorizationStarted.complete(Unit)
                releaseAuthorization.await()
                backend.authorization
            }
            val presenter = presenter(backend)
            presenter.models

            try {
                runCurrent()
                assertTrue(presenter.dispatch(DiscourseAuthenticationAction.BeginAuthorization))
                runCurrent()
                assertTrue(authorizationStarted.isCompleted)

                val receipt =
                    requireNotNull(
                        presenter.completeRedirectWithReceipt(
                            "discourse://auth_redirect?payload=secret&oneTimePassword=secret",
                        ),
                    )
                runCurrent()

                assertFalse(receipt.awaitAcceptance(1_000L))
                assertTrue(backend.redirects.isEmpty())
            } finally {
                releaseAuthorization.complete(Unit)
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun timedOutRedirectReceiptCannotRunAfterTheActorStartsLater() =
        runTest {
            val backend = RecordingAuthenticationBackend()
            val presenter = presenter(backend)
            val receipt =
                requireNotNull(
                    presenter.completeRedirectWithReceipt(
                        "discourse://auth_redirect?payload=secret&oneTimePassword=secret",
                    ),
                )

            assertFalse(receipt.awaitAcceptance(1L))
            presenter.models
            advanceUntilIdle()

            assertTrue(backend.redirects.isEmpty())
            presenter.close()
            runCurrent()
        }

    @Test
    fun closeBeforeTheRedirectChildStartsRejectsItsReceipt() =
        runTest {
            val backend = RecordingAuthenticationBackend()
            val dispatcher = GatedCoroutineDispatcher(StandardTestDispatcher(testScheduler))
            val presenter =
                DiscourseAuthenticationPresenter(
                    backend = backend,
                    dispatcher = dispatcher,
                    testMarker = Unit,
                )
            presenter.models

            try {
                runCurrent()
                dispatcher.hold()
                val receipt =
                    requireNotNull(
                        presenter.completeRedirectWithReceipt(
                            "discourse://auth_redirect?payload=secret&oneTimePassword=secret",
                        ),
                    )
                // Release only the waiting actor. The operation child and any recomposition remain
                // behind the gate, reproducing DEFAULT launch cancellation before block execution.
                dispatcher.releaseNext()
                runCurrent()

                presenter.close()
                dispatcher.releaseAll()
                runCurrent()

                assertFalse(receipt.awaitAcceptance(1_000L))
                assertTrue(backend.redirects.isEmpty())
            } finally {
                dispatcher.releaseAll()
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun timedOutReceiptSkipsAGatedChildAndClearsBusyStateWhenItResumes() =
        runTest {
            val backend = RecordingAuthenticationBackend()
            val dispatcher = GatedCoroutineDispatcher(StandardTestDispatcher(testScheduler))
            val presenter =
                DiscourseAuthenticationPresenter(
                    backend = backend,
                    dispatcher = dispatcher,
                    testMarker = Unit,
                )
            val models = presenter.models

            try {
                runCurrent()
                dispatcher.hold()
                val receipt =
                    requireNotNull(
                        presenter.completeRedirectWithReceipt(
                            "discourse://auth_redirect?payload=secret&oneTimePassword=secret",
                        ),
                    )
                dispatcher.releaseNext()
                runCurrent()

                assertFalse(receipt.awaitAcceptance(1L))
                dispatcher.releaseAll()
                advanceUntilIdle()

                assertTrue(backend.redirects.isEmpty())
                assertFalse(models.value.isBusy)
            } finally {
                dispatcher.releaseAll()
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun immediatelyCompletedRedirectChildKeepsItsPositiveReceipt() =
        runTest {
            val backend = RecordingAuthenticationBackend()
            backend.completeRedirectHandler = { DiscourseLoginResult.Stale }
            val presenter = presenter(backend)
            val models = presenter.models

            try {
                runCurrent()
                val receipt =
                    requireNotNull(
                        presenter.completeRedirectWithReceipt(
                            "discourse://auth_redirect?payload=secret&oneTimePassword=secret",
                        ),
                    )
                advanceUntilIdle()

                assertTrue(receipt.awaitAcceptance(1_000L))
                assertEquals(1, backend.redirects.size)
                assertFalse(models.value.isBusy)
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun logoutCancelsAndJoinsAnActiveAuthenticationOperation() =
        runTest {
            val operationStarted = CompletableDeferred<Unit>()
            val operationCancelled = CompletableDeferred<Unit>()
            val backend = RecordingAuthenticationBackend()
            backend.session.value =
                DiscourseSessionState.Authenticated(
                    generation = 7L,
                    accountId = "42",
                    username = "member",
                )
            backend.completeRedirectHandler = {
                operationStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    operationCancelled.complete(Unit)
                }
            }
            val presenter = presenter(backend)
            presenter.models

            try {
                runCurrent()
                assertTrue(
                    presenter.completeRedirect(
                        "discourse://auth_redirect?payload=secret&oneTimePassword=secret",
                    ),
                )
                runCurrent()
                assertTrue(operationStarted.isCompleted)

                assertTrue(presenter.dispatch(DiscourseAuthenticationAction.Logout))
                advanceUntilIdle()

                assertTrue(operationCancelled.isCompleted)
                assertEquals(listOf(7L to "42"), backend.logoutOwners)
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun closeAndJoinWaitsForAcceptedBrowserCancellationCleanup() =
        runTest {
            val cleanupStarted = CompletableDeferred<Unit>()
            val allowCleanup = CompletableDeferred<Unit>()
            val cleanupFinished = CompletableDeferred<Unit>()
            var cleanupWasCancelled = false
            val backend = RecordingAuthenticationBackend()
            val presenter = presenter(backend)
            val models = presenter.models

            try {
                runCurrent()
                presenter.dispatch(DiscourseAuthenticationAction.BeginFallbackLogin)
                advanceUntilIdle()
                val request = requireNotNull(models.value.restrictedBrowser)
                backend.clearBrowserCookiesHandler = {
                    cleanupStarted.complete(Unit)
                    try {
                        allowCleanup.await()
                    } catch (cancellation: CancellationException) {
                        cleanupWasCancelled = true
                        throw cancellation
                    } finally {
                        cleanupFinished.complete(Unit)
                    }
                }

                assertTrue(
                    presenter.dispatch(
                        DiscourseAuthenticationAction.CancelRestrictedBrowser(
                            requestId = request.requestId,
                            mode = request.mode,
                        ),
                    ),
                )
                runCurrent()
                assertTrue(cleanupStarted.isCompleted)

                val closing =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        presenter.closeAndJoin()
                    }
                runCurrent()
                assertFalse(cleanupWasCancelled)
                assertFalse(cleanupFinished.isCompleted)
                assertFalse(closing.isCompleted)

                allowCleanup.complete(Unit)
                advanceUntilIdle()
                closing.await()
                assertFalse(cleanupWasCancelled)
                assertTrue(cleanupFinished.isCompleted)
                assertTrue(closing.isCompleted)
            } finally {
                allowCleanup.complete(Unit)
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun closeAndJoinBeforeLazyActorStartupRejectsQueuedReceiptWithoutAdvancingTime() =
        runTest {
            val presenter = presenter(RecordingAuthenticationBackend())
            val receipt =
                requireNotNull(
                    presenter.completeRedirectWithReceipt(
                        "discourse://auth_redirect?payload=secret&oneTimePassword=secret",
                    ),
                )

            val closing =
                async(start = CoroutineStart.UNDISPATCHED) {
                    presenter.closeAndJoin()
                }

            assertTrue(closing.isCompleted)
            closing.await()
            assertFalse(receipt.awaitAcceptance(1_000L))
        }

    @Test
    fun closeThenCloseAndJoinDoesNotInitializeTheCancelledLazyActor() =
        runTest {
            val presenter = presenter(RecordingAuthenticationBackend())
            presenter.close()

            val closing =
                async(start = CoroutineStart.UNDISPATCHED) {
                    presenter.closeAndJoin()
                }

            assertTrue(closing.isCompleted)
            closing.await()
        }

    @Test
    fun backendCancellationDuringBrowserCleanupIsNeverSwallowed() =
        runTest {
            val backend = RecordingAuthenticationBackend()
            val presenter = presenter(backend)
            val models = presenter.models

            try {
                runCurrent()
                presenter.dispatch(DiscourseAuthenticationAction.BeginFallbackLogin)
                advanceUntilIdle()
                val request = requireNotNull(models.value.restrictedBrowser)
                backend.clearBrowserCookiesHandler = {
                    throw CancellationException("fixture backend cancellation")
                }

                assertTrue(
                    presenter.dispatch(
                        DiscourseAuthenticationAction.CancelRestrictedBrowser(
                            requestId = request.requestId,
                            mode = request.mode,
                        ),
                    ),
                )
                advanceUntilIdle()

                // Propagation terminates the actor and closes its bounded input queue. No caller may
                // receive a false acknowledgement for work that can never have another consumer.
                assertFalse(presenter.dispatch(DiscourseAuthenticationAction.BeginFallbackLogin))
                assertFalse(
                    presenter.completeRedirect(
                        "discourse://auth_redirect?payload=secret&oneTimePassword=secret",
                    ),
                )
                assertNull(models.value.restrictedBrowser)
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun operationResultCannotRenderAfterItsSessionGenerationChanges() =
        runTest {
            val backend = RecordingAuthenticationBackend()
            backend.beginAuthorizationHandler = {
                // The operation result is queued before the flow collector can publish this state
                // change, reproducing the ordering that could briefly expose a stale URL.
                backend.session.value = DiscourseSessionState.Guest(generation = 1L)
                backend.authorization
            }
            val presenter = presenter(backend)
            val models = presenter.models

            try {
                runCurrent()
                assertTrue(presenter.dispatch(DiscourseAuthenticationAction.BeginAuthorization))
                advanceUntilIdle()

                assertEquals(DiscourseAuthenticationState(), models.value)
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun independentlyCancelledOperationClearsBusyState() =
        runTest {
            val backend = RecordingAuthenticationBackend()
            backend.beginAuthorizationHandler = { throw CancellationException("fixture cancellation") }
            val presenter = presenter(backend)
            val models = presenter.models

            try {
                runCurrent()
                assertTrue(presenter.dispatch(DiscourseAuthenticationAction.BeginAuthorization))
                advanceUntilIdle()

                assertEquals(DiscourseAuthenticationState(), models.value)
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun initializedPresenterRejectsCommandsAfterClose() =
        runTest {
            val presenter = presenter(RecordingAuthenticationBackend())
            presenter.models
            runCurrent()

            presenter.close()
            runCurrent()

            assertFalse(presenter.dispatch(DiscourseAuthenticationAction.DismissFailure))
            assertFalse(
                presenter.completeRedirect(
                    "discourse://auth_redirect?payload=secret&oneTimePassword=secret",
                ),
            )
        }

    @Test
    fun uninitializedPresenterRejectsCommandsAfterClose() =
        runTest {
            val presenter = presenter(RecordingAuthenticationBackend())

            presenter.close()

            assertFalse(presenter.dispatch(DiscourseAuthenticationAction.DismissFailure))
            assertFalse(
                presenter.completeRedirect(
                    "discourse://auth_redirect?payload=secret&oneTimePassword=secret",
                ),
            )
        }

    @Test
    fun presenterCloseRejectsQueuedTerminalReceiptBeforeActorStartup() =
        runTest {
            val presenter = presenter(RecordingAuthenticationBackend())
            val action =
                DiscourseAuthenticationAction.CancelRestrictedBrowser(
                    requestId = 1L,
                    mode = DiscourseRestrictedBrowserMode.FallbackLogin,
                )

            assertTrue(presenter.dispatch(action))
            presenter.close()

            assertFalse(action.receipt.awaitOwnership(1_000L))
        }

    @Test
    fun saturatedActorQueueImmediatelyRejectsTerminalReceipt() =
        runTest {
            val presenter = presenter(RecordingAuthenticationBackend())
            val queuedActions =
                (1L..24L).map { requestId ->
                    DiscourseAuthenticationAction.CancelRestrictedBrowser(
                        requestId = requestId,
                        mode = DiscourseRestrictedBrowserMode.FallbackLogin,
                    )
                }
            val saturatedAction =
                DiscourseAuthenticationAction.RestrictedBrowserFailed(
                    requestId = 25L,
                    mode = DiscourseRestrictedBrowserMode.FallbackLogin,
                )

            queuedActions.forEach { action -> assertTrue(presenter.dispatch(action)) }
            assertFalse(presenter.dispatch(saturatedAction))
            assertFalse(saturatedAction.receipt.awaitOwnership(1_000L))

            presenter.close()
            queuedActions.forEach { action ->
                assertFalse(action.receipt.awaitOwnership(1_000L))
            }
        }

    private fun TestScope.presenter(backend: RecordingAuthenticationBackend): DiscourseAuthenticationPresenter =
        DiscourseAuthenticationPresenter(
            backend = backend,
            dispatcher = StandardTestDispatcher(testScheduler),
            testMarker = Unit,
        )

    private suspend fun TestScope.assertFallbackCompletionCancelled(
        terminalAction: (DiscourseRestrictedBrowserRequest) -> DiscourseRestrictedBrowserTerminalAction,
        expectedFailure: DiscourseAuthenticationFailureKind?,
    ) {
        val completionStarted = CompletableDeferred<Unit>()
        val completionCancelled = CompletableDeferred<Unit>()
        val backend = RecordingAuthenticationBackend()
        backend.completeWebSessionHandler = {
            completionStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                completionCancelled.complete(Unit)
            }
        }
        val presenter = presenter(backend)
        val models = presenter.models

        try {
            runCurrent()
            assertTrue(presenter.dispatch(DiscourseAuthenticationAction.BeginFallbackLogin))
            advanceUntilIdle()
            val request = requireNotNull(models.value.restrictedBrowser)

            val completeAction =
                DiscourseAuthenticationAction.CompleteRestrictedBrowser(
                    requestId = request.requestId,
                    mode = request.mode,
                )
            assertTrue(presenter.dispatch(completeAction))
            runCurrent()
            assertTrue(completeAction.receipt.awaitOwnership(1_000L))
            assertTrue(completionStarted.isCompleted)

            val terminal = terminalAction(request)
            assertTrue(presenter.dispatch(terminal))
            advanceUntilIdle()

            assertTrue(terminal.receipt.awaitOwnership(1_000L))
            assertTrue(completionCancelled.isCompleted)
            assertTrue(backend.session.value is DiscourseSessionState.Guest)
            assertNull(models.value.restrictedBrowser)
            assertFalse(models.value.isBusy)
            assertEquals(expectedFailure, models.value.failure)
        } finally {
            presenter.close()
            runCurrent()
        }
    }
}

/** Holds selected continuations while the test independently closes or expires their owner. */
private class GatedCoroutineDispatcher(
    private val delegate: CoroutineDispatcher,
) : CoroutineDispatcher() {
    private data class HeldDispatch(
        val context: CoroutineContext,
        val block: Runnable,
    )

    private val heldDispatches = ArrayDeque<HeldDispatch>()
    private var isHolding = false

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        if (isHolding) {
            heldDispatches.addLast(HeldDispatch(context, block))
        } else {
            delegate.dispatch(context, block)
        }
    }

    fun hold() {
        check(!isHolding) { "The test dispatcher is already holding continuations" }
        isHolding = true
    }

    fun releaseNext() {
        val dispatch = heldDispatches.removeFirstOrNull() ?: error("No held continuation is available")
        delegate.dispatch(dispatch.context, dispatch.block)
    }

    fun releaseAll() {
        isHolding = false
        while (heldDispatches.isNotEmpty()) {
            val dispatch = heldDispatches.removeFirst()
            delegate.dispatch(dispatch.context, dispatch.block)
        }
    }
}

private class RecordingAuthenticationBackend : DiscourseAuthenticationBackend {
    val session = MutableStateFlow<DiscourseSessionState>(DiscourseSessionState.Guest(0L))
    val challenge = MutableStateFlow<DiscourseManualChallengeRequest?>(null)
    val calls = mutableListOf<String>()
    val logoutOwners = mutableListOf<Pair<Long, String>>()
    val completedChallenges = mutableListOf<Long>()
    val redirects = mutableListOf<String>()
    var cancelAuthorizationCalls = 0
    var completeWebSessionCalls = 0
    var authorization =
        DiscoursePendingAuthorization(
            url = Url("$DISCOURSE_ORIGIN/user-api-key/new?nonce=fixture"),
            expiresAtEpochMillis = 10_000L,
        )
    var beginAuthorizationHandler: suspend () -> DiscoursePendingAuthorization = { authorization }
    var clearBrowserCookiesHandler: suspend () -> Unit = {}
    var completeWebSessionHandler: suspend () -> DiscourseLoginResult.Authenticated = {
        DiscourseLoginResult.Authenticated("42", "member", null)
    }
    var completeRedirectHandler: suspend (String) -> DiscourseLoginResult = { DiscourseLoginResult.Stale }
    var completeManualChallengeHandler: suspend (Long) -> Boolean = { requestId ->
        completedChallenges += requestId
        challenge.value = null
        true
    }

    override val sessionState = session
    override val manualChallenge = challenge

    override suspend fun beginAuthorization(): DiscoursePendingAuthorization = beginAuthorizationHandler()

    override suspend fun cancelAuthorization(): Boolean {
        cancelAuthorizationCalls += 1
        calls += "cancel-authorization"
        return true
    }

    override suspend fun clearBrowserCookies() {
        calls += "clear-browser-cookies"
        clearBrowserCookiesHandler()
    }

    override suspend fun completeWebSession(): DiscourseLoginResult.Authenticated {
        completeWebSessionCalls += 1
        return completeWebSessionHandler()
    }

    override suspend fun completeRedirect(rawUri: String): DiscourseLoginResult {
        redirects += rawUri
        return completeRedirectHandler(rawUri)
    }

    override suspend fun logout(
        expectedGeneration: Long,
        expectedAccountId: String,
    ): Boolean {
        logoutOwners += expectedGeneration to expectedAccountId
        return true
    }

    override suspend fun completeManualChallengeAfterCookieConsumption(requestId: Long): Boolean = completeManualChallengeHandler(requestId)

    override suspend fun cancelManualChallenge(requestId: Long): Boolean {
        challenge.value = null
        return true
    }
}
