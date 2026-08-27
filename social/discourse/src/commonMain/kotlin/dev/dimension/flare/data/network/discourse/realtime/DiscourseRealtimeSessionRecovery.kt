package dev.dimension.flare.data.network.discourse.realtime

import dev.dimension.flare.data.network.discourse.session.DiscourseSessionLifecycle
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Fails an expired realtime session closed without racing a replacement login.
 *
 * Implementations must compare [DiscourseSessionRecoveryRequest.expectedSessionGeneration] before
 * deleting any state. A delayed 401/403 from an old long poll must never clear credentials that
 * belong to a newer account generation. The boolean result is false when the request was stale,
 * belonged to a guest session, had a failed or exhausted user-mediated Cloudflare handoff, or local
 * persistence could not safely make the current vault reference unreachable. A successful
 * challenge handoff is consumed by [DiscourseRealtimeCoordinator] before this recovery callback.
 */
public fun interface DiscourseRealtimeSessionRecovery {
    public suspend fun recover(request: DiscourseSessionRecoveryRequest): Boolean
}

/** Process-only fallback used by anonymous graphs that do not install authentication persistence. */
internal class MemoryDiscourseRealtimeSessionRecovery(
    private val sessionManager: DiscourseSessionManager,
) : DiscourseRealtimeSessionRecovery {
    override suspend fun recover(request: DiscourseSessionRecoveryRequest): Boolean {
        if (request.reason == DiscourseSessionRecoveryReason.ManualChallengeRequired) return false
        val current = sessionManager.state.value
        if (
            current !is DiscourseSessionState.Authenticated ||
            current.generation != request.expectedSessionGeneration
        ) {
            return false
        }
        return withContext(NonCancellable) {
            sessionManager.logoutIfGeneration(request.expectedSessionGeneration)
        }
    }
}

/** Production recovery that clears the in-memory jar and its exact platform-vault reference. */
internal class PersistedDiscourseRealtimeSessionRecovery(
    private val sessionManager: DiscourseSessionManager,
    private val sessionLifecycle: DiscourseSessionLifecycle,
) : DiscourseRealtimeSessionRecovery {
    override suspend fun recover(request: DiscourseSessionRecoveryRequest): Boolean {
        if (request.reason == DiscourseSessionRecoveryReason.ManualChallengeRequired) return false
        val current = sessionManager.state.value
        if (
            current !is DiscourseSessionState.Authenticated ||
            current.generation != request.expectedSessionGeneration
        ) {
            return false
        }
        // The lifecycle owns a second generation CAS under its persistence mutex and performs the
        // vault cleanup in NonCancellable context. Persistence failure intentionally keeps the owner
        // authenticated; reporting false lets the presenter surface recovery UI instead of losing
        // this terminal callback to a failed cleanup exception.
        return try {
            sessionLifecycle.logoutIfGeneration(request.expectedSessionGeneration)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        }
    }
}
