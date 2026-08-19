package dev.dimension.flare.data.network.discourse.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Raised when `/session/csrf` returns a value that is unsafe to place in an HTTP header. */
public class InvalidDiscourseCsrfTokenException internal constructor() :
    IllegalArgumentException("The Discourse CSRF token is missing, malformed, or too large")

/**
 * Memory-only CSRF cache with single-flight refresh semantics.
 *
 * The store intentionally exposes no serialization or snapshot operation. Concurrent callers wait
 * on one [Mutex]-protected fetch, so only the first caller reaches `/session/csrf`; the rest reuse
 * its validated result. [invalidate] is non-suspending and revisioned. If invalidation races an
 * in-flight fetch, that response is never cached and the fetching caller obtains a fresh value for
 * the new revision before returning.
 *
 * [fetch] executes in the caller's coroutine. Cancellation therefore propagates naturally and is
 * never converted or swallowed by this class; a later waiter can safely become the next fetcher.
 */
public class DiscourseCsrfTokenStore(
    private val maxTokenBytes: Int = DEFAULT_MAX_TOKEN_BYTES,
) {
    private data class CacheState(
        val revision: Long = 0L,
        val token: String? = null,
    )

    private val mutableCache: MutableStateFlow<CacheState> = MutableStateFlow(CacheState())
    private val refreshMutex: Mutex = Mutex()

    init {
        require(maxTokenBytes > 0) { "maxTokenBytes must be positive" }
    }

    /** Returns the cached token, fetching and validating exactly once when it is absent. */
    public suspend fun getOrFetch(fetch: suspend () -> String): String {
        while (true) {
            val result =
                refreshMutex.withLock {
                    val beforeFetch = mutableCache.value
                    beforeFetch.token?.let { return@withLock FetchResult.Value(it) }

                    val fetched = fetch()
                    validate(fetched)
                    val afterFetch = mutableCache.value
                    val cached = afterFetch.copy(token = fetched)
                    if (
                        afterFetch.revision != beforeFetch.revision ||
                        !mutableCache.compareAndSet(afterFetch, cached)
                    ) {
                        FetchResult.Invalidated
                    } else {
                        FetchResult.Value(fetched)
                    }
                }
            when (result) {
                FetchResult.Invalidated -> continue
                is FetchResult.Value -> return result.token
            }
        }
    }

    /** Invalidates the current token without waiting for a possibly in-flight network fetch. */
    public fun invalidate() {
        while (true) {
            val current = mutableCache.value
            val cleared = current.copy(revision = current.revision.nextRevision(), token = null)
            if (mutableCache.compareAndSet(current, cleared)) return
        }
    }

    /**
     * Invalidates only [expectedToken], returning false when a newer token already replaced it.
     *
     * Mutation retries must use this form. Otherwise a delayed 403 produced with an old token can
     * erase a token that another request has already refreshed successfully.
     */
    public fun invalidate(expectedToken: String): Boolean {
        while (true) {
            val current = mutableCache.value
            if (current.token != expectedToken) return false
            val cleared = current.copy(revision = current.revision.nextRevision(), token = null)
            if (mutableCache.compareAndSet(current, cleared)) return true
        }
    }

    /** Alias used by logout and session replacement to emphasize credential cleanup. */
    public fun clear() {
        invalidate()
    }

    private fun validate(token: String) {
        if (
            token.isBlank() ||
            token != token.trim() ||
            token.length > maxTokenBytes ||
            token.encodeToByteArray().size > maxTokenBytes ||
            token.any { it == '\r' || it == '\n' || it == '\u0000' }
        ) {
            throw InvalidDiscourseCsrfTokenException()
        }
    }

    private sealed interface FetchResult {
        data object Invalidated : FetchResult

        data class Value(
            val token: String,
        ) : FetchResult
    }

    public companion object {
        public const val DEFAULT_MAX_TOKEN_BYTES: Int = 4 * 1024
    }
}

private fun Long.nextRevision(): Long {
    check(this < Long.MAX_VALUE) { "CSRF token revision space is exhausted" }
    return this + 1L
}
