package dev.dimension.flare.data.network.discourse.composer

import dev.dimension.flare.data.database.ComposerDraftDao
import dev.dimension.flare.data.database.ComposerDraftEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class DiscourseDraftStoreTest {
    @Test
    fun incompleteDraftsRoundTripAndNewerRevisionSurvivesConditionalDelete() =
        runTest {
            val store = RoomDiscourseDraftStore(FakeComposerDraftDao())
            val target = DiscourseComposerTarget.NewTopic(categoryId = 8L)

            val first =
                store.save(
                    accountId = ACCOUNT_ID,
                    target = target,
                    title = null,
                    raw = "",
                    tags = listOf("", "kotlin", "kotlin"),
                    updatedAtEpochMillis = 10L,
                )
            val second =
                store.save(
                    accountId = ACCOUNT_ID,
                    target = target,
                    title = "Half-written title",
                    raw = "Partial body",
                    tags = listOf("kotlin"),
                    updatedAtEpochMillis = 20L,
                )

            assertEquals(1L, first.revision)
            assertEquals(listOf("", "kotlin", "kotlin"), first.tags)
            assertEquals(2L, second.revision)
            assertFalse(store.deleteIfRevision(ACCOUNT_ID, target, first.revision))
            assertEquals(second, store.load(ACCOUNT_ID, target))
            assertTrue(store.deleteIfRevision(ACCOUNT_ID, target, second.revision))
            assertNull(store.load(ACCOUNT_ID, target))
        }

    @Test
    fun accountPartitionsAndPersistentBoundsAreIndependent() =
        runTest {
            val dao = FakeComposerDraftDao()
            val store = RoomDiscourseDraftStore(dao = dao, maxEntriesPerAccount = 2)
            val targets =
                listOf(
                    DiscourseComposerTarget.Reply(1L),
                    DiscourseComposerTarget.Reply(2L),
                    DiscourseComposerTarget.Reply(3L),
                )
            targets.forEachIndexed { index, target ->
                store.save(
                    accountId = ACCOUNT_ID,
                    target = target,
                    raw = "draft-$index",
                    updatedAtEpochMillis = index.toLong(),
                )
            }
            store.save(
                accountId = OTHER_ACCOUNT_ID,
                target = targets.first(),
                raw = "other account",
                updatedAtEpochMillis = 0L,
            )

            assertNull(store.load(ACCOUNT_ID, targets.first()))
            assertEquals(listOf(targets[2], targets[1]), store.list(ACCOUNT_ID).map { it.target })
            assertEquals("other account", store.load(OTHER_ACCOUNT_ID, targets.first())?.raw)
            assertEquals(2, dao.countForAccount(ACCOUNT_ID))
            assertEquals(1, dao.countForAccount(OTHER_ACCOUNT_ID))
        }

    @Test
    fun sessionChangesDoNotImplicitlyDeleteMemoryDrafts() =
        runTest {
            val store = MemoryDiscourseDraftStore()
            val target = DiscourseComposerTarget.Reply(topicId = 42L)
            val saved =
                store.save(
                    accountId = ACCOUNT_ID,
                    target = target,
                    raw = "Preserve me",
                    updatedAtEpochMillis = 1L,
                )

            // Draft persistence has no session dependency by design. Login/logout owners may replace
            // cookies and credentials without gaining an implicit draft deletion operation.
            assertEquals(saved, store.load(ACCOUNT_ID, target))
            assertEquals(emptyList(), store.list(OTHER_ACCOUNT_ID))
        }

    @Test
    fun memoryStoreDetachesMutableTagsWithoutBreakingRevisionCas() =
        runTest {
            val store = MemoryDiscourseDraftStore()
            val target = DiscourseComposerTarget.NewTopic(categoryId = 8L)
            val callerTags = mutableListOf("kotlin", "security")
            val first =
                store.save(
                    accountId = ACCOUNT_ID,
                    target = target,
                    title = "Detached tags",
                    raw = "First revision",
                    tags = callerTags,
                    updatedAtEpochMillis = 1L,
                )

            callerTags[0] = "mutated-source"
            first.tags.unsafeMutableView()[0] = "mutated-save-result"
            val loadedFirst = checkNotNull(store.load(ACCOUNT_ID, target))
            loadedFirst.tags.unsafeMutableView()[0] = "mutated-load-result"
            val listedFirst = store.list(ACCOUNT_ID).single()
            listedFirst.tags.unsafeMutableView()[0] = "mutated-list-result"

            val durableFirst = checkNotNull(store.load(ACCOUNT_ID, target))
            assertEquals(listOf("kotlin", "security"), durableFirst.tags)
            assertEquals(first.revision, durableFirst.revision)

            val second =
                store.save(
                    accountId = ACCOUNT_ID,
                    target = target,
                    title = "Detached tags",
                    raw = "Second revision",
                    tags = mutableListOf("compose", "room"),
                    updatedAtEpochMillis = 2L,
                )
            second.tags.unsafeMutableView()[0] = "mutated-second-result"

            assertFalse(store.deleteIfRevision(ACCOUNT_ID, target, first.revision))
            assertEquals(listOf("compose", "room"), store.load(ACCOUNT_ID, target)?.tags)
            assertTrue(store.deleteIfRevision(ACCOUNT_ID, target, second.revision))
            assertNull(store.load(ACCOUNT_ID, target))
        }

    private companion object {
        const val ACCOUNT_ID: String = "42"
        const val OTHER_ACCOUNT_ID: String = "84"
    }
}

@Suppress("UNCHECKED_CAST")
private fun List<String>.unsafeMutableView(): MutableList<String> = this as MutableList<String>

private class FakeComposerDraftDao : ComposerDraftDao {
    private val entries = mutableMapOf<Pair<String, String>, ComposerDraftEntity>()

    override suspend fun get(
        accountId: String,
        draftKey: String,
    ): ComposerDraftEntity? = entries[accountId to draftKey]

    override suspend fun listForAccount(accountId: String): List<ComposerDraftEntity> =
        entries.values
            .filter { it.accountId == accountId }
            .sortedWith(compareByDescending<ComposerDraftEntity> { it.updatedAtEpochMillis }.thenByDescending { it.draftKey })

    override suspend fun upsert(entity: ComposerDraftEntity) {
        entries[entity.accountId to entity.draftKey] = entity
    }

    override suspend fun delete(
        accountId: String,
        draftKey: String,
    ) {
        entries.remove(accountId to draftKey)
    }

    override suspend fun deleteIfRevisionMatches(
        accountId: String,
        draftKey: String,
        expectedRevision: Long,
    ): Int {
        val key = accountId to draftKey
        if (entries[key]?.revision != expectedRevision) return 0
        entries.remove(key)
        return 1
    }

    override suspend fun pruneToNewest(
        accountId: String,
        maxEntries: Int,
    ) {
        val retained =
            listForAccount(accountId)
                .take(maxEntries)
                .mapTo(mutableSetOf()) { it.draftKey }
        entries.keys.removeAll { (entryAccountId, draftKey) ->
            entryAccountId == accountId && draftKey !in retained
        }
    }

    override suspend fun countForAccount(accountId: String): Int = entries.keys.count { it.first == accountId }
}
