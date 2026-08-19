@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.io.encoding.ExperimentalEncodingApi::class,
)

package dev.dimension.flare.data.network.discourse.auth

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecKeyCreateEncryptedData
import platform.Security.SecKeyCreateWithData
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeRSA
import platform.Security.kSecKeyAlgorithmRSAEncryptionPKCS1
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class AppleDiscourseRsaPkcs1CryptoTest {
    @Test
    fun generatedSpkiAndPkcs8RoundTripPkcs1Ciphertext() =
        runTest {
            val crypto = AppleDiscourseRsaPkcs1Crypto(StandardTestDispatcher(testScheduler))
            val keyPair = crypto.generate(minimumKeySizeBits = 1_024)
            val privateKey = keyPair.copyPrivateKeyPkcs8()
            try {
                val expected = "discourse-apple-rsa-round-trip-vector".encodeToByteArray()
                val ciphertext = encryptWithSpki(keyPair.publicKeySpkiPem, expected)
                try {
                    assertTrue(ciphertext.size >= 256)
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
    fun malformedCiphertextIsRejectedBeforeSecurityFramework() =
        runTest {
            val crypto = AppleDiscourseRsaPkcs1Crypto(StandardTestDispatcher(testScheduler))
            val keyPair = crypto.generate(minimumKeySizeBits = 2_048)
            val privateKey = keyPair.copyPrivateKeyPkcs8()
            try {
                assertFailsWith<IllegalArgumentException> {
                    crypto.decrypt(privateKey, byteArrayOf(1, 2, 3))
                }
            } finally {
                privateKey.fill(0)
                keyPair.close()
            }
        }
}

private fun encryptWithSpki(
    publicKeyPem: String,
    plaintext: ByteArray,
): ByteArray =
    memScoped {
        val spki =
            Base64.Default.decode(
                publicKeyPem
                    .removePrefix("-----BEGIN PUBLIC KEY-----")
                    .removeSuffix("-----END PUBLIC KEY-----")
                    .filterNot(Char::isWhitespace),
            )
        val publicPkcs1 = unwrapTestSpki(spki)
        spki.fill(0)
        val keyData = publicPkcs1.toTestCFData()
        publicPkcs1.fill(0)
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
        CFDictionaryAddValue(attributes, kSecAttrKeyClass, kSecAttrKeyClassPublic)
        val importError = alloc<CFErrorRefVar>().apply { value = null }
        val publicKey = SecKeyCreateWithData(keyData, attributes, importError.ptr)
        CFRelease(attributes)
        CFRelease(keyData)
        importError.value?.let(::CFRelease)
        checkNotNull(publicKey) { "Test SPKI public key import failed" }

        val plaintextData = plaintext.toTestCFData()
        val encryptionError = alloc<CFErrorRefVar>().apply { value = null }
        val encrypted =
            SecKeyCreateEncryptedData(
                publicKey,
                kSecKeyAlgorithmRSAEncryptionPKCS1,
                plaintextData,
                encryptionError.ptr,
            )
        CFRelease(plaintextData)
        CFRelease(publicKey)
        encryptionError.value?.let(::CFRelease)
        checkNotNull(encrypted) { "Test RSA encryption failed" }
        try {
            encrypted.toTestByteArray()
        } finally {
            CFRelease(encrypted)
        }
    }

private fun unwrapTestSpki(spki: ByteArray): ByteArray {
    val outer = TestDerReader(spki).readConstructed(0x30)
    val algorithm = outer.readEncoded(0x30)
    val expectedAlgorithm =
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
    require(algorithm.contentEquals(expectedAlgorithm)) { "Test SPKI algorithm mismatch" }
    val bitString = outer.readValue(0x03)
    outer.requireExhausted()
    require(bitString.isNotEmpty() && bitString[0] == 0.toByte()) { "Test SPKI bit string is invalid" }
    return bitString.copyOfRange(1, bitString.size).also { bitString.fill(0) }
}

private class TestDerReader(
    private val bytes: ByteArray,
    private val start: Int = 0,
    private val limit: Int = bytes.size,
) {
    private var position = start

    fun readConstructed(tag: Int): TestDerReader {
        require(readByte() == tag)
        val length = readLength()
        require(length <= limit - position)
        val nested = TestDerReader(bytes, position, position + length)
        position += length
        if (start == 0 && limit == bytes.size) requireExhausted()
        return nested
    }

    fun readEncoded(tag: Int): ByteArray {
        val elementStart = position
        require(readByte() == tag)
        val length = readLength()
        require(length <= limit - position)
        position += length
        return bytes.copyOfRange(elementStart, position)
    }

    fun readValue(tag: Int): ByteArray {
        require(readByte() == tag)
        val length = readLength()
        require(length <= limit - position)
        return bytes.copyOfRange(position, position + length).also { position += length }
    }

    fun requireExhausted() {
        require(position == limit)
    }

    private fun readLength(): Int {
        val first = readByte()
        if (first < 0x80) return first
        val count = first and 0x7f
        require(count in 1..4 && count <= limit - position)
        var length = 0
        repeat(count) { length = (length shl 8) or readByte() }
        return length
    }

    private fun readByte(): Int {
        require(position < limit)
        return bytes[position++].toInt() and 0xff
    }
}

private fun ByteArray.toTestCFData(): CFDataRef =
    usePinned { pinned ->
        checkNotNull(
            CFDataCreate(
                kCFAllocatorDefault,
                pinned.addressOf(0).reinterpret(),
                size.toLong(),
            ),
        )
    }

private fun CFDataRef.toTestByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    val source = checkNotNull(CFDataGetBytePtr(this))
    return ByteArray(length).also { result ->
        for (index in result.indices) result[index] = source[index].toByte()
    }
}
