package dev.dimension.flare.data.network.discourse.auth

import io.ktor.http.URLProtocol
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

internal class DiscourseUserApiAuthorizationTest {
    @Test
    fun authorizationUrlKeepsEverySecurityParameterFixed() {
        val publicKey = testPublicKeyPem()

        val url =
            DiscourseUserApiAuthorization.buildUrl(
                publicKeyPem = publicKey,
                clientId = "client-42",
                nonce = "nonce-42",
            )

        assertEquals(URLProtocol.HTTPS, url.protocol)
        assertEquals("linux.do", url.host)
        assertEquals("/user-api-key/new", url.encodedPath)
        assertEquals("FlareDo", url.parameters["application_name"])
        assertEquals("client-42", url.parameters["client_id"])
        assertEquals("one_time_password", url.parameters["scopes"])
        assertEquals(publicKey, url.parameters["public_key"])
        assertEquals("nonce-42", url.parameters["nonce"])
        assertEquals("discourse://auth_redirect", url.parameters["auth_redirect"])
        assertEquals(
            setOf("application_name", "client_id", "scopes", "public_key", "nonce", "auth_redirect"),
            url.parameters.names(),
        )
    }

    @Test
    fun rejectsNonSpkiPemAndUnsafeTokens() {
        assertFailsWith<IllegalArgumentException> {
            DiscourseUserApiAuthorization.buildUrl(
                publicKeyPem = "-----BEGIN RSA PUBLIC KEY-----\nAA==\n-----END RSA PUBLIC KEY-----",
                clientId = "client-42",
                nonce = "nonce-42",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DiscourseUserApiAuthorization.buildUrl(
                publicKeyPem = testPublicKeyPem(),
                clientId = "client id",
                nonce = "nonce-42",
            )
        }
    }

    @Test
    fun keyPairCopiesAndClosesPrivateMaterial() {
        val original = ByteArray(512) { index -> (index and 0xff).toByte() }
        val keyPair = DiscourseRsaPkcs1KeyPair(testPublicKeyPem(), original)
        val firstCopy = keyPair.copyPrivateKeyPkcs8()
        firstCopy[0] = 99

        assertContentEquals(original, keyPair.copyPrivateKeyPkcs8())

        keyPair.close()
        keyPair.close()
        assertFailsWith<IllegalStateException> { keyPair.copyPrivateKeyPkcs8() }
        assertFalse(original.all { it == 0.toByte() })
    }

    @Test
    fun discourseGeneratorAlwaysRequestsAtLeast2048Bits() =
        runTest {
            var requestedBits = 0
            val generator =
                DiscourseRsaPkcs1KeyPairGenerator { minimumKeySizeBits ->
                    requestedBits = minimumKeySizeBits
                    DiscourseRsaPkcs1KeyPair(testPublicKeyPem(), ByteArray(512) { 7 })
                }

            generator.generateForDiscourse().close()

            assertEquals(2_048, requestedBits)
        }
}

@OptIn(ExperimentalEncodingApi::class)
internal fun testPublicKeyPem(): String {
    val der = ByteArray(294) { index -> ((index * 17 + 11) and 0xff).toByte() }
    val body =
        Base64.Default
            .encode(der)
            .chunked(64)
            .joinToString("\n")
    return "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----"
}
