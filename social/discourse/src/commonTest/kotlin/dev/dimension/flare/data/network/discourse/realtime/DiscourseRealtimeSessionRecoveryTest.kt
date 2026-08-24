package dev.dimension.flare.data.network.discourse.realtime

import dev.dimension.flare.data.network.discourse.session.DiscourseCookieSnapshot
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionLifecycle
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionState
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionStore
import dev.dimension.flare.data.network.discourse.session.PersistedDiscourseSession
import dev.dimension.flare.data.network.discourse.session.SecureCredentialRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class DiscourseRealtimeSessionRecoveryTest {
    @Test
    fun exactAuthenticatedGenerationIsLoggedOutAfterTerminalFailure() =
        runTest {
            val manager = DiscourseSessionManager()
            manager.startAuthenticatedSession(accountId = "42", username = "member")
            val generation = manager.state.value.generation
            val recovery = MemoryDiscourseRealtimeSessionRecovery(manager)

            assertTrue(
                recovery.recover(
                    DiscourseSessionRecoveryRequest(
                        expectedSessionGeneration = generation,
                        reason = DiscourseSessionRecoveryReason.AuthenticationRequired,
                    ),
                ),
            )

            val guest = assertIs<DiscourseSessionState.Guest>(manager.state.value)
            assertEquals(generation + 1L, guest.generation)
            assertTrue(manager.cookieStorage.snapshot().isEmpty())
        }

    @Test
    fun delayedFailureCannotClearAReplacementGeneration() =
        runTest {
            val manager = DiscourseSessionManager()
            manager.startAuthenticatedSession(accountId = "42", username = "old")
            val staleGeneration = manager.state.value.generation
            manager.startAuthenticatedSession(accountId = "84", username = "replacement")
            val replacement = assertIs<DiscourseSessionState.Authenticated>(manager.state.value)
            val recovery = MemoryDiscourseRealtimeSessionRecovery(manager)

            assertFalse(
                recovery.recover(
                    DiscourseSessionRecoveryRequest(
                        expectedSessionGeneration = staleGeneration,
                        reason = DiscourseSessionRecoveryReason.PermissionDenied,
                    ),
                ),
            )

            assertEquals(replacement, manager.state.value)
        }

    @Test
    fun manualChallengeNeverPerformsAutomaticCredentialCleanup() =
        runTest {
            val manager = DiscourseSessionManager()
            manager.startAuthenticatedSession(accountId = "42", username = "member")
            val authenticated = assertIs<DiscourseSessionState.Authenticated>(manager.state.value)
            val recovery = MemoryDiscourseRealtimeSessionRecovery(manager)

            assertFalse(
                recovery.recover(
                    DiscourseSessionRecoveryRequest(
                        expectedSessionGeneration = authenticated.generation,
                        reason = DiscourseSessionRecoveryReason.ManualChallengeRequired,
                    ),
                ),
            )

            assertEquals(authenticated, manager.state.value)
        }

    @Test
    fun persistedRecoveryFailureKeepsOwnerAndRequestsUserRecovery() =
        runTest {
            val manager = DiscourseSessionManager()
            val reference = SecureCredentialRef("realtime-owner")
            manager.startAuthenticatedSession(
                accountId = "42",
                username = "member",
                credentialRef = reference,
                cookieSnapshot = listOf(realtimeSessionCookie()),
            )
            val owner = assertIs<DiscourseSessionState.Authenticated>(manager.state.value)
            val store = FailingRecoverySessionStore(IllegalStateException("vault unavailable"))
            val recovery =
                PersistedDiscourseRealtimeSessionRecovery(
                    sessionManager = manager,
                    sessionLifecycle = DiscourseSessionLifecycle(manager, store),
                )

            assertFalse(
                recovery.recover(
                    DiscourseSessionRecoveryRequest(
                        expectedSessionGeneration = owner.generation,
                        reason = DiscourseSessionRecoveryReason.AuthenticationRequired,
                    ),
                ),
            )

            assertEquals(owner, manager.state.value)
            assertEquals(reference, store.clearedReference)
            assertEquals(
                "active-session",
                manager.cookieStorage
                    .snapshot()
                    .single()
                    .value,
            )
        }

    @Test
    fun persistedRecoveryNeverSwallowsCancellation() =
        runTest {
            val manager = DiscourseSessionManager()
            manager.startAuthenticatedSession(
                accountId = "42",
                username = "member",
                credentialRef = SecureCredentialRef("realtime-owner"),
                cookieSnapshot = listOf(realtimeSessionCookie()),
            )
            val owner = assertIs<DiscourseSessionState.Authenticated>(manager.state.value)
            val recovery =
                PersistedDiscourseRealtimeSessionRecovery(
                    sessionManager = manager,
                    sessionLifecycle =
                        DiscourseSessionLifecycle(
                            manager,
                            FailingRecoverySessionStore(CancellationException("recovery cancelled")),
                        ),
                )

            val failure =
                assertFailsWith<CancellationException> {
                    recovery.recover(
                        DiscourseSessionRecoveryRequest(
                            expectedSessionGeneration = owner.generation,
                            reason = DiscourseSessionRecoveryReason.PermissionDenied,
                        ),
                    )
                }

            assertEquals("recovery cancelled", failure.message)
            assertEquals(owner, manager.state.value)
        }
}

private class FailingRecoverySessionStore(
    private val failure: Throwable,
) : DiscourseSessionStore {
    var clearedReference: SecureCredentialRef? = null
        private set

    override suspend fun replace(
        accountId: String,
        username: String?,
        cookies: List<DiscourseCookieSnapshot>,
    ): SecureCredentialRef = error("Replacement is not expected during recovery")

    override suspend fun restore(): PersistedDiscourseSession? = null

    override suspend fun clear(expectedCredentialRef: SecureCredentialRef?) {
        clearedReference = expectedCredentialRef
        throw failure
    }
}

private fun realtimeSessionCookie(): DiscourseCookieSnapshot =
    DiscourseCookieSnapshot(
        name = "_t",
        value = "active-session",
        httpOnly = true,
    )
