package dev.dimension.flare.data.network.discourse.session

import dev.dimension.flare.data.network.discourse.DISCOURSE_ORIGIN
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.fillDefaults
import io.ktor.client.plugins.cookies.matches
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlin.time.Clock

private const val LINUX_DO_HOST = "linux.do"
private val LINUX_DO_ROOT_URL = Url("$DISCOURSE_ORIGIN/")
private val COOKIE_NAME = Regex("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$")

/** Serializable counterpart of Ktor's [Cookie] for encrypted vault persistence. */
@Serializable
public data class DiscourseCookieSnapshot(
    public val name: String,
    public val value: String,
    public val encoding: DiscourseCookieEncoding = DiscourseCookieEncoding.RAW,
    /** Absolute expiry; unlike Max-Age this cannot be extended by restoring a snapshot later. */
    public val expiresAtEpochMillis: Long? = null,
    public val domain: String = LINUX_DO_HOST,
    public val path: String = "/",
    public val secure: Boolean = true,
    public val httpOnly: Boolean = false,
)

/** Stable serializable representation of Ktor cookie encodings. */
@Serializable
public enum class DiscourseCookieEncoding {
    RAW,
    DQUOTES,
    URI_ENCODING,
    BASE64_ENCODING,
}

/** Raised when a cookie or restored cookie snapshot violates the Linux.do storage policy. */
public class RejectedDiscourseCookieException internal constructor(
    reason: String,
) : IllegalArgumentException(reason)

/**
 * Strict, bounded cookie jar for the single fixed Linux.do origin.
 *
 * Ktor's general-purpose cookie jar accepts cookies for every host reached by an [io.ktor.client.HttpClient].
 * FlareDo intentionally does not: authenticated cookies must never cross a scheme, host, or port
 * boundary, even if a future API bug constructs an absolute URL. Foreign reads return an empty
 * list, while foreign writes throw so configuration mistakes remain visible in tests.
 *
 * State is kept in a [MutableStateFlow] so updates, full imports, clears, and [close] are atomic on
 * every KMP target without blocking a coroutine dispatcher thread. The snapshot is bounded by
 * cookie count, individual UTF-8 value size, and aggregate UTF-8 size.
 */
public class DiscourseCookieStorage(
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val maxCookies: Int = DEFAULT_MAX_COOKIES,
    private val maxCookieNameBytes: Int = DEFAULT_MAX_COOKIE_NAME_BYTES,
    private val maxCookieValueBytes: Int = DEFAULT_MAX_COOKIE_VALUE_BYTES,
    private val maxTotalBytes: Int = DEFAULT_MAX_TOTAL_BYTES,
) : CookiesStorage {
    private data class StoredCookie(
        val cookie: Cookie,
        val expiresAtEpochMillis: Long?,
    )

    private data class JarState(
        val revision: Long = 0L,
        val isClosed: Boolean = false,
        val cookies: List<StoredCookie> = emptyList(),
    )

    private val mutableState: MutableStateFlow<JarState> = MutableStateFlow(JarState())

    /**
     * Immutable cookie material captured for one HTTP request.
     *
     * [revision] is a session boundary, not a general mutation counter: ordinary `Set-Cookie`
     * updates keep it unchanged, while a full import, clear, or close advances it.
     */
    internal data class RequestLease(
        val revision: Long,
        val cookies: List<Cookie>,
    )

    init {
        require(maxCookies > 0) { "maxCookies must be positive" }
        require(maxCookieNameBytes > 0) { "maxCookieNameBytes must be positive" }
        require(maxCookieValueBytes > 0) { "maxCookieValueBytes must be positive" }
        require(
            maxTotalBytes.toLong() >=
                maxCookieNameBytes.toLong() + maxCookieValueBytes.toLong(),
        ) {
            "maxTotalBytes must hold at least one maximum-size cookie"
        }
    }

    override suspend fun get(requestUrl: Url): List<Cookie> {
        if (!requestUrl.isLinuxDoOrigin()) return emptyList()

        // CookiesStorage.get has no session-lease parameter. It remains useful for diagnostics and
        // tests, but the HTTP plugin uses captureForRequest with the revision signed by the manager.
        val expectedRevision = currentRevision()
        return captureForRequest(requestUrl, expectedRevision)?.cookies.orEmpty()
    }

    override suspend fun addCookie(
        requestUrl: Url,
        cookie: Cookie,
    ) {
        requireLinuxDoOrigin(requestUrl)
        val entryState = mutableState.value
        check(!entryState.isClosed) { "The Discourse cookie storage is closed" }
        addCookiesIfRevision(
            requestUrl = requestUrl,
            cookies = listOf(cookie),
            expectedRevision = entryState.revision,
        )
    }

    /** Current session boundary captured by [DiscourseSessionManager] under its transition lock. */
    internal fun currentRevision(): Long = mutableState.value.revision

    /**
     * Atomically captures the cookies for [requestUrl] only when [expectedRevision] is current.
     *
     * A caller from an old session receives `null`; it can continue without credentials, but it can
     * never observe cookies imported for a replacement account. Expiry pruning and the returned
     * cookie list come from the same successful state snapshot.
     */
    internal fun captureForRequest(
        requestUrl: Url,
        expectedRevision: Long,
    ): RequestLease? {
        if (!requestUrl.isLinuxDoOrigin()) return null

        val now = nowEpochMillis()
        while (true) {
            val state = mutableState.value
            if (state.isClosed || state.revision != expectedRevision) return null

            val retained = state.cookies.filterNot { it.isExpired(now) }
            val captured =
                RequestLease(
                    revision = state.revision,
                    cookies =
                        retained
                            .asSequence()
                            .filter { it.cookie.matches(requestUrl) }
                            .map(StoredCookie::cookie)
                            .toList(),
                )
            if (retained == state.cookies) return captured
            if (mutableState.compareAndSet(state, state.copy(cookies = retained))) return captured
        }
    }

    /**
     * Applies a complete response cookie batch only if its request revision is still current.
     *
     * Parsing happens before this method through Ktor's structured `setCookie()` API. Normalizing
     * and validating the whole batch before the CAS also prevents a malformed later header from
     * leaving an earlier header partially committed.
     */
    internal fun addCookiesIfRevision(
        requestUrl: Url,
        cookies: List<Cookie>,
        expectedRevision: Long,
    ): Boolean {
        requireLinuxDoOrigin(requestUrl)
        val entryState = mutableState.value
        if (entryState.isClosed || entryState.revision != expectedRevision) return false

        val now = nowEpochMillis()
        val normalized = cookies.map { normalize(cookie = it, requestUrl = requestUrl, now = now) }

        while (true) {
            val state = mutableState.value
            if (state.isClosed || state.revision != expectedRevision) return false

            val candidate =
                normalized.fold(state.cookies.filterNot { it.isExpired(now) }) { current, item ->
                    val retained = current.filterNot { it.hasSameKeyAs(item) }
                    if (item.isExpired(now)) retained else retained + item
                }
            requireWithinBounds(candidate)
            if (mutableState.compareAndSet(state, state.copy(cookies = candidate))) return true
        }
    }

    /** Returns all unexpired cookies in a persistence-safe representation. */
    public suspend fun snapshot(): List<DiscourseCookieSnapshot> {
        val now = nowEpochMillis()
        mutableState.update { state ->
            state.copy(cookies = state.cookies.filterNot { it.isExpired(now) })
        }
        return mutableState.value
            .takeUnless(JarState::isClosed)
            ?.cookies
            .orEmpty()
            .map { it.toSnapshot() }
    }

    /**
     * Atomically replaces the jar with [snapshot].
     *
     * Every entry is normalized and the entire bound is checked before shared state changes. An
     * invalid or oversized snapshot therefore leaves the current authenticated cookie set intact.
     */
    public suspend fun importSnapshot(snapshot: List<DiscourseCookieSnapshot>) {
        val prepared = prepareSnapshot(snapshot)
        mutableState.update { state ->
            check(!state.isClosed) { "The Discourse cookie storage is closed" }
            state.copy(revision = state.revision.nextRevision(), cookies = prepared)
        }
    }

    /**
     * Merges cookies without changing the request boundary when [expectedRevision] still owns it.
     *
     * This narrow operation exists for a foreground Cloudflare challenge that is already executing
     * inside a generation-bound request. Advancing the revision there would make the retry reject the
     * freshly bridged cookies. Conversely, accepting a mismatched revision could write an old browser
     * snapshot into a newly logged-in account. The compare-and-set therefore preserves the revision
     * on success and fails closed after any import, clear, close, login, or logout boundary.
     */
    internal fun mergeSnapshotIfRevision(
        snapshot: List<DiscourseCookieSnapshot>,
        expectedRevision: Long,
    ): Boolean {
        val now = nowEpochMillis()
        val prepared = prepareSnapshot(snapshot)
        while (true) {
            val state = mutableState.value
            if (state.isClosed || state.revision != expectedRevision) return false
            val candidate =
                prepared.fold(state.cookies.filterNot { it.isExpired(now) }) { current, item ->
                    current.filterNot { it.hasSameKeyAs(item) } + item
                }
            requireWithinBounds(candidate)
            if (mutableState.compareAndSet(state, state.copy(cookies = candidate))) return true
        }
    }

    /** Removes every cookie, including cookies that have not yet expired. */
    public fun clear() {
        mutableState.update { state ->
            state.copy(revision = state.revision.nextRevision(), cookies = emptyList())
        }
    }

    /** Ktor lifecycle hook; closing the client also erases the in-memory cookie set. */
    override fun close() {
        mutableState.update { state ->
            if (state.isClosed) {
                state
            } else {
                state.copy(
                    revision = state.revision.nextRevision(),
                    isClosed = true,
                    cookies = emptyList(),
                )
            }
        }
    }

    internal fun requireValidSnapshot(snapshot: List<DiscourseCookieSnapshot>) {
        prepareSnapshot(snapshot)
    }

    private fun prepareSnapshot(snapshot: List<DiscourseCookieSnapshot>): List<StoredCookie> {
        if (snapshot.size > maxCookies) {
            throw RejectedDiscourseCookieException("Cookie snapshot exceeds the cookie-count limit")
        }

        val now = nowEpochMillis()
        val restored =
            snapshot.fold(emptyList<StoredCookie>()) { accumulated, item ->
                val normalized = normalize(snapshot = item)
                val withoutDuplicate = accumulated.filterNot { it.hasSameKeyAs(normalized) }
                if (normalized.isExpired(now)) withoutDuplicate else withoutDuplicate + normalized
            }
        requireWithinBounds(restored)
        return restored
    }

    private fun normalize(
        cookie: Cookie,
        requestUrl: Url,
        now: Long,
    ): StoredCookie {
        val explicitDomain =
            cookie.domain
                ?.trim()
                ?.removePrefix(".")
                ?.lowercase()
        if (explicitDomain != null && explicitDomain != LINUX_DO_HOST) {
            throw RejectedDiscourseCookieException("Cookie domain is outside linux.do")
        }

        val withDefaults = cookie.fillDefaults(requestUrl)
        val expiresAt =
            cookie.maxAge?.let { maxAgeSeconds ->
                if (maxAgeSeconds <= 0) now else saturatedAdd(now, maxAgeSeconds.toLong() * 1_000L)
            } ?: cookie.expires?.timestamp
        val normalizedCookie =
            withDefaults.copy(
                maxAge = null,
                expires = expiresAt?.let(::GMTDate),
                domain = LINUX_DO_HOST,
                path = withDefaults.path ?: "/",
                // All stored cookies are restricted to HTTPS even when Set-Cookie omitted Secure.
                secure = true,
                extensions = emptyMap(),
            )
        validateCookie(normalizedCookie)
        return StoredCookie(normalizedCookie, expiresAt)
    }

    private fun normalize(snapshot: DiscourseCookieSnapshot): StoredCookie {
        val normalizedDomain =
            snapshot.domain
                .trim()
                .removePrefix(".")
                .lowercase()
        if (normalizedDomain != LINUX_DO_HOST) {
            throw RejectedDiscourseCookieException("Restored cookie domain is outside linux.do")
        }
        val cookie =
            Cookie(
                name = snapshot.name,
                value = snapshot.value,
                encoding = snapshot.encoding.toKtorEncoding(),
                expires = snapshot.expiresAtEpochMillis?.let(::GMTDate),
                domain = LINUX_DO_HOST,
                path = snapshot.path,
                secure = true,
                httpOnly = snapshot.httpOnly,
            )
        validateCookie(cookie)
        return StoredCookie(cookie, snapshot.expiresAtEpochMillis)
    }

    private fun validateCookie(cookie: Cookie) {
        if (
            cookie.name.length > maxCookieNameBytes ||
            !COOKIE_NAME.matches(cookie.name) ||
            cookie.name.encodeToByteArray().size > maxCookieNameBytes
        ) {
            throw RejectedDiscourseCookieException("Cookie name is invalid or too long")
        }
        if (
            cookie.value.length > maxCookieValueBytes ||
            cookie.value.encodeToByteArray().size > maxCookieValueBytes ||
            cookie.value.any { character -> !character.isRfc6265CookieOctet() }
        ) {
            throw RejectedDiscourseCookieException("Cookie value is invalid or too long")
        }
        val path = cookie.path
        if (
            path == null ||
            path.length > maxTotalBytes ||
            !path.startsWith('/') ||
            path.any(Char::isControlCharacter)
        ) {
            throw RejectedDiscourseCookieException("Cookie path must be an absolute safe path")
        }
        if (cookie.domain != LINUX_DO_HOST) {
            throw RejectedDiscourseCookieException("Cookie domain must be exactly linux.do")
        }
    }

    private fun requireWithinBounds(cookies: List<StoredCookie>) {
        if (cookies.size > maxCookies) {
            throw RejectedDiscourseCookieException("Cookie jar exceeds the cookie-count limit")
        }
        val totalBytes = cookies.sumOf { it.persistedSizeBytes() }
        if (totalBytes > maxTotalBytes.toLong()) {
            throw RejectedDiscourseCookieException("Cookie jar exceeds the aggregate size limit")
        }
    }

    private fun requireLinuxDoOrigin(url: Url) {
        if (!url.isLinuxDoOrigin()) {
            throw RejectedDiscourseCookieException("Cookies may only be written by $DISCOURSE_ORIGIN")
        }
    }

    private fun StoredCookie.hasSameKeyAs(other: StoredCookie): Boolean =
        cookie.name == other.cookie.name &&
            cookie.domain == other.cookie.domain &&
            cookie.path == other.cookie.path

    private fun StoredCookie.isExpired(now: Long): Boolean = expiresAtEpochMillis?.let { it <= now } == true

    private fun StoredCookie.persistedSizeBytes(): Long =
        utf8Size(cookie.name) +
            utf8Size(cookie.value) +
            utf8Size(cookie.domain.orEmpty()) +
            utf8Size(cookie.path.orEmpty()) +
            PERSISTED_COOKIE_OVERHEAD_BYTES.toLong()

    private fun StoredCookie.toSnapshot(): DiscourseCookieSnapshot =
        DiscourseCookieSnapshot(
            name = cookie.name,
            value = cookie.value,
            encoding = cookie.encoding.toSnapshotEncoding(),
            expiresAtEpochMillis = expiresAtEpochMillis,
            domain = cookie.domain ?: LINUX_DO_HOST,
            path = cookie.path ?: "/",
            secure = true,
            httpOnly = cookie.httpOnly,
        )

    private fun Url.isLinuxDoOrigin(): Boolean =
        protocol == URLProtocol.HTTPS &&
            host.lowercase() == LINUX_DO_HOST &&
            port == URLProtocol.HTTPS.defaultPort &&
            user.isNullOrEmpty() &&
            password.isNullOrEmpty()

    public companion object {
        public const val DEFAULT_MAX_COOKIES: Int = 64
        public const val DEFAULT_MAX_COOKIE_NAME_BYTES: Int = 256
        public const val DEFAULT_MAX_COOKIE_VALUE_BYTES: Int = 8 * 1024
        public const val DEFAULT_MAX_TOTAL_BYTES: Int = 64 * 1024
        private const val PERSISTED_COOKIE_OVERHEAD_BYTES: Int = 64
    }
}

/**
 * RFC 6265 `cookie-octet` after an optional surrounding DQUOTE has been removed by the parser.
 *
 * Validating the logical value, rather than only CR/LF, is essential for RAW browser snapshots:
 * accepting `;` would let a non-session Cookie append another `_t` pair to the request header.
 */
private fun Char.isRfc6265CookieOctet(): Boolean =
    this == '\u0021' ||
        this in '\u0023'..'\u002b' ||
        this in '\u002d'..'\u003a' ||
        this in '\u003c'..'\u005b' ||
        this in '\u005d'..'\u007e'

private fun utf8Size(value: String): Long = value.encodeToByteArray().size.toLong()

private fun Long.nextRevision(): Long {
    check(this < Long.MAX_VALUE) { "Cookie revision space is exhausted" }
    return this + 1L
}

private fun saturatedAdd(
    left: Long,
    right: Long,
): Long = if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private fun CookieEncoding.toSnapshotEncoding(): DiscourseCookieEncoding =
    when (this) {
        CookieEncoding.RAW -> DiscourseCookieEncoding.RAW
        CookieEncoding.DQUOTES -> DiscourseCookieEncoding.DQUOTES
        CookieEncoding.URI_ENCODING -> DiscourseCookieEncoding.URI_ENCODING
        CookieEncoding.BASE64_ENCODING -> DiscourseCookieEncoding.BASE64_ENCODING
    }

private fun DiscourseCookieEncoding.toKtorEncoding(): CookieEncoding =
    when (this) {
        DiscourseCookieEncoding.RAW -> CookieEncoding.RAW
        DiscourseCookieEncoding.DQUOTES -> CookieEncoding.DQUOTES
        DiscourseCookieEncoding.URI_ENCODING -> CookieEncoding.URI_ENCODING
        DiscourseCookieEncoding.BASE64_ENCODING -> CookieEncoding.BASE64_ENCODING
    }
