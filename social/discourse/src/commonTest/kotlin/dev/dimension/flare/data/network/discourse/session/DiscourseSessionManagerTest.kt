package dev.dimension.flare.data.network.discourse.session

import io.ktor.http.Cookie
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class DiscourseSessionManagerTest {
    @Test
    fun loginCancelsOldGenerationAndReportsAnOrdinaryStaleError() =
        runTest {
            val manager = DiscourseSessionManager()
            val requestEntered = CompletableDeferred<Unit>()
            val requestCleanupRan = CompletableDeferred<Unit>()
            val request =
                async {
                    runCatching {
                        manager.runForCurrentSession {
                            requestEntered.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                requestCleanupRan.complete(Unit)
                            }
                        }
                    }.exceptionOrNull()
                }

            requestEntered.await()
            manager.startAuthenticatedSession(
                accountId = "account-42",
                username = "fixture-user",
            )

            val failure = assertIs<StaleDiscourseSessionException>(request.await())
            assertEquals(0L, failure.expectedGeneration)
            assertEquals(1L, failure.actualGeneration)
            assertTrue(requestCleanupRan.isCompleted)
            val state = assertIs<DiscourseSessionState.Authenticated>(manager.state.value)
            assertEquals(1L, state.generation)
            assertEquals("fixture-user", state.username)
        }

    @Test
    fun callerCancellationRemainsCancellation() =
        runTest {
            val manager = DiscourseSessionManager()
            val entered = CompletableDeferred<Unit>()
            val request =
                launch {
                    manager.runForCurrentSession {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }

            entered.await()
            request.cancelAndJoin()
            assertTrue(request.isCancelled)

            val cancellation =
                assertFailsWith<CancellationException> {
                    manager.runForCurrentSession {
                        throw CancellationException("operation cancelled itself")
                    }
                }
            assertEquals("operation cancelled itself", cancellation.message)
        }

    @Test
    fun logoutAdvancesGenerationAndClearsCookiesAndCsrf() =
        runTest {
            val manager = DiscourseSessionManager()
            manager.startAuthenticatedSession(
                accountId = "account-42",
                cookieSnapshot =
                    listOf(
                        DiscourseCookieSnapshot(
                            name = "_t",
                            value = "session-value",
                            httpOnly = true,
                        ),
                    ),
            )
            var csrfFetches = 0
            assertEquals("csrf-1", manager.csrfToken { "csrf-${++csrfFetches}" })
            assertEquals("csrf-1", manager.csrfToken { "csrf-${++csrfFetches}" })

            manager.logout()

            assertEquals(2L, manager.state.value.generation)
            assertIs<DiscourseSessionState.Guest>(manager.state.value)
            assertTrue(manager.cookieStorage.get(LINUX_DO_ROOT).isEmpty())
            assertEquals("csrf-2", manager.csrfToken { "csrf-${++csrfFetches}" })
        }

    @Test
    fun invalidLoginSnapshotLeavesCurrentSessionUntouched() =
        runTest {
            val manager = DiscourseSessionManager()
            manager.cookieStorage.addCookie(
                LINUX_DO_ROOT,
                Cookie(name = "guest-cookie", value = "kept"),
            )

            assertFailsWith<RejectedDiscourseCookieException> {
                manager.startAuthenticatedSession(
                    accountId = "account-42",
                    cookieSnapshot =
                        listOf(
                            DiscourseCookieSnapshot(
                                name = "foreign",
                                value = "blocked",
                                domain = "example.test",
                            ),
                        ),
                )
            }

            assertEquals(0L, manager.state.value.generation)
            assertEquals(
                "kept",
                manager.cookieStorage
                    .get(LINUX_DO_ROOT)
                    .single()
                    .value,
            )
        }

    private companion object {
        val LINUX_DO_ROOT: Url = Url("https://linux.do/")
    }
}
