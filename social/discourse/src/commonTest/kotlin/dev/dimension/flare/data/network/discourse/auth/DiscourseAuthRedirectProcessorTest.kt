package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.network.discourse.session.SessionOnlySecureCredentialStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalEncodingApi::class)
internal class DiscourseAuthRedirectProcessorTest {
    @Test
    fun acceptsOnceConsumesAttemptAndDeletesPrivateKey() =
        runTest {
            val fixture = processorFixture()
            val redirect = fixture.redirect(nonce = fixture.attempt.nonce)

            val accepted = assertIs<DiscourseAuthRedirectResult.Accepted>(fixture.processor.process(redirect))
            val apiKey = accepted.secrets.copyApiKey()
            val otp = accepted.secrets.copyOneTimePassword()
            try {
                assertContentEquals(API_KEY.encodeToByteArray(), apiKey)
                assertContentEquals(OTP.encodeToByteArray(), otp)
                assertEquals(fixture.attempt.clientId, accepted.clientId)
                assertEquals(4, accepted.apiVersion)
            } finally {
                apiKey.fill(0)
                otp.fill(0)
                accepted.secrets.close()
            }

            assertNull(fixture.attemptStore.peek())
            assertNull(fixture.credentialStore.load(fixture.attempt.privateKeyRef))
            assertIs<DiscourseAuthRedirectResult.Stale>(fixture.processor.process(redirect))
        }

    @Test
    fun malformedUriAndNonceMismatchDoNotCancelLegitimateAttempt() =
        runTest {
            val fixture = processorFixture()

            assertIs<DiscourseAuthRedirectResult.Malformed>(
                fixture.processor.process("discourse://auth_redirect?payload=AA=="),
            )
            assertIs<DiscourseAuthRedirectResult.Stale>(
                fixture.processor.process(fixture.redirect(nonce = "nonce-for-another-attempt")),
            )

            assertEquals(fixture.attempt, fixture.attemptStore.peek())
            assertContentEquals(PRIVATE_KEY, fixture.credentialStore.load(fixture.attempt.privateKeyRef))
        }

    @Test
    fun expiredAttemptIsConsumedAndPrivateKeyIsDeleted() =
        runTest {
            val fixture = processorFixture(nowEpochMillis = 601_000L)

            assertIs<DiscourseAuthRedirectResult.Expired>(
                fixture.processor.process(fixture.redirect(nonce = fixture.attempt.nonce)),
            )
            assertNull(fixture.attemptStore.peek())
            assertNull(fixture.credentialStore.load(fixture.attempt.privateKeyRef))
        }

    @Test
    fun clockRollbackBeforeCreationAlsoConsumesFailClosed() =
        runTest {
            val fixture = processorFixture(nowEpochMillis = 999L)

            assertIs<DiscourseAuthRedirectResult.Expired>(
                fixture.processor.process(fixture.redirect(nonce = fixture.attempt.nonce)),
            )
            assertNull(fixture.attemptStore.peek())
            assertNull(fixture.credentialStore.load(fixture.attempt.privateKeyRef))
        }

    @Test
    fun authenticatedNonceMakesLaterPayloadFailureSingleUse() =
        runTest {
            val fixture = processorFixture()
            val redirect =
                fixture.redirect(
                    nonce = fixture.attempt.nonce,
                    apiKey = "bad key with spaces",
                )

            val malformed = assertIs<DiscourseAuthRedirectResult.Malformed>(fixture.processor.process(redirect))
            assertEquals(DiscourseAuthMalformedReason.ApiKey, malformed.reason)
            assertNull(fixture.attemptStore.peek())
            assertNull(fixture.credentialStore.load(fixture.attempt.privateKeyRef))
            assertIs<DiscourseAuthRedirectResult.Stale>(fixture.processor.process(redirect))
        }

    @Test
    fun concurrentReplayCanAcceptOnlyOnce() =
        runTest {
            val fixture = processorFixture()
            val redirect = fixture.redirect(nonce = fixture.attempt.nonce)

            val outcomes =
                List(8) {
                    async { fixture.processor.process(redirect) }
                }.awaitAll()

            assertEquals(1, outcomes.count { it is DiscourseAuthRedirectResult.Accepted })
            assertEquals(7, outcomes.count { it is DiscourseAuthRedirectResult.Stale })
            outcomes.filterIsInstance<DiscourseAuthRedirectResult.Accepted>().forEach { it.secrets.close() }
        }

    @Test
    fun rejectsOtpOutsideTheBoundedLowercaseHexAlphabetAfterConsumption() =
        runTest {
            val fixture = processorFixture()
            val malformed =
                assertIs<DiscourseAuthRedirectResult.Malformed>(
                    fixture.processor.process(
                        fixture.redirect(nonce = fixture.attempt.nonce, otp = "ABCDEF"),
                    ),
                )

            assertEquals(DiscourseAuthMalformedReason.Otp, malformed.reason)
            assertNull(fixture.attemptStore.peek())
        }
}

@OptIn(ExperimentalEncodingApi::class)
private suspend fun processorFixture(nowEpochMillis: Long = 2_000L): ProcessorFixture {
    val credentialStore = SessionOnlySecureCredentialStore()
    val reference = credentialStore.save("auth-attempt", PRIVATE_KEY)
    val attempt = attempt(privateKeyRef = reference)
    val attemptStore = MemoryDiscourseAuthAttemptStore().apply { replace(attempt) }
    val decryptor =
        DiscourseRsaPkcs1Decryptor { privateKey, ciphertext ->
            assertContentEquals(PRIVATE_KEY, privateKey)
            ciphertext.copyOf()
        }
    return ProcessorFixture(
        attempt = attempt,
        attemptStore = attemptStore,
        credentialStore = credentialStore,
        processor =
            DiscourseAuthRedirectProcessor(
                attemptStore = attemptStore,
                credentialStore = credentialStore,
                decryptor = decryptor,
                nowEpochMillis = { nowEpochMillis },
            ),
    )
}

private data class ProcessorFixture(
    val attempt: DiscourseAuthAttempt,
    val attemptStore: MemoryDiscourseAuthAttemptStore,
    val credentialStore: SessionOnlySecureCredentialStore,
    val processor: DiscourseAuthRedirectProcessor,
) {
    @OptIn(ExperimentalEncodingApi::class)
    fun redirect(
        nonce: String,
        apiKey: String = API_KEY,
        otp: String = OTP,
    ): String {
        val payload = "{\"key\":\"$apiKey\",\"nonce\":\"$nonce\",\"api\":4}"
        return validRedirect(
            payload = Base64.Default.encode(payload.encodeToByteArray()),
            otp = Base64.Default.encode(otp.encodeToByteArray()),
        )
    }
}

private val PRIVATE_KEY: ByteArray = ByteArray(512) { index -> ((index * 7 + 3) and 0xff).toByte() }
private const val API_KEY: String = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
private const val OTP: String = "0123456789abcdef"
