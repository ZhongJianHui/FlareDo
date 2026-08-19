package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.network.discourse.session.SecureCredentialRef
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class DiscourseAuthRedirectParserTest {
    @Test
    fun parsesOnlyTheTwoCanonicalEncryptedFields() {
        val parsed = assertNotNull(DiscourseAuthRedirectParser.parse(validRedirect()))

        assertEquals(PAYLOAD_CIPHER, parsed.payload)
        assertEquals(OTP_CIPHER, parsed.oneTimePassword)
    }

    @Test
    fun rejectsWrongOriginPathPortUserinfoAndFragment() {
        val invalidUris =
            listOf(
                validRedirect().replace("discourse://", "https://"),
                validRedirect().replace("auth_redirect", "outside"),
                validRedirect().replace("auth_redirect?", "auth_redirect/path?"),
                validRedirect().replace("auth_redirect?", "auth_redirect:443?"),
                validRedirect().replace("auth_redirect?", "user@auth_redirect?"),
                "${validRedirect()}#fragment",
                validRedirect().replace("discourse://", "DISCOURSE://"),
            )

        invalidUris.forEach { uri -> assertNull(DiscourseAuthRedirectParser.parse(uri), uri) }
    }

    @Test
    fun rejectsUnknownDuplicateMissingEmptyAndNonCanonicalQueryValues() {
        val invalidUris =
            listOf(
                "${validRedirect()}&extra=AA==",
                "${validRedirect()}&payload=$PAYLOAD_CIPHER",
                validRedirect().substringBefore("&oneTimePassword"),
                validRedirect().replace("payload=$PAYLOAD_CIPHER", "payload="),
                validRedirect().replace(PAYLOAD_CIPHER, "not_base64"),
                validRedirect().replace(PAYLOAD_CIPHER, "YQ"),
                validRedirect().replace(PAYLOAD_CIPHER, "YQ==%0A"),
            )

        invalidUris.forEach { uri -> assertNull(DiscourseAuthRedirectParser.parse(uri), uri) }
    }

    @Test
    fun attemptLifetimeIsPositiveAndAtMostTenMinutes() {
        val valid = attempt(createdAt = 1_000L, expiresAt = 601_000L)
        assertEquals(600_000L, valid.expiresAtEpochMillis - valid.createdAtEpochMillis)

        assertFailsWith<IllegalArgumentException> {
            attempt(createdAt = 1_000L, expiresAt = 1_000L)
        }
        assertFailsWith<IllegalArgumentException> {
            attempt(createdAt = 1_000L, expiresAt = 601_001L)
        }
    }

    @Test
    fun memoryStoreCompareAndConsumesByOpaqueId() =
        runTest {
            val store = MemoryDiscourseAuthAttemptStore()
            val first = attempt(id = "attempt-one")
            val second = attempt(id = "attempt-two")

            assertNull(store.replace(first))
            assertEquals(first, store.replace(second))
            assertNull(store.consume(first.id))
            assertEquals(second, store.peek())
            assertEquals(second, store.consume(second.id))
            assertNull(store.peek())
        }
}

internal fun attempt(
    id: String = "attempt-42",
    nonce: String = "nonce-42",
    createdAt: Long = 1_000L,
    expiresAt: Long = 601_000L,
    privateKeyRef: SecureCredentialRef = SecureCredentialRef("session:42"),
): DiscourseAuthAttempt =
    DiscourseAuthAttempt(
        id = id,
        privateKeyRef = privateKeyRef,
        nonce = nonce,
        clientId = "client-42",
        createdAtEpochMillis = createdAt,
        expiresAtEpochMillis = expiresAt,
    )

internal const val PAYLOAD_CIPHER: String = "cGF5bG9hZA=="
internal const val OTP_CIPHER: String = "b3Rw"

internal fun validRedirect(
    payload: String = PAYLOAD_CIPHER,
    otp: String = OTP_CIPHER,
): String = "discourse://auth_redirect?payload=$payload&oneTimePassword=$otp"
