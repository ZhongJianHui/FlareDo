package dev.dimension.flare.data.network.discourse.auth

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher

private const val MINIMUM_RSA_BITS = 2_048
private const val MAXIMUM_RSA_BITS = 4_096
private const val MAXIMUM_PRIVATE_KEY_BYTES = 8 * 1024
private const val MAXIMUM_CIPHERTEXT_BYTES = 512
private const val PEM_LINE_LENGTH = 64
private const val RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding"

/** JVM Java Security implementation for one-time Discourse User API Key authorization. */
public class JvmDiscourseRsaPkcs1Crypto public constructor(
    private val cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : DiscourseRsaPkcs1KeyPairGenerator,
    DiscourseRsaPkcs1Decryptor {
    /** Generates at least a 2048-bit RSA key and exports canonical SPKI/PKCS#8 containers. */
    override suspend fun generate(minimumKeySizeBits: Int): DiscourseRsaPkcs1KeyPair =
        withContext(cryptoDispatcher) {
            require(minimumKeySizeBits in 1..MAXIMUM_RSA_BITS) {
                "Requested RSA key size is outside the supported bound"
            }
            try {
                val keySize = maxOf(MINIMUM_RSA_BITS, minimumKeySizeBits)
                val generator = KeyPairGenerator.getInstance("RSA")
                generator.initialize(RSAKeyGenParameterSpec(keySize, RSAKeyGenParameterSpec.F4))
                val generated = generator.generateKeyPair()
                val privateBytes = generated.private.encoded
                try {
                    DiscourseRsaPkcs1KeyPair(
                        publicKeySpkiPem = generated.public.encoded.toSpkiPem(),
                        privateKeyPkcs8 = privateBytes,
                    )
                } finally {
                    privateBytes.fill(0)
                }
            } catch (error: GeneralSecurityException) {
                throw JvmDiscourseRsaCryptoException("JVM RSA key generation failed", error)
            }
        }

    /** Decrypts one RSA/ECB/PKCS1Padding callback field using an in-memory PKCS#8 key. */
    override suspend fun decrypt(
        privateKeyPkcs8: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        require(privateKeyPkcs8.size in 256..MAXIMUM_PRIVATE_KEY_BYTES) {
            "PKCS#8 private key size is outside the supported bound"
        }
        require(ciphertext.size in 1..MAXIMUM_CIPHERTEXT_BYTES) {
            "RSA ciphertext size is outside the supported bound"
        }
        val ownedPrivateKey = privateKeyPkcs8.copyOf()
        val ownedCiphertext = ciphertext.copyOf()
        try {
            return withContext(cryptoDispatcher) {
                try {
                    val privateKey =
                        KeyFactory
                            .getInstance("RSA")
                            .generatePrivate(PKCS8EncodedKeySpec(ownedPrivateKey)) as? RSAPrivateKey
                            ?: throw GeneralSecurityException("Private key is not RSA")
                    require(privateKey.modulus.bitLength() >= MINIMUM_RSA_BITS) {
                        "RSA private key is smaller than 2048 bits"
                    }
                    val modulusBytes = (privateKey.modulus.bitLength() + 7) / 8
                    require(ownedCiphertext.size == modulusBytes) {
                        "RSA ciphertext does not match the private key size"
                    }
                    Cipher
                        .getInstance(RSA_TRANSFORMATION)
                        .apply { init(Cipher.DECRYPT_MODE, privateKey) }
                        .doFinal(ownedCiphertext)
                } catch (error: GeneralSecurityException) {
                    throw JvmDiscourseRsaCryptoException("JVM RSA decryption failed", error)
                }
            }
        } finally {
            ownedPrivateKey.fill(0)
            ownedCiphertext.fill(0)
        }
    }
}

/** Failure from the installed JVM cryptographic provider, without sensitive values in messages. */
public class JvmDiscourseRsaCryptoException public constructor(
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)

private fun ByteArray.toSpkiPem(): String {
    val lineSeparator = "\n".toByteArray(Charsets.US_ASCII)
    val body = Base64.getMimeEncoder(PEM_LINE_LENGTH, lineSeparator).encodeToString(this)
    return "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----"
}
