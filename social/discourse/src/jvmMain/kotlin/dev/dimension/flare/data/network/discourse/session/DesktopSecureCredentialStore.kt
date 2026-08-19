package dev.dimension.flare.data.network.discourse.session

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val MAX_DESKTOP_CREDENTIAL_BYTES = 128 * 1024
private const val MAX_WINDOWS_BLOB_BYTES = 512 * 1024
private const val MAX_SECRET_TOOL_OUTPUT_BYTES = 256 * 1024
private const val MAX_SECRET_TOOL_DIAGNOSTIC_BYTES = 8 * 1024
private const val MAX_REFERENCE_ATTEMPTS = 32
private const val SECRET_TOOL_TIMEOUT_SECONDS = 20L
private const val RANDOM_REFERENCE_BYTES = 16

private const val WINDOWS_REFERENCE_PREFIX = "windows-dpapi-v1:"
private const val LINUX_REFERENCE_PREFIX = "linux-secret-service-v1:"
private const val SECRET_SERVICE_NAME = "io.github.zhongjianhui.flaredo.discourse.credentials.v1"
private const val SECRET_SERVICE_PROBE_ID = "availability-probe-do-not-store"
private const val WINDOWS_DPAPI_DESCRIPTION = "FlareDo Linux.do credential"

private val referenceIdRegex = Regex("[0-9a-f]{32}")
private val secretEnvelopeMagic = byteArrayOf(0x46, 0x44, 0x4f, 0x01)

/** Reason a persistent desktop vault could not be selected. */
public enum class DesktopCredentialStoreUnavailableReason {
    /** The JVM is not running on a supported Windows or Linux desktop. */
    UNSUPPORTED_OPERATING_SYSTEM,

    /** Windows did not expose a usable per-user application-data directory. */
    INVALID_PLATFORM_DATA_DIRECTORY,

    /** CurrentUser DPAPI could not protect and recover a probe value. */
    DPAPI_UNAVAILABLE,

    /** `secret-tool` was not installed as an executable on the current `PATH`. */
    SECRET_TOOL_NOT_INSTALLED,

    /** `secret-tool` was present but could not reach an unlocked Secret Service. */
    SECRET_SERVICE_UNAVAILABLE,
}

/**
 * Result of selecting the operating system's persistent desktop credential vault.
 *
 * Linux deliberately returns [Unavailable] when Secret Service cannot be reached. The host may
 * then opt into [SessionOnlySecureCredentialStore]; this factory never creates a plaintext file
 * fallback and never silently turns a requested persistent login into insecure persistence.
 */
public sealed interface DesktopCredentialStoreAvailability {
    /** A persistent CurrentUser DPAPI or Secret Service store is ready for use. */
    public data class Available(
        public val store: SecureCredentialStore,
    ) : DesktopCredentialStoreAvailability

    /** Persistent secure storage is unavailable and no credential has been written. */
    public data class Unavailable(
        public val reason: DesktopCredentialStoreUnavailableReason,
    ) : DesktopCredentialStoreAvailability
}

/**
 * Selects and probes the secure vault for the current desktop operating system.
 *
 * The probe and all later file, native, and process operations run on [ioDispatcher]. A caller can
 * therefore invoke this from the UI thread safely and can inject a test dispatcher without
 * coupling tests to the global I/O pool.
 */
public suspend fun createDesktopSecureCredentialStore(
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): DesktopCredentialStoreAvailability {
    val operatingSystem = DesktopOperatingSystem.current()
    val random = SecureRandom()
    return when (operatingSystem) {
        DesktopOperatingSystem.WINDOWS -> {
            val directory =
                windowsVaultDirectory()
                    ?: return DesktopCredentialStoreAvailability.Unavailable(
                        DesktopCredentialStoreUnavailableReason.INVALID_PLATFORM_DATA_DIRECTORY,
                    )
            createWindowsCredentialStore(
                directory = directory,
                backend = JnaWindowsDpapiBackend,
                ioDispatcher = ioDispatcher,
                randomBytes = random::nextReferenceBytes,
            )
        }

        DesktopOperatingSystem.LINUX -> {
            val executable =
                findExecutableOnPath("secret-tool")
                    ?: return DesktopCredentialStoreAvailability.Unavailable(
                        DesktopCredentialStoreUnavailableReason.SECRET_TOOL_NOT_INSTALLED,
                    )
            createLinuxCredentialStore(
                executable = executable,
                runner = JvmSecretToolProcessRunner(ioDispatcher),
                randomBytes = random::nextReferenceBytes,
            )
        }

        DesktopOperatingSystem.OTHER -> {
            DesktopCredentialStoreAvailability.Unavailable(
                DesktopCredentialStoreUnavailableReason.UNSUPPORTED_OPERATING_SYSTEM,
            )
        }
    }
}

/** Thrown when a previously selected desktop vault becomes unavailable or rejects its data. */
public class DesktopCredentialStoreException public constructor(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * CurrentUser DPAPI store whose filesystem contains encrypted blobs only.
 *
 * DPAPI optional entropy includes the complete opaque reference, so copying one blob onto another
 * valid filename cannot make it decrypt under the second reference. The reference contains only a
 * fixed format marker and random id; it can neither select a DPAPI scope nor inject native flags.
 */
public class WindowsDpapiCredentialStore internal constructor(
    private val directory: Path,
    private val backend: WindowsDpapiBackend,
    private val ioDispatcher: CoroutineDispatcher,
    private val randomBytes: () -> ByteArray,
) : SecureCredentialStore {
    override suspend fun save(
        accountId: String,
        secret: ByteArray,
    ): SecureCredentialRef {
        requireValidDesktopCredential(accountId, secret)
        val ownedSecret = secret.copyOf()
        try {
            return withContext(ioDispatcher) {
                repeat(MAX_REFERENCE_ATTEMPTS) {
                    val reference = newReference(WINDOWS_REFERENCE_PREFIX, randomBytes())
                    val entropy = windowsEntropy(reference)
                    val encrypted =
                        try {
                            backend.protect(ownedSecret, entropy)
                        } finally {
                            entropy.fill(0)
                        }
                    try {
                        require(encrypted.isNotEmpty() && encrypted.size <= MAX_WINDOWS_BLOB_BYTES) {
                            "DPAPI returned an invalid encrypted blob"
                        }
                        try {
                            writeNewBlob(directory, reference.referenceId(WINDOWS_REFERENCE_PREFIX), encrypted)
                            return@withContext reference
                        } catch (_: FileAlreadyExistsException) {
                            // A cryptographically random collision is retried with fresh entropy.
                        }
                    } finally {
                        encrypted.fill(0)
                    }
                }
                throw DesktopCredentialStoreException("Unable to allocate a unique DPAPI reference")
            }
        } finally {
            ownedSecret.fill(0)
        }
    }

    override suspend fun load(reference: SecureCredentialRef): ByteArray? =
        withContext(ioDispatcher) {
            val id = reference.referenceId(WINDOWS_REFERENCE_PREFIX)
            val encrypted =
                readBoundedBlob(directory.resolve("$id.blob"), MAX_WINDOWS_BLOB_BYTES)
                    ?: return@withContext null
            val entropy = windowsEntropy(reference)
            try {
                val decrypted =
                    try {
                        backend.unprotect(encrypted, entropy)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        throw DesktopCredentialStoreException(
                            "DPAPI rejected the credential blob",
                            error,
                        )
                    }
                if (decrypted.isEmpty() || decrypted.size > MAX_DESKTOP_CREDENTIAL_BYTES) {
                    decrypted.fill(0)
                    throw DesktopCredentialStoreException("DPAPI returned invalid credential data")
                }
                decrypted.copyOf().also { decrypted.fill(0) }
            } finally {
                encrypted.fill(0)
                entropy.fill(0)
            }
        }

    override suspend fun delete(reference: SecureCredentialRef): Unit =
        withContext<Unit>(ioDispatcher) {
            val id = reference.referenceId(WINDOWS_REFERENCE_PREFIX)
            Files.deleteIfExists(directory.resolve("$id.blob"))
        }
}

/**
 * Linux Secret Service store driven through `secret-tool` without placing a secret in argv.
 *
 * Credential bytes are wrapped in a reference-bound binary envelope and base64 encoded because
 * `secret-tool` accepts a textual stdin secret. Lookup output is bounded before decoding. The
 * command runner never invokes a shell, so account data and references cannot become shell syntax.
 */
public class LinuxSecretServiceCredentialStore internal constructor(
    private val executable: Path,
    private val runner: SecretToolRunner,
    private val randomBytes: () -> ByteArray,
) : SecureCredentialStore {
    override suspend fun save(
        accountId: String,
        secret: ByteArray,
    ): SecureCredentialRef {
        requireValidDesktopCredential(accountId, secret)
        ensureAvailable()

        repeat(MAX_REFERENCE_ATTEMPTS) {
            val reference = newReference(LINUX_REFERENCE_PREFIX, randomBytes())
            val existing = lookupEncoded(reference)
            if (existing != null) {
                existing.fill(0)
                return@repeat
            }

            val envelope = createSecretEnvelope(reference, secret)
            val encoded = Base64.getEncoder().encode(envelope)
            envelope.fill(0)
            try {
                val result =
                    runner.run(
                        command =
                            listOf(
                                executable.toString(),
                                "store",
                                "--label=FlareDo Linux.do credential",
                                "service",
                                SECRET_SERVICE_NAME,
                                "credential-id",
                                reference.referenceId(LINUX_REFERENCE_PREFIX),
                            ),
                        stdin = encoded,
                        maxStdoutBytes = MAX_SECRET_TOOL_DIAGNOSTIC_BYTES,
                        maxStderrBytes = MAX_SECRET_TOOL_DIAGNOSTIC_BYTES,
                    )
                try {
                    if (result.exitCode != 0) {
                        throw DesktopCredentialStoreException("Secret Service rejected the credential")
                    }
                    return reference
                } finally {
                    result.clear()
                }
            } finally {
                encoded.fill(0)
            }
        }
        throw DesktopCredentialStoreException("Unable to allocate a unique Secret Service reference")
    }

    override suspend fun load(reference: SecureCredentialRef): ByteArray? {
        ensureAvailable()
        val encoded = lookupEncoded(reference) ?: return null
        try {
            val normalized = encoded.withSingleTrailingLineEndingRemoved()
            try {
                val envelope =
                    try {
                        Base64.getDecoder().decode(normalized)
                    } catch (error: IllegalArgumentException) {
                        throw DesktopCredentialStoreException(
                            "Secret Service returned malformed credential data",
                            error,
                        )
                    }
                return try {
                    readSecretEnvelope(reference, envelope)
                } finally {
                    envelope.fill(0)
                }
            } finally {
                if (normalized !== encoded) normalized.fill(0)
            }
        } finally {
            encoded.fill(0)
        }
    }

    override suspend fun delete(reference: SecureCredentialRef) {
        ensureAvailable()
        val result =
            runner.run(
                command =
                    listOf(
                        executable.toString(),
                        "clear",
                        "service",
                        SECRET_SERVICE_NAME,
                        "credential-id",
                        reference.referenceId(LINUX_REFERENCE_PREFIX),
                    ),
                stdin = null,
                maxStdoutBytes = MAX_SECRET_TOOL_DIAGNOSTIC_BYTES,
                maxStderrBytes = MAX_SECRET_TOOL_DIAGNOSTIC_BYTES,
            )
        try {
            if (result.exitCode != 0) {
                throw DesktopCredentialStoreException("Secret Service could not delete the credential")
            }
        } finally {
            result.clear()
        }
    }

    private suspend fun ensureAvailable() {
        val result =
            runner.run(
                command = secretServiceProbeCommand(executable),
                stdin = null,
                maxStdoutBytes = MAX_SECRET_TOOL_DIAGNOSTIC_BYTES,
                maxStderrBytes = MAX_SECRET_TOOL_DIAGNOSTIC_BYTES,
            )
        try {
            if (result.exitCode != 0) {
                throw DesktopCredentialStoreException("Secret Service is unavailable")
            }
        } finally {
            result.clear()
        }
    }

    private suspend fun lookupEncoded(reference: SecureCredentialRef): ByteArray? {
        val result =
            runner.run(
                command =
                    listOf(
                        executable.toString(),
                        "lookup",
                        "service",
                        SECRET_SERVICE_NAME,
                        "credential-id",
                        reference.referenceId(LINUX_REFERENCE_PREFIX),
                    ),
                stdin = null,
                maxStdoutBytes = MAX_SECRET_TOOL_OUTPUT_BYTES,
                maxStderrBytes = MAX_SECRET_TOOL_DIAGNOSTIC_BYTES,
            )
        try {
            if (result.exitCode == 0) return result.stdout.copyOf()
            if (result.stdout.isEmpty() && result.stderr.isEmpty()) return null
            throw DesktopCredentialStoreException("Secret Service lookup failed")
        } finally {
            result.clear()
        }
    }
}

internal interface WindowsDpapiBackend {
    fun protect(
        plaintext: ByteArray,
        entropy: ByteArray,
    ): ByteArray

    fun unprotect(
        ciphertext: ByteArray,
        entropy: ByteArray,
    ): ByteArray

    fun probe(): Boolean
}

private object JnaWindowsDpapiBackend : WindowsDpapiBackend {
    override fun protect(
        plaintext: ByteArray,
        entropy: ByteArray,
    ): ByteArray =
        Crypt32Util.cryptProtectData(
            plaintext,
            entropy,
            WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,
            WINDOWS_DPAPI_DESCRIPTION,
            null,
        )

    override fun unprotect(
        ciphertext: ByteArray,
        entropy: ByteArray,
    ): ByteArray =
        Crypt32Util.cryptUnprotectData(
            ciphertext,
            entropy,
            WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,
            null,
        )

    override fun probe(): Boolean {
        val plaintext = byteArrayOf(0x46, 0x44, 0x4f, 0x01)
        val entropy = "FlareDo-DPAPI-probe-v1".toByteArray(StandardCharsets.UTF_8)
        val protectedBytes = protect(plaintext, entropy)
        return try {
            val recovered = unprotect(protectedBytes, entropy)
            try {
                plaintext.contentEquals(recovered)
            } finally {
                recovered.fill(0)
            }
        } finally {
            plaintext.fill(0)
            entropy.fill(0)
            protectedBytes.fill(0)
        }
    }
}

internal interface SecretToolRunner {
    suspend fun run(
        command: List<String>,
        stdin: ByteArray?,
        maxStdoutBytes: Int,
        maxStderrBytes: Int,
    ): SecretToolResult
}

internal data class SecretToolResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
) {
    fun clear() {
        stdout.fill(0)
        stderr.fill(0)
    }
}

/** Process runner that drains both pipes concurrently and never delegates to a command shell. */
private class JvmSecretToolProcessRunner(
    private val ioDispatcher: CoroutineDispatcher,
) : SecretToolRunner {
    override suspend fun run(
        command: List<String>,
        stdin: ByteArray?,
        maxStdoutBytes: Int,
        maxStderrBytes: Int,
    ): SecretToolResult =
        coroutineScope {
            require(command.isNotEmpty()) { "A process command must not be empty" }
            require(maxStdoutBytes > 0 && maxStderrBytes > 0) { "Process bounds must be positive" }

            val process = withContext(ioDispatcher) { ProcessBuilder(command).start() }
            try {
                val stdout =
                    async(ioDispatcher) {
                        process.inputStream.use { it.readBounded(maxStdoutBytes) }
                    }
                val stderr =
                    async(ioDispatcher) {
                        process.errorStream.use { it.readBounded(maxStderrBytes) }
                    }

                withContext(ioDispatcher) {
                    process.outputStream.use { input ->
                        if (stdin != null) input.write(stdin)
                    }
                }

                val completed =
                    withContext(ioDispatcher) {
                        process.waitFor(SECRET_TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    }
                if (!completed) {
                    process.destroyForcibly()
                    throw DesktopCredentialStoreException("Secret Service command timed out")
                }
                SecretToolResult(
                    exitCode = process.exitValue(),
                    stdout = stdout.await(),
                    stderr = stderr.await(),
                )
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
}

internal suspend fun createWindowsCredentialStore(
    directory: Path,
    backend: WindowsDpapiBackend,
    ioDispatcher: CoroutineDispatcher,
    randomBytes: () -> ByteArray,
): DesktopCredentialStoreAvailability =
    try {
        val available = withContext(ioDispatcher) { backend.probe() }
        if (available) {
            DesktopCredentialStoreAvailability.Available(
                WindowsDpapiCredentialStore(directory, backend, ioDispatcher, randomBytes),
            )
        } else {
            DesktopCredentialStoreAvailability.Unavailable(
                DesktopCredentialStoreUnavailableReason.DPAPI_UNAVAILABLE,
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DesktopCredentialStoreAvailability.Unavailable(
            DesktopCredentialStoreUnavailableReason.DPAPI_UNAVAILABLE,
        )
    } catch (_: LinkageError) {
        DesktopCredentialStoreAvailability.Unavailable(
            DesktopCredentialStoreUnavailableReason.DPAPI_UNAVAILABLE,
        )
    }

internal suspend fun createLinuxCredentialStore(
    executable: Path,
    runner: SecretToolRunner,
    randomBytes: () -> ByteArray,
): DesktopCredentialStoreAvailability =
    try {
        val result =
            runner.run(
                command = secretServiceProbeCommand(executable),
                stdin = null,
                maxStdoutBytes = MAX_SECRET_TOOL_DIAGNOSTIC_BYTES,
                maxStderrBytes = MAX_SECRET_TOOL_DIAGNOSTIC_BYTES,
            )
        val isAvailable =
            try {
                result.exitCode == 0
            } finally {
                result.clear()
            }
        if (isAvailable) {
            DesktopCredentialStoreAvailability.Available(
                LinuxSecretServiceCredentialStore(executable, runner, randomBytes),
            )
        } else {
            DesktopCredentialStoreAvailability.Unavailable(
                DesktopCredentialStoreUnavailableReason.SECRET_SERVICE_UNAVAILABLE,
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DesktopCredentialStoreAvailability.Unavailable(
            DesktopCredentialStoreUnavailableReason.SECRET_SERVICE_UNAVAILABLE,
        )
    }

private enum class DesktopOperatingSystem {
    WINDOWS,
    LINUX,
    OTHER,
    ;

    companion object {
        fun current(): DesktopOperatingSystem {
            val name = System.getProperty("os.name", "").lowercase(Locale.ROOT)
            return when {
                "windows" in name -> WINDOWS
                "linux" in name -> LINUX
                else -> OTHER
            }
        }
    }
}

private fun windowsVaultDirectory(): Path? {
    val localAppData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank) ?: return null
    val root =
        try {
            Paths.get(localAppData)
        } catch (_: RuntimeException) {
            return null
        }
    if (!root.isAbsolute) return null
    return root.resolve("FlareDo").resolve("credential-vault-v1")
}

private fun findExecutableOnPath(name: String): Path? {
    val pathValue = System.getenv("PATH") ?: return null
    return pathValue
        .split(java.io.File.pathSeparatorChar)
        .asSequence()
        .filter(String::isNotBlank)
        .mapNotNull { directory ->
            try {
                Paths
                    .get(directory)
                    .resolve(name)
                    .toAbsolutePath()
                    .normalize()
            } catch (_: RuntimeException) {
                null
            }
        }.firstOrNull { candidate ->
            Files.isRegularFile(candidate) && Files.isExecutable(candidate)
        }
}

private fun secretServiceProbeCommand(executable: Path): List<String> =
    listOf(
        executable.toString(),
        "search",
        "--all",
        "service",
        SECRET_SERVICE_NAME,
        "credential-id",
        SECRET_SERVICE_PROBE_ID,
    )

private fun requireValidDesktopCredential(
    accountId: String,
    secret: ByteArray,
) {
    requireValidAccountId(accountId)
    require(secret.isNotEmpty()) { "Credential bytes must not be empty" }
    require(secret.size <= MAX_DESKTOP_CREDENTIAL_BYTES) {
        "Credential bytes exceed the platform vault limit"
    }
}

private fun SecureRandom.nextReferenceBytes(): ByteArray = ByteArray(RANDOM_REFERENCE_BYTES).also(::nextBytes)

private fun newReference(
    prefix: String,
    randomBytes: ByteArray,
): SecureCredentialRef {
    require(randomBytes.size == RANDOM_REFERENCE_BYTES) {
        "A credential reference requires exactly $RANDOM_REFERENCE_BYTES random bytes"
    }
    val id = randomBytes.toHexString()
    randomBytes.fill(0)
    return SecureCredentialRef(prefix + id)
}

private fun SecureCredentialRef.referenceId(prefix: String): String {
    require(value.startsWith(prefix)) { "Credential reference belongs to another vault" }
    val id = value.removePrefix(prefix)
    require(referenceIdRegex.matches(id)) { "Credential reference has an invalid format" }
    return id
}

private fun ByteArray.toHexString(): String =
    buildString(size * 2) {
        this@toHexString.forEach { value ->
            val unsigned = value.toInt() and 0xff
            append("0123456789abcdef"[unsigned ushr 4])
            append("0123456789abcdef"[unsigned and 0x0f])
        }
    }

private fun windowsEntropy(reference: SecureCredentialRef): ByteArray =
    ("FlareDo-DPAPI-reference-v1\u0000" + reference.value).toByteArray(StandardCharsets.UTF_8)

private fun writeNewBlob(
    directory: Path,
    id: String,
    encrypted: ByteArray,
) {
    Files.createDirectories(directory)
    val target = directory.resolve("$id.blob")
    val temporary = Files.createTempFile(directory, ".$id-", ".tmp")
    try {
        Files.write(
            temporary,
            encrypted,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private fun readBoundedBlob(
    path: Path,
    maximumBytes: Int,
): ByteArray? {
    val size =
        try {
            Files.size(path)
        } catch (_: NoSuchFileException) {
            return null
        }
    if (size <= 0L || size > maximumBytes.toLong()) {
        throw DesktopCredentialStoreException("Credential blob has an invalid size")
    }
    return try {
        Files.readAllBytes(path).also { bytes ->
            if (bytes.size > maximumBytes) {
                bytes.fill(0)
                throw DesktopCredentialStoreException("Credential blob exceeds the vault limit")
            }
        }
    } catch (_: NoSuchFileException) {
        null
    }
}

private fun createSecretEnvelope(
    reference: SecureCredentialRef,
    secret: ByteArray,
): ByteArray {
    val id = reference.referenceId(LINUX_REFERENCE_PREFIX).toByteArray(StandardCharsets.US_ASCII)
    return ByteBuffer
        .allocate(secretEnvelopeMagic.size + id.size + Int.SIZE_BYTES + secret.size)
        .put(secretEnvelopeMagic)
        .put(id)
        .putInt(secret.size)
        .put(secret)
        .array()
}

private fun readSecretEnvelope(
    reference: SecureCredentialRef,
    envelope: ByteArray,
): ByteArray {
    val expectedId = reference.referenceId(LINUX_REFERENCE_PREFIX).toByteArray(StandardCharsets.US_ASCII)
    val headerSize = secretEnvelopeMagic.size + expectedId.size + Int.SIZE_BYTES
    if (envelope.size < headerSize || envelope.size > headerSize + MAX_DESKTOP_CREDENTIAL_BYTES) {
        throw DesktopCredentialStoreException("Secret Service credential envelope has an invalid size")
    }
    val buffer = ByteBuffer.wrap(envelope)
    val magic = ByteArray(secretEnvelopeMagic.size).also(buffer::get)
    val actualId = ByteArray(expectedId.size).also(buffer::get)
    try {
        if (!magic.contentEquals(secretEnvelopeMagic) || !actualId.contentEquals(expectedId)) {
            throw DesktopCredentialStoreException("Secret Service credential reference mismatch")
        }
        val secretSize = buffer.int
        if (secretSize <= 0 || secretSize != buffer.remaining()) {
            throw DesktopCredentialStoreException("Secret Service credential envelope is malformed")
        }
        return ByteArray(secretSize).also(buffer::get)
    } finally {
        magic.fill(0)
        actualId.fill(0)
        expectedId.fill(0)
    }
}

private fun ByteArray.withSingleTrailingLineEndingRemoved(): ByteArray {
    if (isEmpty() || last() != '\n'.code.toByte()) return this
    val contentLength = if (size >= 2 && this[size - 2] == '\r'.code.toByte()) size - 2 else size - 1
    return copyOf(contentLength)
}

private fun InputStream.readBounded(maximumBytes: Int): ByteArray {
    val bytes = readNBytes(maximumBytes + 1)
    if (bytes.size > maximumBytes) {
        bytes.fill(0)
        throw IOException("Process output exceeded its configured bound")
    }
    return bytes
}
