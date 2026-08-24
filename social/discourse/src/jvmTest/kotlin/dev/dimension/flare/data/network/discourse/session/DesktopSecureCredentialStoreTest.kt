package dev.dimension.flare.data.network.discourse.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class DesktopSecureCredentialStoreTest {
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
    fun dpapiStorePersistsOnlyBoundCiphertextAndCopiesValues() =
        runTest {
            val directory = temporaryDirectory()
            val store = windowsStore(directory)
            val input = "_t=fixture-private-cookie".encodeToByteArray()
            val expected = input.copyOf()

            val reference = store.save("account", input)
            input.fill(0)

            val blob = Files.list(directory).use { paths -> paths.findFirst().orElseThrow().readBytes() }
            assertFalse(blob.containsSubsequence(expected))
            val loaded = store.load(reference)!!
            assertContentEquals(expected, loaded)
            loaded.fill(0)
            assertContentEquals(expected, store.load(reference))
        }

    @Test
    fun dpapiStoreRejectsTamperingAndBlobCopying() =
        runTest {
            val directory = temporaryDirectory()
            val store = windowsStore(directory)
            val first = store.save("account", byteArrayOf(1, 2, 3))
            val second = store.save("account", byteArrayOf(4, 5, 6))
            val firstPath = directory.resolve(referenceFileName(1))
            val secondPath = directory.resolve(referenceFileName(2))

            Files.copy(firstPath, secondPath, StandardCopyOption.REPLACE_EXISTING)
            assertContentEquals(byteArrayOf(1, 2, 3), store.load(first))
            assertFailsWith<DesktopCredentialStoreException> { store.load(second) }

            val tampered = firstPath.readBytes().also { bytes -> bytes[bytes.lastIndex] = (bytes.last() + 1).toByte() }
            Files.write(firstPath, tampered)
            assertFailsWith<DesktopCredentialStoreException> { store.load(first) }
        }

    @Test
    fun dpapiStoreHandlesMissingDeleteAndRejectsReferenceInjection() =
        runTest {
            val store = windowsStore(temporaryDirectory())
            val reference = windowsReference(7)
            assertNull(store.load(reference))
            store.delete(reference)

            val stored = store.save("account", byteArrayOf(9))
            store.delete(stored)
            assertNull(store.load(stored))
            assertFailsWith<IllegalArgumentException> {
                store.load(SecureCredentialRef("windows-dpapi-v1:../../credential"))
            }
        }

    @Test
    fun windowsFactoryReturnsUnavailableWhenDpapiProbeFails() =
        runTest {
            val result =
                createWindowsCredentialStore(
                    directory = temporaryDirectory(),
                    backend = FakeDpapiBackend(isAvailable = false),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    randomBytes = { ByteArray(16) },
                )

            val unavailable = assertIs<DesktopCredentialStoreAvailability.Unavailable>(result)
            assertEquals(DesktopCredentialStoreUnavailableReason.DPAPI_UNAVAILABLE, unavailable.reason)
        }

    @Test
    fun linuxStoreUsesStdinAndRoundTripsWithoutAPlaintextFallback() =
        runTest {
            val runner = FakeSecretToolRunner()
            val store = linuxStore(runner)
            val secret = "session-secret-never-in-argv".encodeToByteArray()

            val reference = store.save("account", secret)
            assertTrue(runner.commands.none { command -> command.any { argument -> argument.contains("session-secret") } })
            assertContentEquals(secret, store.load(reference))
            assertTrue(runner.lastStoreStdinWasPresent)

            store.delete(reference)
            assertNull(store.load(reference))
        }

    @Test
    fun linuxStoreRejectsCopiedOrTamperedSecretServiceValues() =
        runTest {
            val runner = FakeSecretToolRunner()
            val store = linuxStore(runner)
            val first = store.save("account", byteArrayOf(1, 2, 3))
            val second = store.save("account", byteArrayOf(4, 5, 6))

            runner.copyStoredValue(referenceId(first), referenceId(second))
            assertFailsWith<DesktopCredentialStoreException> { store.load(second) }

            runner.tamper(referenceId(first))
            assertFailsWith<DesktopCredentialStoreException> { store.load(first) }
        }

    @Test
    fun linuxFactoryExplicitlyReportsBackendUnavailable() =
        runTest {
            val runner = FakeSecretToolRunner(isAvailable = false)
            val result =
                createLinuxCredentialStore(
                    executable = Path.of("/fixture/secret-tool"),
                    runner = runner,
                    randomBytes = { ByteArray(16) },
                )

            val unavailable = assertIs<DesktopCredentialStoreAvailability.Unavailable>(result)
            assertEquals(
                DesktopCredentialStoreUnavailableReason.SECRET_SERVICE_UNAVAILABLE,
                unavailable.reason,
            )
            assertTrue(runner.storedValues.isEmpty())
        }

    @Test
    fun factoryDoesNotSwallowCancellation() =
        runTest {
            val runner =
                object : SecretToolRunner {
                    override suspend fun run(
                        command: List<String>,
                        stdin: ByteArray?,
                        maxStdoutBytes: Int,
                        maxStderrBytes: Int,
                    ): SecretToolResult = throw CancellationException("cancelled")
                }

            assertFailsWith<CancellationException> {
                createLinuxCredentialStore(
                    executable = Path.of("/fixture/secret-tool"),
                    runner = runner,
                    randomBytes = { ByteArray(16) },
                )
            }
        }

    /**
     * Exercises the real current-user desktop vault when the CI environment explicitly requires it.
     *
     * The default remains a fast, hermetic unit suite because developer machines may legitimately
     * lack an unlocked Secret Service. CI sets [REQUIRE_DESKTOP_VAULT_ENV] to `1` only after it has
     * provisioned the platform service. Once enabled, an unavailable backend is a hard failure rather
     * than a skipped assertion, so Windows DPAPI and Linux Secret Service regressions cannot pass
     * unnoticed.
     */
    @Test
    fun requiredCurrentUserVaultRoundTripsAndRemovesCredential() =
        runTest {
            if (System.getenv(REQUIRE_DESKTOP_VAULT_ENV) != "1") return@runTest

            val operatingSystem = System.getProperty("os.name", "").lowercase()
            assertTrue(
                "windows" in operatingSystem || "linux" in operatingSystem,
                "$REQUIRE_DESKTOP_VAULT_ENV is supported only on Windows and Linux runners",
            )

            val availability = createDesktopSecureCredentialStore()
            val store =
                assertIs<DesktopCredentialStoreAvailability.Available>(
                    availability,
                    "$REQUIRE_DESKTOP_VAULT_ENV=1 requires an available current-user vault; got $availability",
                ).store
            val secret = "flaredo-real-vault-round-trip-fixture".encodeToByteArray()
            var cleanupReference: SecureCredentialRef? = null
            var primaryFailure: Throwable? = null

            try {
                val reference = store.save("ci-vault-${System.nanoTime().toString(16)}", secret)
                cleanupReference = reference

                val loaded = requireNotNull(store.load(reference)) { "The real vault lost the saved credential" }
                try {
                    assertContentEquals(secret, loaded)
                } finally {
                    loaded.fill(0)
                }

                store.delete(reference)
                assertNull(store.load(reference), "The real vault retained the deleted credential")
                cleanupReference = null
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                secret.fill(0)
                cleanupReference?.let { reference ->
                    withContext(NonCancellable) {
                        try {
                            store.delete(reference)
                        } catch (cleanupFailure: Throwable) {
                            primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
                        }
                    }
                }
            }
        }

    private fun TestScope.windowsStore(directory: Path): WindowsDpapiCredentialStore {
        var nextId = 1
        return WindowsDpapiCredentialStore(
            directory = directory,
            backend = FakeDpapiBackend(),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            randomBytes = { ByteArray(16) { nextId.toByte() }.also { nextId += 1 } },
        )
    }

    private fun linuxStore(runner: FakeSecretToolRunner): LinuxSecretServiceCredentialStore {
        var nextId = 1
        return LinuxSecretServiceCredentialStore(
            executable = Path.of("/fixture/secret-tool"),
            runner = runner,
            randomBytes = { ByteArray(16) { nextId.toByte() }.also { nextId += 1 } },
        )
    }

    private fun temporaryDirectory(): Path = Files.createTempDirectory("flaredo-desktop-vault-test-").also(directories::add)

    private fun windowsReference(value: Int): SecureCredentialRef =
        SecureCredentialRef("windows-dpapi-v1:${value.toString(16).padStart(2, '0').repeat(16)}")

    private fun referenceFileName(value: Int): String = "${value.toString(16).padStart(2, '0').repeat(16)}.blob"

    private fun referenceId(reference: SecureCredentialRef): String = reference.value.substringAfter(':')

    private companion object {
        const val REQUIRE_DESKTOP_VAULT_ENV = "FLAREDO_REQUIRE_DESKTOP_VAULT"
    }
}

private class FakeDpapiBackend(
    private val isAvailable: Boolean = true,
) : WindowsDpapiBackend {
    override fun protect(
        plaintext: ByteArray,
        entropy: ByteArray,
    ): ByteArray {
        if (!isAvailable) throw GeneralSecurityException("unavailable")
        val masked = plaintext.map { byte -> (byte.toInt() xor MASK).toByte() }.toByteArray()
        val digest = digest(entropy, plaintext)
        return ByteBuffer
            .allocate(Int.SIZE_BYTES + digest.size + masked.size)
            .putInt(masked.size)
            .put(digest)
            .put(masked)
            .array()
    }

    override fun unprotect(
        ciphertext: ByteArray,
        entropy: ByteArray,
    ): ByteArray {
        if (ciphertext.size < Int.SIZE_BYTES + DIGEST_BYTES) throw GeneralSecurityException("truncated")
        val buffer = ByteBuffer.wrap(ciphertext)
        val size = buffer.int
        if (size <= 0 || buffer.remaining() != DIGEST_BYTES + size) throw GeneralSecurityException("size")
        val expectedDigest = ByteArray(DIGEST_BYTES).also(buffer::get)
        val plaintext =
            ByteArray(size).also(buffer::get).also { bytes ->
                bytes.indices.forEach { index -> bytes[index] = (bytes[index].toInt() xor MASK).toByte() }
            }
        if (!MessageDigest.isEqual(expectedDigest, digest(entropy, plaintext))) {
            plaintext.fill(0)
            throw GeneralSecurityException("authentication")
        }
        return plaintext
    }

    override fun probe(): Boolean = isAvailable

    private fun digest(
        entropy: ByteArray,
        plaintext: ByteArray,
    ): ByteArray =
        MessageDigest
            .getInstance("SHA-256")
            .apply {
                update(entropy)
                update(plaintext)
            }.digest()

    private companion object {
        const val MASK = 0xa5
        const val DIGEST_BYTES = 32
    }
}

private class FakeSecretToolRunner(
    var isAvailable: Boolean = true,
) : SecretToolRunner {
    val storedValues = mutableMapOf<String, ByteArray>()
    val commands = mutableListOf<List<String>>()
    var lastStoreStdinWasPresent = false

    override suspend fun run(
        command: List<String>,
        stdin: ByteArray?,
        maxStdoutBytes: Int,
        maxStderrBytes: Int,
    ): SecretToolResult {
        commands += command.toList()
        if (!isAvailable) return SecretToolResult(1, byteArrayOf(), "unavailable".encodeToByteArray())
        val operation = command.getOrNull(1)
        val id = command.lastOrNull().orEmpty()
        return when (operation) {
            "search" -> {
                SecretToolResult(0, byteArrayOf(), byteArrayOf())
            }

            "lookup" -> {
                val value = storedValues[id]
                if (value == null) {
                    SecretToolResult(1, byteArrayOf(), byteArrayOf())
                } else {
                    SecretToolResult(0, value + '\n'.code.toByte(), byteArrayOf())
                }
            }

            "store" -> {
                lastStoreStdinWasPresent = stdin != null
                storedValues[id] = requireNotNull(stdin).copyOf()
                SecretToolResult(0, byteArrayOf(), byteArrayOf())
            }

            "clear" -> {
                storedValues.remove(id)?.fill(0)
                SecretToolResult(0, byteArrayOf(), byteArrayOf())
            }

            else -> {
                error("Unexpected fake operation: $operation")
            }
        }
    }

    fun copyStoredValue(
        sourceId: String,
        destinationId: String,
    ) {
        storedValues[destinationId] = requireNotNull(storedValues[sourceId]).copyOf()
    }

    fun tamper(id: String) {
        val bytes = requireNotNull(storedValues[id])
        bytes[bytes.lastIndex] = (bytes.last() + 1).toByte()
    }
}

private fun ByteArray.containsSubsequence(expected: ByteArray): Boolean =
    indices.any { start ->
        start + expected.size <= size &&
            expected.indices.all { offset -> this[start + offset] == expected[offset] }
    }
