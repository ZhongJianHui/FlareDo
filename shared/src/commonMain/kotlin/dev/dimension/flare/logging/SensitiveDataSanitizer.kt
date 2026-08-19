package dev.dimension.flare.logging

/**
 * Redacts authentication material and direct identifiers before a log reaches any sink.
 *
 * The sanitizer is intentionally conservative: diagnostic value is secondary to ensuring that a
 * copied bug report cannot contain a Linux.do web session, one-time login value, or email address.
 */
public class SensitiveDataSanitizer {
    /** Returns a display-safe version of [message]. */
    public fun sanitize(message: String): String {
        var result =
            URL_USER_INFO.replace(message) { match ->
                "${match.groupValues[1]}$REDACTED@"
            }
        result =
            COOKIE_HEADER.replace(result) { match ->
                "${match.groupValues[1]}: $REDACTED"
            }
        result =
            AUTHORIZATION_HEADER.replace(result) { match ->
                "${match.groupValues[1]}: $REDACTED"
            }
        result =
            AUTHORIZATION_ASSIGNMENT.replace(result) { match ->
                "${match.groupValues[1]}$REDACTED"
            }
        result =
            ENCODED_SECRET_ASSIGNMENT.replace(result) { match ->
                "${match.groupValues[1]}$REDACTED"
            }
        result =
            SECRET_ASSIGNMENT.replace(result) { match ->
                "${match.groupValues[1]}$REDACTED"
            }
        result =
            BEARER_VALUE.replace(result) { match ->
                "${match.groupValues[1]} $REDACTED"
            }
        return EMAIL_ADDRESS.replace(result, REDACTED_EMAIL)
    }

    private companion object {
        private const val REDACTED: String = "[REDACTED]"
        private const val REDACTED_EMAIL: String = "[REDACTED_EMAIL]"

        private val URL_USER_INFO = Regex("(?i)(https?://)[^/@\\s:]+:[^/@\\s]+@")
        private val COOKIE_HEADER = Regex("(?im)\\b(set-cookie|cookie)\\s*:\\s*[^\\r\\n]+")
        private val AUTHORIZATION_HEADER =
            Regex("(?im)\\b(authorization|proxy-authorization)\\s*:\\s*[^\\r\\n]+")
        private val AUTHORIZATION_ASSIGNMENT =
            Regex(
                """(?i)([\"']?(?:authorization|proxy-authorization)[\"']?\s*(?:=|:)\s*[\"']?)[^\"'&,}\r\n]+""",
            )
        private val ENCODED_SECRET_ASSIGNMENT =
            Regex(
                """(?i)((?:_t|user(?:_|%5f|-)?api(?:_|%5f|-)?key|shared(?:_|%5f|-)?session(?:_|%5f|-)?key|access(?:_|%5f|-)?token|refresh(?:_|%5f|-)?token|client(?:_|%5f|-)?secret|csrf|nonce|otp)(?:%3d|%3a))[^&\s]+""",
            )
        private val SECRET_ASSIGNMENT =
            Regex(
                """(?i)([\"']?(?:_t|user[_-]?api[_-]?key|userApiKey|user[_-]?api[_-]?client[_-]?id|shared[_-]?session[_-]?key|session[_-]?key|set[_-]?cookie|cookie|x[_-]?csrf[_-]?token|csrf|nonce|otp|payload|api[_-]?key|apiKey|client[_-]?secret|clientSecret|access[_-]?token|refresh[_-]?token|id[_-]?token|auth[_-]?token|token|session|sid|password|passwd)[\"']?\s*(?:=|:)\s*[\"']?)(?!\[REDACTED(?:_EMAIL)?\])[^\"'&;,\s}\]]+""",
            )
        private val BEARER_VALUE = Regex("(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/=-]+")
        private val EMAIL_ADDRESS =
            Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
    }
}
