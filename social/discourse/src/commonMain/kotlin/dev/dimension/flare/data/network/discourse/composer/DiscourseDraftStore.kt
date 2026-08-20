package dev.dimension.flare.data.network.discourse.composer

import dev.dimension.flare.data.database.ComposerDraftDao
import dev.dimension.flare.data.database.ComposerDraftEntity
import dev.dimension.flare.data.network.discourse.model.discourseJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Stable destination of a local composer draft.
 *
 * The key intentionally contains only public numeric forum identities. It is partitioned by the
 * account ID at the storage layer, so two accounts can edit the same topic without sharing text.
 */
@Serializable
public sealed interface DiscourseComposerTarget {
    /** Application-owned key used in Room and state restoration. */
    public val stableKey: String

    /** A new topic, optionally assigned to a category. */
    @Serializable
    public data class NewTopic(
        val categoryId: Long? = null,
    ) : DiscourseComposerTarget {
        init {
            require(categoryId == null || categoryId > 0L) { "Composer category id must be positive" }
        }

        override val stableKey: String = "new-topic:category:${categoryId ?: "none"}"
    }

    /** A reply to a topic, optionally linked to one visible post number. */
    @Serializable
    public data class Reply(
        val topicId: Long,
        val replyToPostNumber: Int? = null,
    ) : DiscourseComposerTarget {
        init {
            require(topicId > 0L) { "Composer topic id must be positive" }
            require(replyToPostNumber == null || replyToPostNumber > 0) {
                "Reply-to post number must be positive"
            }
        }

        override val stableKey: String = "topic:$topicId:reply:${replyToPostNumber ?: "root"}"
    }

    /** Editing an existing post; both database ID and visible number remain explicit. */
    @Serializable
    public data class Edit(
        val topicId: Long,
        val postId: Long,
        val postNumber: Int,
    ) : DiscourseComposerTarget {
        init {
            require(topicId > 0L) { "Composer topic id must be positive" }
            require(postId > 0L) { "Composer post id must be positive" }
            require(postNumber > 0) { "Composer post number must be positive" }
        }

        override val stableKey: String = "topic:$topicId:edit-post:$postId"
    }
}

/**
 * Editable local content, never an offline send queue.
 *
 * No delivery status, retry timestamp, cookie, token, or upload bytes can be represented here.
 * Authentication expiry and logout deliberately leave this value untouched. A draft is removed
 * only by an explicit user discard or a compare-and-delete after confirmed publication.
 */
@Serializable
public data class DiscourseComposerDraft(
    val accountId: String,
    val target: DiscourseComposerTarget,
    val title: String? = null,
    val raw: String,
    val tags: List<String> = emptyList(),
    val revision: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        requireValidComposerAccountId(accountId)
        validateComposerDraftStorage(title = title, raw = raw, tags = tags)
        require(revision > 0L) { "Composer draft revision must be positive" }
        require(updatedAtEpochMillis >= 0L) { "Composer draft timestamp cannot be negative" }
    }

    /** Stable application key; account partitioning remains a separate storage dimension. */
    public val stableKey: String
        get() = target.stableKey

    /**
     * Returns a value whose collection storage is independent of this instance.
     *
     * Kotlin's read-only [List] interface does not prove that its backing object is immutable. Draft
     * stores use this copy at every in-memory ownership boundary so an unsafe cast by a caller cannot
     * mutate the revision snapshot retained for compare-and-delete.
     */
    internal fun detachedCopy(): DiscourseComposerDraft = copy(tags = tags.toList())
}

/** Restart-capable local draft persistence; implementations must serialize writes per account/key. */
public interface DiscourseDraftStore {
    public suspend fun load(
        accountId: String,
        target: DiscourseComposerTarget,
    ): DiscourseComposerDraft?

    public suspend fun list(accountId: String): List<DiscourseComposerDraft>

    /** Saves a new revision and returns the exact value made durable. */
    public suspend fun save(
        accountId: String,
        target: DiscourseComposerTarget,
        title: String? = null,
        raw: String,
        tags: List<String> = emptyList(),
        updatedAtEpochMillis: Long,
    ): DiscourseComposerDraft

    /** Explicit user discard. Session transitions must never call this implicitly. */
    public suspend fun delete(
        accountId: String,
        target: DiscourseComposerTarget,
    )

    /** Deletes only the revision that was confirmed published by Linux.do. */
    public suspend fun deleteIfRevision(
        accountId: String,
        target: DiscourseComposerTarget,
        expectedRevision: Long,
    ): Boolean
}

/** Process-local fallback for tests and hosts that have not supplied their Room database. */
public class MemoryDiscourseDraftStore(
    private val maxEntriesPerAccount: Int = DEFAULT_COMPOSER_DRAFT_LIMIT,
) : DiscourseDraftStore {
    private val mutex = Mutex()
    private val entries = mutableMapOf<DraftIdentity, DiscourseComposerDraft>()

    init {
        requireValidDraftLimit(maxEntriesPerAccount)
    }

    override suspend fun load(
        accountId: String,
        target: DiscourseComposerTarget,
    ): DiscourseComposerDraft? {
        requireValidComposerAccountId(accountId)
        return mutex.withLock {
            entries[DraftIdentity(accountId, target.stableKey)]?.detachedCopy()
        }
    }

    override suspend fun list(accountId: String): List<DiscourseComposerDraft> {
        requireValidComposerAccountId(accountId)
        return mutex.withLock {
            entries.values
                .filter { it.accountId == accountId }
                .sortedWith(compareByDescending<DiscourseComposerDraft> { it.updatedAtEpochMillis }.thenByDescending { it.stableKey })
                .map(DiscourseComposerDraft::detachedCopy)
        }
    }

    override suspend fun save(
        accountId: String,
        target: DiscourseComposerTarget,
        title: String?,
        raw: String,
        tags: List<String>,
        updatedAtEpochMillis: Long,
    ): DiscourseComposerDraft {
        requireValidComposerAccountId(accountId)
        val ownedTags = tags.toList()
        validateComposerDraftStorage(title = title, raw = raw, tags = ownedTags)
        require(updatedAtEpochMillis >= 0L) { "Composer draft timestamp cannot be negative" }
        return mutex.withLock {
            val identity = DraftIdentity(accountId, target.stableKey)
            val nextRevision = entries[identity]?.revision.nextDraftRevision()
            val ownedDraft =
                DiscourseComposerDraft(
                    accountId = accountId,
                    target = target,
                    title = title,
                    raw = raw,
                    tags = ownedTags,
                    revision = nextRevision,
                    updatedAtEpochMillis = updatedAtEpochMillis,
                )
            entries[identity] = ownedDraft
            pruneMemoryAccount(accountId)
            ownedDraft.detachedCopy()
        }
    }

    override suspend fun delete(
        accountId: String,
        target: DiscourseComposerTarget,
    ) {
        requireValidComposerAccountId(accountId)
        mutex.withLock { entries.remove(DraftIdentity(accountId, target.stableKey)) }
    }

    override suspend fun deleteIfRevision(
        accountId: String,
        target: DiscourseComposerTarget,
        expectedRevision: Long,
    ): Boolean {
        requireValidComposerAccountId(accountId)
        require(expectedRevision > 0L) { "Expected composer revision must be positive" }
        return mutex.withLock {
            val identity = DraftIdentity(accountId, target.stableKey)
            val current = entries[identity]
            if (current?.revision != expectedRevision) return@withLock false
            entries.remove(identity)
            true
        }
    }

    private fun pruneMemoryAccount(accountId: String) {
        val accountEntries = entries.filterKeys { it.accountId == accountId }
        if (accountEntries.size <= maxEntriesPerAccount) return
        val retained =
            accountEntries.values
                .sortedWith(compareByDescending<DiscourseComposerDraft> { it.updatedAtEpochMillis }.thenByDescending { it.stableKey })
                .take(maxEntriesPerAccount)
                .mapTo(mutableSetOf(), DiscourseComposerDraft::stableKey)
        entries.keys.removeAll { it.accountId == accountId && it.stableKey !in retained }
    }
}

/** Room-backed draft persistence shared by Android, Apple, Linux, and Windows hosts. */
public class RoomDiscourseDraftStore(
    private val dao: ComposerDraftDao,
    private val maxEntriesPerAccount: Int = DEFAULT_COMPOSER_DRAFT_LIMIT,
) : DiscourseDraftStore {
    private val mutex = Mutex()

    init {
        requireValidDraftLimit(maxEntriesPerAccount)
    }

    override suspend fun load(
        accountId: String,
        target: DiscourseComposerTarget,
    ): DiscourseComposerDraft? {
        requireValidComposerAccountId(accountId)
        return mutex.withLock {
            dao.get(accountId, target.stableKey)?.decodeOrDeleteCorrupt()
        }
    }

    override suspend fun list(accountId: String): List<DiscourseComposerDraft> {
        requireValidComposerAccountId(accountId)
        return mutex.withLock {
            dao.listForAccount(accountId).mapNotNull { it.decodeOrDeleteCorrupt() }
        }
    }

    override suspend fun save(
        accountId: String,
        target: DiscourseComposerTarget,
        title: String?,
        raw: String,
        tags: List<String>,
        updatedAtEpochMillis: Long,
    ): DiscourseComposerDraft {
        requireValidComposerAccountId(accountId)
        val ownedTags = tags.toList()
        validateComposerDraftStorage(title = title, raw = raw, tags = ownedTags)
        require(updatedAtEpochMillis >= 0L) { "Composer draft timestamp cannot be negative" }
        return mutex.withLock {
            val existing = dao.get(accountId, target.stableKey)
            val nextRevision = existing?.revision.nextDraftRevision()
            val ownedDraft =
                DiscourseComposerDraft(
                    accountId = accountId,
                    target = target,
                    title = title,
                    raw = raw,
                    tags = ownedTags,
                    revision = nextRevision,
                    updatedAtEpochMillis = updatedAtEpochMillis,
                )
            val payload = discourseJson.encodeToString(ownedDraft)
            check(payload.length <= MAX_COMPOSER_DRAFT_PAYLOAD_CHARS) {
                "Serialized composer draft exceeds the local storage bound"
            }
            dao.upsertBounded(
                ComposerDraftEntity(
                    accountId = accountId,
                    draftKey = target.stableKey,
                    payload = payload,
                    revision = nextRevision,
                    updatedAtEpochMillis = updatedAtEpochMillis,
                ),
                maxEntriesPerAccount,
            )
            ownedDraft.detachedCopy()
        }
    }

    override suspend fun delete(
        accountId: String,
        target: DiscourseComposerTarget,
    ) {
        requireValidComposerAccountId(accountId)
        mutex.withLock { dao.delete(accountId, target.stableKey) }
    }

    override suspend fun deleteIfRevision(
        accountId: String,
        target: DiscourseComposerTarget,
        expectedRevision: Long,
    ): Boolean {
        requireValidComposerAccountId(accountId)
        require(expectedRevision > 0L) { "Expected composer revision must be positive" }
        return mutex.withLock {
            dao.deleteIfRevisionMatches(accountId, target.stableKey, expectedRevision) == 1
        }
    }

    private suspend fun ComposerDraftEntity.decodeOrDeleteCorrupt(): DiscourseComposerDraft? {
        val decoded =
            try {
                if (payload.length > MAX_COMPOSER_DRAFT_PAYLOAD_CHARS) {
                    null
                } else {
                    discourseJson.decodeFromString<DiscourseComposerDraft>(payload)
                }
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        if (
            decoded == null ||
            decoded.accountId != accountId ||
            decoded.stableKey != draftKey ||
            decoded.revision != revision ||
            decoded.updatedAtEpochMillis != updatedAtEpochMillis
        ) {
            dao.deleteIfRevisionMatches(accountId, draftKey, revision)
            return null
        }
        return decoded
    }
}

/** Factory used by production host Koin modules. */
public fun roomDiscourseDraftStore(
    dao: ComposerDraftDao,
    maxEntriesPerAccount: Int = DEFAULT_COMPOSER_DRAFT_LIMIT,
): DiscourseDraftStore = RoomDiscourseDraftStore(dao, maxEntriesPerAccount)

/** Persistent draft bound per public account partition. */
public const val DEFAULT_COMPOSER_DRAFT_LIMIT: Int = 32

internal const val MAX_COMPOSER_RAW_CHARS: Int = 2_000_000
internal const val MAX_COMPOSER_TITLE_CHARS: Int = 512
internal const val MAX_COMPOSER_TAGS: Int = 20
internal const val MAX_COMPOSER_TAG_CHARS: Int = 256
private const val MAX_COMPOSER_ACCOUNT_ID_CHARS: Int = 128
private const val MAX_COMPOSER_DRAFT_LIMIT: Int = 256
private const val MAX_COMPOSER_DRAFT_PAYLOAD_CHARS: Int = 2_100_000

private data class DraftIdentity(
    val accountId: String,
    val stableKey: String,
)

internal fun validateComposerDraftStorage(
    title: String?,
    raw: String,
    tags: List<String>,
) {
    require(raw.length <= MAX_COMPOSER_RAW_CHARS) { "Composer content is too long" }
    require(raw.none(Char::isForbiddenComposerControl)) {
        "Composer content contains unsupported control characters"
    }
    title?.let {
        require(it.length <= MAX_COMPOSER_TITLE_CHARS) { "Composer title is too long" }
        require(it.none(Char::isForbiddenComposerControl)) {
            "Composer title contains unsupported control characters"
        }
    }
    require(tags.size <= MAX_COMPOSER_TAGS) { "Too many composer tags" }
    tags.forEach { tag ->
        require(tag.length <= MAX_COMPOSER_TAG_CHARS) { "Composer tag is too long" }
        require(tag.none(Char::isControlCharacter)) { "Composer tag contains control characters" }
    }
}

internal fun requireValidComposerAccountId(accountId: String) {
    require(accountId.isNotBlank()) { "Composer account id must not be blank" }
    require(accountId.length <= MAX_COMPOSER_ACCOUNT_ID_CHARS) { "Composer account id is too long" }
    require(accountId.none(Char::isControlCharacter)) { "Composer account id contains control characters" }
}

private fun requireValidDraftLimit(limit: Int) {
    require(limit in 1..MAX_COMPOSER_DRAFT_LIMIT) { "Composer draft limit is invalid" }
}

private fun Long?.nextDraftRevision(): Long {
    val current = this ?: 0L
    check(current < Long.MAX_VALUE) { "Composer draft revision space is exhausted" }
    return current + 1L
}

private fun Char.isControlCharacter(): Boolean = code < 0x20 || code == 0x7f

private fun Char.isForbiddenComposerControl(): Boolean = isControlCharacter() && this != '\n' && this != '\r' && this != '\t'
