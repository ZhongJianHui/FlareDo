package dev.dimension.flare.ui

import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthenticationAction
import dev.dimension.flare.data.network.discourse.auth.DiscourseExternalAuthorization
import dev.dimension.flare.data.network.discourse.auth.DiscourseRestrictedBrowserMode
import dev.nucleusframework.webview.cookie.Cookie
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class DesktopAuthenticationBrowserTest {
    @Test
    fun acceptedTerminalActionTransfersCleanupOwnershipToPresenter() =
        runTest {
            var cleanupCalls = 0
            var rejectionCalls = 0
            val action =
                DiscourseAuthenticationAction.CancelRestrictedBrowser(
                    requestId = 1L,
                    mode = DiscourseRestrictedBrowserMode.FallbackLogin,
                )

            assertTrue(
                dispatchDesktopRestrictedBrowserAction(
                    action = action,
                    onAction = { received -> received == action },
                    clearBrowserState = { cleanupCalls += 1 },
                    onRejected = { rejectionCalls += 1 },
                    awaitOwnership = { _, _ -> true },
                ),
            )
            assertEquals(0, cleanupCalls)
            assertEquals(0, rejectionCalls)
        }

    @Test
    fun actorRejectionAfterChannelAdmissionClearsHandoffAndUnlocks() =
        runTest {
            val calls = mutableListOf<String>()
            val action =
                DiscourseAuthenticationAction.CompleteRestrictedBrowser(
                    requestId = 2L,
                    mode = DiscourseRestrictedBrowserMode.ManualChallenge,
                )

            assertFalse(
                dispatchDesktopRestrictedBrowserAction(
                    action = action,
                    onAction = { true },
                    clearBrowserState = { calls += "clear-handoff" },
                    onRejected = { calls += "unlock-and-fail" },
                    awaitOwnership = { _, _ -> false },
                ),
            )
            assertEquals(listOf("clear-handoff", "unlock-and-fail"), calls)
        }

    @Test
    fun receiptTimeoutClearsHandoffAndUnlocks() =
        runTest {
            var cleared = false
            var unlocked = false
            val action =
                DiscourseAuthenticationAction.CancelRestrictedBrowser(
                    requestId = 3L,
                    mode = DiscourseRestrictedBrowserMode.FallbackLogin,
                )

            assertFalse(
                dispatchDesktopRestrictedBrowserAction(
                    action = action,
                    onAction = { true },
                    clearBrowserState = { cleared = true },
                    onRejected = { unlocked = true },
                    receiptTimeoutMillis = 100L,
                ),
            )
            assertTrue(cleared)
            assertTrue(unlocked)
        }

    @Test
    fun callerCancellationWhileAwaitingReceiptCleansAndRethrowsOriginalCancellation() =
        runTest {
            val awaitingReceipt = CompletableDeferred<Unit>()
            var cleared = false
            var unlocked = false
            val action =
                DiscourseAuthenticationAction.RestrictedBrowserFailed(
                    requestId = 4L,
                    mode = DiscourseRestrictedBrowserMode.ManualChallenge,
                )
            val caller =
                async {
                    dispatchDesktopRestrictedBrowserAction(
                        action = action,
                        onAction = { true },
                        clearBrowserState = {
                            cleared = true
                            throw CancellationException("cleanup cancelled")
                        },
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
            assertTrue(cleared)
            assertTrue(unlocked)
        }

    @Test
    fun promptCancellationAfterActorOwnershipDoesNotClearPresenterHandoff() =
        runTest {
            var cleanupCalls = 0
            var rejectionCalls = 0
            val action =
                DiscourseAuthenticationAction.CompleteRestrictedBrowser(
                    requestId = 5L,
                    mode = DiscourseRestrictedBrowserMode.ManualChallenge,
                )

            val failure =
                assertFailsWith<CancellationException> {
                    dispatchDesktopRestrictedBrowserAction(
                        action = action,
                        onAction = { true },
                        clearBrowserState = { cleanupCalls += 1 },
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
    fun outerCancellationCleanupSkipsActorOwnedHandoff() =
        runTest {
            var cleanupCalls = 0
            val cancellation = CancellationException("caller cancelled")
            val action =
                DiscourseAuthenticationAction.CompleteRestrictedBrowser(
                    requestId = 6L,
                    mode = DiscourseRestrictedBrowserMode.FallbackLogin,
                )

            cleanupDesktopRestrictedBrowserAfterCallerCancellation(
                action = action,
                cancellation = cancellation,
                clearBrowserState = { cleanupCalls += 1 },
                ownershipTransferred = { true },
            )

            assertEquals(0, cleanupCalls)
            assertTrue(cancellation.suppressed.isEmpty())
        }

    @Test
    fun outerCancellationCleanupPreservesOriginalCancellationWhenNotOwned() =
        runTest {
            var cleanupCalls = 0
            val cancellation = CancellationException("caller cancelled")
            val action =
                DiscourseAuthenticationAction.CompleteRestrictedBrowser(
                    requestId = 7L,
                    mode = DiscourseRestrictedBrowserMode.ManualChallenge,
                )

            cleanupDesktopRestrictedBrowserAfterCallerCancellation(
                action = action,
                cancellation = cancellation,
                clearBrowserState = {
                    cleanupCalls += 1
                    throw CancellationException("cleanup cancelled")
                },
                ownershipTransferred = { false },
            )

            assertEquals(1, cleanupCalls)
            assertEquals("caller cancelled", cancellation.message)
            assertEquals("cleanup cancelled", cancellation.suppressed.single().message)
        }

    @Test
    fun disposePreservesOnlyAnActorOwnedCompleteHandoff() {
        assertFalse(
            shouldClearDesktopRestrictedBrowserCookiesOnDispose(actorHandoffOwned = true),
        )
        assertTrue(
            shouldClearDesktopRestrictedBrowserCookiesOnDispose(actorHandoffOwned = false),
        )
    }

    @Test
    fun rejectedTerminalActionClearsHandoffAndUnlocksAfterCleanupFailure() =
        runTest {
            val calls = mutableListOf<String>()
            val action =
                DiscourseAuthenticationAction.CompleteRestrictedBrowser(
                    requestId = 2L,
                    mode = DiscourseRestrictedBrowserMode.ManualChallenge,
                )

            assertFalse(
                dispatchDesktopRestrictedBrowserAction(
                    action = action,
                    onAction = { false },
                    clearBrowserState = {
                        calls += "clear-handoff"
                        error("broken native WebView backend")
                    },
                    onRejected = { calls += "unlock-and-fail" },
                ),
            )
            assertEquals(listOf("clear-handoff", "unlock-and-fail"), calls)
        }

    @Test
    fun cleanupCancellationUnlocksThenPropagates() =
        runTest {
            var unlocked = false
            val action =
                DiscourseAuthenticationAction.CancelRestrictedBrowser(
                    requestId = 3L,
                    mode = DiscourseRestrictedBrowserMode.FallbackLogin,
                )

            val failure =
                assertFailsWith<CancellationException> {
                    dispatchDesktopRestrictedBrowserAction(
                        action = action,
                        onAction = { false },
                        clearBrowserState = { throw CancellationException("WebView cleanup cancelled") },
                        onRejected = { unlocked = true },
                    )
                }

            assertEquals("WebView cleanup cancelled", failure.message)
            assertTrue(unlocked)
        }

    @Test
    fun systemAuthorizationDispatchesOnlyTheValidatedUrlAndReportsProviderFailure() =
        runTest {
            val request =
                DiscourseExternalAuthorization(
                    requestId = 1L,
                    url = "https://linux.do/user-api-key/new?nonce=public-value",
                    expiresAtEpochMillis = 10_000L,
                )
            var openedUrl: String? = null

            assertTrue(
                openDesktopSystemAuthorization(request) { rawUrl ->
                    openedUrl = rawUrl
                    true
                },
            )
            assertEquals(request.url, openedUrl)
            assertFalse(openDesktopSystemAuthorization(request) { false })
        }

    @Test
    fun readinessPollingCompletesAfterMountAndTimesOutDeterministically() =
        runTest {
            var ready = false
            val eventuallyReady =
                backgroundScope.async {
                    awaitDesktopWebViewReady(timeoutMillis = 1_000L, pollMillis = 25L) { ready }
                }
            advanceTimeBy(50L)
            ready = true
            advanceTimeBy(25L)
            assertTrue(eventuallyReady.await())

            assertFalse(
                awaitDesktopWebViewReady(timeoutMillis = 100L, pollMillis = 25L) { false },
            )
        }

    @Test
    fun fallbackCopiesOnlyValidatedFixedOriginCookiesAndRequiresSession() {
        val destination = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)

        assertTrue(
            copyRestrictedDesktopCookies(
                mode = DiscourseRestrictedBrowserMode.FallbackLogin,
                cookies =
                    listOf(
                        desktopCookie("theme", "dark"),
                        desktopCookie("_t", "session-value", httpOnly = true),
                        desktopCookie("ignored", "value", domain = "linux.do.evil.invalid"),
                    ),
                destination = destination,
                nowEpochSeconds = 1_000L,
            ),
        )

        val stored = destination.cookieStore.get(URI.create("https://linux.do/"))
        assertEquals(setOf("theme", "_t"), stored.map { it.name }.toSet())
        assertTrue(stored.all { it.secure })
        assertTrue(stored.single { it.name == "_t" }.isHttpOnly)
    }

    @Test
    fun fallbackWithoutSessionFailsClosedAndClearsAnOlderHandoff() {
        val destination = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        destination.cookieStore.add(
            URI.create("https://linux.do/"),
            java.net.HttpCookie("_t", "older-session").apply { domain = "linux.do" },
        )

        assertFalse(
            copyRestrictedDesktopCookies(
                mode = DiscourseRestrictedBrowserMode.FallbackLogin,
                cookies = listOf(desktopCookie("theme", "dark")),
                destination = destination,
                nowEpochSeconds = 1_000L,
            ),
        )
        // A rejected source is never merged with a previous one-use bridge snapshot.
        assertTrue(destination.cookieStore.get(URI.create("https://linux.do/")).isEmpty())
    }

    @Test
    fun challengeCopiesClearanceButRejectsAnyEmbeddedAccountSession() {
        val destination = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        assertTrue(
            copyRestrictedDesktopCookies(
                mode = DiscourseRestrictedBrowserMode.ManualChallenge,
                cookies = listOf(desktopCookie("cf_clearance", "challenge-value")),
                destination = destination,
                nowEpochSeconds = 1_000L,
            ),
        )
        assertEquals(
            listOf("cf_clearance"),
            destination.cookieStore.get(URI.create("https://linux.do/")).map { it.name },
        )

        assertFalse(
            copyRestrictedDesktopCookies(
                mode = DiscourseRestrictedBrowserMode.ManualChallenge,
                cookies =
                    listOf(
                        desktopCookie("cf_clearance", "challenge-value"),
                        desktopCookie("_t", "other-account"),
                    ),
                destination = destination,
                nowEpochSeconds = 1_000L,
            ),
        )
        assertTrue(destination.cookieStore.get(URI.create("https://linux.do/")).isEmpty())
    }

    @Test
    fun futureExpiringRequiredCookiesKeepTheirSecondBasedLifetime() {
        val cases =
            listOf(
                Triple(DiscourseRestrictedBrowserMode.FallbackLogin, "_t", 1_120L),
                Triple(DiscourseRestrictedBrowserMode.ManualChallenge, "cf_clearance", 1_300L),
            )

        cases.forEach { (mode, name, expiresAtEpochSeconds) ->
            val destination = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)

            assertTrue(
                copyRestrictedDesktopCookies(
                    mode = mode,
                    cookies =
                        listOf(
                            desktopCookie(
                                name = name,
                                value = "future-value",
                                expiresAtEpochSeconds = expiresAtEpochSeconds,
                            ),
                        ),
                    destination = destination,
                    nowEpochSeconds = 1_000L,
                ),
            )
            val stored = destination.cookieStore.get(URI.create("https://linux.do/")).single()
            assertEquals(name, stored.name)
            assertEquals(expiresAtEpochSeconds - 1_000L, stored.maxAge)
        }
    }

    @Test
    fun expiredRequiredCookiesAreRejectedForFallbackAndChallenge() {
        val cases =
            listOf(
                DiscourseRestrictedBrowserMode.FallbackLogin to "_t",
                DiscourseRestrictedBrowserMode.ManualChallenge to "cf_clearance",
            )

        cases.forEach { (mode, name) ->
            val destination = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)

            assertFalse(
                copyRestrictedDesktopCookies(
                    mode = mode,
                    cookies =
                        listOf(
                            desktopCookie(
                                name = name,
                                value = "expired-value",
                                expiresAtEpochSeconds = 1_000L,
                            ),
                        ),
                    destination = destination,
                    nowEpochSeconds = 1_000L,
                ),
            )
            assertTrue(destination.cookieStore.get(URI.create("https://linux.do/")).isEmpty())
        }
    }

    private fun desktopCookie(
        name: String,
        value: String,
        domain: String = "linux.do",
        path: String = "/",
        httpOnly: Boolean = false,
        expiresAtEpochSeconds: Long? = null,
    ): Cookie =
        Cookie(
            name = name,
            value = value,
            domain = domain,
            path = path,
            expiresDate = expiresAtEpochSeconds,
            isSessionOnly = expiresAtEpochSeconds == null,
            maxAge = null,
            sameSite = null,
            isSecure = true,
            isHttpOnly = httpOnly,
        )
}
