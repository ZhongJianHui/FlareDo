package dev.dimension.flare.data.network.discourse.realtime

import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
