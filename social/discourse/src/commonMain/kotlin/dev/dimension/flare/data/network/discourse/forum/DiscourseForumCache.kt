package dev.dimension.flare.data.network.discourse.forum

import dev.dimension.flare.data.database.ForumCacheEntryDao
import dev.dimension.flare.data.database.ForumCacheEntryEntity
import dev.dimension.flare.data.network.discourse.model.discourseJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Non-secret cache contract used by the anonymous forum repository.
 *
 * Implementations store only already-sanitized UI models. Cookies, CSRF tokens, user API keys,
 * secure-vault bytes, request headers, and raw HTTP payloads cannot enter this API. A cache hit is
 * returned as its original network snapshot; the repository is solely responsible for marking it
 * stale after a request fails.
 */
public interface DiscourseForumCache {
    public suspend fun getFeed(
        feed: DiscourseForumFeed,
        page: Int,
    ): DiscourseForumFeedPage?

    public suspend fun putFeed(value: DiscourseForumFeedPage)

    public suspend fun getCategories(): DiscourseForumCategories?

    public suspend fun putCategories(value: DiscourseForumCategories)

    public suspend fun getTags(): DiscourseForumTags?

    public suspend fun putTags(value: DiscourseForumTags)

    public suspend fun getTopic(topicId: Long): DiscourseForumTopic?

    public suspend fun putTopic(value: DiscourseForumTopic)

    public suspend fun clear()
}

/**
 * Process-local bounded cache used by tests and by hosts that have not installed a Room database.
 *
 * Values are serialized before storage so tests exercise the same ownership and compatibility
 * boundary as the persistent adapter. This implementation is intentionally not presented as an
 * offline cache across restarts; production hosts should override it with
 * [roomDiscourseForumCache].
 */
public class MemoryDiscourseForumCache(
    private val maxEntries: Int = DEFAULT_FORUM_CACHE_ENTRY_LIMIT,
) : DiscourseForumCache {
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, ForumCacheEntryEntity>()

    init {
        requireValidEntryLimit(maxEntries)
    }

    override suspend fun getFeed(
        feed: DiscourseForumFeed,
        page: Int,
    ): DiscourseForumFeedPage? =
        read(feedCacheKey(feed, page)) { payload ->
            ForumCacheCodec.decodeFeed(payload).also { cached ->
                require(cached.feed == feed && cached.page == page) {
                    "Forum feed cache identity does not match its key"
                }
            }
        }

    override suspend fun putFeed(value: DiscourseForumFeedPage) {
        requireFreshSnapshot(value.source, value.fallbackFailure)
        write(
            key = feedCacheKey(value.feed, value.page),
            updatedAtEpochMillis = value.updatedAtEpochMillis,
            payload = ForumCacheCodec.encode(value),
        )
    }

    override suspend fun getCategories(): DiscourseForumCategories? = read(CATEGORIES_CACHE_KEY, ForumCacheCodec::decodeCategories)

    override suspend fun putCategories(value: DiscourseForumCategories) {
        requireFreshSnapshot(value.source, value.fallbackFailure)
        write(CATEGORIES_CACHE_KEY, value.updatedAtEpochMillis, ForumCacheCodec.encode(value))
    }

    override suspend fun getTags(): DiscourseForumTags? = read(TAGS_CACHE_KEY, ForumCacheCodec::decodeTags)

    override suspend fun putTags(value: DiscourseForumTags) {
        requireFreshSnapshot(value.source, value.fallbackFailure)
        write(TAGS_CACHE_KEY, value.updatedAtEpochMillis, ForumCacheCodec.encode(value))
    }

    override suspend fun getTopic(topicId: Long): DiscourseForumTopic? =
        read(topicCacheKey(topicId)) { payload ->
            ForumCacheCodec.decodeTopic(payload).also { cached ->
                require(cached.topicId == topicId) { "Forum topic cache identity does not match its key" }
            }
        }

    override suspend fun putTopic(value: DiscourseForumTopic) {
        requireFreshSnapshot(value.source, value.fallbackFailure)
        write(topicCacheKey(value.topicId), value.updatedAtEpochMillis, ForumCacheCodec.encode(value))
    }

    override suspend fun clear() {
        mutex.withLock { entries.clear() }
    }

    private suspend fun <T> read(
        key: String,
        decode: (String) -> T,
    ): T? {
        val entry = mutex.withLock { entries[key] } ?: return null
        return decodeCachePayload(entry.payload, decode) {
            mutex.withLock {
                if (entries[key]?.payload == entry.payload) entries.remove(key)
            }
        }
    }

    private suspend fun write(
        key: String,
        updatedAtEpochMillis: Long,
        payload: String,
    ) {
        requireNetworkSnapshot(payload, updatedAtEpochMillis)
        if (payload.length > MAX_FORUM_CACHE_PAYLOAD_CHARS) return
        val entry =
            ForumCacheEntryEntity(
                accountId = ANONYMOUS_FORUM_CACHE_ACCOUNT_ID,
                cacheKey = key,
                payload = payload,
                updatedAtEpochMillis = updatedAtEpochMillis,
            )
        mutex.withLock {
            entries[key] = entry
            while (entries.size > maxEntries) {
                val oldest =
                    entries.values.minWithOrNull(
                        compareBy<ForumCacheEntryEntity> { it.updatedAtEpochMillis }
                            .thenBy { it.cacheKey },
                    ) ?: break
                entries.remove(oldest.cacheKey)
            }
        }
    }
}

/** Room-backed cache that survives process restarts and enforces an atomic per-account row bound. */
public class RoomDiscourseForumCache(
    private val dao: ForumCacheEntryDao,
    private val accountId: String = ANONYMOUS_FORUM_CACHE_ACCOUNT_ID,
    private val maxEntries: Int = DEFAULT_FORUM_CACHE_ENTRY_LIMIT,
) : DiscourseForumCache {
    private val mutex = Mutex()

    init {
        requireValidAccountId(accountId)
        requireValidEntryLimit(maxEntries)
    }

    override suspend fun getFeed(
        feed: DiscourseForumFeed,
        page: Int,
    ): DiscourseForumFeedPage? =
        read(feedCacheKey(feed, page)) { payload ->
            ForumCacheCodec.decodeFeed(payload).also { cached ->
                require(cached.feed == feed && cached.page == page) {
                    "Forum feed cache identity does not match its key"
                }
            }
        }

    override suspend fun putFeed(value: DiscourseForumFeedPage) {
        requireFreshSnapshot(value.source, value.fallbackFailure)
        write(
            key = feedCacheKey(value.feed, value.page),
            updatedAtEpochMillis = value.updatedAtEpochMillis,
            payload = ForumCacheCodec.encode(value),
        )
    }

    override suspend fun getCategories(): DiscourseForumCategories? = read(CATEGORIES_CACHE_KEY, ForumCacheCodec::decodeCategories)

    override suspend fun putCategories(value: DiscourseForumCategories) {
        requireFreshSnapshot(value.source, value.fallbackFailure)
        write(CATEGORIES_CACHE_KEY, value.updatedAtEpochMillis, ForumCacheCodec.encode(value))
    }

    override suspend fun getTags(): DiscourseForumTags? = read(TAGS_CACHE_KEY, ForumCacheCodec::decodeTags)

    override suspend fun putTags(value: DiscourseForumTags) {
        requireFreshSnapshot(value.source, value.fallbackFailure)
        write(TAGS_CACHE_KEY, value.updatedAtEpochMillis, ForumCacheCodec.encode(value))
    }

    override suspend fun getTopic(topicId: Long): DiscourseForumTopic? =
        read(topicCacheKey(topicId)) { payload ->
            ForumCacheCodec.decodeTopic(payload).also { cached ->
                require(cached.topicId == topicId) { "Forum topic cache identity does not match its key" }
            }
        }

    override suspend fun putTopic(value: DiscourseForumTopic) {
        requireFreshSnapshot(value.source, value.fallbackFailure)
        write(topicCacheKey(value.topicId), value.updatedAtEpochMillis, ForumCacheCodec.encode(value))
    }

    override suspend fun clear() {
        mutex.withLock { dao.deleteForAccount(accountId) }
    }

    private suspend fun <T> read(
        key: String,
        decode: (String) -> T,
    ): T? {
        val entry = mutex.withLock { dao.get(accountId, key) } ?: return null
        if (entry.accountId != accountId || entry.cacheKey != key) return null
        return decodeCachePayload(entry.payload, decode) {
            mutex.withLock {
                dao.deleteIfPayloadMatches(accountId, key, entry.payload)
            }
        }
    }

    private suspend fun write(
        key: String,
        updatedAtEpochMillis: Long,
        payload: String,
    ) {
        requireNetworkSnapshot(payload, updatedAtEpochMillis)
        if (payload.length > MAX_FORUM_CACHE_PAYLOAD_CHARS) return
        val entity =
            ForumCacheEntryEntity(
                accountId = accountId,
                cacheKey = key,
                payload = payload,
                updatedAtEpochMillis = updatedAtEpochMillis,
            )
        mutex.withLock {
            dao.upsertBounded(entity, maxEntries)
        }
    }
}

/** Factory used by platform hosts when overriding the default in-memory Koin definition. */
public fun roomDiscourseForumCache(
    dao: ForumCacheEntryDao,
    accountId: String = ANONYMOUS_FORUM_CACHE_ACCOUNT_ID,
    maxEntries: Int = DEFAULT_FORUM_CACHE_ENTRY_LIMIT,
): DiscourseForumCache = RoomDiscourseForumCache(dao, accountId, maxEntries)

/** Public partition used before a future authenticated account cache is selected. */
public const val ANONYMOUS_FORUM_CACHE_ACCOUNT_ID: String = "anonymous"

/** Default persistent and process-local cache row bound. */
public const val DEFAULT_FORUM_CACHE_ENTRY_LIMIT: Int = 32

private const val MAX_FORUM_CACHE_ENTRY_LIMIT: Int = 512
private const val MAX_FORUM_CACHE_PAYLOAD_CHARS: Int = 2_000_000
private const val MAX_FORUM_CACHE_ACCOUNT_ID_CHARS: Int = 128
private const val CATEGORIES_CACHE_KEY: String = "categories"
private const val TAGS_CACHE_KEY: String = "tags"

private object ForumCacheCodec {
    fun encode(value: DiscourseForumFeedPage): String = discourseJson.encodeToString(value)

    fun encode(value: DiscourseForumCategories): String = discourseJson.encodeToString(value)

    fun encode(value: DiscourseForumTags): String = discourseJson.encodeToString(value)

    fun encode(value: DiscourseForumTopic): String = discourseJson.encodeToString(value)

    fun decodeFeed(payload: String): DiscourseForumFeedPage = discourseJson.decodeFromString(payload)

    fun decodeCategories(payload: String): DiscourseForumCategories = discourseJson.decodeFromString(payload)

    fun decodeTags(payload: String): DiscourseForumTags = discourseJson.decodeFromString(payload)

    fun decodeTopic(payload: String): DiscourseForumTopic = discourseJson.decodeFromString(payload)
}

private suspend fun <T> decodeCachePayload(
    payload: String,
    decode: (String) -> T,
    removeCorrupt: suspend () -> Unit,
): T? {
    if (payload.length > MAX_FORUM_CACHE_PAYLOAD_CHARS) {
        removeCorrupt()
        return null
    }
    return try {
        decode(payload)
    } catch (_: SerializationException) {
        removeCorrupt()
        null
    } catch (_: IllegalArgumentException) {
        removeCorrupt()
        null
    }
}

private fun feedCacheKey(
    feed: DiscourseForumFeed,
    page: Int,
): String {
    require(page >= 0) { "Forum cache page cannot be negative" }
    return "feed:${feed.stableKey}:page:$page"
}

private fun topicCacheKey(topicId: Long): String {
    require(topicId > 0L) { "Forum cache topic id must be positive" }
    return "topic:$topicId"
}

private fun requireNetworkSnapshot(
    payload: String,
    updatedAtEpochMillis: Long,
) {
    require(payload.isNotEmpty()) { "Forum cache payload must not be empty" }
    require(updatedAtEpochMillis >= 0L) { "Forum cache timestamp cannot be negative" }
}

private fun requireFreshSnapshot(
    source: DiscourseForumContentSource,
    fallbackFailure: DiscourseForumFailureKind?,
) {
    require(source == DiscourseForumContentSource.Network && fallbackFailure == null) {
        "A stale fallback must never replace the last successful forum cache snapshot"
    }
}

private fun requireValidEntryLimit(maxEntries: Int) {
    require(maxEntries in 1..MAX_FORUM_CACHE_ENTRY_LIMIT) { "Forum cache entry limit is invalid" }
}

private fun requireValidAccountId(accountId: String) {
    require(accountId.isNotBlank()) { "Forum cache account id must not be blank" }
    require(accountId.length <= MAX_FORUM_CACHE_ACCOUNT_ID_CHARS) { "Forum cache account id is too long" }
    require(accountId.none { it.code < 0x20 || it.code == 0x7f }) {
        "Forum cache account id contains control characters"
    }
}
