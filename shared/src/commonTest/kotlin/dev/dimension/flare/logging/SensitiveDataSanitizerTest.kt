package dev.dimension.flare.logging

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

internal class SensitiveDataSanitizerTest {
    private val sanitizer: SensitiveDataSanitizer = SensitiveDataSanitizer()

    @Test
    fun redactsHeadersAssignmentsBearerTokensAndEmail() {
        val input =
            """
            Authorization: Bearer header-secret
            Cookie: _t=cookie-secret; theme=dark
            Set-Cookie: shared_session_key=cookie-response
            {"user_api_key":"api-secret","csrf":"csrf-secret","nonce":"nonce-secret"}
            otp=123456&access_token=access-secret payload: signed-secret
            cookie=session-secret; client_secret=client-secret session=web-session
            authorization=Basic basic-secret user_api_key%3Dencoded-secret
            {"authorization":"Basic json-basic-secret"}
            https://name:url-password@linux.do/latest.json
            account=user@example.com Bearer standalone-secret
            """.trimIndent()

        val result = sanitizer.sanitize(input)

        listOf(
            "header-secret",
            "cookie-secret",
            "cookie-response",
            "api-secret",
            "csrf-secret",
            "nonce-secret",
            "123456",
            "access-secret",
            "signed-secret",
            "session-secret",
            "client-secret",
            "web-session",
            "basic-secret",
            "json-basic-secret",
            "encoded-secret",
            "url-password",
            "user@example.com",
            "standalone-secret",
        ).forEach { secret -> assertFalse(result.contains(secret), "Leaked $secret") }
        assertContains(result, "[REDACTED]")
        assertContains(result, "[REDACTED_EMAIL]")
    }

    @Test
    fun sanitizingAlreadySafeTextIsIdempotent() {
        val once = sanitizer.sanitize("cookie=secret user_api_key%3Dencoded")

        assertEquals(once, sanitizer.sanitize(once))
    }

    @Test
    fun leavesOrdinaryForumDiagnosticsReadable() {
        val input = "GET /latest.json?page=0 -> 200 (42 ms)"

        assertEquals(input, sanitizer.sanitize(input))
    }
}
