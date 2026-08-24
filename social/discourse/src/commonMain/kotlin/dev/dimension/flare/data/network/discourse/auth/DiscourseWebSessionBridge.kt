package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.network.discourse.DISCOURSE_ORIGIN
import dev.dimension.flare.data.network.discourse.error.DiscourseCloudflareChallengeException
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieSnapshot
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionLifecycle
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionState
import dev.dimension.flare.data.network.discourse.session.StaleDiscourseSessionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

    /**
     * Acknowledges that request-bound code finished snapshotting and clearing the browser handoff.
     * Presenters without an externally owned one-use buffer do not need additional coordination.
     */
    public suspend fun acknowledgeCookieConsumption() {}
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
        val cookieConsumption: CompletableDeferred<Unit> = CompletableDeferred(),
        var waitsForCookieConsumption: Boolean = false,
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

        var returnedForCookieConsumption = false
        return try {
            // A platform UI is attached in the host interaction stage. Keeping this wait bounded
            // makes the shared authentication service fail closed if that host is unavailable,
            // destroyed without replying, or has not subscribed yet.
            (withTimeoutOrNull(timeoutMillis) { result.await() } ?: false).also { completed ->
                returnedForCookieConsumption = completed
            }
        } finally {
            // Await cancellation does not cancel an independently created CompletableDeferred.
            // Remove this exact request and resolve its orphaned result without masking cancellation.
            withContext(NonCancellable) {
                operationMutex.withLock {
                    val current = activeRequest
                    if (
                        current?.request?.requestId == request.requestId &&
                        (!current.waitsForCookieConsumption || !returnedForCookieConsumption)
                    ) {
                        activeRequest = null
                        mutableRequest.value = null
                        result.complete(false)
                        current.cookieConsumption.complete(Unit)
                    }
                }
            }
        }
    }

    /** Completes the matching visible request and resumes its authentication exchange once. */
    public suspend fun complete(requestId: Long): Boolean = resolve(requestId, completed = true, waitForCookieConsumption = false) != null

    /**
     * Accepts the visible request but keeps it published until request-bound code consumes the
     * Cookie handoff. Platform hosts use this form so disposing or replacing their WebView cannot
     * erase/overwrite the shared buffer before Kotlin snapshots and clears it.
     */
    public suspend fun completeAfterCookieConsumption(requestId: Long): Boolean {
        val completion =
            resolve(requestId, completed = true, waitForCookieConsumption = true)
                ?: return false
        completion.await()
        return true
    }

    /** Cancels the matching visible request without granting a replay. */
    public suspend fun cancel(requestId: Long): Boolean = resolve(requestId, completed = false, waitForCookieConsumption = false) != null

    override suspend fun acknowledgeCookieConsumption() {
        operationMutex.withLock {
            val current = activeRequest ?: return@withLock
            if (!current.waitsForCookieConsumption || !current.result.isCompleted) return@withLock
            activeRequest = null
            mutableRequest.value = null
            current.cookieConsumption.complete(Unit)
        }
    }

    private suspend fun resolve(
        requestId: Long,
        completed: Boolean,
        waitForCookieConsumption: Boolean,
    ): CompletableDeferred<Unit>? =
        operationMutex.withLock {
            val current = activeRequest ?: return@withLock null
            if (current.request.requestId != requestId || current.result.isCompleted) return@withLock null
            current.waitsForCookieConsumption = completed && waitForCookieConsumption
            current.result.complete(completed)
            if (!current.waitsForCookieConsumption) {
                activeRequest = null
                mutableRequest.value = null
                current.cookieConsumption.complete(Unit)
            }
            current.cookieConsumption
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
    private val handoffMutex: Mutex = Mutex()

    override suspend fun handle(challenge: DiscourseCloudflareChallengeException): Boolean {
        // A failed concurrent presentation does not own the process-wide handoff buffer and must not
        // clear cookies that the accepted request is about to snapshot.
        if (!handoffMutex.tryLock()) return false
        var cookieHandoffAccepted = false
        return try {
            cookieHandoffAccepted = presenter.present(DISCOURSE_ORIGIN)
            if (!cookieHandoffAccepted) {
                false
            } else {
                // The OTP-created `_t` remains authoritative. A browser may contain a session for another
                // Linux.do account, so the challenge bridge may contribute proxy cookies but never replace
                // the request's authenticated Discourse session.
                val challengeCookies =
                    cookieBridge
                        .snapshotLinuxDoCookies()
                        .filterNot { cookie -> cookie.name == "_t" }
                if (challengeCookies.isEmpty()) {
                    false
                } else {
                    sessionManager.mergeCookiesForCurrentRequest(challengeCookies)
                    true
                }
            }
        } finally {
            // Foundation's shared Cookie storage is only a one-use Apple handoff buffer. Clearing it
            // here, in the request coroutine after the snapshot, avoids retaining `cf_clearance` or
            // a browser account's `_t` outside the encrypted vault and preserves cancellation safety.
            withContext(NonCancellable) {
                try {
                    try {
                        cookieBridge.clearLinuxDoCookiesBestEffort()
                    } finally {
                        if (cookieHandoffAccepted) presenter.acknowledgeCookieConsumption()
                    }
                } finally {
                    handoffMutex.unlock()
                }
            }
        }
    }
}

/**
 * Validates the fallback WebView cookie path through Linux.do before activating persistence.
 *
 * Browser Cookies are verified by an isolated [DiscourseWebSessionProbe]. The main session manager
 * therefore remains at the captured guest generation until Linux.do supplies an authoritative
 * identity and final bounded Cookie jar. Only that result reaches [DiscourseSessionLifecycle], in
 * one compare-and-set activation; every failure clears browser state in a non-cancellable
 * fail-closed cleanup without publishing a temporary account to shared observers.
 */
public class DiscourseWebSessionLogin internal constructor(
    private val cookieBridge: DiscourseWebSessionCookieBridge,
    private val sessionManager: DiscourseSessionManager,
    private val sessionLifecycle: DiscourseSessionLifecycle,
    private val probe: DiscourseWebSessionProbe,
) {
    private val completionMutex: Mutex = Mutex()

    public suspend fun complete(): DiscourseLoginResult.Authenticated {
        // The loser has no ownership over either the browser buffer or local probe generation. It
        // therefore fails before entering cleanup, which belongs exclusively to the accepted call.
        if (!completionMutex.tryLock()) {
            throw DiscourseAuthExchangeException(DiscourseAuthExchangeFailure.ActiveSession)
        }
        return try {
            completeOwnedHandoff()
        } finally {
            completionMutex.unlock()
        }
    }

    private suspend fun completeOwnedHandoff(): DiscourseLoginResult.Authenticated {
        var activatedGeneration: Long? = null
        var browserCookiesCleared = false
        try {
            val bridgedCookies = cookieBridge.snapshotLinuxDoCookies()
            require(bridgedCookies.any { it.name == "_t" && it.value.isNotEmpty() }) {
                "The browser session does not contain a Linux.do authentication cookie"
            }
            val guest =
                sessionManager.state.value as? DiscourseSessionState.Guest
                    ?: throw DiscourseAuthExchangeException(DiscourseAuthExchangeFailure.ActiveSession)

            // The probe has its own client, Cookie jar, CSRF store, and session manager. Nothing in
            // this suspension can advance or authenticate the process-wide manager captured above.
            val verified = probe.probe(bridgedCookies)
            val activeState =
                sessionLifecycle.activateAndRunForAuthenticatedOwner(
                    expectedGeneration = guest.generation,
                    accountId = verified.accountId,
                    username = verified.username,
                    cookies = verified.cookies,
                ) { activated ->
                    // From this point cancellation owns the one newly authenticated generation, so
                    // an incomplete handoff can remove exactly that session without touching a
                    // replacement. The lifecycle mutex stays held through browser cleanup: there is
                    // no activation-to-cleanup acquisition gap in which `_t` could be stranded.
                    activatedGeneration = activated.generation
                    withContext(NonCancellable) {
                        cookieBridge.clearLinuxDoCookies()
                    }
                    browserCookiesCleared = true
                    // `withContext(NonCancellable)` does not necessarily dispatch on return. Check
                    // the request context explicitly so cancellation that arrived during mandatory
                    // browser cleanup rolls back this newly activated generation before success.
                    currentCoroutineContext().ensureActive()
                    activated
                }
            val finalState = sessionManager.state.value
            val finalOwner = finalState as? DiscourseSessionState.Authenticated
            if (
                finalOwner?.generation != activeState.generation ||
                finalOwner.accountId != activeState.accountId
            ) {
                throw StaleDiscourseSessionException(activeState.generation, finalState.generation)
            }
            return DiscourseLoginResult.Authenticated(
                accountId = verified.accountId,
                username = verified.username,
                displayName = verified.displayName,
            )
        } catch (cancellation: CancellationException) {
            clearIncompleteBrowserSession(
                ownedGeneration = activatedGeneration,
                browserCookiesCleared = browserCookiesCleared,
                primaryFailure = cancellation,
            )
            throw cancellation
        } catch (failure: Throwable) {
            clearIncompleteBrowserSession(
                ownedGeneration = activatedGeneration,
                browserCookiesCleared = browserCookiesCleared,
                primaryFailure = failure,
            )
            throw failure
        }
    }

    private suspend fun clearIncompleteBrowserSession(
        ownedGeneration: Long?,
        browserCookiesCleared: Boolean,
        primaryFailure: Throwable,
    ) {
        withContext(NonCancellable) {
            // Session ownership gates destructive vault cleanup, but not cleanup of this call's
            // one-use browser handoff. [completionMutex] excludes another fallback completion until
            // this finally-equivalent path releases its duplicate Cookie snapshot. Every cleanup
            // failure is attached to the failure that entered this path: neither a vault error nor a
            // repeated browser error may replace caller cancellation or the original handoff error.
            try {
                ownedGeneration?.let { sessionLifecycle.logoutIfGeneration(it) }
            } catch (cleanupFailure: Throwable) {
                primaryFailure.addSuppressedIfDistinct(cleanupFailure)
            } finally {
                if (!browserCookiesCleared) {
                    try {
                        cookieBridge.clearLinuxDoCookiesBestEffort()
                    } catch (cleanupFailure: Throwable) {
                        primaryFailure.addSuppressedIfDistinct(cleanupFailure)
                    }
                }
            }
        }
    }
}

/**
 * Adds secondary cleanup diagnostics without duplicating a retried coroutine exception.
 *
 * Coroutine stack-trace recovery may copy a throwable and retain the original as its root cause, so
 * reference comparison alone is insufficient to recognize the same failure crossing `withContext`.
 */
internal fun Throwable.addSuppressedIfDistinct(secondary: Throwable) {
    val secondaryRoot = secondary.rootCauseIdentity()
    if (
        rootCauseIdentity() !== secondaryRoot &&
        suppressedExceptions.none { it.rootCauseIdentity() === secondaryRoot }
    ) {
        addSuppressed(secondary)
    }
}

private fun Throwable.rootCauseIdentity(): Throwable {
    var current = this
    repeat(32) {
        val next = current.cause ?: return current
        if (next === current) return current
        current = next
    }
    return current
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
