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
     * Remote invalidation remains best effort because the device can be offline. The local Ktor jar,
     * vault reference, pending authorization, and restricted-browser profile are therefore cleaned in
     * a non-cancellable section before the original transport failure or caller cancellation is
     * propagated. Browser cleanup errors are deliberately suppressed: an unavailable WebView backend
     * must never prevent the authoritative in-memory session and vault reference from being destroyed.
     */
    public suspend fun logout() {
        var remoteFailure: Throwable? = null
        try {
            authorizationCoordinator.cancelPending()
            val authenticated = sessionManager.state.value as? DiscourseSessionState.Authenticated
            authenticated?.username?.let { api.logout(it) }
        } catch (failure: Throwable) {
            remoteFailure = failure
        }

        var localFailure: Throwable? = null
        withContext(NonCancellable) {
            try {
                sessionLifecycle.logout()
            } catch (failure: Throwable) {
                localFailure = failure
            }
            cookieBridge.clearLinuxDoCookiesBestEffort()
        }

        // Cancellation that arrived during non-cancellable cleanup wins over ordinary failures.
        currentCoroutineContext().ensureActive()
        remoteFailure?.let { throw it }
        localFailure?.let { throw it }
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
