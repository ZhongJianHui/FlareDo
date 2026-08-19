package dev.dimension.flare.data.network.discourse.error

/**
 * Sanitized failure exposed by the Linux.do Discourse transport.
 *
 * Messages are deliberately fixed and never contain a URL, cookie, CSRF token, response body, or
 * arbitrary server text. Transport code may inspect a response to classify it, but must discard that
 * sensitive material before constructing one of these exceptions. Callers can branch on the subtype
 * and structured scalar fields without logging raw server content.
 */
public sealed class DiscourseException protected constructor(
    message: String,
) : Exception(message)

/** HTTP 401. The active session must be refreshed or replaced before retrying. */
public class DiscourseAuthenticationException : DiscourseException("Discourse authentication is required")

/** HTTP 403 that is neither an explicitly identified CSRF failure nor a Cloudflare challenge. */
public class DiscoursePermissionException : DiscourseException("Discourse denied this operation")

/**
 * An explicit invalid-CSRF response.
 *
 * The session layer may invalidate its in-memory token, fetch `/session/csrf.json`, and replay the
 * original request once. A second [DiscourseCsrfException] for that operation must be surfaced rather
 * than replayed again; this type carries no counter because retry ownership belongs to the caller.
 */
public class DiscourseCsrfException : DiscourseException("Discourse CSRF token was rejected")

/**
 * HTTP 429 that represents ordinary Discourse rate limiting.
 *
 * [retryAfterSeconds] is `null` when no valid Retry-After value was available and may be zero when
 * the server permits an immediate retry.
 */
public class DiscourseRateLimitException(
    public val retryAfterSeconds: Long?,
) : DiscourseException("Discourse rate limit reached") {
    init {
        require(retryAfterSeconds == null || retryAfterSeconds >= 0L) {
            "Retry-After seconds cannot be negative"
        }
    }
}

/**
 * An explicit Cloudflare managed challenge returned with HTTP 403 or 429.
 *
 * This is separate from [DiscoursePermissionException] and [DiscourseRateLimitException] because it
 * requires an intentional user-mediated challenge flow and permits at most one replay after that flow
 * succeeds.
 */
public class DiscourseCloudflareChallengeException(
    public val statusCode: Int,
) : DiscourseException("Cloudflare challenge required") {
    init {
        require(statusCode == 403 || statusCode == 429) {
            "A Cloudflare challenge must use HTTP 403 or 429"
        }
    }
}

/** HTTP 5xx response. Only the status code is retained. */
public class DiscourseServerException(
    public val statusCode: Int,
) : DiscourseException("Discourse server failure ($statusCode)") {
    init {
        require(statusCode in 500..599) { "A server failure must use an HTTP 5xx status" }
    }
}

/** Other non-success HTTP response. Only the status code is retained. */
public class DiscourseHttpException(
    public val statusCode: Int,
) : DiscourseException("Discourse HTTP failure ($statusCode)") {
    init {
        require(statusCode in 100..599) { "Invalid HTTP status code" }
        require(statusCode !in 200..299) { "A successful HTTP status is not a failure" }
    }
}

/** Transport failure classified without retaining the platform exception or request URL. */
public class DiscourseNetworkException(
    public val kind: DiscourseNetworkFailureKind,
) : DiscourseException("Discourse network failure (${kind.name})")

/** JSON encode/decode failure that intentionally omits the input and parser exception message. */
public class DiscourseSerializationException(
    public val phase: DiscourseSerializationPhase,
) : DiscourseException("Discourse serialization failure (${phase.name})")

/**
 * A successful mutation response that Discourse placed into its moderation queue.
 *
 * The pending draft body and title are intentionally not retained by the exception. Stable numeric
 * references are sufficient for reconciliation while keeping logs free of unpublished content.
 */
public class DiscoursePostEnqueuedException(
    public val pendingCount: Int,
    public val pendingPostId: Long? = null,
    public val topicId: Long? = null,
) : DiscourseException("Discourse post is pending moderation") {
    init {
        require(pendingCount >= 0) { "The pending moderation count cannot be negative" }
        require(pendingPostId == null || pendingPostId > 0L) {
            "A pending post ID must be positive"
        }
        require(topicId == null || topicId > 0L) { "A pending topic ID must be positive" }
    }
}

/** Coarse network categories that are safe to expose to presenters and bounded diagnostics. */
public enum class DiscourseNetworkFailureKind {
    Timeout,
    Connection,
    Cancelled,
    Unknown,
}

/** Whether serialization failed while encoding a request or decoding a response. */
public enum class DiscourseSerializationPhase {
    RequestEncoding,
    ResponseDecoding,
}
