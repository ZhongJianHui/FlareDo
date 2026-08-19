package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.database.FlareDoDatabase
import dev.dimension.flare.data.database.createJvmFlareDoDatabase
import dev.dimension.flare.data.network.discourse.session.SecureCredentialRef
import dev.dimension.flare.data.network.discourse.session.SessionOnlySecureCredentialStore
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RoomDiscourseAuthAttemptStoreJvmTest {
    @Test
    fun metadataStaysInVaultAndAttemptConsumptionIsCompareAndDelete() =
        runTest {
            withStore { database, vault, store ->
                val firstKey = vault.save("pending-key", byteArrayOf(1, 2, 3))
                val secondKey = vault.save("pending-key", byteArrayOf(4, 5, 6))
                val first = attempt("attempt-one", firstKey, createdAt = 1_000L)
                val second = attempt("attempt-two", secondKey, createdAt = 2_000L)

                assertNull(store.replace(first))
                val firstEntity = assertNotNull(database.secureVaultReferenceDao().get(PENDING_SLOT))
                assertEquals(firstKey.value, firstEntity.relatedCredentialRef)
                assertEquals(null, firstEntity.accountId)
                assertEquals(null, firstEntity.username)
                assertEquals(first, store.peek())

                assertEquals(first, store.replace(second))
                assertNull(vault.load(SecureCredentialRef(firstEntity.credentialRef)))
                assertContentEquals(byteArrayOf(1, 2, 3), vault.load(firstKey))
                assertNull(store.consume(first.id))
                assertEquals(second, store.peek())

                val secondEntity = assertNotNull(database.secureVaultReferenceDao().get(PENDING_SLOT))
                assertEquals(second, store.consume(second.id))
                assertNull(database.secureVaultReferenceDao().get(PENDING_SLOT))
                assertNull(vault.load(SecureCredentialRef(secondEntity.credentialRef)))
                assertContentEquals(byteArrayOf(4, 5, 6), vault.load(secondKey))
            }
        }

    @Test
    fun missingEnvelopeFailsClosedAndDeletesItsOrphanedPrivateKey() =
        runTest {
            withStore { database, vault, store ->
                val privateKey = vault.save("pending-key", byteArrayOf(7, 8, 9))
                store.replace(attempt("attempt-corrupt", privateKey, createdAt = 5_000L))
                val entity = assertNotNull(database.secureVaultReferenceDao().get(PENDING_SLOT))

                vault.delete(SecureCredentialRef(entity.credentialRef))

                assertNull(store.peek())
                assertNull(database.secureVaultReferenceDao().get(PENDING_SLOT))
                assertNull(vault.load(privateKey))
            }
        }

    private suspend fun withStore(
        block: suspend (
            FlareDoDatabase,
            SessionOnlySecureCredentialStore,
            RoomDiscourseAuthAttemptStore,
        ) -> Unit,
    ) {
        val directory = Files.createTempDirectory("flaredo-auth-room-test-")
        val database = createJvmFlareDoDatabase(directory.resolve("auth.db"))
        val vault = SessionOnlySecureCredentialStore()
        try {
            block(
                database,
                vault,
                RoomDiscourseAuthAttemptStore(database.secureVaultReferenceDao(), vault),
            )
        } finally {
            vault.close()
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun attempt(
        id: String,
        privateKeyRef: SecureCredentialRef,
        createdAt: Long,
    ): DiscourseAuthAttempt =
        DiscourseAuthAttempt(
            id = id,
            privateKeyRef = privateKeyRef,
            nonce = "nonce-$id",
            clientId = "client-$id",
            createdAtEpochMillis = createdAt,
            expiresAtEpochMillis = createdAt + 600_000L,
        )

    private companion object {
        const val PENDING_SLOT: String = "discourse.pending-auth"
    }
}
