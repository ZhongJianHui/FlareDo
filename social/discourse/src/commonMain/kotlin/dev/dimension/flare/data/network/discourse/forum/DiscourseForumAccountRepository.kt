package dev.dimension.flare.data.network.discourse.forum

import dev.dimension.flare.data.network.discourse.DiscourseDataSource
import dev.dimension.flare.data.network.discourse.error.DiscourseAuthenticationException
import dev.dimension.flare.data.network.discourse.model.DiscourseNotificationResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserActionsResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserSummaryResponse
import dev.dimension.flare.data.network.discourse.paging.DiscourseNotificationOffset
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Public account views and authenticated notification operations. */
public interface DiscourseForumAccountRepository {
    public suspend fun loadProfile(username: String): DiscourseForumProfile

    public suspend fun loadActivity(
        username: String,
        offset: Int = 0,
        knownItemKeys: Set<String> = emptySet(),
    ): DiscourseForumActivityPage

    public suspend fun loadNotifications(
        offset: DiscourseNotificationOffset = DiscourseNotificationOffset.Initial,
        knownIds: Set<Long> = emptySet(),
        limit: Int = 60,
    ): DiscourseForumNotificationPage

    /**
     * Marks one positive [notificationId], or every notification when it is null.
     * The supplied immutable snapshot is copied only after Linux.do confirms the mutation.
     */
    public suspend fun markNotificationsRead(
        current: DiscourseForumNotificationSnapshot,
        notificationId: Long? = null,
    ): DiscourseForumNotificationSnapshot
}

/**
 * Session-aware account repository.
 *
 * All multi-call reads and every notification operation run under one generation lease. No catch
 * block wraps these suspend calls: caller cancellation and `StaleDiscourseSessionException` retain
 * their exact types, while the transport continues to expose its sanitized Discourse failures.
 */
public class DefaultDiscourseForumAccountRepository internal constructor(
    private val remote: DiscourseForumAccountRemoteDataSource,
    private val mapper: DiscourseForumAccountMapper,
    private val sessionManager: DiscourseSessionManager,
) : DiscourseForumAccountRepository {
    public constructor(
        dataSource: DiscourseDataSource,
        mapper: DiscourseForumAccountMapper,
        sessionManager: DiscourseSessionManager,
    ) : this(
        remote = DefaultDiscourseForumAccountRemoteDataSource(dataSource),
        mapper = mapper,
        sessionManager = sessionManager,
    )

    override suspend fun loadProfile(username: String): DiscourseForumProfile {
        requireRepositoryUsername(username)
        return sessionManager.runForCurrentSession {
            // Public profiles remain browsable, but the username representing the active account
            // must also match its numeric identity. Usernames can be renamed or compared with
            // different casing; the numeric id is the stable authorization boundary.
            val expectedCurrentUserId =
                (this as? DiscourseSessionState.Authenticated)
                    ?.takeIf { it.username?.equals(username, ignoreCase = true) == true }
                    ?.requireAuthenticatedRecipientId()
            coroutineScope {
                val profile = async { remote.user(username) }
                val summary = async { remote.userSummary(username) }
                mapper
                    .mapProfile(
                        requestedUsername = username,
                        profileResponse = profile.await(),
                        summaryResponse = summary.await(),
                    ).also { mapped ->
                        if (expectedCurrentUserId != null && mapped.userId != expectedCurrentUserId) {
                            throw forumProtocolFailure()
                        }
                    }
            }
        }
    }

    override suspend fun loadActivity(
        username: String,
        offset: Int,
        knownItemKeys: Set<String>,
    ): DiscourseForumActivityPage {
        requireRepositoryUsername(username)
        require(offset >= 0) { "Activity offset cannot be negative" }
        require(knownItemKeys.all { it.isNotBlank() && it.length <= MAX_ACTIVITY_ITEM_KEY_CHARS }) {
            "Known activity item keys are invalid"
        }
        return sessionManager.runForCurrentSession {
            mapper.mapActivityPage(
                offset = offset,
                response = remote.userActions(username = username, offset = offset),
                knownItemKeys = knownItemKeys,
            )
        }
    }

    override suspend fun loadNotifications(
        offset: DiscourseNotificationOffset,
        knownIds: Set<Long>,
        limit: Int,
    ): DiscourseForumNotificationPage =
        sessionManager.runForCurrentSession {
            val expectedRecipientUserId = requireAuthenticatedRecipientId()
            require(limit in 1..60) { "Notification limit must be between 1 and 60" }
            require(knownIds.all { it > 0L }) { "Known notification ids must be positive" }
            mapper.mapNotificationPage(
                offset = offset,
                response = remote.notifications(offset = offset, limit = limit),
                expectedRecipientUserId = expectedRecipientUserId,
                knownIds = knownIds,
            )
        }

    override suspend fun markNotificationsRead(
        current: DiscourseForumNotificationSnapshot,
        notificationId: Long?,
    ): DiscourseForumNotificationSnapshot =
        sessionManager.runForCurrentSession {
            val expectedRecipientUserId = requireAuthenticatedRecipientId()
            notificationId?.let { require(it > 0L) { "Notification id must be positive" } }
            if (current.items.any { it.recipientUserId != expectedRecipientUserId }) {
                throw forumProtocolFailure()
            }

            // Nothing local changes before this suspend call succeeds. A transport, CSRF, stale
            // session, or cancellation failure therefore leaves `current` byte-for-byte unchanged.
            remote.markNotificationsRead(notificationId)
            current.copy(
                items =
                    current.items.map { notification ->
                        if (notificationId == null || notification.id == notificationId) {
                            notification.copy(read = true)
                        } else {
                            notification
                        }
                    },
            )
        }
}

internal interface DiscourseForumAccountRemoteDataSource {
    suspend fun user(username: String): DiscourseUserResponse

    suspend fun userSummary(username: String): DiscourseUserSummaryResponse

    suspend fun userActions(
        username: String,
        offset: Int,
    ): DiscourseUserActionsResponse

    suspend fun notifications(
        offset: DiscourseNotificationOffset,
        limit: Int,
    ): DiscourseNotificationResponse

    suspend fun markNotificationsRead(notificationId: Long?)
}

private class DefaultDiscourseForumAccountRemoteDataSource(
    private val dataSource: DiscourseDataSource,
) : DiscourseForumAccountRemoteDataSource {
    override suspend fun user(username: String): DiscourseUserResponse = dataSource.user(username)

    override suspend fun userSummary(username: String): DiscourseUserSummaryResponse = dataSource.userSummary(username)

    override suspend fun userActions(
        username: String,
        offset: Int,
    ): DiscourseUserActionsResponse = dataSource.userActions(username = username, offset = offset)

    override suspend fun notifications(
        offset: DiscourseNotificationOffset,
        limit: Int,
    ): DiscourseNotificationResponse = dataSource.notifications(offset = offset, limit = limit)

    override suspend fun markNotificationsRead(notificationId: Long?) {
        dataSource.markNotificationsRead(notificationId)
    }
}

private fun DiscourseSessionState.requireAuthenticatedRecipientId(): Long {
    val authenticated =
        this as? DiscourseSessionState.Authenticated
            ?: throw DiscourseAuthenticationException()
    val accountId = authenticated.accountId
    if (accountId.isEmpty() || accountId.any { it !in '0'..'9' }) throw forumProtocolFailure()
    return accountId.toLongOrNull()?.takeIf { it > 0L } ?: throw forumProtocolFailure()
}

private fun requireRepositoryUsername(username: String) {
    require(username.isNotBlank()) { "Username must not be blank" }
    require(username.length <= 256) { "Username is too long" }
    require(username == username.trim()) { "Username must not contain surrounding whitespace" }
    require(username.none(Char::isForumMappingControlCharacter)) {
        "Username contains control characters"
    }
}

private const val MAX_ACTIVITY_ITEM_KEY_CHARS: Int = 512
