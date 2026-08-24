package dev.dimension.flare.ui

import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthenticationAction
import dev.dimension.flare.data.network.discourse.auth.DiscourseRestrictedBrowserMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class AndroidAuthenticationBrowserTest {
    @Test
    fun acceptedTerminalActionTransfersCleanupOwnershipToPresenter() =
        runBlocking {
            var cleanupCalls = 0
            var rejectionCalls = 0
            val action =
                DiscourseAuthenticationAction.CancelRestrictedBrowser(
                    requestId = 1L,
                    mode = DiscourseRestrictedBrowserMode.FallbackLogin,
                )

            assertTrue(
                dispatchAndroidRestrictedBrowserAction(
                    action = action,
                    onAction = { received -> received == action },
                    clearWebStorage = { cleanupCalls += 1 },
                    clearCookies = { cleanupCalls += 1 },
                    onRejected = { rejectionCalls += 1 },
                    awaitOwnership = { _, _ -> true },
                ),
            )
            assertEquals(0, cleanupCalls)
            assertEquals(0, rejectionCalls)
        }

    @Test
    fun actorRejectionAfterChannelAdmissionClearsSecretsAndUnlocks() =
        runBlocking {
            val calls = mutableListOf<String>()
            val action =
                DiscourseAuthenticationAction.CompleteRestrictedBrowser(
                    requestId = 2L,
                    mode = DiscourseRestrictedBrowserMode.FallbackLogin,
                )

            assertFalse(
                dispatchAndroidRestrictedBrowserAction(
                    action = action,
                    onAction = { true },
                    clearWebStorage = { calls += "web-storage" },
                    clearCookies = { calls += "cookies" },
                    onRejected = { calls += "unlock-and-fail" },
                    awaitOwnership = { _, _ -> false },
                ),
            )
            assertEquals(listOf("web-storage", "cookies", "unlock-and-fail"), calls)
        }

    @Test
    fun receiptTimeoutClearsSecretsAndUnlocks() =
        runBlocking {
            var cookiesCleared = false
            var unlocked = false
            val action =
                DiscourseAuthenticationAction.CancelRestrictedBrowser(
                    requestId = 3L,
                    mode = DiscourseRestrictedBrowserMode.ManualChallenge,
                )

            assertFalse(
                dispatchAndroidRestrictedBrowserAction(
                    action = action,
                    onAction = { true },
                    clearWebStorage = {},
                    clearCookies = { cookiesCleared = true },
                    onRejected = { unlocked = true },
                    receiptTimeoutMillis = 1L,
                ),
            )
            assertTrue(cookiesCleared)
            assertTrue(unlocked)
        }

    @Test
    fun callerCancellationWhileAwaitingReceiptCleansAndRethrowsOriginalCancellation() =
        runBlocking {
            val awaitingReceipt = CompletableDeferred<Unit>()
            var cookiesCleared = false
            var unlocked = false
            val action =
                DiscourseAuthenticationAction.RestrictedBrowserFailed(
                    requestId = 4L,
                    mode = DiscourseRestrictedBrowserMode.FallbackLogin,
                )
            val caller =
                async {
                    dispatchAndroidRestrictedBrowserAction(
                        action = action,
                        onAction = { true },
                        clearWebStorage = { throw CancellationException("cleanup cancelled") },
                        clearCookies = { cookiesCleared = true },
                        onRejected = { unlocked = true },
                        awaitOwnership = { _, _ ->
                            awaitingReceipt.complete(Unit)
                            awaitCancellation()
                        },
                    )
                }

            awaitingReceipt.await()
            caller.cancel(CancellationException("caller cancelled"))
            val failure = assertFailsWith<CancellationException> { caller.await() }

            assertEquals("caller cancelled", failure.message)
            assertTrue(cookiesCleared)
            assertTrue(unlocked)
        }

    @Test
    fun promptCancellationAfterActorOwnershipDoesNotClearPresenterHandoff() =
        runBlocking {
            var cleanupCalls = 0
            var rejectionCalls = 0
            val action =
                DiscourseAuthenticationAction.CompleteRestrictedBrowser(
                    requestId = 5L,
                    mode = DiscourseRestrictedBrowserMode.FallbackLogin,
                )

            val failure =
                assertFailsWith<CancellationException> {
                    dispatchAndroidRestrictedBrowserAction(
                        action = action,
                        onAction = { true },
                        clearWebStorage = { cleanupCalls += 1 },
                        clearCookies = { cleanupCalls += 1 },
                        onRejected = { rejectionCalls += 1 },
                        awaitOwnership = { _, _ -> throw CancellationException("caller cancelled") },
                        ownershipTransferred = { true },
                    )
                }

            assertEquals("caller cancelled", failure.message)
            assertEquals(0, cleanupCalls)
            assertEquals(0, rejectionCalls)
        }

    @Test
    fun rejectedTerminalActionClearsSecretsAndUnlocksAfterPartialCleanupFailure() =
        runBlocking {
            val calls = mutableListOf<String>()
            val action =
                DiscourseAuthenticationAction.CompleteRestrictedBrowser(
                    requestId = 2L,
                    mode = DiscourseRestrictedBrowserMode.FallbackLogin,
                )

            assertFalse(
                dispatchAndroidRestrictedBrowserAction(
                    action = action,
                    onAction = { false },
                    clearWebStorage = {
                        calls += "web-storage"
                        error("broken WebStorage backend")
                    },
                    clearCookies = { calls += "cookies" },
                    onRejected = { calls += "unlock-and-fail" },
                ),
            )
            assertEquals(listOf("web-storage", "cookies", "unlock-and-fail"), calls)
        }

    @Test
    fun rejectedTerminalActionUnlockSurvivesCallerCancellation() =
        runBlocking {
            val cleanupStarted = CompletableDeferred<Unit>()
            val allowCleanup = CompletableDeferred<Unit>()
            var unlocked = false
            val action =
                DiscourseAuthenticationAction.CancelRestrictedBrowser(
                    requestId = 3L,
                    mode = DiscourseRestrictedBrowserMode.ManualChallenge,
                )
            val caller =
                launch {
                    dispatchAndroidRestrictedBrowserAction(
                        action = action,
                        onAction = { false },
                        clearWebStorage = {},
                        clearCookies = {
                            cleanupStarted.complete(Unit)
                            allowCleanup.await()
                        },
                        onRejected = { unlocked = true },
                    )
                }

            cleanupStarted.await()
            caller.cancel()
            allowCleanup.complete(Unit)
            caller.join()

            assertTrue(unlocked)
            assertTrue(caller.isCancelled)
        }

    @Test
    fun undispatchedSubmissionEntersCleanupBeforeImmediateScopeCancellation() =
        runBlocking {
            val parent = Job()
            var cookiesCleared = false
            var unlocked = false
            val action =
                DiscourseAuthenticationAction.CompleteRestrictedBrowser(
                    requestId = 4L,
                    mode = DiscourseRestrictedBrowserMode.FallbackLogin,
                )
            val caller =
                CoroutineScope(coroutineContext + parent).launch(start = CoroutineStart.UNDISPATCHED) {
                    dispatchAndroidRestrictedBrowserAction(
                        action = action,
                        onAction = {
                            parent.cancel()
                            false
                        },
                        clearWebStorage = {},
                        clearCookies = { cookiesCleared = true },
                        onRejected = { unlocked = true },
                    )
                }

            caller.join()

            assertTrue(cookiesCleared)
            assertTrue(unlocked)
            assertTrue(caller.isCancelled)
        }

    @Test
    fun explicitCleanupCancellationUnlocksThenPropagates() =
        runBlocking {
            var cookiesCleared = false
            var unlocked = false
            val action =
                DiscourseAuthenticationAction.CancelRestrictedBrowser(
                    requestId = 5L,
                    mode = DiscourseRestrictedBrowserMode.FallbackLogin,
                )

            val failure =
                assertFailsWith<CancellationException> {
                    dispatchAndroidRestrictedBrowserAction(
                        action = action,
                        onAction = { false },
                        clearWebStorage = { throw CancellationException("WebStorage cancelled") },
                        clearCookies = { cookiesCleared = true },
                        onRejected = { unlocked = true },
                    )
                }

            assertEquals("WebStorage cancelled", failure.message)
            assertTrue(cookiesCleared)
            assertTrue(unlocked)
        }

    @Test
    fun fallbackRequiresANonEmptySessionCookie() {
        assertTrue(
            isValidRestrictedCookieHandoff(
                DiscourseRestrictedBrowserMode.FallbackLogin,
                "theme=dark; _t=session-value",
            ),
        )
        assertFalse(
            isValidRestrictedCookieHandoff(
                DiscourseRestrictedBrowserMode.FallbackLogin,
                "theme=dark; _t=",
            ),
        )
        assertFalse(
            isValidRestrictedCookieHandoff(
                DiscourseRestrictedBrowserMode.FallbackLogin,
                "cf_clearance=challenge-only",
            ),
        )
    }

    @Test
    fun challengeRequiresClearanceAndRejectsAnyBrowserAccountCookie() {
        assertTrue(
            isValidRestrictedCookieHandoff(
                DiscourseRestrictedBrowserMode.ManualChallenge,
                "cf_clearance=challenge-value; theme=dark",
            ),
        )
        assertFalse(
            isValidRestrictedCookieHandoff(
                DiscourseRestrictedBrowserMode.ManualChallenge,
                "cf_clearance=challenge-value; _t=other-account",
            ),
        )
        assertFalse(
            isValidRestrictedCookieHandoff(
                DiscourseRestrictedBrowserMode.ManualChallenge,
                "_t=other-account",
            ),
        )
    }

    @Test
    fun handoffRejectsAmbiguousOrControlCharacterHeaders() {
        assertFalse(
            isValidRestrictedCookieHandoff(
                DiscourseRestrictedBrowserMode.FallbackLogin,
                "_t=one; _t=two",
            ),
        )
        assertFalse(
            isValidRestrictedCookieHandoff(
                DiscourseRestrictedBrowserMode.FallbackLogin,
                "_t=one\r\nInjected=value",
            ),
        )
        assertFalse(
            isValidRestrictedCookieHandoff(
                DiscourseRestrictedBrowserMode.FallbackLogin,
                "_t",
            ),
        )
    }

    @Test
    fun requestInterceptorBlocksCrossOriginMainFramePostsBeforeNetwork() {
        // The production interceptor applies this method-independent gate to WebResourceRequest,
        // including POST requests that shouldOverrideUrlLoading never receives.
        assertTrue(
            shouldBlockRestrictedMainFrameRequest(
                isForMainFrame = true,
                rawUrl = "https://evil.invalid/collect",
            ),
        )
        assertTrue(
            shouldBlockRestrictedMainFrameRequest(
                isForMainFrame = true,
                rawUrl = "http://linux.do/session",
            ),
        )
        assertFalse(
            shouldBlockRestrictedMainFrameRequest(
                isForMainFrame = true,
                rawUrl = "https://linux.do/session",
            ),
        )
        assertFalse(
            shouldBlockRestrictedMainFrameRequest(
                isForMainFrame = false,
                rawUrl = "https://cdn.example.invalid/challenge.js",
            ),
        )
    }

    @Test
    fun browserFailureGateDispatchesOnceAcrossConcurrentWebViewCallbacks() {
        val calls = AtomicInteger(0)
        val gate = AndroidBrowserFailureGate { calls.incrementAndGet() }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures =
                List(32) {
                    executor.submit {
                        start.await()
                        gate.report()
                    }
                }
            start.countDown()
            futures.forEach { it.get() }
            assertEquals(1, calls.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun configurationRebuildKeepsACommittedHandoffLockedUntilSharedCompletion() {
        val beforeRebuild =
            isAndroidRestrictedBrowserHandoffLocked(
                sharedHandoffInProgress = true,
                localHandoffStarted = false,
            )
        val afterRebuild =
            isAndroidRestrictedBrowserHandoffLocked(
                sharedHandoffInProgress = true,
                localHandoffStarted = false,
            )

        assertTrue(beforeRebuild)
        assertTrue(afterRebuild)
        assertFalse(
            shouldClearAndroidRestrictedBrowserCookiesOnDispose(
                handoffInProgress = beforeRebuild,
                isChangingConfigurations = true,
            ),
        )
        assertFalse(
            shouldClearAndroidRestrictedBrowserCookiesOnDispose(
                handoffInProgress = afterRebuild,
                isChangingConfigurations = true,
            ),
        )
        assertFalse(shouldClearAndroidRestrictedBrowserWebStorageOnDispose(isChangingConfigurations = true))
    }

    @Test
    fun localProcessMemoryGuardClosesTheClickToActorPublicationWindow() {
        val beforeActorPublishes =
            isAndroidRestrictedBrowserHandoffLocked(
                sharedHandoffInProgress = false,
                localHandoffStarted = true,
            )

        assertTrue(beforeActorPublishes)
        // The local bit prevents a configuration rebuild from resetting the profile, but terminal
        // disposal still clears until either the receipt or shared state proves actor ownership.
        assertTrue(
            shouldClearAndroidRestrictedBrowserCookiesOnDispose(
                handoffInProgress = false,
                isChangingConfigurations = false,
            ),
        )
        assertFalse(
            shouldClearAndroidRestrictedBrowserCookiesOnDispose(
                handoffInProgress = false,
                isChangingConfigurations = true,
            ),
        )
        assertTrue(shouldClearAndroidRestrictedBrowserWebStorageOnDispose(isChangingConfigurations = false))
        assertTrue(shouldClearAndroidRestrictedBrowserCookiesOnDispose(handoffInProgress = false))
    }

    @Test
    fun actorOwnedReceiptPreventsDisposeBeforeSharedHandoffStateIsPublished() {
        assertFalse(
            shouldClearAndroidRestrictedBrowserCookiesOnDispose(
                handoffInProgress = false,
                actorHandoffOwned = true,
                isChangingConfigurations = false,
            ),
        )
        assertTrue(
            shouldClearAndroidRestrictedBrowserCookiesOnDispose(
                handoffInProgress = false,
                actorHandoffOwned = false,
                isChangingConfigurations = false,
            ),
        )
    }

    @Test
    fun preparedRequestRebuildDoesNotClearCookiesAgain() {
        assertTrue(
            shouldPrepareAndroidRestrictedBrowser(
                isPrepared = false,
                handoffInProgress = false,
            ),
        )
        assertFalse(
            shouldPrepareAndroidRestrictedBrowser(
                isPrepared = true,
                handoffInProgress = false,
            ),
        )
        assertFalse(
            shouldClearAndroidRestrictedBrowserCookiesOnDispose(
                handoffInProgress = false,
                isChangingConfigurations = true,
            ),
        )
        assertFalse(shouldClearAndroidRestrictedBrowserWebStorageOnDispose(isChangingConfigurations = true))
        assertFalse(
            shouldPrepareAndroidRestrictedBrowser(
                isPrepared = false,
                handoffInProgress = true,
            ),
        )
    }

    @Test
    fun terminalHandoffClearsWebStorageWithoutRacingCookieSnapshot() {
        assertTrue(shouldClearAndroidRestrictedBrowserWebStorageOnDispose(isChangingConfigurations = false))
        assertFalse(
            shouldClearAndroidRestrictedBrowserCookiesOnDispose(
                handoffInProgress = true,
                isChangingConfigurations = false,
            ),
        )
    }

    @Test
    fun processRequestStateSurvivesOnlyConfigurationRebuild() {
        val key =
            AndroidRestrictedBrowserProcessRequestKey(
                requestId = 1L,
                mode = DiscourseRestrictedBrowserMode.FallbackLogin,
            )
        val process = AndroidRestrictedBrowserProcessStateStore()
        val beforeRebuild = process.acquire(key)
        beforeRebuild.isPrepared.value = true
        beforeRebuild.localHandoffStarted.value = true

        process.release(beforeRebuild, isChangingConfigurations = true)
        val afterRebuild = process.acquire(key)

        assertTrue(afterRebuild === beforeRebuild)
        assertTrue(afterRebuild.isPrepared.value)
        assertTrue(afterRebuild.localHandoffStarted.value)

        process.release(afterRebuild, isChangingConfigurations = false)
        val afterRealClose = process.acquire(key)
        assertFalse(afterRealClose === afterRebuild)
        assertFalse(afterRealClose.isPrepared.value)
        assertFalse(afterRealClose.localHandoffStarted.value)

        // A fresh process has no marker even if Android restores a colliding request id.
        val afterProcessDeath = AndroidRestrictedBrowserProcessStateStore().acquire(key)
        assertFalse(afterProcessDeath.isPrepared.value)
        assertFalse(afterProcessDeath.localHandoffStarted.value)
    }

    @Test
    fun differentRequestAlwaysGetsAFreshBrowserProfileMarker() {
        val process = AndroidRestrictedBrowserProcessStateStore()
        val first =
            process.acquire(
                AndroidRestrictedBrowserProcessRequestKey(
                    requestId = 1L,
                    mode = DiscourseRestrictedBrowserMode.FallbackLogin,
                ),
            )
        first.isPrepared.value = true

        val second =
            process.acquire(
                AndroidRestrictedBrowserProcessRequestKey(
                    requestId = 2L,
                    mode = DiscourseRestrictedBrowserMode.FallbackLogin,
                ),
            )

        assertFalse(second === first)
        assertFalse(second.isPrepared.value)
        assertTrue(shouldPrepareAndroidRestrictedBrowser(second.isPrepared.value, handoffInProgress = false))
    }
}
