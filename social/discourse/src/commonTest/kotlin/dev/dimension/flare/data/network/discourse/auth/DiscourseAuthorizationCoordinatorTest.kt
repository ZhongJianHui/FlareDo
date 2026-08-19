package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.network.discourse.session.SessionOnlySecureCredentialStore
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class DiscourseAuthorizationCoordinatorTest {
    @Test
    fun beginPersistsOnlyPrivateKeyReferenceAndBuildsFixedAuthorizationUrl() =
        runTest {
            val vault = SessionOnlySecureCredentialStore()
            try {
                val attempts = MemoryDiscourseAuthAttemptStore()
                val coordinator = coordinator(vault, attempts)

                val pending = coordinator.begin()
                val attempt = assertNotNull(attempts.peek())

                assertEquals(610_000L, pending.expiresAtEpochMillis)
                assertEquals("https", pending.url.protocol.name)
                assertEquals("linux.do", pending.url.host)
                assertEquals("/user-api-key/new", pending.url.encodedPath)
                assertEquals("FlareDo", pending.url.parameters["application_name"])
                assertEquals("one_time_password", pending.url.parameters["scopes"])
                assertEquals("discourse://auth_redirect", pending.url.parameters["auth_redirect"])
                assertEquals("token-3", pending.url.parameters["client_id"])
                assertEquals("token-2", pending.url.parameters["nonce"])
                assertFalse(pending.url.toString().contains(attempt.privateKeyRef.value))
                assertEquals(256, assertNotNull(vault.load(attempt.privateKeyRef)).size)
            } finally {
                vault.close()
            }
        }

    @Test
    fun replacingOrCancellingAnAttemptDeletesItsOneUsePrivateKey() =
        runTest {
            val vault = SessionOnlySecureCredentialStore()
            try {
                val attempts = MemoryDiscourseAuthAttemptStore()
                val tokens = CountingTokenGenerator()
                val coordinator = coordinator(vault, attempts, tokens)

                coordinator.begin()
                val first = assertNotNull(attempts.peek())
                coordinator.begin()
                val second = assertNotNull(attempts.peek())

                assertNull(vault.load(first.privateKeyRef))
                assertNotNull(vault.load(second.privateKeyRef))
                assertTrue(coordinator.cancelPending())
                assertNull(vault.load(second.privateKeyRef))
                assertNull(attempts.peek())
                assertFalse(coordinator.cancelPending())
            } finally {
                vault.close()
            }
        }

    private fun coordinator(
        vault: SessionOnlySecureCredentialStore,
        attempts: DiscourseAuthAttemptStore,
        tokens: DiscourseAuthTokenGenerator = CountingTokenGenerator(),
    ): DiscourseAuthorizationCoordinator =
        DiscourseAuthorizationCoordinator(
            keyPairGenerator =
                DiscourseRsaPkcs1KeyPairGenerator {
                    DiscourseRsaPkcs1KeyPair(
                        publicKeySpkiPem = fakePublicKeyPem(),
                        privateKeyPkcs8 = ByteArray(256) { 0x5a },
                    )
                },
            tokenGenerator = tokens,
            credentialStore = vault,
            attemptStore = attempts,
            nowEpochMillis = { 10_000L },
        )

    private fun fakePublicKeyPem(): String =
        buildString {
            append("-----BEGIN PUBLIC KEY-----\n")
            append(Base64.Default.encode(ByteArray(256) { 0x2a }))
            append("\n-----END PUBLIC KEY-----")
        }

    private class CountingTokenGenerator : DiscourseAuthTokenGenerator {
        private var next = 1

        override suspend fun generate(byteCount: Int): String {
            assertEquals(32, byteCount)
            return "token-${next++}"
        }
    }
}
