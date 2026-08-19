package dev.dimension.flare.data.network.discourse.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.random.Random

private const val MAX_CREDENTIAL_REFERENCE_LENGTH = 512
private const val MAX_ACCOUNT_ID_LENGTH = 512

/**
 * Opaque locator for a credential held by a platform security service.
 *
 * Only this reference, never the credential bytes, may be written to Room. A reference is a
 * locator rather than an authentication capability; callers must still treat it as private
 * metadata because it can reveal that an account exists on the device.
 */
@Serializable
@JvmInline
public value class SecureCredentialRef(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "A secure credential reference must not be blank" }
        require(value.length <= MAX_CREDENTIAL_REFERENCE_LENGTH) {
            "A secure credential reference is too long"
        }
        require(value.none(Char::isControlCharacter)) {
            "A secure credential reference must not contain control characters"
        }
    }
}

/**
 * Minimal platform-vault contract used by the shared session layer.
 *
 * Android Keystore, Apple Keychain, Windows DPAPI, and Linux Secret Service implementations are
 * supplied in the authentication stage. Implementations must return independent byte arrays from
 * [load], must never log credential contents, and must fail closed when their secure backend is
 * unavailable. The shared layer intentionally does not prescribe the credential encoding.
 */
public interface SecureCredentialStore {
    /** Stores a defensive copy of [secret] and returns its opaque vault reference. */
    public suspend fun save(
        accountId: String,
        secret: ByteArray,
    ): SecureCredentialRef

    /** Returns an independent copy of the stored bytes, or `null` when the reference is absent. */
    public suspend fun load(reference: SecureCredentialRef): ByteArray?

    /** Permanently removes the value identified by [reference]. */
    public suspend fun delete(reference: SecureCredentialRef)
}

/**
 * Process-only credential storage used when no platform vault is installed.
 *
 * This implementation deliberately has no persistence or snapshot API. It enables a Linux session
 * when Secret Service is unavailable without ever falling back to a plaintext file. Byte arrays
 * are copied at both boundaries, and retained buffers are overwritten when removed. Calling
 * [close] is terminal: later saves fail and later reads return `null`.
 */
public class SessionOnlySecureCredentialStore private constructor(
    private val maxCredentialBytes: Int,
    private val onReadLeaseAcquired: (suspend (SecureCredentialRef) -> Unit)?,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) : SecureCredentialStore,
    AutoCloseable {
    // Session-only references can remain in Room after a process exits. A per-instance namespace
    // makes every such stale reference miss instead of resolving to an unrelated new-process value.
    private val referenceNamespace: String = createSessionOnlyReferenceNamespace()

    private data class CredentialRecord(
        val accountId: String,
        val secret: ByteArray,
        val activeReaders: Int = 0,
    )

    private data class StoreState(
        val isClosed: Boolean = false,
        val nextReference: Long = 1L,
        val records: Map<SecureCredentialRef, CredentialRecord> = emptyMap(),
        val retiredRecords: Map<SecureCredentialRef, CredentialRecord> = emptyMap(),
    )

    private val mutableState: MutableStateFlow<StoreState> = MutableStateFlow(StoreState())

    public constructor(
        maxCredentialBytes: Int = DEFAULT_MAX_CREDENTIAL_BYTES,
    ) : this(
        maxCredentialBytes = maxCredentialBytes,
        onReadLeaseAcquired = null,
        constructorMarker = Unit,
    )

    /**
     * Deterministic concurrency-test seam invoked after a read lease is committed.
     *
     * The callback never receives credential material. Suspending it lets common tests interleave
     * deletion with a load at the exact point where the retained buffer must remain readable.
     */
    internal constructor(
        maxCredentialBytes: Int = DEFAULT_MAX_CREDENTIAL_BYTES,
        onReadLeaseAcquired: suspend (SecureCredentialRef) -> Unit,
    ) : this(
        maxCredentialBytes = maxCredentialBytes,
        onReadLeaseAcquired = onReadLeaseAcquired,
        constructorMarker = Unit,
    )

    init {
        require(maxCredentialBytes > 0) { "maxCredentialBytes must be positive" }
    }

    override suspend fun save(
        accountId: String,
        secret: ByteArray,
    ): SecureCredentialRef {
        requireValidAccountId(accountId)
        require(secret.isNotEmpty()) { "Credential bytes must not be empty" }
        require(secret.size <= maxCredentialBytes) { "Credential bytes exceed the in-memory limit" }

        val ownedSecret = secret.copyOf()
        var committed = false
        try {
            while (true) {
                val current = mutableState.value
                check(!current.isClosed) { "The session-only credential store is closed" }
                check(current.nextReference > 0L) { "Credential reference space is exhausted" }

                val reference = SecureCredentialRef("session:$referenceNamespace:${current.nextReference}")
                val candidate =
                    current.copy(
                        nextReference = current.nextReference.nextReference(),
                        records =
                            current.records +
                                (reference to CredentialRecord(accountId, ownedSecret)),
                    )
                if (mutableState.compareAndSet(current, candidate)) {
                    committed = true
                    return reference
                }
            }
        } finally {
            if (!committed) ownedSecret.fill(0)
        }
    }

    override suspend fun load(reference: SecureCredentialRef): ByteArray? {
        val leasedRecord = acquireReadLease(reference) ?: return null
        return try {
            onReadLeaseAcquired?.invoke(reference)
            leasedRecord.secret.copyOf()
        } finally {
            releaseReadLease(reference)
        }
    }

    override suspend fun delete(reference: SecureCredentialRef) {
        while (true) {
            val current = mutableState.value
            val removed = current.records[reference] ?: return
            val candidate = current.withRecordRetired(reference, removed)
            if (mutableState.compareAndSet(current, candidate)) {
                if (removed.activeReaders == 0) removed.secret.fill(0)
                return
            }
        }
    }

    /**
     * Removes every visible credential while keeping the store reusable.
     *
     * Buffers without readers are overwritten after the successful CAS. Buffers with readers are
     * made unreachable to new loads immediately and overwritten by the last reader to release its
     * lease. This ordering avoids both copying a buffer while it is being zeroed and erasing a CAS
     * candidate that another coroutine actually committed.
     */
    public fun clear() {
        while (true) {
            val current = mutableState.value
            if (current.records.isEmpty()) return

            val candidate = current.withAllRecordsRetired(isClosed = current.isClosed)
            if (mutableState.compareAndSet(current, candidate)) {
                current.records.values
                    .asSequence()
                    .filter { it.activeReaders == 0 }
                    .forEach { it.secret.fill(0) }
                return
            }
        }
    }

    /**
     * Removes all visible records and permanently closes this process-only store.
     *
     * Closure is linearized by one CAS, so a concurrent save is either included in the erase or
     * observes the terminal state and fails. Existing readers retain a private logical lease until
     * their copy completes; the last reader then overwrites the retired buffer. Heap snapshots or
     * runtime copies cannot be erased by Kotlin, so overwriting is deliberately best effort.
     */
    override fun close() {
        while (true) {
            val current = mutableState.value
            if (current.isClosed) return

            val candidate = current.withAllRecordsRetired(isClosed = true)
            if (mutableState.compareAndSet(current, candidate)) {
                current.records.values
                    .asSequence()
                    .filter { it.activeReaders == 0 }
                    .forEach { it.secret.fill(0) }
                return
            }
        }
    }

    /**
     * Commits a logical reader before exposing the retained byte array to [load].
     *
     * Removal operations move leased records to [StoreState.retiredRecords] instead of mutating
     * their buffers. A CAS loser discards only immutable map wrappers; it never mutates a shared
     * byte array.
     */
    private fun acquireReadLease(reference: SecureCredentialRef): CredentialRecord? {
        while (true) {
            val current = mutableState.value
            if (current.isClosed) return null
            val record = current.records[reference] ?: return null
            check(record.activeReaders < Int.MAX_VALUE) {
                "Too many concurrent credential readers"
            }

            val leasedRecord = record.copy(activeReaders = record.activeReaders + 1)
            val candidate = current.copy(records = current.records + (reference to leasedRecord))
            if (mutableState.compareAndSet(current, candidate)) return leasedRecord
        }
    }

    /** Releases one reader and overwrites a retired buffer after its final copy completes. */
    private fun releaseReadLease(reference: SecureCredentialRef) {
        while (true) {
            val current = mutableState.value
            val active = current.records[reference]
            if (active != null) {
                check(active.activeReaders > 0) { "Credential read lease underflow" }
                val candidate =
                    current.copy(
                        records =
                            current.records +
                                (reference to active.copy(activeReaders = active.activeReaders - 1)),
                    )
                if (mutableState.compareAndSet(current, candidate)) return
                continue
            }

            val retired =
                checkNotNull(current.retiredRecords[reference]) {
                    "Credential read lease lost its retained buffer"
                }
            check(retired.activeReaders > 0) { "Credential read lease underflow" }
            val isLastReader = retired.activeReaders == 1
            val decrementedRetired = retired.copy(activeReaders = retired.activeReaders - 1)
            val candidate =
                current.copy(
                    retiredRecords =
                        if (isLastReader) {
                            current.retiredRecords - reference
                        } else {
                            current.retiredRecords + (reference to decrementedRetired)
                        },
                )
            if (mutableState.compareAndSet(current, candidate)) {
                if (isLastReader) retired.secret.fill(0)
                return
            }
        }
    }

    private fun StoreState.withRecordRetired(
        reference: SecureCredentialRef,
        record: CredentialRecord,
    ): StoreState =
        copy(
            records = records - reference,
            retiredRecords =
                if (record.activeReaders == 0) {
                    retiredRecords
                } else {
                    retiredRecords + (reference to record)
                },
        )

    private fun StoreState.withAllRecordsRetired(isClosed: Boolean): StoreState =
        copy(
            isClosed = isClosed,
            records = emptyMap(),
            retiredRecords =
                retiredRecords + records.filterValues { it.activeReaders > 0 },
        )

    public companion object {
        public const val DEFAULT_MAX_CREDENTIAL_BYTES: Int = 128 * 1024
    }
}

private fun Long.nextReference(): Long = if (this == Long.MAX_VALUE) 0L else this + 1L

private fun createSessionOnlyReferenceNamespace(): String {
    val bytes = Random.Default.nextBytes(16)
    return try {
        bytes.joinToString(separator = "") { byte ->
            byte.toUByte().toString(radix = 16).padStart(2, '0')
        }
    } finally {
        bytes.fill(0)
    }
}

internal fun requireValidAccountId(accountId: String) {
    require(accountId.isNotBlank()) { "Account id must not be blank" }
    require(accountId.length <= MAX_ACCOUNT_ID_LENGTH) { "Account id is too long" }
    require(accountId.none(Char::isControlCharacter)) {
        "Account id must not contain control characters"
    }
}

internal fun Char.isControlCharacter(): Boolean = code < 0x20 || code == 0x7f
