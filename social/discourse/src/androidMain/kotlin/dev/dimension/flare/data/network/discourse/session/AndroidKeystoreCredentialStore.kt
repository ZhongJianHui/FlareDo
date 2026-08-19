package dev.dimension.flare.data.network.discourse.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ANDROID_REFERENCE_PREFIX = "android-keystore-v1:"
private const val ANDROID_KEY_ALIAS = "io.github.zhongjianhui.flaredo.discourse.credentials.v1"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128
private const val AES_KEY_BITS = 256
private const val GCM_IV_BYTES = 12
private const val RANDOM_REFERENCE_BYTES = 16
private const val MAX_REFERENCE_ATTEMPTS = 32
private const val MAX_ANDROID_CREDENTIAL_BYTES = 128 * 1024
private const val MAX_ANDROID_BLOB_BYTES = 256 * 1024

private val androidReferenceIdRegex = Regex("[0-9a-f]{32}")
private val androidBlobMagic = byteArrayOf(0x46, 0x44, 0x41, 0x01)

/** Thrown when Android Keystore rejects or cannot authenticate a credential blob. */
public class AndroidCredentialStoreException public constructor(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Android API 26+ persistent credential store backed by a non-exportable AES-GCM key.
 *
 * Only ciphertext is placed in the app-private no-backup directory. The key alias is a fixed
 * implementation constant and never comes from [SecureCredentialRef], which prevents a crafted
 * database reference from selecting an attacker-controlled alias. The full opaque reference is
 * authenticated as GCM associated data, so moving a valid blob to another valid filename fails
 * authentication rather than disclosing the credential.
 */
public class AndroidKeystoreCredentialStore private constructor(
    private val directory: Path,
    private val crypto: AndroidCredentialCryptoBackend,
    private val ioDispatcher: CoroutineDispatcher,
    private val randomBytes: () -> ByteArray,
) : SecureCredentialStore {
    /** Creates the production store in this application's private no-backup directory. */
    public constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        directory = context.noBackupFilesDir.toPath().resolve("credential-vault-v1"),
        crypto = AndroidKeystoreAesGcmBackend,
        ioDispatcher = ioDispatcher,
        randomBytes = SecureRandom()::nextReferenceBytes,
    )

    /** Test seam that cannot alter the production Android Keystore alias. */
    internal constructor(
        directory: Path,
        crypto: AndroidCredentialCryptoBackend,
        ioDispatcher: CoroutineDispatcher,
        randomBytes: () -> ByteArray,
        @Suppress("UNUSED_PARAMETER") testMarker: Unit,
    ) : this(directory, crypto, ioDispatcher, randomBytes)

    override suspend fun save(
        accountId: String,
        secret: ByteArray,
    ): SecureCredentialRef {
        requireValidAccountId(accountId)
        require(secret.isNotEmpty()) { "Credential bytes must not be empty" }
        require(secret.size <= MAX_ANDROID_CREDENTIAL_BYTES) {
            "Credential bytes exceed the Android vault limit"
        }

        val ownedSecret = secret.copyOf()
        try {
            return withContext(ioDispatcher) {
                repeat(MAX_REFERENCE_ATTEMPTS) {
                    val reference = newAndroidReference(randomBytes())
                    val associatedData = reference.value.encodeToByteArray()
                    val encrypted =
                        try {
                            crypto.seal(ownedSecret, associatedData)
                        } catch (error: GeneralSecurityException) {
                            throw AndroidCredentialStoreException("Android Keystore encryption failed", error)
                        } finally {
                            associatedData.fill(0)
                        }
                    try {
                        require(encrypted.isNotEmpty() && encrypted.size <= MAX_ANDROID_BLOB_BYTES) {
                            "Android Keystore returned an invalid encrypted blob"
                        }
                        try {
                            writeNewAndroidBlob(reference, encrypted)
                            return@withContext reference
                        } catch (_: FileAlreadyExistsException) {
                            // Retry the impossible-in-practice random collision with new AAD.
                        }
                    } finally {
                        encrypted.fill(0)
                    }
                }
                throw AndroidCredentialStoreException("Unable to allocate a unique vault reference")
            }
        } finally {
            ownedSecret.fill(0)
        }
    }

    override suspend fun load(reference: SecureCredentialRef): ByteArray? =
        withContext(ioDispatcher) {
            val encrypted = readAndroidBlob(reference) ?: return@withContext null
            val associatedData = reference.value.encodeToByteArray()
            try {
                val decrypted =
                    try {
                        crypto.open(encrypted, associatedData)
                    } catch (error: GeneralSecurityException) {
                        throw AndroidCredentialStoreException(
                            "Android Keystore rejected the credential blob",
                            error,
                        )
                    }
                if (decrypted.isEmpty() || decrypted.size > MAX_ANDROID_CREDENTIAL_BYTES) {
                    decrypted.fill(0)
                    throw AndroidCredentialStoreException("Android Keystore returned invalid data")
                }
                decrypted.copyOf().also { decrypted.fill(0) }
            } finally {
                encrypted.fill(0)
                associatedData.fill(0)
            }
        }

    override suspend fun delete(reference: SecureCredentialRef): Unit =
        withContext<Unit>(ioDispatcher) {
            Files.deleteIfExists(fileFor(reference))
        }

    private fun writeNewAndroidBlob(
        reference: SecureCredentialRef,
        encrypted: ByteArray,
    ) {
        Files.createDirectories(directory)
        val target = fileFor(reference)
        val id = reference.androidReferenceId()
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

    private fun readAndroidBlob(reference: SecureCredentialRef): ByteArray? {
        val path = fileFor(reference)
        val size =
            try {
                Files.size(path)
            } catch (_: NoSuchFileException) {
                return null
            }
        if (size <= 0L || size > MAX_ANDROID_BLOB_BYTES.toLong()) {
            throw AndroidCredentialStoreException("Android credential blob has an invalid size")
        }
        return try {
            Files.readAllBytes(path).also { bytes ->
                if (bytes.size > MAX_ANDROID_BLOB_BYTES) {
                    bytes.fill(0)
                    throw AndroidCredentialStoreException("Android credential blob exceeds its limit")
                }
            }
        } catch (_: NoSuchFileException) {
            null
        }
    }

    private fun fileFor(reference: SecureCredentialRef): Path = directory.resolve("${reference.androidReferenceId()}.blob")
}

internal interface AndroidCredentialCryptoBackend {
    @Throws(GeneralSecurityException::class)
    fun seal(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray

    @Throws(GeneralSecurityException::class)
    fun open(
        encryptedBlob: ByteArray,
        associatedData: ByteArray,
    ): ByteArray
}

/**
 * A single non-exportable key protects every random record id. Reference-bound GCM AAD provides
 * record separation, while synchronization covers the only shared mutable object: KeyStore alias
 * creation. Cipher instances themselves remain local to each operation.
 */
private object AndroidKeystoreAesGcmBackend : AndroidCredentialCryptoBackend {
    private val aliasLock = Any()

    override fun seal(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv
        try {
            check(iv.size == GCM_IV_BYTES) { "Android Keystore returned an unexpected GCM IV" }
            return ByteBuffer
                .allocate(androidBlobMagic.size + 1 + iv.size + ciphertext.size)
                .put(androidBlobMagic)
                .put(iv.size.toByte())
                .put(iv)
                .put(ciphertext)
                .array()
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    override fun open(
        encryptedBlob: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val minimumSize = androidBlobMagic.size + 1 + GCM_IV_BYTES + (GCM_TAG_BITS / Byte.SIZE_BITS)
        if (encryptedBlob.size < minimumSize) {
            throw GeneralSecurityException("Encrypted credential blob is truncated")
        }
        val buffer = ByteBuffer.wrap(encryptedBlob)
        val magic = ByteArray(androidBlobMagic.size).also(buffer::get)
        val ivSize = buffer.get().toInt() and 0xff
        if (!magic.contentEquals(androidBlobMagic) || ivSize != GCM_IV_BYTES || buffer.remaining() <= ivSize) {
            magic.fill(0)
            throw GeneralSecurityException("Encrypted credential blob header is invalid")
        }
        magic.fill(0)
        val iv = ByteArray(ivSize).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        try {
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(associatedData)
            return cipher.doFinal(ciphertext)
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun getOrCreateKey(): SecretKey =
        synchronized(aliasLock) {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getKey(ANDROID_KEY_ALIAS, null) as? SecretKey) ?: generateKey()
        }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(
                    ANDROID_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}

private fun SecureRandom.nextReferenceBytes(): ByteArray = ByteArray(RANDOM_REFERENCE_BYTES).also(::nextBytes)

private fun newAndroidReference(randomBytes: ByteArray): SecureCredentialRef {
    require(randomBytes.size == RANDOM_REFERENCE_BYTES) {
        "An Android credential reference requires exactly $RANDOM_REFERENCE_BYTES random bytes"
    }
    val id = randomBytes.toHexString()
    randomBytes.fill(0)
    return SecureCredentialRef(ANDROID_REFERENCE_PREFIX + id)
}

private fun SecureCredentialRef.androidReferenceId(): String {
    require(value.startsWith(ANDROID_REFERENCE_PREFIX)) {
        "Credential reference belongs to another vault"
    }
    val id = value.removePrefix(ANDROID_REFERENCE_PREFIX)
    require(androidReferenceIdRegex.matches(id)) { "Credential reference has an invalid format" }
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
