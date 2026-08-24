package dev.dimension.flare.data.network.discourse.session

import dev.dimension.flare.data.database.SecureVaultReferenceDao
import dev.dimension.flare.data.database.SecureVaultReferenceEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Clock

private const val ACTIVE_SESSION_SLOT: String = "discourse.active-session"
private const val ACTIVE_SESSION_ENVELOPE_VERSION: Int = 1
private const val MAX_SESSION_ENVELOPE_BYTES: Int = 128 * 1024

/** Decrypted active-session snapshot returned only at the vault boundary. */
public data class PersistedDiscourseSession(
    public val credentialRef: SecureCredentialRef,
    public val accountId: String,
    public val username: String?,
    public val cookies: List<DiscourseCookieSnapshot>,
)

/** Persistence contract used by shared login, restore, checkpoint, and logout orchestration. */
public interface DiscourseSessionStore {
    public suspend fun replace(
        accountId: String,
        username: String?,
        cookies: List<DiscourseCookieSnapshot>,
    ): SecureCredentialRef

    public suspend fun restore(): PersistedDiscourseSession?

    public suspend fun clear(expectedCredentialRef: SecureCredentialRef? = null)
}

/**
 * Stores the complete Cookie snapshot as one encrypted platform-vault value.
 *
 * Room contains only the opaque vault reference plus public account display metadata. Restoring an
 * envelope cross-checks those public fields and runs the same strict Cookie validation used by the
 * live jar. Missing, malformed, oversized, or mismatched material is compare-deleted and treated as
 * logged out; there is no partial restore and no plaintext fallback.
 */
public class RoomDiscourseSessionStore(
    private val dao: SecureVaultReferenceDao,
    private val credentialStore: SecureCredentialStore,
    private val cookieValidator: DiscourseCookieStorage,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : DiscourseSessionStore {
    private val operationMutex: Mutex = Mutex()

    override suspend fun replace(
        accountId: String,
        username: String?,
        cookies: List<DiscourseCookieSnapshot>,
    ): SecureCredentialRef =
        operationMutex.withLock {
            validateSession(accountId, username, cookies)
            val encoded =
                PersistedSessionEnvelope(
                    version = ACTIVE_SESSION_ENVELOPE_VERSION,
                    accountId = accountId,
                    username = username,
                    cookies = cookies,
                ).encode()
            require(encoded.size <= MAX_SESSION_ENVELOPE_BYTES) {
                "Encrypted session envelope exceeds the persistence limit"
            }
            val newReference =
                try {
                    credentialStore.save(accountId, encoded)
                } finally {
                    encoded.fill(0)
                }
            val previous =
                try {
                    dao.replace(
                        SecureVaultReferenceEntity(
                            slot = ACTIVE_SESSION_SLOT,
                            credentialRef = newReference.value,
                            accountId = accountId,
                            username = username,
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
            newReference
        }

    override suspend fun restore(): PersistedDiscourseSession? =
        operationMutex.withLock {
            val entity = dao.get(ACTIVE_SESSION_SLOT) ?: return@withLock null
            val reference = entity.credentialRef.toCredentialRefOrNull()
            val restored = reference?.let { decodeSession(it, entity) }
            if (restored != null) return@withLock restored

            val removed = dao.consume(ACTIVE_SESSION_SLOT, entity.credentialRef)
            removed?.credentialRef?.toCredentialRefOrNull()?.let { deleteVaultValue(it) }
            null
        }

    override suspend fun clear(expectedCredentialRef: SecureCredentialRef?) {
        operationMutex.withLock {
            val current = dao.get(ACTIVE_SESSION_SLOT) ?: return@withLock
            if (expectedCredentialRef != null && current.credentialRef != expectedCredentialRef.value) {
                return@withLock
            }
            val removed = dao.consume(ACTIVE_SESSION_SLOT, current.credentialRef) ?: return@withLock
            removed.credentialRef.toCredentialRefOrNull()?.let { deleteVaultValue(it) }
        }
    }

    private suspend fun decodeSession(
        reference: SecureCredentialRef,
        entity: SecureVaultReferenceEntity,
    ): PersistedDiscourseSession? {
        val bytes =
            try {
                credentialStore.load(reference)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            } ?: return null
        return try {
            if (bytes.size !in 1..MAX_SESSION_ENVELOPE_BYTES) return null
            val envelope =
                try {
                    SESSION_VAULT_JSON.decodeFromString<PersistedSessionEnvelope>(
                        bytes.decodeToString(throwOnInvalidSequence = true),
                    )
                } catch (_: CharacterCodingException) {
                    null
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return null
            if (
                envelope.version != ACTIVE_SESSION_ENVELOPE_VERSION ||
                envelope.accountId != entity.accountId ||
                envelope.username != entity.username
            ) {
                return null
            }
            validateSession(envelope.accountId, envelope.username, envelope.cookies)
            PersistedDiscourseSession(
                credentialRef = reference,
                accountId = envelope.accountId,
                username = envelope.username,
                cookies = envelope.cookies,
            )
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            bytes.fill(0)
        }
    }

    private fun validateSession(
        accountId: String,
        username: String?,
        cookies: List<DiscourseCookieSnapshot>,
    ) {
        DiscourseSessionState.Authenticated(
            generation = 0L,
            accountId = accountId,
            username = username,
        )
        cookieValidator.requireValidSnapshot(cookies)
        val now = nowEpochMillis()
        require(
            cookies.any {
                it.name == "_t" &&
                    it.value.isNotEmpty() &&
                    (it.expiresAtEpochMillis == null || it.expiresAtEpochMillis > now)
            },
        ) {
            "An authenticated session must contain the Discourse session cookie"
        }
    }

    private suspend fun deleteVaultValue(reference: SecureCredentialRef) {
        withContext(NonCancellable) {
            try {
                credentialStore.delete(reference)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // An unreachable encrypted value is preferable to retaining a database reference.
            }
        }
    }
}

/** Coordinates encrypted persistence with the generation-bound in-memory session manager. */
public class DiscourseSessionLifecycle(
    private val sessionManager: DiscourseSessionManager,
    private val sessionStore: DiscourseSessionStore,
) {
    /** Serializes every operation that can change ownership of the single persisted session slot. */
    private val operationMutex: Mutex = Mutex()

    /** Restores one valid vault snapshot; invalid or missing material remains a guest session. */
    public suspend fun restore(): Boolean =
        operationMutex.withLock {
            val expectedGeneration = sessionManager.state.value.generation
            val persisted = sessionStore.restore() ?: return@withLock false
            return@withLock try {
                sessionManager.startAuthenticatedSession(
                    accountId = persisted.accountId,
                    username = persisted.username,
                    credentialRef = persisted.credentialRef,
                    cookieSnapshot = persisted.cookies,
                    expectedGeneration = expectedGeneration,
                )
                true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                sessionStore.clear(persisted.credentialRef)
                false
            }
        }

    /** Atomically persists and activates cookies obtained from an authenticated browser exchange. */
    public suspend fun activate(
        expectedGeneration: Long,
        accountId: String,
        username: String?,
        cookies: List<DiscourseCookieSnapshot>,
    ) {
        operationMutex.withLock {
            activateLocked(expectedGeneration, accountId, username, cookies)
        }
    }

    /**
     * Activates one browser-authenticated owner and runs its request-bound cleanup without releasing
     * [operationMutex] between those steps.
     *
     * A second lifecycle operation therefore cannot replace the freshly persisted session before the
     * browser's duplicate `_t` is cleared. A direct in-memory replacement can still race this method,
     * but [block] is entered immediately after activation and is never skipped because of that race;
     * the trailing owner check then rejects stale success without deleting the replacement's vault.
     */
    internal suspend fun <T> activateAndRunForAuthenticatedOwner(
        expectedGeneration: Long,
        accountId: String,
        username: String?,
        cookies: List<DiscourseCookieSnapshot>,
        block: suspend (DiscourseSessionState.Authenticated) -> T,
    ): T =
        operationMutex.withLock {
            val active = activateLocked(expectedGeneration, accountId, username, cookies)
            val result = block(active)
            requireCurrentAuthenticatedOwner(active.generation, active.accountId)
            result
        }

    /** Writes the current bounded Cookie snapshot after a server-side session refresh. */
    public suspend fun checkpoint(): Boolean =
        operationMutex.withLock {
            val state =
                sessionManager.state.value as? DiscourseSessionState.Authenticated
                    ?: return@withLock false
            val cookies = sessionManager.cookieStorage.snapshot()
            requireCurrentGeneration(state.generation)
            val reference = sessionStore.replace(state.accountId, state.username, cookies)
            var committed = false
            try {
                committed =
                    sessionManager.updateAuthenticatedCredentialRefIfMatches(
                        expectedGeneration = state.generation,
                        expectedAccountId = state.accountId,
                        expectedCredentialRef = state.credentialRef,
                        credentialRef = reference,
                    )
                committed
            } finally {
                if (!committed) {
                    withContext(NonCancellable) {
                        sessionStore.clear(reference)
                    }
                }
            }
        }

    /**
     * Runs a browser handoff step only while the exact persisted-session owner remains active.
     *
     * Browser cleanup suspends and can therefore overlap another login. Holding [operationMutex]
     * prevents lifecycle-managed replacement until cleanup completes, while the second owner check
     * also detects direct in-memory session replacement before the caller can report success.
     */
    internal suspend fun <T> runForAuthenticatedOwner(
        expectedGeneration: Long,
        expectedAccountId: String,
        block: suspend () -> T,
    ): T =
        operationMutex.withLock {
            requireCurrentAuthenticatedOwner(expectedGeneration, expectedAccountId)
            val result = block()
            requireCurrentAuthenticatedOwner(expectedGeneration, expectedAccountId)
            result
        }

    /**
     * Makes the persisted owner unreachable before publishing a guest session.
     *
     * Room or vault invalidation can fail independently of the in-memory transition. Keeping the
     * authenticated owner unchanged on that failure avoids a false logged-out state whose retained
     * reference would authenticate again on the next process start. The generation CAS then protects
     * a direct in-memory replacement that might win while persistence is suspending.
     */
    public suspend fun logout() {
        withContext(NonCancellable) {
            operationMutex.withLock {
                val current = sessionManager.state.value
                val credentialRef =
                    (current as? DiscourseSessionState.Authenticated)?.credentialRef
                sessionStore.clear(credentialRef)
                sessionManager.logoutIfGeneration(current.generation)
            }
        }
    }

    /**
     * Clears only the generation still owned by an incomplete authentication handoff.
     *
     * The exact vault reference is invalidated before the in-memory CAS. A persistence failure keeps
     * the authenticated owner and its Cookie jar intact instead of publishing a guest state that can
     * be silently reversed by [restore]. A replacement login must acquire this lifecycle mutex to
     * publish a new reference, while the trailing generation CAS also preserves any direct in-memory
     * replacement that wins while the store operation suspends.
     */
    internal suspend fun logoutIfGeneration(expectedGeneration: Long): Boolean =
        withContext(NonCancellable) {
            operationMutex.withLock {
                val current = sessionManager.state.value
                if (current.generation != expectedGeneration) return@withLock false
                val credentialRef =
                    (current as? DiscourseSessionState.Authenticated)?.credentialRef
                sessionStore.clear(credentialRef)
                sessionManager.logoutIfGeneration(expectedGeneration)
            }
        }

    private suspend fun activateLocked(
        expectedGeneration: Long,
        accountId: String,
        username: String?,
        cookies: List<DiscourseCookieSnapshot>,
    ): DiscourseSessionState.Authenticated {
        requireCurrentGeneration(expectedGeneration)
        val reference = sessionStore.replace(accountId, username, cookies)
        var activated = false
        try {
            sessionManager.startAuthenticatedSession(
                accountId = accountId,
                username = username,
                credentialRef = reference,
                cookieSnapshot = cookies,
                expectedGeneration = expectedGeneration,
            )
            activated = true
            val current = sessionManager.state.value
            val active = current as? DiscourseSessionState.Authenticated
            if (
                active?.generation != expectedGeneration + 1L ||
                active.accountId != accountId
            ) {
                throw StaleDiscourseSessionException(expectedGeneration + 1L, current.generation)
            }
            return active
        } finally {
            if (!activated) {
                withContext(NonCancellable) {
                    sessionStore.clear(reference)
                }
            }
        }
    }

    private fun requireCurrentGeneration(expectedGeneration: Long) {
        val actualGeneration = sessionManager.state.value.generation
        if (actualGeneration != expectedGeneration) {
            throw StaleDiscourseSessionException(expectedGeneration, actualGeneration)
        }
    }

    private fun requireCurrentAuthenticatedOwner(
        expectedGeneration: Long,
        expectedAccountId: String,
    ) {
        val current = sessionManager.state.value
        val authenticated = current as? DiscourseSessionState.Authenticated
        if (
            authenticated?.generation != expectedGeneration ||
            authenticated.accountId != expectedAccountId
        ) {
            throw StaleDiscourseSessionException(expectedGeneration, current.generation)
        }
    }
}

@Serializable
private data class PersistedSessionEnvelope(
    val version: Int,
    val accountId: String,
    val username: String?,
    val cookies: List<DiscourseCookieSnapshot>,
) {
    fun encode(): ByteArray = SESSION_VAULT_JSON.encodeToString(serializer(), this).encodeToByteArray()
}

private val SESSION_VAULT_JSON: Json =
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
