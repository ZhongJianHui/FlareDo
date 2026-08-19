package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.network.discourse.session.SecureCredentialRef
import io.ktor.http.Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val MAX_AUTH_ATTEMPT_LIFETIME_MILLIS: Long = 10L * 60L * 1_000L
private const val MAX_AUTH_REDIRECT_CHARS: Int = 16_384
private const val MAX_ENCRYPTED_QUERY_CHARS: Int = 4_096
private val AUTH_REDIRECT_QUERY_NAMES: Set<String> = setOf("payload", "oneTimePassword")

/**
 * Non-secret metadata for one pending browser authorization.
 *
 * [privateKeyRef] locates the PKCS#8 key in a platform vault; private key bytes never enter this
 * object or Room. [id] is independent of [nonce] and is used for compare-and-consume so replacing a
 * pending attempt cannot create an ABA race in a concurrently arriving callback.
 */
public data class DiscourseAuthAttempt(
    public val id: String,
    public val privateKeyRef: SecureCredentialRef,
    public val nonce: String,
    public val clientId: String,
    public val createdAtEpochMillis: Long,
    public val expiresAtEpochMillis: Long,
) {
    init {
        requireValidAuthToken(id, "Authorization attempt id")
        requireValidAuthToken(nonce, "Authorization nonce")
        requireValidAuthToken(clientId, "Client id")
        require(createdAtEpochMillis >= 0L) { "Authorization creation time must not be negative" }
        require(expiresAtEpochMillis > createdAtEpochMillis) {
            "Authorization expiry must be later than creation"
        }
        require(expiresAtEpochMillis - createdAtEpochMillis <= MAX_AUTH_ATTEMPT_LIFETIME_MILLIS) {
            "Authorization attempt lifetime must not exceed ten minutes"
        }
    }
}

/** Atomic single-active-attempt persistence boundary. */
public interface DiscourseAuthAttemptStore {
    /** Replaces the active attempt and returns the previous metadata for caller-owned key cleanup. */
    public suspend fun replace(attempt: DiscourseAuthAttempt): DiscourseAuthAttempt?

    /** Returns the active metadata without consuming it. */
    public suspend fun peek(): DiscourseAuthAttempt?

    /** Atomically removes and returns the active attempt only when [expectedId] still matches. */
    public suspend fun consume(expectedId: String): DiscourseAuthAttempt?
}

/** Process-only attempt store used by tests and hosts that have not installed Room persistence. */
public class MemoryDiscourseAuthAttemptStore : DiscourseAuthAttemptStore {
    private val mutex: Mutex = Mutex()
    private var attempt: DiscourseAuthAttempt? = null

    override suspend fun replace(attempt: DiscourseAuthAttempt): DiscourseAuthAttempt? =
        mutex.withLock {
            val previous = this.attempt
            this.attempt = attempt
            previous
        }

    override suspend fun peek(): DiscourseAuthAttempt? = mutex.withLock { attempt }

    override suspend fun consume(expectedId: String): DiscourseAuthAttempt? =
        mutex.withLock {
            val current = attempt
            if (current?.id != expectedId) return@withLock null
            attempt = null
            current
        }
}

/** Encrypted fields extracted from a structurally valid `discourse://auth_redirect` URI. */
public data class DiscourseEncryptedAuthRedirect(
    public val payload: String,
    public val oneTimePassword: String,
)

/**
 * Parses the callback independently from Android Intent validation.
 *
 * Android must first require `ACTION_VIEW`, reject nested intents and URI-grant flags, and pass only
 * `Intent.dataString` here. This parser then rejects path, port, userinfo, fragment, unknown query
 * fields, duplicate query fields, missing encrypted values, and allocation-unbounded input.
 */
public object DiscourseAuthRedirectParser {
    public fun parse(rawUri: String): DiscourseEncryptedAuthRedirect? {
        if (rawUri.length !in 1..MAX_AUTH_REDIRECT_CHARS) return null
        // Requiring `?` immediately after the authority rejects path, port, and userinfo before a
        // permissive URL parser can normalize them into an equivalent-looking URI.
        if (!rawUri.startsWith("discourse://auth_redirect?")) return null

        val url =
            try {
                Url(rawUri)
            } catch (_: Exception) {
                return null
            }
        if (url.protocol.name != "discourse" || url.host != "auth_redirect") return null
        if (url.encodedPath.isNotEmpty()) return null
        if (url.port != 0 || !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty()) return null
        if (url.fragment.isNotEmpty()) return null
        if (url.parameters.names() != AUTH_REDIRECT_QUERY_NAMES) return null

        val payloadValues = url.parameters.getAll("payload") ?: return null
        val otpValues = url.parameters.getAll("oneTimePassword") ?: return null
        if (payloadValues.size != 1 || otpValues.size != 1) return null
        val payload = payloadValues.single()
        val otp = otpValues.single()
        if (payload.length !in 1..MAX_ENCRYPTED_QUERY_CHARS) return null
        if (otp.length !in 1..MAX_ENCRYPTED_QUERY_CHARS) return null
        if (!payload.isCanonicalBase64() || !otp.isCanonicalBase64()) return null

        return DiscourseEncryptedAuthRedirect(payload = payload, oneTimePassword = otp)
    }
}

internal fun String.isCanonicalBase64(): Boolean {
    if (length % 4 != 0) return false
    val paddingStart = indexOf('=')
    val contentEnd = if (paddingStart < 0) length else paddingStart
    if (length - contentEnd > 2) return false
    if (substring(contentEnd).any { it != '=' }) return false
    return substring(0, contentEnd).all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '+' || it == '/'
    }
}
