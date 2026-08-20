package dev.dimension.flare.data.network.discourse.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class DiscourseErrorClassifierTest {
    @Test
    fun classifiesAuthenticationPermissionAndServerFailures() {
        assertIs<DiscourseAuthenticationException>(
            DiscourseErrorClassifier.classifyHttp(401),
        )
        assertIs<DiscoursePermissionException>(
            DiscourseErrorClassifier.classifyHttp(403),
        )
        assertEquals(
            503,
            assertIs<DiscourseServerException>(
                DiscourseErrorClassifier.classifyHttp(503),
            ).statusCode,
        )
        assertEquals(
            DiscourseValidationField.Unknown,
            assertIs<DiscourseValidationException>(
                DiscourseErrorClassifier.classifyHttp(422),
            ).field,
        )
        assertFailsWith<IllegalArgumentException> {
            mapDiscourseResponseException(statusCode = 200)
        }
    }

    @Test
    fun ordinaryRateLimitRetainsOnlyParsedRetryDelay() {
        val failure =
            assertIs<DiscourseRateLimitException>(
                DiscourseErrorClassifier.classifyHttp(
                    statusCode = 429,
                    retryAfterHeader = "120",
                ),
            )

        assertEquals(120L, failure.retryAfterSeconds)
        assertFalse(failure.toString().contains("120"))
    }

    @Test
    fun cloudflareChallengeIsNotMisclassifiedAsPermissionOrRateLimit() {
        val forbiddenChallenge =
            DiscourseErrorClassifier.classifyHttp(
                statusCode = 403,
                explicitCloudflareChallenge = true,
            )
        val throttledChallenge =
            DiscourseErrorClassifier.classifyHttp(
                statusCode = 429,
                retryAfterHeader = "30",
                explicitCloudflareChallenge = true,
            )

        assertEquals(
            403,
            assertIs<DiscourseCloudflareChallengeException>(forbiddenChallenge).statusCode,
        )
        assertEquals(
            429,
            assertIs<DiscourseCloudflareChallengeException>(throttledChallenge).statusCode,
        )
    }

    @Test
    fun missingOrMalformedRetryAfterRemainsUnknown() {
        assertNull(
            assertIs<DiscourseRateLimitException>(
                DiscourseErrorClassifier.classifyHttp(429),
            ).retryAfterSeconds,
        )
        assertNull(DiscourseRetryAfterParser.parseSeconds("not a date"))
        assertNull(DiscourseRetryAfterParser.parseSeconds("-1"))
        assertNull(DiscourseRetryAfterParser.parseSeconds("x".repeat(129)))
    }

    @Test
    fun parsesHttpDateRetryAfterRelativeToAnInjectedClock() {
        val failure =
            assertIs<DiscourseRateLimitException>(
                DiscourseErrorClassifier.classifyHttp(
                    statusCode = 429,
                    retryAfterHeader = "Wed, 21 Oct 2015 07:28:00 GMT",
                    nowEpochSeconds = 1_445_412_420L,
                ),
            )

        assertEquals(60L, failure.retryAfterSeconds)
        assertEquals(
            0L,
            DiscourseRetryAfterParser.parseSeconds(
                value = "Wed, 21 Oct 2015 07:28:00 GMT",
                nowEpochSeconds = 1_445_412_540L,
            ),
        )
    }

    @Test
    fun challengeDetectorRequiresPositiveEvidence() {
        assertFalse(
            DiscourseCloudflareChallengeDetector.isExplicitChallenge(
                statusCode = 429,
                headers = mapOf("Server" to "cloudflare"),
                bodyPrefix = "ordinary rate limit",
            ),
        )
        assertTrue(
            DiscourseCloudflareChallengeDetector.isExplicitChallenge(
                statusCode = 429,
                headers = mapOf("CF-Mitigated" to "challenge"),
            ),
        )
        assertTrue(
            DiscourseCloudflareChallengeDetector.isExplicitChallenge(
                statusCode = 403,
                bodyPrefix = "<script src='/cdn-cgi/challenge-platform/h/g/orchestrate'></script>",
            ),
        )
        assertFalse(
            DiscourseCloudflareChallengeDetector.isExplicitChallenge(
                statusCode = 500,
                headers = mapOf("cf-mitigated" to "challenge"),
            ),
        )
    }

    @Test
    fun sanitizedFailureTypesDoNotRetainSensitivePayloads() {
        val sensitiveBody =
            "_t=session-secret raw=unpublished-post /cdn-cgi/challenge-platform/"
        val isChallenge =
            DiscourseCloudflareChallengeDetector.isExplicitChallenge(
                statusCode = 403,
                bodyPrefix = sensitiveBody,
            )
        val failure =
            DiscourseErrorClassifier.classifyHttp(
                statusCode = 403,
                explicitCloudflareChallenge = isChallenge,
            )

        assertTrue(isChallenge)
        assertFalse(failure.toString().contains("session-secret"))
        assertFalse(failure.toString().contains("unpublished-post"))
        assertFalse(
            DiscourseNetworkException(DiscourseNetworkFailureKind.Connection)
                .toString()
                .contains("session-secret"),
        )
        assertFalse(
            DiscourseSerializationException(
                DiscourseSerializationPhase.ResponseDecoding,
            ).toString().contains("unpublished-post"),
        )
    }

    @Test
    fun moderationQueueHasDedicatedValidatedStateWithoutDraftContent() {
        val enqueued =
            DiscoursePostEnqueuedException(
                pendingCount = 2,
                pendingPostId = 73L,
                topicId = 11L,
            )

        assertEquals(2, enqueued.pendingCount)
        assertEquals(73L, enqueued.pendingPostId)
        assertEquals(11L, enqueued.topicId)
        assertFalse(enqueued.toString().contains("73"))
        assertFailsWith<IllegalArgumentException> {
            DiscoursePostEnqueuedException(pendingCount = -1)
        }
    }

    @Test
    fun unifiedMapperPrioritizesChallengeThenCsrfThenOrdinaryPermission() {
        assertIs<DiscourseCloudflareChallengeException>(
            mapDiscourseResponseException(
                statusCode = 403,
                headers = mapOf("cf-mitigated" to "challenge"),
                bodyPrefix = "BAD CSRF",
            ),
        )
        assertIs<DiscourseCsrfException>(
            mapDiscourseResponseException(
                statusCode = 403,
                bodyPrefix = "[\"BAD CSRF\"]",
            ),
        )
        assertIs<DiscoursePermissionException>(
            mapDiscourseResponseException(
                statusCode = 403,
                bodyPrefix = "operation is not permitted",
            ),
        )
    }

    @Test
    fun unifiedMapperReadsRetryAfterCaseInsensitivelyWithoutRetainingBody() {
        val secret = "unpublished reply and _t=cookie-secret"
        val failure =
            assertIs<DiscourseRateLimitException>(
                mapDiscourseResponseException(
                    statusCode = 429,
                    headers = mapOf("ReTrY-AfTeR" to "45"),
                    bodyPrefix = secret,
                ),
            )

        assertEquals(45L, failure.retryAfterSeconds)
        assertFalse(failure.toString().contains(secret))
        assertFalse(failure.toString().contains("cookie-secret"))
    }

    @Test
    fun validationMapperRetainsOnlyAllowlistedEnumsAndBoundedNumericScalars() {
        val unpublishedBody = "Never retain this unpublished fixture reply"
        val failure =
            assertIs<DiscourseValidationException>(
                mapDiscourseResponseException(
                    statusCode = 422,
                    bodyPrefix =
                        """
                        {
                          "errors":["$unpublishedBody"],
                          "field":"post[raw]",
                          "error_type":"too_short",
                          "minimum":20,
                          "extras":{"maximum":1000000,"retry_after_seconds":5},
                          "plugin_debug":"_t=fixture-cookie"
                        }
                        """.trimIndent(),
                ),
            )

        assertEquals(DiscourseValidationField.Raw, failure.field)
        assertEquals(DiscourseValidationReason.TooShort, failure.reason)
        assertEquals(20L, failure.minimum)
        assertEquals(1_000_000L, failure.maximum)
        assertEquals(5L, failure.retryAfterSeconds)
        assertFalse(failure.toString().contains(unpublishedBody))
        assertFalse(failure.toString().contains("fixture-cookie"))
    }

    @Test
    fun validationMapperRejectsUnknownStringsStringScalarsAndInvalidBounds() {
        val failure =
            assertIs<DiscourseValidationException>(
                mapDiscourseResponseException(
                    statusCode = 422,
                    bodyPrefix =
                        """
                        {
                          "field":"private_plugin_field",
                          "error_type":"private_plugin_reason",
                          "minimum":"7",
                          "maximum":-1,
                          "extras":{"retry_after_seconds":86401}
                        }
                        """.trimIndent(),
                ),
            )

        assertEquals(DiscourseValidationField.Unknown, failure.field)
        assertEquals(DiscourseValidationReason.Unknown, failure.reason)
        assertNull(failure.minimum)
        assertNull(failure.maximum)
        assertNull(failure.retryAfterSeconds)
    }
}
