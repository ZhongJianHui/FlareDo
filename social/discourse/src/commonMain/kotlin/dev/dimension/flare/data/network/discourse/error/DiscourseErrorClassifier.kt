package dev.dimension.flare.data.network.discourse.error

import dev.dimension.flare.data.network.discourse.model.discourseJson
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock

/**
 * Converts sanitized HTTP metadata into a typed [DiscourseException].
 *
 * This API intentionally has no response-body or server-message parameter. A caller may inspect a
 * bounded prefix with [DiscourseCloudflareChallengeDetector], but only the resulting Boolean crosses
 * the classification boundary. This makes accidental propagation of cookies, unpublished posts, or
 * proxy challenge payloads into exception logs substantially harder.
 */
public object DiscourseErrorClassifier {
    /**
     * Classifies a non-success HTTP response.
     *
     * [explicitCloudflareChallenge] must only be true after positive challenge evidence was found;
     * status 403/429 alone is insufficient. [nowEpochSeconds] is injectable for deterministic parsing
     * of the HTTP-date variant of Retry-After.
     */
    public fun classifyHttp(
        statusCode: Int,
        retryAfterHeader: String? = null,
        explicitCloudflareChallenge: Boolean = false,
        nowEpochSeconds: Long = Clock.System.now().epochSeconds,
    ): DiscourseException {
        require(statusCode in 100..599) { "Invalid HTTP status code" }
        require(statusCode !in 200..299) { "A successful HTTP status cannot be classified as an error" }

        if (explicitCloudflareChallenge && (statusCode == 403 || statusCode == 429)) {
            return DiscourseCloudflareChallengeException(statusCode)
        }

        if (statusCode == 429) {
            return DiscourseRateLimitException(
                retryAfterSeconds =
                    DiscourseRetryAfterParser.parseSeconds(
                        value = retryAfterHeader,
                        nowEpochSeconds = nowEpochSeconds,
                    ),
            )
        }

        return when (statusCode) {
            401 -> DiscourseAuthenticationException()
            403 -> DiscoursePermissionException()
            422 -> DiscourseValidationException(DiscourseValidationField.Unknown, DiscourseValidationReason.Unknown)
            in 500..599 -> DiscourseServerException(statusCode)
            else -> DiscourseHttpException(statusCode)
        }
    }
}

/**
 * Unified pure mapping entry point for an HTTP response failure.
 *
 * [bodyPrefix] is inspected only for bounded Cloudflare and CSRF markers and is never retained by the
 * returned exception. Callers should pass at most an already bounded prefix rather than buffering a
 * large response solely for error mapping. Explicit Cloudflare evidence wins over CSRF evidence,
 * followed by ordinary status classification.
 */
public fun mapDiscourseResponseException(
    statusCode: Int,
    headers: Map<String, String> = emptyMap(),
    bodyPrefix: String? = null,
    nowEpochSeconds: Long = Clock.System.now().epochSeconds,
): DiscourseException {
    require(statusCode !in 200..299) { "A successful HTTP status cannot be mapped as an error" }
    val explicitCloudflareChallenge =
        DiscourseCloudflareChallengeDetector.isExplicitChallenge(
            statusCode = statusCode,
            headers = headers,
            bodyPrefix = bodyPrefix,
        )
    if (explicitCloudflareChallenge) {
        return DiscourseCloudflareChallengeException(statusCode)
    }

    if (DiscourseCsrfFailureDetector.isExplicitCsrfFailure(statusCode, bodyPrefix)) {
        return DiscourseCsrfException()
    }

    if (statusCode == 422) {
        return DiscourseValidationResponseParser.parse(bodyPrefix)
    }

    val retryAfter =
        headers.entries
            .firstOrNull { (name, _) -> name.equals("retry-after", ignoreCase = true) }
            ?.value
    return DiscourseErrorClassifier.classifyHttp(
        statusCode = statusCode,
        retryAfterHeader = retryAfter,
        nowEpochSeconds = nowEpochSeconds,
    )
}

/**
 * Extracts only a fixed allowlist from a bounded HTTP 422 body.
 *
 * The free-form `errors` array is never read. Unknown strings, string-encoded numbers, malformed
 * JSON, out-of-range scalars, and truncated prefixes all collapse to absent/Unknown metadata.
 */
public object DiscourseValidationResponseParser {
    private const val MAX_BODY_PREFIX_CHARS: Int = 4_096
    private const val MAX_VALIDATION_BOUND: Long = Int.MAX_VALUE.toLong()
    private const val MAX_RETRY_AFTER_SECONDS: Long = 86_400L

    public fun parse(bodyPrefix: String?): DiscourseValidationException {
        val root =
            bodyPrefix
                ?.take(MAX_BODY_PREFIX_CHARS)
                ?.let(::parseBoundedObject)
        val extras = root?.get("extras") as? JsonObject
        val field =
            allowlistedString(root, "field", "attribute")
                ?.toValidationField()
                ?: DiscourseValidationField.Unknown
        val reason =
            allowlistedString(root, "error_type")
                ?.toValidationReason()
                ?: DiscourseValidationReason.Unknown
        val minimum = allowlistedScalar(root, extras, "minimum", "min", upperBound = MAX_VALIDATION_BOUND)
        val maximum = allowlistedScalar(root, extras, "maximum", "max", "limit", upperBound = MAX_VALIDATION_BOUND)
        val validMinimum = minimum.takeIf { maximum == null || it == null || it <= maximum }
        val retryAfter =
            allowlistedScalar(
                root,
                extras,
                "wait_seconds",
                "retry_after_seconds",
                upperBound = MAX_RETRY_AFTER_SECONDS,
            )
        return DiscourseValidationException(
            field = field,
            reason = reason,
            minimum = validMinimum,
            maximum = maximum,
            retryAfterSeconds = retryAfter,
        )
    }

    private fun parseBoundedObject(value: String): JsonObject? =
        try {
            discourseJson.parseToJsonElement(value) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun allowlistedString(
        root: JsonObject?,
        vararg keys: String,
    ): String? =
        keys.firstNotNullOfOrNull { key ->
            val primitive = root?.get(key) as? JsonPrimitive
            primitive?.takeIf(JsonPrimitive::isString)?.content?.takeIf { it.length <= 64 }
        }

    private fun allowlistedScalar(
        root: JsonObject?,
        extras: JsonObject?,
        vararg keys: String,
        upperBound: Long,
    ): Long? =
        keys.firstNotNullOfOrNull { key ->
            (root?.get(key) as? JsonPrimitive).boundedLong(upperBound)
                ?: (extras?.get(key) as? JsonPrimitive).boundedLong(upperBound)
        }

    private fun JsonPrimitive?.boundedLong(upperBound: Long): Long? =
        this
            ?.takeUnless(JsonPrimitive::isString)
            ?.longOrNull
            ?.takeIf { it in 0L..upperBound }

    private fun String.toValidationField(): DiscourseValidationField =
        when (lowercase()) {
            "title", "topic_title" -> DiscourseValidationField.Title
            "raw", "post[raw]", "body" -> DiscourseValidationField.Raw
            "tags", "tags[]" -> DiscourseValidationField.Tags
            "category", "category_id" -> DiscourseValidationField.Category
            "upload", "file", "files" -> DiscourseValidationField.Upload
            "post", "post_id" -> DiscourseValidationField.Post
            else -> DiscourseValidationField.Unknown
        }

    private fun String.toValidationReason(): DiscourseValidationReason =
        when (lowercase()) {
            "invalid_parameters", "unprocessable_entity", "invalid_input" -> DiscourseValidationReason.InvalidInput
            "required", "blank" -> DiscourseValidationReason.Required
            "too_short" -> DiscourseValidationReason.TooShort
            "too_long" -> DiscourseValidationReason.TooLong
            "invalid_access", "forbidden" -> DiscourseValidationReason.Forbidden
            "not_found" -> DiscourseValidationReason.NotFound
            "rate_limit", "rate_limited" -> DiscourseValidationReason.RateLimited
            else -> DiscourseValidationReason.Unknown
        }
}

/** Parses the delta-seconds and IMF-fixdate forms allowed by the HTTP Retry-After header. */
public object DiscourseRetryAfterParser {
    private const val MAX_HEADER_LENGTH: Int = 128

    private val imfFixdate =
        Regex(
            pattern =
                "^[A-Za-z]{3},\\s*(\\d{1,2})\\s+([A-Za-z]{3})\\s+" +
                    "(\\d{4})\\s+(\\d{2}):(\\d{2}):(\\d{2})\\s+GMT$",
            option = RegexOption.IGNORE_CASE,
        )

    private val months: Map<String, Month> =
        mapOf(
            "jan" to Month.JANUARY,
            "feb" to Month.FEBRUARY,
            "mar" to Month.MARCH,
            "apr" to Month.APRIL,
            "may" to Month.MAY,
            "jun" to Month.JUNE,
            "jul" to Month.JULY,
            "aug" to Month.AUGUST,
            "sep" to Month.SEPTEMBER,
            "oct" to Month.OCTOBER,
            "nov" to Month.NOVEMBER,
            "dec" to Month.DECEMBER,
        )

    /**
     * Returns the non-negative delay represented by [value], or `null` for malformed input.
     *
     * Numeric values are interpreted as seconds. HTTP dates in the standard IMF-fixdate form are
     * converted relative to [nowEpochSeconds]; past dates produce zero. Input length is bounded before
     * regular-expression parsing so an untrusted header cannot create unbounded diagnostic work.
     */
    public fun parseSeconds(
        value: String?,
        nowEpochSeconds: Long = Clock.System.now().epochSeconds,
    ): Long? {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (normalized.length > MAX_HEADER_LENGTH) return null

        normalized.toLongOrNull()?.let { seconds ->
            return seconds.takeIf { it >= 0L }
        }

        val retryAtEpochSeconds = parseImfFixdate(normalized) ?: return null
        return (retryAtEpochSeconds - nowEpochSeconds).coerceAtLeast(0L)
    }

    private fun parseImfFixdate(value: String): Long? {
        val match = imfFixdate.matchEntire(value) ?: return null
        val month = months[match.groupValues[2].lowercase()] ?: return null
        return runCatching {
            LocalDateTime(
                year = match.groupValues[3].toInt(),
                month = month,
                day = match.groupValues[1].toInt(),
                hour = match.groupValues[4].toInt(),
                minute = match.groupValues[5].toInt(),
                second = match.groupValues[6].toInt(),
            ).toInstant(TimeZone.UTC).epochSeconds
        }.getOrNull()
    }
}

/**
 * Detects positive Cloudflare challenge evidence without retaining the inspected response content.
 *
 * A `Server: cloudflare` header alone is deliberately insufficient because ordinary proxied errors
 * carry it too. Detection is limited to 403/429 plus either the official `cf-mitigated: challenge`
 * header or an explicit challenge-platform marker in a bounded body prefix.
 */
public object DiscourseCloudflareChallengeDetector {
    private const val MAX_BODY_PREFIX_CHARS: Int = 4_096

    private val explicitBodyMarkers: List<String> =
        listOf(
            "/cdn-cgi/challenge-platform/",
            "cf-chl-",
            "cf-turnstile-response",
        )

    /** Returns true only when [statusCode] and the supplied metadata identify a challenge. */
    public fun isExplicitChallenge(
        statusCode: Int,
        headers: Map<String, String> = emptyMap(),
        bodyPrefix: String? = null,
    ): Boolean {
        if (statusCode != 403 && statusCode != 429) return false

        val mitigated =
            headers.entries
                .firstOrNull { (name, _) -> name.equals("cf-mitigated", ignoreCase = true) }
                ?.value
        if (mitigated?.trim().equals("challenge", ignoreCase = true)) return true

        val boundedBody = bodyPrefix?.take(MAX_BODY_PREFIX_CHARS)?.lowercase() ?: return false
        return explicitBodyMarkers.any(boundedBody::contains)
    }
}

/** Detects an explicit Discourse CSRF rejection without retaining the response body. */
public object DiscourseCsrfFailureDetector {
    private const val MAX_BODY_PREFIX_CHARS: Int = 4_096

    private val explicitMarkers: List<String> =
        listOf(
            "bad csrf",
            "invalid csrf",
            "csrf token is invalid",
            "csrf token was rejected",
        )

    /** Returns true only for HTTP 403 with a known CSRF rejection marker. */
    public fun isExplicitCsrfFailure(
        statusCode: Int,
        bodyPrefix: String?,
    ): Boolean {
        if (statusCode != 403) return false
        val boundedBody = bodyPrefix?.take(MAX_BODY_PREFIX_CHARS)?.lowercase() ?: return false
        return explicitMarkers.any(boundedBody::contains)
    }
}
