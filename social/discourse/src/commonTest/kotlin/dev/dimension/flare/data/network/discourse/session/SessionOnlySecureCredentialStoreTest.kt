package dev.dimension.flare.data.network.discourse.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

internal class SessionOnlySecureCredentialStoreTest {
    @Test
    fun referencesCannotResolveAcrossStoreInstances() =
        runTest {
            val firstStore = SessionOnlySecureCredentialStore()
            val secondStore = SessionOnlySecureCredentialStore()
            try {
                val staleReference = firstStore.save("account", byteArrayOf(1))
                val currentReference = secondStore.save("account", byteArrayOf(2))

                kotlin.test.assertNotEquals(staleReference, currentReference)
                assertNull(secondStore.load(staleReference))
            } finally {
                firstStore.close()
                secondStore.close()
            }
        }

    @Test
    fun copiesAtBothBoundariesAndDeletesValues() =
        runTest {
            val store = SessionOnlySecureCredentialStore()
            val input = byteArrayOf(1, 2, 3, 4)
            val reference = store.save(accountId = "account-42", secret = input)
            input.fill(9)

            val firstRead = requireNotNull(store.load(reference))
            assertContentEquals(byteArrayOf(1, 2, 3, 4), firstRead)
            firstRead.fill(8)
            assertContentEquals(byteArrayOf(1, 2, 3, 4), store.load(reference))

            store.delete(reference)
            assertNull(store.load(reference))
        }

    @Test
    fun clearKeepsStoreReusableButCloseIsTerminal() =
        runTest {
            val store = SessionOnlySecureCredentialStore()
            val first = store.save("account-42", byteArrayOf(1))
            store.clear()
            assertNull(store.load(first))

            val second = store.save("account-42", byteArrayOf(2))
            store.close()
            assertNull(store.load(second))
            assertFailsWith<IllegalStateException> {
                store.save("account-42", byteArrayOf(3))
            }
        }

    @Test
    fun deleteDoesNotZeroASecretWhileLoadCopiesIt() =
        runTest {
            assertReadSurvivesConcurrentRemoval { store, reference ->
                store.delete(reference)
            }
        }

    @Test
    fun clearDoesNotZeroASecretWhileLoadCopiesIt() =
        runTest {
            val store =
                assertReadSurvivesConcurrentRemoval { store, _ ->
                    store.clear()
                }

            val replacement = store.save("replacement", byteArrayOf(8, 9))
            assertContentEquals(byteArrayOf(8, 9), store.load(replacement))
        }

    @Test
    fun closeDoesNotZeroASecretWhileLoadCopiesItAndRemainsTerminal() =
        runTest {
            val store =
                assertReadSurvivesConcurrentRemoval { store, _ ->
                    store.close()
                }

            assertFailsWith<IllegalStateException> {
                store.save("late-account", byteArrayOf(8, 9))
            }
        }

    private suspend fun assertReadSurvivesConcurrentRemoval(
        remove: suspend (SessionOnlySecureCredentialStore, SecureCredentialRef) -> Unit,
    ): SessionOnlySecureCredentialStore =
        coroutineScope {
            val leaseAcquired = CompletableDeferred<Unit>()
            val continueCopy = CompletableDeferred<Unit>()
            val expected = byteArrayOf(11, 22, 33, 44)
            val store =
                SessionOnlySecureCredentialStore { _ ->
                    leaseAcquired.complete(Unit)
                    continueCopy.await()
                }
            val reference = store.save("account-42", expected)
            val read = async { store.load(reference) }

            leaseAcquired.await()
            remove(store, reference)
            assertNull(store.load(reference))

            continueCopy.complete(Unit)
            assertContentEquals(expected, read.await())
            store
        }
}
