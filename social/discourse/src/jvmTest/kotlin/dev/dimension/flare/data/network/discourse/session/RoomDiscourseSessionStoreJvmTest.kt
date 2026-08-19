package dev.dimension.flare.data.network.discourse.session

import dev.dimension.flare.data.database.FlareDoDatabase
import dev.dimension.flare.data.database.createJvmFlareDoDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomDiscourseSessionStoreJvmTest {
    @Test
    fun activateRestoreCheckpointAndLogoutKeepOnlyOpaqueRoomReferences() =
        runTest {
            withStore { database, vault, manager, store ->
                val lifecycle = DiscourseSessionLifecycle(manager, store)
                lifecycle.activate(
                    expectedGeneration = manager.state.value.generation,
                    accountId = "42",
                    username = "member",
                    cookies = listOf(sessionCookie("first-cookie")),
                )

                val active = assertIs<DiscourseSessionState.Authenticated>(manager.state.value)
                val firstReference = assertNotNull(active.credentialRef)
                val roomRow = assertNotNull(database.secureVaultReferenceDao().get(ACTIVE_SLOT))
                assertEquals(firstReference.value, roomRow.credentialRef)
                assertEquals("42", roomRow.accountId)
                assertEquals("member", roomRow.username)
                assertFalse(roomRow.toString().contains("first-cookie"))

                manager.cookieStorage.importSnapshot(listOf(sessionCookie("refreshed-cookie")))
                assertTrue(lifecycle.checkpoint())
                val checkpointed = assertIs<DiscourseSessionState.Authenticated>(manager.state.value)
                val secondReference = assertNotNull(checkpointed.credentialRef)
                assertNotEquals(firstReference, secondReference)
                assertNull(vault.load(firstReference))

                val restoredManager = DiscourseSessionManager()
                assertTrue(DiscourseSessionLifecycle(restoredManager, store).restore())
                val restored = assertIs<DiscourseSessionState.Authenticated>(restoredManager.state.value)
                assertEquals("42", restored.accountId)
                assertEquals("member", restored.username)
                assertEquals(
                    "refreshed-cookie",
                    restoredManager.cookieStorage
                        .snapshot()
                        .single()
                        .value,
                )

                DiscourseSessionLifecycle(restoredManager, store).logout()
                assertIs<DiscourseSessionState.Guest>(restoredManager.state.value)
                assertNull(database.secureVaultReferenceDao().get(ACTIVE_SLOT))
                assertNull(vault.load(secondReference))
            }
        }

    @Test
    fun missingVaultValueFailsClosedAndRemovesRoomReference() =
        runTest {
            withStore { database, vault, _, store ->
                val reference = store.replace("7", null, listOf(sessionCookie("cookie")))
                vault.delete(reference)

                assertNull(store.restore())
                assertNull(database.secureVaultReferenceDao().get(ACTIVE_SLOT))
                assertFalse(DiscourseSessionLifecycle(DiscourseSessionManager(), store).restore())
            }
        }

    @Test
    fun logoutWaitsForInFlightActivationThenClearsItsRoomAndVaultState() =
        runTest {
            withPausingStore { database, vault, manager, lifecycle, store ->
                val gate = store.pauseNextReplace()
                val expectedGeneration = manager.state.value.generation
                val activation =
                    async {
                        lifecycle.activate(
                            expectedGeneration = expectedGeneration,
                            accountId = "42",
                            username = "member",
                            cookies = listOf(sessionCookie("login-cookie")),
                        )
                    }
                gate.saveStarted.await()

                val logout = launch { lifecycle.logout() }
                yield()
                val logoutCompletedBeforeActivation = logout.isCompleted

                gate.continueSave.complete(Unit)
                activation.await()
                logout.join()

                assertFalse(logoutCompletedBeforeActivation)
                assertIs<DiscourseSessionState.Guest>(manager.state.value)
                assertNull(database.secureVaultReferenceDao().get(ACTIVE_SLOT))
                vault.assertEverySavedReferenceWasDeleted()
            }
        }

    @Test
    fun logoutWaitsForInFlightCheckpointThenClearsItsRoomAndVaultState() =
        runTest {
            withPausingStore { database, vault, manager, lifecycle, store ->
                lifecycle.activate(
                    expectedGeneration = manager.state.value.generation,
                    accountId = "42",
                    username = "member",
                    cookies = listOf(sessionCookie("first-cookie")),
                )
                manager.cookieStorage.importSnapshot(listOf(sessionCookie("checkpoint-cookie")))

                val gate = store.pauseNextReplace()
                val checkpoint = async { lifecycle.checkpoint() }
                gate.saveStarted.await()

                val logout = launch { lifecycle.logout() }
                yield()
                val logoutCompletedBeforeCheckpoint = logout.isCompleted

                gate.continueSave.complete(Unit)
                assertTrue(checkpoint.await())
                logout.join()

                assertFalse(logoutCompletedBeforeCheckpoint)
                assertIs<DiscourseSessionState.Guest>(manager.state.value)
                assertNull(database.secureVaultReferenceDao().get(ACTIVE_SLOT))
                vault.assertEverySavedReferenceWasDeleted()
            }
        }

    private suspend fun withStore(
        block: suspend (
            FlareDoDatabase,
            SessionOnlySecureCredentialStore,
            DiscourseSessionManager,
            RoomDiscourseSessionStore,
        ) -> Unit,
    ) {
        val directory = Files.createTempDirectory("flaredo-session-room-test-")
        val database = createJvmFlareDoDatabase(directory.resolve("session.db"))
        val vault = SessionOnlySecureCredentialStore()
        val manager = DiscourseSessionManager()
        try {
            block(
                database,
                vault,
                manager,
                RoomDiscourseSessionStore(
                    dao = database.secureVaultReferenceDao(),
                    credentialStore = vault,
                    cookieValidator = manager.cookieStorage,
                    nowEpochMillis = { 10_000L },
                ),
            )
        } finally {
            vault.close()
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    private suspend fun withPausingStore(
        block: suspend (
            FlareDoDatabase,
            PausingSecureCredentialStore,
            DiscourseSessionManager,
            DiscourseSessionLifecycle,
            PausingDiscourseSessionStore,
        ) -> Unit,
    ) {
        val directory = Files.createTempDirectory("flaredo-session-race-test-")
        val database = createJvmFlareDoDatabase(directory.resolve("session.db"))
        val vault = PausingSecureCredentialStore()
        val manager = DiscourseSessionManager()
        val roomStore =
            RoomDiscourseSessionStore(
                dao = database.secureVaultReferenceDao(),
                credentialStore = vault,
                cookieValidator = manager.cookieStorage,
                nowEpochMillis = { 10_000L },
            )
        val store = PausingDiscourseSessionStore(roomStore)
        try {
            block(database, vault, manager, DiscourseSessionLifecycle(manager, store), store)
        } finally {
            vault.close()
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun sessionCookie(value: String): DiscourseCookieSnapshot =
        DiscourseCookieSnapshot(
            name = "_t",
            value = value,
            httpOnly = true,
        )

    private companion object {
        const val ACTIVE_SLOT: String = "discourse.active-session"
    }
}

private class PausingSecureCredentialStore :
    SecureCredentialStore,
    AutoCloseable {
    private val delegate = SessionOnlySecureCredentialStore()
    private val savedReferences = mutableListOf<SecureCredentialRef>()

    override suspend fun save(
        accountId: String,
        secret: ByteArray,
    ): SecureCredentialRef = delegate.save(accountId, secret).also(savedReferences::add)

    override suspend fun load(reference: SecureCredentialRef): ByteArray? = delegate.load(reference)

    override suspend fun delete(reference: SecureCredentialRef) {
        delegate.delete(reference)
    }

    suspend fun assertEverySavedReferenceWasDeleted() {
        savedReferences.forEach { reference -> assertNull(delegate.load(reference)) }
    }

    override fun close() {
        delegate.close()
    }
}

private class PausingDiscourseSessionStore(
    private val delegate: DiscourseSessionStore,
) : DiscourseSessionStore {
    data class ReplaceGate(
        val saveStarted: CompletableDeferred<Unit> = CompletableDeferred(),
        val continueSave: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private var nextReplaceGate: ReplaceGate? = null

    fun pauseNextReplace(): ReplaceGate =
        ReplaceGate().also { gate ->
            check(nextReplaceGate == null) { "A session replacement is already paused" }
            nextReplaceGate = gate
        }

    override suspend fun replace(
        accountId: String,
        username: String?,
        cookies: List<DiscourseCookieSnapshot>,
    ): SecureCredentialRef {
        nextReplaceGate?.also { gate ->
            nextReplaceGate = null
            gate.saveStarted.complete(Unit)
            gate.continueSave.await()
        }
        return delegate.replace(accountId, username, cookies)
    }

    override suspend fun restore(): PersistedDiscourseSession? = delegate.restore()

    override suspend fun clear(expectedCredentialRef: SecureCredentialRef?) {
        delegate.clear(expectedCredentialRef)
    }
}
