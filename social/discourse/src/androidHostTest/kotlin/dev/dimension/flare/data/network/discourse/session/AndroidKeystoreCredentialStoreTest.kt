package dev.dimension.flare.data.network.discourse.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.MessageDigest
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

internal class AndroidKeystoreCredentialStoreTest {
    private val directories = mutableListOf<Path>()

    @AfterTest
    fun removeTemporaryDirectories() {
        directories.forEach { directory ->
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
            }
        }
    }

    @Test
    fun roundTripCopiesInputsAndKeepsOnlyAuthenticatedCiphertext() =
        runTest {
            val directory = temporaryDirectory()
            val store = store(directory, FakeAuthenticatedCrypto())
            val input = "cookie=_t/private-value".encodeToByteArray()
            val expected = input.copyOf()

            val reference = store.save("account", input)
            input.fill(0)

            val storedBytes = Files.list(directory).use { files -> files.findFirst().orElseThrow().readBytes() }
            assertFalse(storedBytes.containsSubsequence(expected))
            val firstLoad = store.load(reference)!!
            assertContentEquals(expected, firstLoad)
            firstLoad.fill(0)
            assertContentEquals(expected, store.load(reference))
        }

    @Test
    fun missingAndDeletedReferencesReturnNull() =
        runTest {
            val directory = temporaryDirectory()
            val store = store(directory, FakeAuthenticatedCrypto())
            val missing = reference(9)

            assertNull(store.load(missing))
            val stored = store.save("account", byteArrayOf(1, 2, 3))
            store.delete(stored)
            assertNull(store.load(stored))
            store.delete(stored)
        }

    @Test
    fun tamperedCiphertextFailsClosed() =
        runTest {
            val directory = temporaryDirectory()
            val store = store(directory, FakeAuthenticatedCrypto())
            val reference = store.save("account", byteArrayOf(4, 5, 6))
            val path = directory.resolve(referenceFileName(1))
            val tampered = path.readBytes().also { bytes -> bytes[bytes.lastIndex] = (bytes.last() + 1).toByte() }
            Files.write(path, tampered)

            assertFailsWith<AndroidCredentialStoreException> { store.load(reference) }
        }

    @Test
    fun copiedBlobCannotBeOpenedThroughAnotherValidReference() =
        runTest {
            val directory = temporaryDirectory()
            val store = store(directory, FakeAuthenticatedCrypto())
            val first = store.save("account", byteArrayOf(1, 1, 1))
            val second = store.save("account", byteArrayOf(2, 2, 2))
            Files.copy(
                directory.resolve(referenceFileName(1)),
                directory.resolve(referenceFileName(2)),
                StandardCopyOption.REPLACE_EXISTING,
            )

            assertContentEquals(byteArrayOf(1, 1, 1), store.load(first))
            assertFailsWith<AndroidCredentialStoreException> { store.load(second) }
        }

    @Test
    fun craftedReferenceCannotSelectAPathOrKeystoreAlias() =
        runTest {
            val store = store(temporaryDirectory(), FakeAuthenticatedCrypto())

            assertFailsWith<IllegalArgumentException> {
                store.load(SecureCredentialRef("android-keystore-v1:../../outside"))
            }
            assertFailsWith<IllegalArgumentException> {
                store.load(SecureCredentialRef("android-keystore-v1:${"01".repeat(16)}:other-alias"))
            }
        }

    @Test
    fun cancellationFromBackendIsNotConvertedToVaultFailure() =
        runTest {
            val store =
                store(
                    temporaryDirectory(),
                    object : AndroidCredentialCryptoBackend {
                        override fun seal(
                            plaintext: ByteArray,
                            associatedData: ByteArray,
                        ): ByteArray = throw CancellationException("cancel")

                        override fun open(
                            encryptedBlob: ByteArray,
                            associatedData: ByteArray,
                        ): ByteArray = error("not called")
                    },
                )

            assertFailsWith<CancellationException> {
                store.save("account", byteArrayOf(1))
            }
        }

    private fun TestScope.store(
        directory: Path,
        crypto: AndroidCredentialCryptoBackend,
    ): AndroidKeystoreCredentialStore {
        var nextId = 1
        return AndroidKeystoreCredentialStore(
            directory = directory,
            crypto = crypto,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            randomBytes = { ByteArray(16) { nextId.toByte() }.also { nextId += 1 } },
            testMarker = Unit,
        )
    }

    private fun temporaryDirectory(): Path = Files.createTempDirectory("flaredo-android-vault-test-").also(directories::add)

    private fun reference(value: Int): SecureCredentialRef =
        SecureCredentialRef("android-keystore-v1:${value.toString(16).padStart(2, '0').repeat(16)}")

    private fun referenceFileName(value: Int): String = "${value.toString(16).padStart(2, '0').repeat(16)}.blob"
}

private class FakeAuthenticatedCrypto : AndroidCredentialCryptoBackend {
    override fun seal(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val masked = plaintext.map { byte -> (byte.toInt() xor MASK).toByte() }.toByteArray()
        val digest = digest(associatedData, plaintext)
        return ByteBuffer
            .allocate(Int.SIZE_BYTES + digest.size + masked.size)
            .putInt(masked.size)
            .put(digest)
            .put(masked)
            .array()
    }

    override fun open(
        encryptedBlob: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        if (encryptedBlob.size < Int.SIZE_BYTES + DIGEST_BYTES) throw GeneralSecurityException("truncated")
        val buffer = ByteBuffer.wrap(encryptedBlob)
        val size = buffer.int
        if (size <= 0 || buffer.remaining() != DIGEST_BYTES + size) {
            throw GeneralSecurityException("size")
        }
        val expectedDigest = ByteArray(DIGEST_BYTES).also(buffer::get)
        val plaintext =
            ByteArray(size).also(buffer::get).also { bytes ->
                bytes.indices.forEach { index -> bytes[index] = (bytes[index].toInt() xor MASK).toByte() }
            }
        if (!MessageDigest.isEqual(expectedDigest, digest(associatedData, plaintext))) {
            plaintext.fill(0)
            throw GeneralSecurityException("authentication")
        }
        return plaintext
    }

    private fun digest(
        associatedData: ByteArray,
        plaintext: ByteArray,
    ): ByteArray =
        MessageDigest
            .getInstance("SHA-256")
            .apply {
                update(associatedData)
                update(plaintext)
            }.digest()

    private companion object {
        const val MASK = 0xa5
        const val DIGEST_BYTES = 32
    }
}

private fun ByteArray.containsSubsequence(expected: ByteArray): Boolean =
    indices.any { start ->
        start + expected.size <= size &&
            expected.indices.all { offset -> this[start + offset] == expected[offset] }
    }
