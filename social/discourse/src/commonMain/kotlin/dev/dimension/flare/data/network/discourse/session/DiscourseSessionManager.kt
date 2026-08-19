package dev.dimension.flare.data.network.discourse.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

private const val MAX_USERNAME_LENGTH = 256

/** Observable state of the one active Linux.do account supported by the first release. */
public sealed interface DiscourseSessionState {
    /** Monotonically increasing request boundary. It changes for every login and logout. */
    public val generation: Long

    /** Anonymous forum access with no active account cookies. */
    public data class Guest(
        override val generation: Long,
    ) : DiscourseSessionState {
        init {
            require(generation >= 0L) { "Session generation must not be negative" }
        }
    }

    /**
     * Active forum session.
     *
     * [credentialRef] is nullable for the explicitly supported session-only Linux fallback. The
     * account id remains independent of [username] so later releases can safely support account
     * renames and more than one stored account.
     */
    public data class Authenticated(
        override val generation: Long,
        public val accountId: String,
        public val username: String? = null,
        public val credentialRef: SecureCredentialRef? = null,
    ) : DiscourseSessionState {
        init {
            require(generation >= 0L) { "Session generation must not be negative" }
            requireValidAccountId(accountId)
            requireValidUsername(username)
        }
    }
}

/**
 * Ordinary domain failure reported when a request belongs to a replaced session generation.
 *
 * This deliberately does not extend [CancellationException]. Internally the old operation is
 * cancelled so Ktor can abort sockets promptly; only after confirming the caller itself is still
 * active does [DiscourseSessionManager] translate that internal cancellation into this catchable
 * domain error.
 */
public class StaleDiscourseSessionException(
    public val expectedGeneration: Long,
    public val actualGeneration: Long,
) : IllegalStateException(
        "Discourse session generation $expectedGeneration was replaced by $actualGeneration",
    )

/**
 * Owns Linux.do cookie, CSRF, and request-generation lifetime.
 *
 * [runForCurrentSession] keeps each operation as a structured child of its caller while installing
 * a cancellation bridge from the captured generation. This avoids the common but unsafe pattern
 * of replacing the caller's `Job` with a manager-owned `Job` in `withContext`. Consequently both
 * directions work: UI cancellation still reaches the request, and login/logout immediately reaches
 * every request captured from the previous generation.
 */
public class DiscourseSessionManager(
    public val cookieStorage: DiscourseCookieStorage = DiscourseCookieStorage(),
    public val csrfTokenStore: DiscourseCsrfTokenStore = DiscourseCsrfTokenStore(),
) {
    private data class SessionLease(
        val state: DiscourseSessionState,
        val generationJob: CompletableJob,
        val cookieRevision: Long,
    )

    private val transitionMutex: Mutex = Mutex()
    private val mutableState: MutableStateFlow<DiscourseSessionState> =
        MutableStateFlow(DiscourseSessionState.Guest(generation = 0L))
    private var generationJob: CompletableJob = SupervisorJob()

    /** Hot state for presentation and request-header integration. */
    public val state: StateFlow<DiscourseSessionState> = mutableState.asStateFlow()

    /**
     * Starts or restores an authenticated session and invalidates every older request.
     *
     * The cookie snapshot is fully validated before the transition begins. Once the transition lock
     * is held, the old generation is cancelled before its cookie set is replaced, preventing an old
     * request from intentionally continuing with credentials for the new account.
     */
    public suspend fun startAuthenticatedSession(
        accountId: String,
        username: String? = null,
        credentialRef: SecureCredentialRef? = null,
        cookieSnapshot: List<DiscourseCookieSnapshot> = emptyList(),
    ) {
        requireValidAccountId(accountId)
        requireValidUsername(username)
        cookieStorage.requireValidSnapshot(cookieSnapshot)

        transitionMutex.withLock {
            val previousState = mutableState.value
            val nextGeneration = previousState.generation.nextGeneration()
            val previousJob = generationJob
            val nextJob = SupervisorJob()

            previousJob.cancel(SessionGenerationCancellation(previousState.generation, nextGeneration))
            cookieStorage.importSnapshot(cookieSnapshot)
            csrfTokenStore.clear()
            generationJob = nextJob
            mutableState.value =
                DiscourseSessionState.Authenticated(
                    generation = nextGeneration,
                    accountId = accountId,
                    username = username,
                    credentialRef = credentialRef,
                )
        }
    }

    /** Logs out, cancels old requests, and removes all in-memory Cookie and CSRF material. */
    public suspend fun logout() {
        transitionMutex.withLock {
            val previousState = mutableState.value
            val nextGeneration = previousState.generation.nextGeneration()
            val previousJob = generationJob
            val nextJob = SupervisorJob()

            previousJob.cancel(SessionGenerationCancellation(previousState.generation, nextGeneration))
            cookieStorage.clear()
            csrfTokenStore.clear()
            generationJob = nextJob
            mutableState.value = DiscourseSessionState.Guest(generation = nextGeneration)
        }
    }

    /** Updates display metadata without replacing credentials or cancelling valid requests. */
    public suspend fun updateAuthenticatedUsername(username: String?) {
        requireValidUsername(username)
        transitionMutex.withLock {
            val current = mutableState.value
            if (current is DiscourseSessionState.Authenticated) {
                mutableState.value = current.copy(username = username)
            }
        }
    }

    /** Obtains a generation-bound, memory-only CSRF token. */
    public suspend fun csrfToken(fetch: suspend () -> String): String = runForCurrentSession { csrfTokenStore.getOrFetch(fetch) }

    /** Forces the next state-changing request to obtain a fresh CSRF token. */
    public fun invalidateCsrfToken(expectedToken: String): Boolean = csrfTokenStore.invalidate(expectedToken)

    /**
     * Runs [block] for an immutable snapshot of the current session.
     *
     * A login or logout cancels [block] promptly. If the outer caller remains active, that internal
     * cancellation is surfaced as [StaleDiscourseSessionException]. Genuine caller cancellation is
     * rethrown unchanged, and a [CancellationException] thrown by [block] is never mistaken for a
     * domain failure while the generation remains current.
     */
    public suspend fun <T> runForCurrentSession(block: suspend DiscourseSessionState.() -> T): T =
        coroutineScope {
            val lease =
                transitionMutex.withLock {
                    SessionLease(
                        state = mutableState.value,
                        generationJob = generationJob,
                        cookieRevision = cookieStorage.currentRevision(),
                    )
                }
            val operation =
                async(
                    context = DiscourseCookieRevisionContext(lease.cookieRevision),
                    start = CoroutineStart.LAZY,
                ) {
                    lease.state.block()
                }
            val cancellationBridge: DisposableHandle =
                lease.generationJob.invokeOnCompletion { cause ->
                    if (cause is SessionGenerationCancellation) {
                        operation.cancel(cause)
                    }
                }

            try {
                operation.start()
                val result = operation.await()
                currentCoroutineContext().ensureActive()
                transitionMutex.withLock {
                    val actualGeneration = mutableState.value.generation
                    if (actualGeneration != lease.state.generation) {
                        throw StaleDiscourseSessionException(
                            expectedGeneration = lease.state.generation,
                            actualGeneration = actualGeneration,
                        )
                    }
                }
                result
            } catch (cancelled: CancellationException) {
                // Caller cancellation wins over a simultaneous session transition.
                currentCoroutineContext().ensureActive()
                val actualGeneration =
                    (cancelled as? SessionGenerationCancellation)?.replacementGeneration
                        ?: mutableState.value.generation
                if (
                    cancelled is SessionGenerationCancellation ||
                    actualGeneration != lease.state.generation
                ) {
                    throw StaleDiscourseSessionException(
                        expectedGeneration = lease.state.generation,
                        actualGeneration = actualGeneration,
                    )
                }
                throw cancelled
            } finally {
                cancellationBridge.dispose()
            }
        }
}

/**
 * Session-signed cookie revision propagated to every Ktor call made by one operation.
 *
 * This element intentionally contains no [kotlinx.coroutines.Job]. Adding it to `async` therefore
 * preserves structured parent cancellation while preventing a non-cancellable cleanup section in
 * an old generation from ever acquiring a replacement account's cookies.
 */
internal class DiscourseCookieRevisionContext(
    val revision: Long,
) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> = Key

    internal companion object Key : CoroutineContext.Key<DiscourseCookieRevisionContext>
}

private class SessionGenerationCancellation(
    replacedGeneration: Long,
    val replacementGeneration: Long,
) : CancellationException(
        "Discourse session generation $replacedGeneration was replaced by $replacementGeneration",
    )

private fun Long.nextGeneration(): Long {
    check(this < Long.MAX_VALUE) { "Session generation space is exhausted" }
    return this + 1L
}

private fun requireValidUsername(username: String?) {
    if (username == null) return
    require(username.isNotBlank()) { "Username must not be blank when present" }
    require(username.length <= MAX_USERNAME_LENGTH) { "Username is too long" }
    require(username.none(Char::isControlCharacter)) {
        "Username must not contain control characters"
    }
}
