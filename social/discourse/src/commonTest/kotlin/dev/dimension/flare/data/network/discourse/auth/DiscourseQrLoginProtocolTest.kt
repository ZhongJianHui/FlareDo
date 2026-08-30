package dev.dimension.flare.data.network.discourse.auth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class DiscourseQrLoginProtocolTest {
    @Test
    fun roundTripUsesFlareDoSchemeAndPreservesBoundedCapability() {
        val apiKey = "api-key-value".encodeToByteArray()
        val otp = "0123456789abcdef".encodeToByteArray()
        val source = DiscourseQrLoginPayload(apiKey, otp, "member", 610_000L)

        val encoded = DiscourseQrLoginProtocol.encode(source)
        val decoded = checkNotNull(DiscourseQrLoginProtocol.parse(encoded))
        try {
            assertTrue(encoded.startsWith("flaredo://qr-login?"))
            assertFalse(encoded.contains("api-key-value"))
            assertEquals("member", decoded.username)
            assertEquals(610_000L, decoded.expiresAtEpochMillis)
            assertContentEquals(apiKey, decoded.copyApiKey())
            assertContentEquals(otp, decoded.copyOtp())
            assertFalse(decoded.isExpired(609_999L))
            assertTrue(decoded.isExpired(610_000L))
            assertFalse(decoded.toString().contains("member"))
            assertFalse(decoded.toString().contains("api-key"))
        } finally {
            source.close()
            decoded.close()
        }
    }

    @Test
    fun parserRejectsForeignDuplicateAndExpandedRoutes() {
        val payload =
            DiscourseQrLoginPayload(
                apiKey = "key".encodeToByteArray(),
                otp = "abcdef".encodeToByteArray(),
                username = "",
                expiresAtEpochMillis = 10L,
            )
        val valid = DiscourseQrLoginProtocol.encode(payload)
        payload.close()

        assertNull(DiscourseQrLoginProtocol.parse(valid.replace("flaredo://", "fluxdo://")))
        assertNull(DiscourseQrLoginProtocol.parse(valid.replace("qr-login?", "qr-login/path?")))
        assertNull(DiscourseQrLoginProtocol.parse("$valid&unexpected=1"))
        assertNull(DiscourseQrLoginProtocol.parse("$valid&version=1"))
        assertNull(DiscourseQrLoginProtocol.parse(valid.replace("version=1", "version=2")))
        assertNull(DiscourseQrLoginProtocol.parse("javascript:alert(1)"))
    }

    @Test
    fun closingPayloadMakesBothSecretsUnavailable() {
        val payload =
            DiscourseQrLoginPayload(
                apiKey = "key".encodeToByteArray(),
                otp = "abcdef".encodeToByteArray(),
                username = "member",
                expiresAtEpochMillis = 10L,
            )
        payload.close()

        assertFailsWith<IllegalStateException> { payload.copyApiKey() }
        assertFailsWith<IllegalStateException> { payload.copyOtp() }
    }
}
