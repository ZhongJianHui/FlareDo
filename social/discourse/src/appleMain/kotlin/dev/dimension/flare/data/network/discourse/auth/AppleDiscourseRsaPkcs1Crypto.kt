@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.io.encoding.ExperimentalEncodingApi::class,
)

package dev.dimension.flare.data.network.discourse.auth

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFNumberSInt32Type
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateDecryptedData
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyRef
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeRSA
import platform.Security.kSecKeyAlgorithmRSAEncryptionPKCS1
import kotlin.io.encoding.Base64

private const val MINIMUM_RSA_BITS = 2_048
private const val MAXIMUM_RSA_BITS = 4_096
private const val MAXIMUM_PRIVATE_KEY_BYTES = 8 * 1024
private const val MAXIMUM_CIPHERTEXT_BYTES = 512
private const val PEM_LINE_LENGTH = 64

private val rsaAlgorithmIdentifier =
    byteArrayOf(
        0x30,
        0x0d,
        0x06,
        0x09,
        0x2a,
        0x86.toByte(),
        0x48,
        0x86.toByte(),
        0xf7.toByte(),
        0x0d,
        0x01,
        0x01,
        0x01,
        0x05,
        0x00,
    )

/** Security.framework RSA implementation shared by the iOS and macOS SwiftUI hosts. */
public class AppleDiscourseRsaPkcs1Crypto public constructor(
    private val cryptoDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DiscourseRsaPkcs1KeyPairGenerator,
    DiscourseRsaPkcs1Decryptor {
    /**
     * Generates an in-memory RSA key with Security.framework.
     *
     * Apple exports RSA keys as PKCS#1. Discourse requires an SPKI public PEM and the shared vault
     * contract requires PKCS#8, so this method wraps the provider bytes in strict DER containers;
     * it does not reinterpret or copy fields out of the RSA key itself.
     */
    override suspend fun generate(minimumKeySizeBits: Int): DiscourseRsaPkcs1KeyPair =
        withContext(cryptoDispatcher) {
            require(minimumKeySizeBits in 1..MAXIMUM_RSA_BITS) {
                "Requested RSA key size is outside the supported bound"
            }
            val keySize = maxOf(MINIMUM_RSA_BITS, minimumKeySizeBits)
            val raw = generateRawRsaKeyPair(keySize)
            try {
                val privatePkcs8 = wrapPkcs1PrivateKeyAsPkcs8(raw.privatePkcs1)
                val publicSpki = wrapPkcs1PublicKeyAsSpki(raw.publicPkcs1)
                try {
                    DiscourseRsaPkcs1KeyPair(
                        publicKeySpkiPem = publicSpki.toSpkiPem(),
                        privateKeyPkcs8 = privatePkcs8,
                    )
                } finally {
                    privatePkcs8.fill(0)
                    publicSpki.fill(0)
                }
            } finally {
                raw.privatePkcs1.fill(0)
                raw.publicPkcs1.fill(0)
            }
        }

    /** Imports PKCS#8 into a transient SecKey and applies RSAEncryptionPKCS1 exactly once. */
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
                val privatePkcs1 = unwrapPkcs8PrivateKey(ownedPrivateKey)
                try {
                    val modulusBits = rsaPrivateModulusBits(privatePkcs1)
                    require(modulusBits in MINIMUM_RSA_BITS..MAXIMUM_RSA_BITS) {
                        "RSA private key size is outside the supported bound"
                    }
                    require(ownedCiphertext.size == (modulusBits + 7) / 8) {
                        "RSA ciphertext does not match the private key size"
                    }
                    decryptWithSecKey(privatePkcs1, ownedCiphertext)
                } finally {
                    privatePkcs1.fill(0)
                }
            }
        } finally {
            ownedPrivateKey.fill(0)
            ownedCiphertext.fill(0)
        }
    }
}

/** Security.framework failure without private key or ciphertext content in its message. */
public class AppleDiscourseRsaCryptoException public constructor(
    message: String,
) : IllegalStateException(message)

private data class RawAppleRsaKeyPair(
    val privatePkcs1: ByteArray,
    val publicPkcs1: ByteArray,
)

private fun generateRawRsaKeyPair(keySizeBits: Int): RawAppleRsaKeyPair =
    memScoped {
        val attributes =
            checkNotNull(
                CFDictionaryCreateMutable(
                    kCFAllocatorDefault,
                    2,
                    kCFTypeDictionaryKeyCallBacks.ptr,
                    kCFTypeDictionaryValueCallBacks.ptr,
                ),
            )
        val keySizeValue = alloc<IntVar>().apply { value = keySizeBits }
        val keySizeNumber =
            CFNumberCreate(
                kCFAllocatorDefault,
                kCFNumberSInt32Type,
                keySizeValue.ptr,
            ) ?: run {
                CFRelease(attributes)
                throw AppleDiscourseRsaCryptoException("Apple RSA key attributes could not be created")
            }
        CFDictionaryAddValue(attributes, kSecAttrKeyType, kSecAttrKeyTypeRSA)
        CFDictionaryAddValue(attributes, kSecAttrKeySizeInBits, keySizeNumber)
        CFRelease(keySizeNumber)

        val error = alloc<CFErrorRefVar>().apply { value = null }
        val privateKey = SecKeyCreateRandomKey(attributes, error.ptr)
        CFRelease(attributes)
        error.value?.let(::CFRelease)
        if (privateKey == null) {
            throw AppleDiscourseRsaCryptoException("Apple RSA key generation failed")
        }
        val publicKey = SecKeyCopyPublicKey(privateKey)
        if (publicKey == null) {
            CFRelease(privateKey)
            throw AppleDiscourseRsaCryptoException("Apple RSA public key extraction failed")
        }
        try {
            RawAppleRsaKeyPair(
                privatePkcs1 = exportSecKey(privateKey),
                publicPkcs1 = exportSecKey(publicKey),
            )
        } finally {
            CFRelease(publicKey)
            CFRelease(privateKey)
        }
    }

private fun exportSecKey(key: SecKeyRef): ByteArray =
    memScoped {
        val error = alloc<CFErrorRefVar>().apply { value = null }
        val data = SecKeyCopyExternalRepresentation(key, error.ptr)
        error.value?.let(::CFRelease)
        if (data == null) {
            throw AppleDiscourseRsaCryptoException("Apple RSA key export failed")
        }
        try {
            data.toByteArray()
        } finally {
            CFRelease(data)
        }
    }

private fun decryptWithSecKey(
    privatePkcs1: ByteArray,
    ciphertext: ByteArray,
): ByteArray =
    memScoped {
        val keyData = privatePkcs1.toCFData()
        val attributes =
            checkNotNull(
                CFDictionaryCreateMutable(
                    kCFAllocatorDefault,
                    2,
                    kCFTypeDictionaryKeyCallBacks.ptr,
                    kCFTypeDictionaryValueCallBacks.ptr,
                ),
            )
        CFDictionaryAddValue(attributes, kSecAttrKeyType, kSecAttrKeyTypeRSA)
        CFDictionaryAddValue(attributes, kSecAttrKeyClass, kSecAttrKeyClassPrivate)
        val importError = alloc<CFErrorRefVar>().apply { value = null }
        val privateKey = SecKeyCreateWithData(keyData, attributes, importError.ptr)
        CFRelease(attributes)
        CFRelease(keyData)
        importError.value?.let(::CFRelease)
        if (privateKey == null) {
            throw AppleDiscourseRsaCryptoException("Apple RSA private key import failed")
        }

        val encryptedData = ciphertext.toCFData()
        val decryptError = alloc<CFErrorRefVar>().apply { value = null }
        val decrypted =
            SecKeyCreateDecryptedData(
                privateKey,
                kSecKeyAlgorithmRSAEncryptionPKCS1,
                encryptedData,
                decryptError.ptr,
            )
        CFRelease(encryptedData)
        CFRelease(privateKey)
        decryptError.value?.let(::CFRelease)
        if (decrypted == null) {
            throw AppleDiscourseRsaCryptoException("Apple RSA decryption failed")
        }
        try {
            decrypted.toByteArray()
        } finally {
            CFRelease(decrypted)
        }
    }

private fun wrapPkcs1PublicKeyAsSpki(pkcs1: ByteArray): ByteArray {
    val bitString = der(0x03, byteArrayOf(0) + pkcs1)
    return der(0x30, rsaAlgorithmIdentifier + bitString)
}

private fun wrapPkcs1PrivateKeyAsPkcs8(pkcs1: ByteArray): ByteArray =
    der(
        0x30,
        byteArrayOf(0x02, 0x01, 0x00) + rsaAlgorithmIdentifier + der(0x04, pkcs1),
    )

private fun unwrapPkcs8PrivateKey(pkcs8: ByteArray): ByteArray {
    val outer = DerReader(pkcs8).readConstructed(0x30)
    outer.requireIntegerZero()
    val algorithm = outer.readEncodedElement(0x30)
    require(algorithm.contentEquals(rsaAlgorithmIdentifier)) { "PKCS#8 key is not RSA" }
    algorithm.fill(0)
    val privateKey = outer.readElement(0x04)
    outer.requireExhausted()
    return privateKey
}

private fun rsaPrivateModulusBits(pkcs1: ByteArray): Int {
    val sequence = DerReader(pkcs1).readConstructed(0x30)
    sequence.requireIntegerZero()
    val modulus = sequence.readElement(0x02)
    try {
        var first = 0
        while (first < modulus.size && modulus[first] == 0.toByte()) first += 1
        require(first < modulus.size) { "RSA modulus is empty" }
        val mostSignificant = modulus[first].toInt() and 0xff
        return (modulus.size - first - 1) * 8 + (Int.SIZE_BITS - mostSignificant.countLeadingZeroBits())
    } finally {
        modulus.fill(0)
    }
}

private class DerReader(
    private val bytes: ByteArray,
    private val start: Int = 0,
    private val limit: Int = bytes.size,
) {
    private var position: Int = start

    fun readConstructed(expectedTag: Int): DerReader {
        require(readUnsignedByte() == expectedTag) { "Unexpected DER tag" }
        val length = readLength()
        require(length <= remaining()) { "Truncated DER value" }
        val nested = DerReader(bytes, position, position + length)
        position += length
        if (start == 0 && limit == bytes.size) requireExhausted()
        return nested
    }

    fun readElement(expectedTag: Int): ByteArray {
        require(readUnsignedByte() == expectedTag) { "Unexpected DER tag" }
        val length = readLength()
        require(length <= remaining()) { "Truncated DER value" }
        val value = bytes.copyOfRange(position, position + length)
        position += length
        return value
    }

    fun readEncodedElement(expectedTag: Int): ByteArray {
        val elementStart = position
        require(readUnsignedByte() == expectedTag) { "Unexpected DER tag" }
        val length = readLength()
        require(length <= remaining()) { "Truncated DER value" }
        position += length
        return bytes.copyOfRange(elementStart, position)
    }

    fun requireIntegerZero() {
        val value = readElement(0x02)
        try {
            require(value.contentEquals(byteArrayOf(0))) { "Unexpected DER version" }
        } finally {
            value.fill(0)
        }
    }

    fun requireExhausted() {
        require(position == limit) { "Trailing DER data is not allowed" }
    }

    private fun readLength(): Int {
        val first = readUnsignedByte()
        if (first < 0x80) return first
        val count = first and 0x7f
        require(count in 1..4 && count <= remaining()) { "Invalid DER length" }
        require((bytes[position].toInt() and 0xff) != 0) { "Non-canonical DER length" }
        var value = 0
        repeat(count) { value = (value shl 8) or readUnsignedByte() }
        require(value >= 0x80) { "Non-canonical DER length" }
        return value
    }

    private fun readUnsignedByte(): Int {
        require(position < limit) { "Truncated DER value" }
        return bytes[position++].toInt() and 0xff
    }

    private fun remaining(): Int = limit - position
}

private fun der(
    tag: Int,
    value: ByteArray,
): ByteArray = byteArrayOf(tag.toByte()) + derLength(value.size) + value

private fun derLength(length: Int): ByteArray {
    require(length >= 0) { "DER length must not be negative" }
    if (length < 0x80) return byteArrayOf(length.toByte())
    val byteCount = (Int.SIZE_BITS - length.countLeadingZeroBits() + 7) / 8
    return ByteArray(byteCount + 1).also { encoded ->
        encoded[0] = (0x80 or byteCount).toByte()
        repeat(byteCount) { index ->
            encoded[index + 1] = (length ushr (8 * (byteCount - index - 1))).toByte()
        }
    }
}

private fun ByteArray.toCFData(): CFDataRef =
    usePinned { pinned ->
        checkNotNull(
            CFDataCreate(
                kCFAllocatorDefault,
                pinned.addressOf(0).reinterpret(),
                size.convert(),
            ),
        )
    }

private fun CFDataRef.toByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    require(length > 0) { "Apple RSA operation returned empty data" }
    val source = checkNotNull(CFDataGetBytePtr(this))
    return ByteArray(length).also { result ->
        for (index in result.indices) result[index] = source[index].toByte()
    }
}

private fun ByteArray.toSpkiPem(): String {
    val body =
        Base64.Default
            .encode(this)
            .chunked(PEM_LINE_LENGTH)
            .joinToString("\n")
    return "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----"
}
