package dev.dimension.flare.data.network.discourse.realtime

import dev.dimension.flare.data.database.MessageBusCursorDao
import dev.dimension.flare.data.network.discourse.session.requireValidAccountId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val LATEST_CHANNEL: String = "/latest"
private const val NEW_CHANNEL: String = "/new"
private const val DEFAULT_MAX_TOPICS_PER_ACCOUNT: Int = 64
private const val DEFAULT_MAX_TOPICS_GLOBALLY: Int = 128
private val NOTIFICATION_CHANNEL = Regex("^/notification/([1-9][0-9]{0,18})$")
private val TOPIC_CHANNEL = Regex("^/topic/([1-9][0-9]{0,18})(?:/reactions)?$")

/**
 * Monotonic per-account cursor ownership for Linux.do MessageBus subscriptions.
 *
 * A missing cursor means that the coordinator must perform its ordinary REST catch-up before
 * opening long polling. Implementations return the authoritative maximum from [advance], rather
 * than echoing the candidate, so delayed and duplicate deliveries cannot move a subscription
 * backwards. This store accepts only the fixed channels used by FlareDo v1; accepting an arbitrary
 * server-provided string would let untrusted input create unbounded persistent keys.
 */
public interface DiscourseMessageBusCursorStore {
    /** Returns the last accepted non-negative message ID, or null when no checkpoint exists. */
    public suspend fun read(
        accountId: String,
        channel: String,
    ): Long?

    /** Atomically advances one checkpoint and returns the authoritative non-decreasing value. */
    public suspend fun advance(
        accountId: String,
        channel: String,
        messageId: Long,
    ): DiscourseMessageBusCursorAdvance

    /** Removes all volatile and durable checkpoints owned by exactly one account partition. */
    public suspend fun clearAccount(accountId: String)
}

/** Authoritative cursor plus whether this caller uniquely won the compare-and-set. */
public data class DiscourseMessageBusCursorAdvance(
    val cursor: Long,
    val advanced: Boolean,
) {
    init {
        require(cursor >= 0L) { "Discourse MessageBus cursor must not be negative" }
    }
}

/**
 * Process-local cursor storage used by default and for every non-notification subscription.
 *
 * Topic and reaction channels are one logical least-recently-used entry. The paired ownership
 * prevents visiting an unbounded number of topics from growing the process map forever and avoids
 * retaining an orphan reaction cursor after its topic cursor is evicted. Fixed feed channels are
 * not part of this browsing-history budget.
 */
public class MemoryDiscourseMessageBusCursorStore(
    private val maxTopicsPerAccount: Int = DEFAULT_MAX_TOPICS_PER_ACCOUNT,
    private val maxTopicsGlobally: Int = DEFAULT_MAX_TOPICS_GLOBALLY,
) : DiscourseMessageBusCursorStore {
    private data class Identity(
        val accountId: String,
        val channel: String,
    )

    private data class TopicIdentity(
        val accountId: String,
        val topicId: Long,
    )

    private val mutex: Mutex = Mutex()
    private val cursors: MutableMap<Identity, Long> = mutableMapOf()
    private val topicLru: LinkedHashMap<TopicIdentity, Unit> = linkedMapOf()

    init {
        require(maxTopicsPerAccount in 1..DEFAULT_MAX_TOPICS_PER_ACCOUNT) {
            "Per-account MessageBus topic cursor limit is invalid"
        }
        require(maxTopicsGlobally in 1..DEFAULT_MAX_TOPICS_GLOBALLY) {
            "Global MessageBus topic cursor limit is invalid"
        }
    }

    override suspend fun read(
        accountId: String,
        channel: String,
    ): Long? {
        requireValidCursorIdentity(accountId, channel)
        return mutex.withLock {
            cursors[Identity(accountId, channel)]?.also {
                channel.topicIdOrNull()?.let { topicId -> touchTopic(TopicIdentity(accountId, topicId)) }
            }
        }
    }

    override suspend fun advance(
        accountId: String,
        channel: String,
        messageId: Long,
    ): DiscourseMessageBusCursorAdvance {
        requireValidCursorIdentity(accountId, channel)
        requireValidMessageId(messageId)
        return mutex.withLock {
            val identity = Identity(accountId, channel)
            val previous = cursors[identity]
            val authoritative = maxOf(previous ?: messageId, messageId)
            val result =
                DiscourseMessageBusCursorAdvance(
                    cursor = authoritative,
                    advanced = previous == null || messageId > previous,
                )
            cursors[identity] = authoritative
            channel.topicIdOrNull()?.let { topicId ->
                touchTopic(TopicIdentity(accountId, topicId))
                pruneTopics(accountId)
            }
            result
        }
    }

    override suspend fun clearAccount(accountId: String) {
        requireValidAccountId(accountId)
        mutex.withLock {
            cursors.keys.removeAll { it.accountId == accountId }
            topicLru.keys.removeAll { it.accountId == accountId }
        }
    }

    private fun touchTopic(identity: TopicIdentity) {
        topicLru.remove(identity)
        topicLru[identity] = Unit
    }

    private fun pruneTopics(accountId: String) {
        while (topicLru.keys.count { it.accountId == accountId } > maxTopicsPerAccount) {
            val oldestForAccount = topicLru.keys.first { it.accountId == accountId }
            evictTopic(oldestForAccount)
        }
        while (topicLru.size > maxTopicsGlobally) {
            evictTopic(topicLru.keys.first())
        }
    }

    private fun evictTopic(identity: TopicIdentity) {
        topicLru.remove(identity)
        cursors.keys.removeAll { cursor ->
            cursor.accountId == identity.accountId && cursor.channel.topicIdOrNull() == identity.topicId
        }
    }
}

/**
 * Room-backed notification checkpoint plus a process-local store for every other known channel.
 *
 * Only `/notification/{accountId}` is durable. `/latest`, `/new`, topic, and reaction cursors are
 * deliberately delegated to [memory], because they can be reconstructed by the foreground REST
 * catch-up and would otherwise grow the database with browsing history. The notification suffix
 * must exactly equal the authenticated account partition, preventing one account from observing or
 * modifying another account's checkpoint.
 */
public class RoomDiscourseMessageBusCursorStore(
    private val dao: MessageBusCursorDao,
    private val memory: DiscourseMessageBusCursorStore = MemoryDiscourseMessageBusCursorStore(),
) : DiscourseMessageBusCursorStore {
    override suspend fun read(
        accountId: String,
        channel: String,
    ): Long? {
        requireValidCursorIdentity(accountId, channel)
        return if (channel.isPersistentNotificationFor(accountId)) {
            dao.get(accountId, channel)?.messageId
        } else {
            memory.read(accountId, channel)
        }
    }

    override suspend fun advance(
        accountId: String,
        channel: String,
        messageId: Long,
    ): DiscourseMessageBusCursorAdvance {
        requireValidCursorIdentity(accountId, channel)
        requireValidMessageId(messageId)
        return if (channel.isPersistentNotificationFor(accountId)) {
            dao.advance(accountId, channel, messageId).let { result ->
                DiscourseMessageBusCursorAdvance(
                    cursor = result.cursor,
                    advanced = result.advanced,
                )
            }
        } else {
            memory.advance(accountId, channel, messageId)
        }
    }

    override suspend fun clearAccount(accountId: String) {
        requireValidAccountId(accountId)
        // Logout cleanup must finish even when cancelling the generation that initiated it.
        withContext(NonCancellable) {
            dao.deleteForAccount(accountId)
            memory.clearAccount(accountId)
        }
    }
}

/** Host factory kept beside the policy so platform modules cannot accidentally persist all channels. */
public fun roomDiscourseMessageBusCursorStore(
    dao: MessageBusCursorDao,
    memory: DiscourseMessageBusCursorStore = MemoryDiscourseMessageBusCursorStore(),
): DiscourseMessageBusCursorStore = RoomDiscourseMessageBusCursorStore(dao = dao, memory = memory)

private fun requireValidCursorIdentity(
    accountId: String,
    channel: String,
) {
    requireValidAccountId(accountId)
    val notificationAccountId = channel.notificationAccountIdOrNull()
    val recognized =
        channel == LATEST_CHANNEL ||
            channel == NEW_CHANNEL ||
            channel.isValidTopicChannel() ||
            notificationAccountId != null
    require(recognized) { "Unsupported Discourse MessageBus channel" }
    if (notificationAccountId != null) {
        require(notificationAccountId == accountId) {
            "Notification channel does not belong to the account partition"
        }
    }
}

private fun requireValidMessageId(messageId: Long) {
    require(messageId >= 0L) { "Discourse MessageBus message id must not be negative" }
}

private fun String.notificationAccountIdOrNull(): String? =
    NOTIFICATION_CHANNEL
        .matchEntire(this)
        ?.groupValues
        ?.get(1)
        ?.takeIf { it.toLongOrNull()?.let { value -> value > 0L } == true }

private fun String.isValidTopicChannel(): Boolean = topicIdOrNull() != null

private fun String.topicIdOrNull(): Long? =
    TOPIC_CHANNEL
        .matchEntire(this)
        ?.groupValues
        ?.get(1)
        ?.toLongOrNull()
        ?.takeIf { it > 0L }

private fun String.isPersistentNotificationFor(accountId: String): Boolean = notificationAccountIdOrNull() == accountId
