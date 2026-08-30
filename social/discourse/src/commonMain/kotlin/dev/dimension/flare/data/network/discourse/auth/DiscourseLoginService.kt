package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.network.discourse.DiscourseApi
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionLifecycle
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Host-facing result which never carries a callback URI or temporary authorization secret. */
public sealed interface DiscourseLoginResult {
    public data class Authenticated(
        public val accountId: String,
        public val username: String,
        public val displayName: String?,
    ) : DiscourseLoginResult

    public data object Stale : DiscourseLoginResult

    public data object Expired : DiscourseLoginResult

    public data class Malformed(
        public val reason: DiscourseAuthMalformedReason,
    ) : DiscourseLoginResult
}

/**
 * Complete shared login facade used by Android, iOS, Windows, Linux, and macOS hosts.
 *
 * Cryptographic callback validation happens before the network exchange. Once an authenticated
 * `_t` cookie exists, [DiscourseSessionLifecycle.activate] writes it to the platform vault before
 * publishing the authenticated state. Any exchange or persistence failure clears the guest jar and
 * increments the session generation, preventing a half-completed login from leaking into browsing.
 */
public class DiscourseLoginService(
    private val authorizationCoordinator: DiscourseAuthorizationCoordinator,
    private val redirectProcessor: DiscourseAuthRedirectProcessor,
    private val exchangeTransport: DiscourseOtpSessionExchangeTransport,
    private val sessionLifecycle: DiscourseSessionLifecycle,
    private val sessionManager: DiscourseSessionManager,
    private val cookieBridge: DiscourseWebSessionCookieBridge,
    private val api: DiscourseApi,
) {
    public suspend fun beginAuthorization(): DiscoursePendingAuthorization {
        if (sessionManager.state.value !is DiscourseSessionState.Guest) {
            throw DiscourseAuthExchangeException(DiscourseAuthExchangeFailure.ActiveSession)
        }
        return authorizationCoordinator.begin()
    }

    public suspend fun completeRedirect(rawUri: String): DiscourseLoginResult {
        val guest =
            sessionManager.state.value as? DiscourseSessionState.Guest
                ?: throw DiscourseAuthExchangeException(DiscourseAuthExchangeFailure.ActiveSession)
        val expectedGeneration = guest.generation
        return when (val redirect = redirectProcessor.process(rawUri)) {
            DiscourseAuthRedirectResult.Stale -> {
                DiscourseLoginResult.Stale
            }

            DiscourseAuthRedirectResult.Expired -> {
                DiscourseLoginResult.Expired
            }

            is DiscourseAuthRedirectResult.Malformed -> {
                DiscourseLoginResult.Malformed(redirect.reason)
            }

            is DiscourseAuthRedirectResult.Accepted -> {
                completeAcceptedRedirect(redirect, expectedGeneration)
            }
        }
    }

    /** Restores only a vault-backed cookie snapshot which passes the strict shared validator. */
    public suspend fun restoreSession(): Boolean = sessionLifecycle.restore()

    /** Cancels an unfinished browser flow and deletes its one-use private key. */
    public suspend fun cancelAuthorization(): Boolean = authorizationCoordinator.cancelPending()

    /**
     * Destroys every app-owned copy of the web session even when remote logout fails or is cancelled.
     *
     * Remote invalidation remains best effort because the device can be offline. Vault invalidation,
     * the generation CAS, and restricted-browser cleanup run in a non-cancellable section before the
     * original transport failure or caller cancellation is propagated. If persistence cannot make its
     * reference unreachable, the authenticated in-memory owner is deliberately retained so restart
     * cannot reverse a falsely published logout; its browser Cookie is still cleared while that exact
     * owner remains current. Browser cleanup errors never replace the authoritative local result.
     */
    public suspend fun logout() {
        val owner = sessionManager.state.value as? DiscourseSessionState.Authenticated ?: return
        logout(owner.generation, owner.accountId)
    }

    /**
     * Logs out only the exact authenticated owner captured by a host snapshot.
     *
     * The initial comparison rejects callbacks that were already stale when invoked. The lifecycle
     * performs the final generation CAS under its persistence mutex after remote invalidation, so a
     * login that replaces this owner while the request is suspended keeps its vault reference and
     * Cookie jar. Browser cookies are cleared after that CAS succeeds, or after a persistence failure
     * only while a second lifecycle owner check still proves the original generation is active.
     */
    public suspend fun logout(
        expectedGeneration: Long,
        expectedAccountId: String,
    ): Boolean {
        require(expectedGeneration >= 0L) { "Expected session generation cannot be negative" }
        require(expectedAccountId.isNotBlank()) { "Expected account id must not be blank" }
        val authenticated =
            (sessionManager.state.value as? DiscourseSessionState.Authenticated)
                ?.takeIf {
                    it.generation == expectedGeneration &&
                        it.accountId == expectedAccountId
                } ?: return false

        var remoteFailure: Throwable? = null
        try {
            authorizationCoordinator.cancelPending()
            authenticated.username?.let {
                api.logout(
                    username = it,
                    expectedSessionGeneration = expectedGeneration,
                    expectedAccountId = expectedAccountId,
                )
            }
        } catch (failure: Throwable) {
            remoteFailure = failure
        }

        var localFailure: Throwable? = null
        var browserCleanupFailure: Throwable? = null
        var clearedOwnedSession = false
        withContext(NonCancellable) {
            try {
                clearedOwnedSession = sessionLifecycle.logoutIfGeneration(expectedGeneration)
            } catch (failure: Throwable) {
                localFailure = failure
            }
            if (clearedOwnedSession) {
                try {
                    cookieBridge.clearLinuxDoCookiesBestEffort()
                } catch (cleanupFailure: Throwable) {
                    browserCleanupFailure = cleanupFailure
                }
            } else if (localFailure != null) {
                try {
                    // Persistence failed before the generation CAS, so the old owner remains active.
                    // Re-entering the lifecycle mutex prevents a queued replacement from having its
                    // browser profile cleared after it acquires ownership of the persisted slot.
                    sessionLifecycle.runForAuthenticatedOwner(
                        expectedGeneration = expectedGeneration,
                        expectedAccountId = expectedAccountId,
                    ) {
                        cookieBridge.clearLinuxDoCookiesBestEffort()
                    }
                } catch (cleanupFailure: Throwable) {
                    browserCleanupFailure = cleanupFailure
                }
            }
        }

        // Cancellation that arrived during non-cancellable cleanup wins over ordinary failures,
        // while every secondary transport/persistence/browser error remains available for diagnosis.
        val cancellationFailure: CancellationException? =
            try {
                currentCoroutineContext().ensureActive()
                null
            } catch (cancellation: CancellationException) {
                cancellation
            }
        if (!clearedOwnedSession && localFailure == null && cancellationFailure == null) return false
        val primaryFailure = cancellationFailure ?: remoteFailure ?: localFailure ?: browserCleanupFailure
        if (primaryFailure != null) {
            remoteFailure?.let(primaryFailure::addSuppressedIfDistinct)
            localFailure?.let(primaryFailure::addSuppressedIfDistinct)
            browserCleanupFailure?.let(primaryFailure::addSuppressedIfDistinct)
            throw primaryFailure
        }
        return true
    }

    /** Clears a guest or stale session slot without requiring an authenticated account owner. */
    public suspend fun clearSession(expectedGeneration: Long): Boolean {
        require(expectedGeneration >= 0L) { "Expected session generation cannot be negative" }
        return withContext(NonCancellable) {
            val cleared = sessionLifecycle.logoutIfGeneration(expectedGeneration)
            if (cleared) {
                // A recovery action may be the only cleanup path after the in-memory owner was
                // already lost. Never clear a newer generation's browser profile.
                cookieBridge.clearLinuxDoCookiesBestEffort()
            }
            cleared
        }
    }

    private suspend fun completeAcceptedRedirect(
        redirect: DiscourseAuthRedirectResult.Accepted,
        expectedGeneration: Long,
    ): DiscourseLoginResult {
        try {
            val exchanged = exchangeTransport.exchange(redirect, expectedGeneration)
            sessionLifecycle.activate(
                expectedGeneration = expectedGeneration,
                accountId = exchanged.accountId,
                username = exchanged.username,
                cookies = exchanged.copyCookies(),
            )
            return DiscourseLoginResult.Authenticated(
                accountId = exchanged.accountId,
                username = exchanged.username,
                displayName = exchanged.displayName,
            )
        } catch (cancellation: CancellationException) {
            clearIncompleteSession(expectedGeneration)
            throw cancellation
        } catch (failure: Throwable) {
            clearIncompleteSession(expectedGeneration)
            throw failure
        }
    }

    private suspend fun clearIncompleteSession(expectedGeneration: Long) {
        withContext(NonCancellable) {
            sessionManager.logoutIfGeneration(expectedGeneration)
        }
    }
}
