package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.database.SecureVaultReferenceDao
import dev.dimension.flare.data.database.SecureVaultReferenceEntity
import dev.dimension.flare.data.network.discourse.session.SecureCredentialRef
import dev.dimension.flare.data.network.discourse.session.SecureCredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Clock

private const val SAVED_LOGIN_SLOT: String = "discourse.saved-login"
private const val SAVED_LOGIN_VAULT_ACCOUNT: String = "discourse.saved-login-password"
private const val SAVED_LOGIN_VERSION: Int = 1
private const val MAX_SAVED_LOGIN_IDENTIFIER_CHARS: Int = 254
private const val MAX_SAVED_LOGIN_PASSWORD_CHARS: Int = 512
private const val MAX_SAVED_LOGIN_ENVELOPE_BYTES: Int = 8 * 1024

/** One vault-backed login suggestion whose password is never included in diagnostics. */
public class DiscourseSavedLogin internal constructor(
    public val identifier: String,
    password: String,
) : AutoCloseable {
    private var retainedPassword: String? = password

    /** Returns the password only while this short-lived value remains open. */
    public fun copyPassword(): String = checkNotNull(retainedPassword) { "Saved login is closed" }

    override fun close() {
        retainedPassword = null
    }

    override fun toString(): String = "DiscourseSavedLogin(identifier=<redacted>, password=<redacted>)"
}

/** Persistent saved-login boundary backed by the same fail-closed platform vault as sessions. */
public interface DiscourseSavedLoginStore {
    public suspend fun save(
        identifier: String,
        password: String,
    )

    public suspend fun load(): DiscourseSavedLogin?

    public suspend fun clear(): Boolean
}

/**
 * Stores only one remembered Linux.do login, independently from the active web session.
 *
 * Room contains the public identifier and an opaque reference. The complete envelope is encrypted by
 * the platform vault. Missing or corrupt vault material compare-deletes its stale Room reference, so
 * Linux's session-only fallback never becomes a plaintext or silently persistent password store.
 */
public class RoomDiscourseSavedLoginStore(
    private val dao: SecureVaultReferenceDao,
    private val credentialStore: SecureCredentialStore,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : DiscourseSavedLoginStore {
    private val operationMutex: Mutex = Mutex()

    override suspend fun save(
        identifier: String,
        password: String,
    ) {
        val normalizedIdentifier = identifier.trim()
        requireValidSavedLogin(normalizedIdentifier, password)
        operationMutex.withLock {
            val encoded =
                SavedLoginEnvelope(
                    version = SAVED_LOGIN_VERSION,
                    identifier = normalizedIdentifier,
                    password = password,
                ).encode()
            require(encoded.size <= MAX_SAVED_LOGIN_ENVELOPE_BYTES) {
                "Saved login envelope exceeds the vault limit"
            }
            val newReference =
                try {
                    credentialStore.save(SAVED_LOGIN_VAULT_ACCOUNT, encoded)
                } finally {
                    encoded.fill(0)
                }
            val previous =
                try {
                    dao.replace(
                        SecureVaultReferenceEntity(
                            slot = SAVED_LOGIN_SLOT,
                            credentialRef = newReference.value,
                            username = normalizedIdentifier,
                            createdAtEpochMillis = nowEpochMillis(),
                        ),
                    )
                } catch (cancellation: CancellationException) {
                    deleteVaultValue(newReference)
                    throw cancellation
                } catch (failure: Throwable) {
                    deleteVaultValue(newReference)
                    throw failure
                }
            previous?.credentialRef?.toCredentialRefOrNull()?.let { deleteVaultValue(it) }
        }
    }

    override suspend fun load(): DiscourseSavedLogin? =
        operationMutex.withLock {
            val entity = dao.get(SAVED_LOGIN_SLOT) ?: return@withLock null
            val reference = entity.credentialRef.toCredentialRefOrNull()
            val restored = reference?.let { decodeSavedLogin(it, entity.username) }
            if (restored != null) return@withLock restored

            val removed = dao.consume(SAVED_LOGIN_SLOT, entity.credentialRef)
            removed?.credentialRef?.toCredentialRefOrNull()?.let { deleteVaultValue(it) }
            null
        }

    override suspend fun clear(): Boolean =
        operationMutex.withLock {
            val current = dao.get(SAVED_LOGIN_SLOT) ?: return@withLock false
            val removed = dao.consume(SAVED_LOGIN_SLOT, current.credentialRef) ?: return@withLock false
            removed.credentialRef.toCredentialRefOrNull()?.let { deleteVaultValue(it) }
            true
        }

    private suspend fun decodeSavedLogin(
        reference: SecureCredentialRef,
        expectedIdentifier: String?,
    ): DiscourseSavedLogin? {
        val bytes =
            try {
                credentialStore.load(reference)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            } ?: return null
        return try {
            if (bytes.size !in 1..MAX_SAVED_LOGIN_ENVELOPE_BYTES) return null
            val envelope =
                try {
                    SAVED_LOGIN_JSON.decodeFromString<SavedLoginEnvelope>(
                        bytes.decodeToString(throwOnInvalidSequence = true),
                    )
                } catch (_: CharacterCodingException) {
                    null
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return null
            if (envelope.version != SAVED_LOGIN_VERSION || envelope.identifier != expectedIdentifier) {
                return null
            }
            try {
                requireValidSavedLogin(envelope.identifier, envelope.password)
            } catch (_: IllegalArgumentException) {
                return null
            }
            DiscourseSavedLogin(envelope.identifier, envelope.password)
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun deleteVaultValue(reference: SecureCredentialRef) {
        withContext(NonCancellable) {
            try {
                credentialStore.delete(reference)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Removing the Room reference keeps an undeletable vault value unreachable.
            }
        }
    }
}

@Serializable
private data class SavedLoginEnvelope(
    val version: Int,
    val identifier: String,
    val password: String,
) {
    fun encode(): ByteArray = SAVED_LOGIN_JSON.encodeToString(serializer(), this).encodeToByteArray()
}

private val SAVED_LOGIN_JSON: Json =
    Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = true
    }

private fun requireValidSavedLogin(
    identifier: String,
    password: String,
) {
    require(identifier.isNotBlank() && identifier.length <= MAX_SAVED_LOGIN_IDENTIFIER_CHARS) {
        "Saved login identifier is invalid"
    }
    require(identifier.none(Char::isControlCharacter)) {
        "Saved login identifier contains control characters"
    }
    require(password.isNotEmpty() && password.length <= MAX_SAVED_LOGIN_PASSWORD_CHARS) {
        "Saved login password is invalid"
    }
    require(password.none { it == '\u0000' }) { "Saved login password contains a forbidden character" }
}

private fun String.toCredentialRefOrNull(): SecureCredentialRef? =
    try {
        SecureCredentialRef(this)
    } catch (_: IllegalArgumentException) {
        null
    }

private fun Char.isControlCharacter(): Boolean = code < 0x20 || code == 0x7f
