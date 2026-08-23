package dev.dimension.flare.data.network.discourse.realtime

import dev.dimension.flare.data.network.discourse.session.DiscourseSessionLifecycle
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Fails an expired realtime session closed without racing a replacement login.
 *
 * Implementations must compare [DiscourseSessionRecoveryRequest.expectedSessionGeneration] before
 * deleting any state. A delayed 401/403 from an old long poll must never clear credentials that
 * belong to a newer account generation. The boolean result is false when the request was stale,
 * belonged to a guest session, or requires the separate user-mediated Cloudflare flow.
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
        // vault cleanup in NonCancellable context. The pre-check merely avoids entering it for a
        // guest or already replaced session.
        return sessionLifecycle.logoutIfGeneration(request.expectedSessionGeneration)
    }
}
