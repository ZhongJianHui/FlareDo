package dev.dimension.flare.data.network.discourse.session

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

internal class AppleKeychainCredentialStoreTest {
    @Test
    fun roundTripCopiesAtBothBoundaries() =
        runTest {
            val backend = FakeAppleKeychainBackend()
            val store = store(backend)
            val input = byteArrayOf(1, 2, 3)
            val reference = store.save("account", input)
            input.fill(0)

            val first = store.load(reference)!!
            assertContentEquals(byteArrayOf(1, 2, 3), first)
            first.fill(0)
            assertContentEquals(byteArrayOf(1, 2, 3), store.load(reference))
        }

    @Test
    fun duplicateReferenceIsRetriedWithoutOverwriting() =
        runTest {
            val backend = FakeAppleKeychainBackend(duplicateOnce = true)
            val store = store(backend)

            val reference = store.save("account", byteArrayOf(7))

            assertContentEquals(byteArrayOf(7), store.load(reference))
            assertContentEquals(ByteArray(16) { 2 }, referenceIdBytes(reference))
        }

    @Test
    fun missingAndDeletedValuesReturnNull() =
        runTest {
            val backend = FakeAppleKeychainBackend()
            val store = store(backend)
            val missing = SecureCredentialRef("apple-keychain-v1:${"09".repeat(16)}")
            assertNull(store.load(missing))

            val reference = store.save("account", byteArrayOf(9))
            store.delete(reference)
            assertNull(store.load(reference))
            store.delete(reference)
        }

    @Test
    fun backendUnavailableAndInvalidPayloadFailClosed() =
        runTest {
            val backend = FakeAppleKeychainBackend()
            val store = store(backend)
            backend.isAvailable = false
            assertFailsWith<AppleCredentialStoreException> {
                store.save("account", byteArrayOf(1))
            }

            backend.isAvailable = true
            val reference = store.save("account", byteArrayOf(1))
            backend.returnInvalidPayload = true
            assertFailsWith<AppleCredentialStoreException> { store.load(reference) }
        }

    @Test
    fun referenceCannotInjectKeychainAttributes() =
        runTest {
            val store = store(FakeAppleKeychainBackend())
            assertFailsWith<IllegalArgumentException> {
                store.load(SecureCredentialRef("apple-keychain-v1:${"01".repeat(16)};access-group=other"))
            }
        }

    private fun TestScope.store(backend: AppleKeychainBackend): AppleKeychainCredentialStore {
        var next = 1
        return AppleKeychainCredentialStore(
            backend = backend,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            randomBytes = { ByteArray(16) { next.toByte() }.also { next += 1 } },
            testMarker = Unit,
        )
    }
}

private class FakeAppleKeychainBackend(
    private var duplicateOnce: Boolean = false,
) : AppleKeychainBackend {
    private val values = mutableMapOf<String, ByteArray>()
    var isAvailable = true
    var returnInvalidPayload = false

    override fun add(
        referenceId: String,
        secret: ByteArray,
    ): AppleKeychainStatus {
        if (!isAvailable) return AppleKeychainStatus.UNAVAILABLE
        if (duplicateOnce) {
            duplicateOnce = false
            return AppleKeychainStatus.DUPLICATE
        }
        if (referenceId in values) return AppleKeychainStatus.DUPLICATE
        values[referenceId] = secret.copyOf()
        return AppleKeychainStatus.SUCCESS
    }

    override fun load(referenceId: String): AppleKeychainLoadResult {
        if (!isAvailable) return AppleKeychainLoadResult.Unavailable
        if (returnInvalidPayload) return AppleKeychainLoadResult.Found(byteArrayOf())
        return values[referenceId]
            ?.copyOf()
            ?.let(AppleKeychainLoadResult::Found)
            ?: AppleKeychainLoadResult.Missing
    }

    override fun delete(referenceId: String): AppleKeychainStatus {
        if (!isAvailable) return AppleKeychainStatus.UNAVAILABLE
        val removed = values.remove(referenceId) ?: return AppleKeychainStatus.NOT_FOUND
        removed.fill(0)
        return AppleKeychainStatus.SUCCESS
    }
}

private fun referenceIdBytes(reference: SecureCredentialRef): ByteArray {
    val hex = reference.value.substringAfter(':')
    return ByteArray(hex.length / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
