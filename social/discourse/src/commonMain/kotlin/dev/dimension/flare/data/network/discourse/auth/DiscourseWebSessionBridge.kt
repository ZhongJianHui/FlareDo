package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.network.discourse.DISCOURSE_ORIGIN
import dev.dimension.flare.data.network.discourse.DiscourseApi
import dev.dimension.flare.data.network.discourse.error.DiscourseCloudflareChallengeException
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieSnapshot
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionLifecycle
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionState
import dev.dimension.flare.data.network.discourse.session.StaleDiscourseSessionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val DEFAULT_MANUAL_CHALLENGE_TIMEOUT_MILLIS: Long = 180_000L

/** Fixed-origin browser Cookie bridge implemented by each host's restricted web surface. */
public interface DiscourseWebSessionCookieBridge {
    /** Returns a bounded defensive snapshot for `https://linux.do` only. */
    public suspend fun snapshotLinuxDoCookies(): List<DiscourseCookieSnapshot>

    /** Removes Linux.do authentication state owned by this application's browser surface. */
    public suspend fun clearLinuxDoCookies()
}

/** Visible foreground UI used only after an explicit Cloudflare challenge response. */
public fun interface DiscourseManualChallengePresenter {
    /** Returns true after the user visibly completed the fixed-origin challenge. */
    public suspend fun present(fixedOrigin: String): Boolean
}

/**
 * Opaque UI request for the single fixed-origin Cloudflare challenge surface.
 *
 * The monotonically increasing [requestId] prevents a delayed completion from dismissing a newer
 * request. No response body, challenge token, URL path, query, Cookie, or exception crosses this
 * boundary; the only exposed network value is the compile-time Linux.do origin.
 */
public data class DiscourseManualChallengeRequest internal constructor(
    public val requestId: Long,
    public val origin: String,
) {
    init {
        require(requestId > 0L) { "A manual challenge request id must be positive" }
        require(origin == DISCOURSE_ORIGIN) { "A manual challenge request must use the fixed origin" }
    }
}

/**
 * Suspends the authentication exchange while a foreground host presents the manual challenge.
 *
 * Exactly one request may be active. A concurrent caller fails closed instead of joining another
 * authentication flow. UI completion and cancellation are matched by [requestId], while caller
 * cancellation removes the request under [NonCancellable] cleanup and then propagates unchanged.
 */
public class DiscourseManualChallengeCoordinator(
    private val timeoutMillis: Long = DEFAULT_MANUAL_CHALLENGE_TIMEOUT_MILLIS,
) : DiscourseManualChallengePresenter {
    init {
        require(timeoutMillis > 0L) { "The manual challenge timeout must be positive" }
    }

    private data class ActiveRequest(
        val request: DiscourseManualChallengeRequest,
        val result: CompletableDeferred<Boolean>,
    )

    private val operationMutex: Mutex = Mutex()
    private val mutableRequest: MutableStateFlow<DiscourseManualChallengeRequest?> =
        MutableStateFlow(null)
    private var activeRequest: ActiveRequest? = null
    private var nextRequestId: Long = 1L

    /** Current fixed-origin request, or `null` when no challenge UI should be visible. */
    public val request: StateFlow<DiscourseManualChallengeRequest?> = mutableRequest.asStateFlow()

    override suspend fun present(fixedOrigin: String): Boolean {
        if (fixedOrigin != DISCOURSE_ORIGIN) return false

        val result = CompletableDeferred<Boolean>()
        val request =
            operationMutex.withLock {
                if (activeRequest != null) return@withLock null
                check(nextRequestId > 0L) { "Manual challenge request id space is exhausted" }
                val created =
                    DiscourseManualChallengeRequest(
                        requestId = nextRequestId,
                        origin = DISCOURSE_ORIGIN,
                    )
                nextRequestId = if (nextRequestId == Long.MAX_VALUE) 0L else nextRequestId + 1L
                activeRequest = ActiveRequest(created, result)
                mutableRequest.value = created
                created
            } ?: return false

        return try {
            // A platform UI is attached in the host interaction stage. Keeping this wait bounded
            // makes the shared authentication service fail closed if that host is unavailable,
            // destroyed without replying, or has not subscribed yet.
            withTimeoutOrNull(timeoutMillis) { result.await() } ?: false
        } finally {
            // Await cancellation does not cancel an independently created CompletableDeferred.
            // Remove this exact request and resolve its orphaned result without masking cancellation.
            withContext(NonCancellable) {
                operationMutex.withLock {
                    if (activeRequest?.request?.requestId == request.requestId) {
                        activeRequest = null
                        mutableRequest.value = null
                        result.complete(false)
                    }
                }
            }
        }
    }

    /** Completes the matching visible request and resumes its authentication exchange once. */
    public suspend fun complete(requestId: Long): Boolean = resolve(requestId, completed = true)

    /** Cancels the matching visible request without granting a replay. */
    public suspend fun cancel(requestId: Long): Boolean = resolve(requestId, completed = false)

    private suspend fun resolve(
        requestId: Long,
        completed: Boolean,
    ): Boolean =
        operationMutex.withLock {
            val current = activeRequest ?: return@withLock false
            if (current.request.requestId != requestId) return@withLock false
            activeRequest = null
            mutableRequest.value = null
            current.result.complete(completed)
            true
        }
}

/**
 * Bridges challenge cookies once without granting the presenter access to network credentials.
 *
 * The exchange transport owns the one-replay budget. This handler merely presents the fixed origin,
 * obtains the platform bridge's already bounded Cookie snapshot, discards browser authentication,
 * and merges only challenge state into the current generation. No arbitrary URL, response body, or
 * challenge parameter crosses the UI boundary.
 */
public class DiscourseManualChallengeCookieHandler(
    private val presenter: DiscourseManualChallengePresenter,
    private val cookieBridge: DiscourseWebSessionCookieBridge,
    private val sessionManager: DiscourseSessionManager,
) : DiscourseCloudflareChallengeHandler {
    override suspend fun handle(challenge: DiscourseCloudflareChallengeException): Boolean {
        if (!presenter.present(DISCOURSE_ORIGIN)) return false
        // The OTP-created `_t` remains authoritative. A browser may contain a session for another
        // Linux.do account, so the challenge bridge may contribute proxy cookies but never replace
        // the request's authenticated Discourse session.
        val challengeCookies =
            cookieBridge
                .snapshotLinuxDoCookies()
                .filterNot { cookie -> cookie.name == "_t" }
        if (challengeCookies.isEmpty()) return false
        sessionManager.mergeCookiesForCurrentRequest(challengeCookies)
        return true
    }
}

/**
 * Validates the fallback WebView cookie path through Linux.do before activating persistence.
 *
 * A browser-provided `_t` is first installed under a deliberately non-user temporary account. The
 * fixed-origin API then supplies the authoritative numeric id and username. Only that verified
 * identity and the final bounded jar reach [DiscourseSessionLifecycle]; every failure clears both
 * memory and browser state in a non-cancellable fail-closed cleanup.
 */
public class DiscourseWebSessionLogin(
    private val cookieBridge: DiscourseWebSessionCookieBridge,
    private val sessionManager: DiscourseSessionManager,
    private val sessionLifecycle: DiscourseSessionLifecycle,
    private val api: DiscourseApi,
) {
    public suspend fun complete(): DiscourseLoginResult.Authenticated {
        var ownedGeneration: Long? = null
        try {
            val bridgedCookies = cookieBridge.snapshotLinuxDoCookies()
            require(bridgedCookies.any { it.name == "_t" && it.value.isNotEmpty() }) {
                "The browser session does not contain a Linux.do authentication cookie"
            }
            val guest =
                sessionManager.state.value as? DiscourseSessionState.Guest
                    ?: throw DiscourseAuthExchangeException(DiscourseAuthExchangeFailure.ActiveSession)
            sessionManager.startAuthenticatedSession(
                accountId = "web-session-probe",
                cookieSnapshot = bridgedCookies,
                expectedGeneration = guest.generation,
            )
            val probeState =
                sessionManager.state.value as? DiscourseSessionState.Authenticated
                    ?: throw StaleDiscourseSessionException(
                        expectedGeneration = guest.generation,
                        actualGeneration = sessionManager.state.value.generation,
                    )
            ownedGeneration = probeState.generation
            val user =
                api.currentSession().currentUser
                    ?: throw DiscourseAuthExchangeException(DiscourseAuthExchangeFailure.Identity)
            if (user.id <= 0L || user.username.isBlank()) {
                throw DiscourseAuthExchangeException(DiscourseAuthExchangeFailure.Identity)
            }
            val finalCookies = sessionManager.cookieStorage.snapshot()
            sessionLifecycle.activate(
                expectedGeneration = probeState.generation,
                accountId = user.id.toString(),
                username = user.username,
                cookies = finalCookies,
            )
            val expectedActiveGeneration = probeState.generation + 1L
            val currentState = sessionManager.state.value
            val activeState = currentState as? DiscourseSessionState.Authenticated
            if (
                activeState == null ||
                activeState.generation != expectedActiveGeneration ||
                activeState.accountId != user.id.toString()
            ) {
                throw StaleDiscourseSessionException(
                    expectedGeneration = expectedActiveGeneration,
                    actualGeneration = currentState.generation,
                )
            }
            // From this point cancellation owns the final authenticated generation, not the
            // temporary probe generation, so incomplete handoff cleanup cannot leave it active.
            ownedGeneration = activeState.generation
            // Successful handoff is not complete while the browser still owns a second copy of
            // `_t`. A cleanup failure therefore enters the catch path, destroys the newly active
            // local session, and makes one final best-effort browser cleanup attempt.
            withContext(NonCancellable) {
                cookieBridge.clearLinuxDoCookies()
            }
            return DiscourseLoginResult.Authenticated(
                accountId = user.id.toString(),
                username = user.username,
                displayName = user.name,
            )
        } catch (cancellation: CancellationException) {
            clearIncompleteBrowserSession(ownedGeneration)
            throw cancellation
        } catch (failure: Throwable) {
            clearIncompleteBrowserSession(ownedGeneration)
            throw failure
        }
    }

    private suspend fun clearIncompleteBrowserSession(ownedGeneration: Long?) {
        withContext(NonCancellable) {
            ownedGeneration?.let { sessionLifecycle.logoutIfGeneration(it) }
            cookieBridge.clearLinuxDoCookiesBestEffort()
        }
    }
}

/**
 * Removes fixed-origin browser authentication without allowing a platform cleanup failure to mask
 * the caller's result. Callers establish the authoritative fail-closed state first by persisting the
 * accepted session or destroying the local session; browser cleanup is the final containment step.
 */
internal suspend fun DiscourseWebSessionCookieBridge.clearLinuxDoCookiesBestEffort() {
    withContext(NonCancellable) {
        try {
            clearLinuxDoCookies()
        } catch (cancellation: CancellationException) {
            // Cancellation must remain observable even inside non-cancellable cleanup. Callers
            // establish the local fail-closed state before invoking this helper.
            throw cancellation
        } catch (_: Throwable) {
            // Never log platform errors here because implementations may retain private metadata.
        }
    }
}
