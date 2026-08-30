package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.database.SecureVaultReferenceDao
import dev.dimension.flare.data.database.SecureVaultReferenceEntity
import dev.dimension.flare.data.network.discourse.session.SecureCredentialRef
import dev.dimension.flare.data.network.discourse.session.SessionOnlySecureCredentialStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class DiscourseSavedLoginStoreTest {
    @Test
    fun saveReplaceLoadAndClearKeepOnlyOpaqueRoomReference() =
        runTest {
            val dao = MemoryVaultReferenceDao()
            val vault = SessionOnlySecureCredentialStore()
            val store = RoomDiscourseSavedLoginStore(dao, vault) { 50L }
            try {
                store.save(" first@example.com ", "first-password")
                val firstEntity = checkNotNull(dao.entity)
                assertEquals("first@example.com", firstEntity.username)
                assertFalse(firstEntity.credentialRef.contains("password"))

                store.save("member", "second-password")
                val secondEntity = checkNotNull(dao.entity)
                assertFalse(firstEntity.credentialRef == secondEntity.credentialRef)
                assertNull(vault.load(SecureCredentialRef(firstEntity.credentialRef)))

                val restored = checkNotNull(store.load())
                try {
                    assertEquals("member", restored.identifier)
                    assertEquals("second-password", restored.copyPassword())
                    assertFalse(restored.toString().contains("member"))
                    assertFalse(restored.toString().contains("second-password"))
                } finally {
                    restored.close()
                }

                assertTrue(store.clear())
                assertNull(dao.entity)
                assertNull(vault.load(SecureCredentialRef(secondEntity.credentialRef)))
                assertFalse(store.clear())
            } finally {
                vault.close()
            }
        }

    @Test
    fun metadataMismatchConsumesStaleReferenceAndFailsClosed() =
        runTest {
            val dao = MemoryVaultReferenceDao()
            val vault = SessionOnlySecureCredentialStore()
            val store = RoomDiscourseSavedLoginStore(dao, vault) { 50L }
            try {
                store.save("member", "password")
                val original = checkNotNull(dao.entity)
                dao.entity = original.copy(username = "other")

                assertNull(store.load())
                assertNull(dao.entity)
                assertNull(vault.load(SecureCredentialRef(original.credentialRef)))
            } finally {
                vault.close()
            }
        }
}

private class MemoryVaultReferenceDao : SecureVaultReferenceDao {
    var entity: SecureVaultReferenceEntity? = null

    override suspend fun get(slot: String): SecureVaultReferenceEntity? = entity?.takeIf { it.slot == slot }

    override suspend fun upsert(entity: SecureVaultReferenceEntity) {
        this.entity = entity
    }

    override suspend fun deleteIfMatches(
        slot: String,
        expectedCredentialRef: String,
    ): Int {
        val current = entity
        if (current?.slot != slot || current.credentialRef != expectedCredentialRef) return 0
        entity = null
        return 1
    }

    override suspend fun delete(slot: String) {
        if (entity?.slot == slot) entity = null
    }
}
