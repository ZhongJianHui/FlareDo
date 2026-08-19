package dev.dimension.flare.data.network.discourse.auth

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class JvmDiscourseRsaPkcs1CryptoTest {
    @Test
    fun generatedSpkiAndPkcs8RoundTripPkcs1Ciphertext() =
        runTest {
            val crypto = JvmDiscourseRsaPkcs1Crypto(StandardTestDispatcher(testScheduler))
            val keyPair = crypto.generate(minimumKeySizeBits = 1_024)
            val privateKey = keyPair.copyPrivateKeyPkcs8()
            try {
                val publicKey = decodePublicKey(keyPair.publicKeySpkiPem)
                assertTrue(publicKey.modulus.bitLength() >= 2_048)
                val expected = "discourse-rsa-round-trip-vector".encodeToByteArray()
                val ciphertext =
                    Cipher
                        .getInstance("RSA/ECB/PKCS1Padding")
                        .apply { init(Cipher.ENCRYPT_MODE, publicKey) }
                        .doFinal(expected)
                try {
                    assertContentEquals(expected, crypto.decrypt(privateKey, ciphertext))
                } finally {
                    ciphertext.fill(0)
                }
            } finally {
                privateKey.fill(0)
                keyPair.close()
            }
        }

    @Test
    fun malformedCiphertextFailsWithoutIncludingSensitiveValues() =
        runTest {
            val crypto = JvmDiscourseRsaPkcs1Crypto(StandardTestDispatcher(testScheduler))
            val keyPair = crypto.generate(minimumKeySizeBits = 2_048)
            val privateKey = keyPair.copyPrivateKeyPkcs8()
            try {
                val error =
                    assertFailsWith<IllegalArgumentException> {
                        crypto.decrypt(privateKey, byteArrayOf(1, 2, 3))
                    }
                assertTrue("1, 2, 3" !in error.message.orEmpty())
            } finally {
                privateKey.fill(0)
                keyPair.close()
            }
        }

    private fun decodePublicKey(pem: String): RSAPublicKey {
        val body =
            pem
                .removePrefix("-----BEGIN PUBLIC KEY-----")
                .removeSuffix("-----END PUBLIC KEY-----")
                .filterNot(Char::isWhitespace)
        val der = Base64.getDecoder().decode(body)
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey
    }
}
