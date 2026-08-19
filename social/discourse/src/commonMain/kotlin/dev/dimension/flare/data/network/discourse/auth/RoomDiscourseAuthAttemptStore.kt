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

private const val PENDING_AUTH_SLOT: String = "discourse.pending-auth"
private const val PENDING_AUTH_VAULT_ACCOUNT: String = "discourse.pending-auth-envelope"
private const val PENDING_AUTH_ENVELOPE_VERSION: Int = 1
private const val MAX_PENDING_AUTH_ENVELOPE_BYTES: Int = 4 * 1024

/**
 * Persists pending browser authorization without placing its nonce or client id in Room.
 *
 * Room owns only two opaque references: [SecureVaultReferenceEntity.credentialRef] points to this
 * class's encrypted metadata envelope and [SecureVaultReferenceEntity.relatedCredentialRef] points
 * to the one-use RSA private key. Keeping the second reference in Room permits deterministic key
 * cleanup when an envelope is missing or corrupt, while neither column contains credential bytes.
 *
 * Vault and Room writes cannot form a platform-wide transaction. [operationMutex] serializes this
 * process, DAO compare-and-delete protects replacement races, and every pre-commit vault write is
 * removed on failure. A crash may leave an unreachable vault value, but can never make a stale
 * callback consume a newer attempt or expose plaintext in the database.
 */
public class RoomDiscourseAuthAttemptStore(
    private val dao: SecureVaultReferenceDao,
    private val credentialStore: SecureCredentialStore,
) : DiscourseAuthAttemptStore {
    private val operationMutex: Mutex = Mutex()

    override suspend fun replace(attempt: DiscourseAuthAttempt): DiscourseAuthAttempt? =
        operationMutex.withLock {
            val encoded = PendingAuthEnvelope.from(attempt).encode()
            val metadataRef =
                try {
                    credentialStore.save(PENDING_AUTH_VAULT_ACCOUNT, encoded)
                } finally {
                    encoded.fill(0)
                }

            val previous =
                try {
                    dao.replace(
                        SecureVaultReferenceEntity(
                            slot = PENDING_AUTH_SLOT,
                            credentialRef = metadataRef.value,
                            relatedCredentialRef = attempt.privateKeyRef.value,
                            createdAtEpochMillis = attempt.createdAtEpochMillis,
                            expiresAtEpochMillis = attempt.expiresAtEpochMillis,
                        ),
                    )
                } catch (cancellation: CancellationException) {
                    deleteVaultValue(metadataRef)
                    throw cancellation
                } catch (failure: Throwable) {
                    deleteVaultValue(metadataRef)
                    throw failure
                }

            previous?.let { readAndDeleteReplacedEnvelope(it) }
        }

    override suspend fun peek(): DiscourseAuthAttempt? =
        operationMutex.withLock {
            val entity = dao.get(PENDING_AUTH_SLOT) ?: return@withLock null
            readEnvelope(entity) ?: run {
                removeCorruptEntity(entity)
                null
            }
        }

    override suspend fun consume(expectedId: String): DiscourseAuthAttempt? =
        operationMutex.withLock {
            val entity = dao.get(PENDING_AUTH_SLOT) ?: return@withLock null
            val attempt =
                readEnvelope(entity) ?: run {
                    removeCorruptEntity(entity)
                    return@withLock null
                }
            if (attempt.id != expectedId) return@withLock null

            val consumed = dao.consume(PENDING_AUTH_SLOT, entity.credentialRef) ?: return@withLock null
            deleteVaultValue(SecureCredentialRef(consumed.credentialRef))
            attempt
        }

    private suspend fun readAndDeleteReplacedEnvelope(entity: SecureVaultReferenceEntity): DiscourseAuthAttempt? {
        val attempt = readEnvelope(entity)
        deleteVaultValue(SecureCredentialRef(entity.credentialRef))
        if (attempt == null) {
            entity.relatedCredentialRef?.let { deleteVaultValue(SecureCredentialRef(it)) }
        }
        return attempt
    }

    private suspend fun readEnvelope(entity: SecureVaultReferenceEntity): DiscourseAuthAttempt? {
        val metadataRef = entity.credentialRef.toCredentialRefOrNull() ?: return null
        val bytes =
            try {
                credentialStore.load(metadataRef)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            } ?: return null
        return try {
            if (bytes.size !in 1..MAX_PENDING_AUTH_ENVELOPE_BYTES) return null
            val envelope =
                try {
                    PENDING_AUTH_JSON.decodeFromString<PendingAuthEnvelope>(
                        bytes.decodeToString(throwOnInvalidSequence = true),
                    )
                } catch (_: CharacterCodingException) {
                    null
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return null
            val attempt = envelope.toAttemptOrNull() ?: return null
            if (
                attempt.privateKeyRef.value != entity.relatedCredentialRef ||
                attempt.createdAtEpochMillis != entity.createdAtEpochMillis ||
                attempt.expiresAtEpochMillis != entity.expiresAtEpochMillis
            ) {
                return null
            }
            attempt
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun removeCorruptEntity(entity: SecureVaultReferenceEntity) {
        val removed = dao.consume(PENDING_AUTH_SLOT, entity.credentialRef) ?: return
        removed.credentialRef.toCredentialRefOrNull()?.let { deleteVaultValue(it) }
        removed.relatedCredentialRef?.toCredentialRefOrNull()?.let { deleteVaultValue(it) }
    }

    private suspend fun deleteVaultValue(reference: SecureCredentialRef) {
        withContext(NonCancellable) {
            try {
                credentialStore.delete(reference)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Cleanup is best effort and never logs a private vault reference.
            }
        }
    }
}

@Serializable
private data class PendingAuthEnvelope(
    val version: Int,
    val id: String,
    val privateKeyRef: String,
    val nonce: String,
    val clientId: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    fun encode(): ByteArray = PENDING_AUTH_JSON.encodeToString(serializer(), this).encodeToByteArray()

    fun toAttemptOrNull(): DiscourseAuthAttempt? {
        if (version != PENDING_AUTH_ENVELOPE_VERSION) return null
        return try {
            DiscourseAuthAttempt(
                id = id,
                privateKeyRef = SecureCredentialRef(privateKeyRef),
                nonce = nonce,
                clientId = clientId,
                createdAtEpochMillis = createdAtEpochMillis,
                expiresAtEpochMillis = expiresAtEpochMillis,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        fun from(attempt: DiscourseAuthAttempt): PendingAuthEnvelope =
            PendingAuthEnvelope(
                version = PENDING_AUTH_ENVELOPE_VERSION,
                id = attempt.id,
                privateKeyRef = attempt.privateKeyRef.value,
                nonce = attempt.nonce,
                clientId = attempt.clientId,
                createdAtEpochMillis = attempt.createdAtEpochMillis,
                expiresAtEpochMillis = attempt.expiresAtEpochMillis,
            )
    }
}

private val PENDING_AUTH_JSON: Json =
    Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = true
    }

private fun String.toCredentialRefOrNull(): SecureCredentialRef? =
    try {
        SecureCredentialRef(this)
    } catch (_: IllegalArgumentException) {
        null
    }
