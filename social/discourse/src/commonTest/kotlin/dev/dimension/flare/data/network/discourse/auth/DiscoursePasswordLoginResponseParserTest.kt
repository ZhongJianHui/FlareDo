package dev.dimension.flare.data.network.discourse.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class DiscoursePasswordLoginResponseParserTest {
    @Test
    fun parsesSuccessfulUserEnvelope() {
        val result =
            DiscoursePasswordLoginResponseParser.parse(
                statusCode = 200,
                body = "{\"user\":{\"id\":42,\"username\":\"fixture\"}}",
            )

        assertIs<DiscoursePasswordLoginResponse.Success>(result)
    }

    @Test
    fun parsesSecondFactorCapabilitiesWithoutRetainingServerText() {
        val result =
            assertIs<DiscoursePasswordLoginResponse.Failure>(
                DiscoursePasswordLoginResponseParser.parse(
                    statusCode = 200,
                    body =
                        """
                        {
                          "reason":"second_factor",
                          "error":"fixture-sensitive text",
                          "totp_enabled":true,
                          "backup_enabled":true,
                          "security_key_enabled":false
                        }
                        """.trimIndent(),
                ),
            )

        assertEquals(DiscoursePasswordLoginFailureKind.SecondFactorRequired, result.kind)
        assertEquals(
            DiscoursePasswordLoginSecondFactor(
                totpEnabled = true,
                backupCodeEnabled = true,
                securityKeyEnabled = false,
            ),
            result.secondFactor,
        )
    }

    @Test
    fun mapsKnownFailureReasons() {
        val expected =
            mapOf(
                "invalid_credentials" to DiscoursePasswordLoginFailureKind.InvalidCredentials,
                "not_activated" to DiscoursePasswordLoginFailureKind.NotActivated,
                "not_approved" to DiscoursePasswordLoginFailureKind.NotApproved,
                "expired" to DiscoursePasswordLoginFailureKind.PasswordExpired,
            )

        expected.forEach { (reason, kind) ->
            val result =
                assertIs<DiscoursePasswordLoginResponse.Failure>(
                    DiscoursePasswordLoginResponseParser.parse(
                        statusCode = 200,
                        body = "{\"reason\":\"$reason\"}",
                    ),
                )
            assertEquals(kind, result.kind)
            assertEquals(null, result.secondFactor)
        }
    }

    @Test
    fun malformedOrNonSuccessEnvelopeFailsClosed() {
        assertIs<DiscoursePasswordLoginResponse.Unexpected>(
            DiscoursePasswordLoginResponseParser.parse(403, "<html>challenge</html>"),
        )

        val unknown =
            assertIs<DiscoursePasswordLoginResponse.Failure>(
                DiscoursePasswordLoginResponseParser.parse(200, "{\"error\":\"unknown\"}"),
            )
        assertEquals(DiscoursePasswordLoginFailureKind.Unknown, unknown.kind)
        assertFalse(unknown.secondFactor?.totpEnabled == true)
    }

    @Test
    fun oversizedBodyIsRejectedBeforeParsing() {
        val result =
            DiscoursePasswordLoginResponseParser.parse(
                statusCode = 200,
                body = "{" + "x".repeat(DiscoursePasswordLoginResponseParser.MAX_RESPONSE_CHARS) + "}",
            )

        assertTrue(result is DiscoursePasswordLoginResponse.Unexpected)
    }
}
