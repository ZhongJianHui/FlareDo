package dev.dimension.flare.data.network.discourse.forum

import dev.dimension.flare.data.network.discourse.content.DiscourseCookedHtmlParser
import dev.dimension.flare.data.network.discourse.error.DiscourseAuthenticationException
import dev.dimension.flare.data.network.discourse.error.DiscoursePermissionException
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationException
import dev.dimension.flare.data.network.discourse.model.DiscourseNotification
import dev.dimension.flare.data.network.discourse.model.DiscourseNotificationResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUser
import dev.dimension.flare.data.network.discourse.model.DiscourseUserActionsResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserSummary
import dev.dimension.flare.data.network.discourse.model.DiscourseUserSummaryResponse
import dev.dimension.flare.data.network.discourse.paging.DiscourseNotificationOffset
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.StaleDiscourseSessionException
import dev.dimension.flare.ui.model.DiscourseTopicRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class DiscourseForumAccountRepositoryStage6Test {
    private val mapper = DiscourseForumAccountMapper(DiscourseCookedHtmlParser())

    @Test
    fun guestNotificationsFailBeforeAnyRemoteOrMutationCall() =
        runTest {
            val remote = RecordingAccountRemote()
            val repository = repository(remote, DiscourseSessionManager())

            assertFailsWith<DiscourseAuthenticationException> {
                repository.loadNotifications()
            }
            assertFailsWith<DiscourseAuthenticationException> {
                repository.markNotificationsRead(notificationSnapshot())
            }

            assertEquals(0, remote.notificationCalls)
            assertEquals(emptyList(), remote.markReadCalls)
        }

    @Test
    fun malformedAuthenticatedAccountIdFailsBeforeRemoteAccess() =
        runTest {
            val remote = RecordingAccountRemote()
            val sessionManager = authenticatedSession("account-42")
            val repository = repository(remote, sessionManager)

            assertFailsWith<DiscourseSerializationException> {
                repository.loadNotifications()
            }

            assertEquals(0, remote.notificationCalls)
        }

    @Test
    fun activeProfileBindsUsernameToNumericSessionIdentityButOtherProfilesRemainPublic() =
        runTest {
            val remote = RecordingAccountRemote()
            remote.userBlock = { username ->
                DiscourseUserResponse(
                    DiscourseUser(
                        id = 99L,
                        username = username,
                    ),
                )
            }
            val repository = repository(remote, authenticatedSession("42"))

            assertFailsWith<DiscourseSerializationException> {
                repository.loadProfile("member")
            }

            val publicProfile = repository.loadProfile("other-member")
            assertEquals(99L, publicProfile.userId)
            assertEquals("other-member", publicProfile.username)
        }

    @Test
    fun everyNotificationMustBelongToNumericActiveAccount() =
        runTest {
            val remote = RecordingAccountRemote()
            remote.notificationResponse =
                repositoryNotificationResponse(
                    DiscourseNotification(
                        id = 101L,
                        userId = 99L,
                        notificationType = 2,
                    ),
                )
            val repository = repository(remote, authenticatedSession("42"))

            assertFailsWith<DiscourseSerializationException> {
                repository.loadNotifications()
            }
            assertEquals(1, remote.notificationCalls)
        }

    @Test
    fun notificationLimitOffsetAndKnownIdsReachOneStructuredRequest() =
        runTest {
            val remote = RecordingAccountRemote()
            remote.notificationResponse =
                DiscourseNotificationResponse(
                    notifications =
                        listOf(
                            DiscourseNotification(id = 101L, userId = 42L, notificationType = 2),
                            DiscourseNotification(id = 102L, userId = 42L, notificationType = 2),
                        ),
                    loadMoreNotifications = "/notifications?offset=12",
                )
            val repository = repository(remote, authenticatedSession("42"))

            val page =
                repository.loadNotifications(
                    offset = DiscourseNotificationOffset(10),
                    knownIds = setOf(101L),
                    limit = 20,
                )

            assertEquals(listOf(DiscourseNotificationOffset(10) to 20), remote.notificationRequests)
            assertEquals(listOf(102L), page.items.map { it.id })
            assertEquals(DiscourseNotificationOffset(11), page.nextOffset)
        }

    @Test
    fun markOneAndMarkAllUpdateImmutableStateOnlyAfterRemoteSuccess() =
        runTest {
            val remote = RecordingAccountRemote()
            val repository = repository(remote, authenticatedSession("42"))
            val current = notificationSnapshot()

            val one = repository.markNotificationsRead(current, notificationId = 101L)
            val all = repository.markNotificationsRead(one)

            assertEquals(listOf(101L, null), remote.markReadCalls)
            assertFalse(current.items.first { it.id == 101L }.read)
            assertTrue(one.items.first { it.id == 101L }.read)
            assertFalse(one.items.first { it.id == 102L }.read)
            assertEquals(0, all.unreadCount)
        }

    @Test
    fun markFailureLeavesSuppliedSnapshotUnchanged() =
        runTest {
            val failure = DiscoursePermissionException()
            val remote = RecordingAccountRemote()
            remote.markReadBlock = { throw failure }
            val repository = repository(remote, authenticatedSession("42"))
            val current = notificationSnapshot()

            assertFailsWith<DiscoursePermissionException> {
                repository.markNotificationsRead(current, 101L)
            }

            assertEquals(2, current.unreadCount)
            assertTrue(current.items.none { it.read })
        }

    @Test
    fun cancellationIsRethrownUnchanged() =
        runTest {
            val cancellation = CancellationException("caller cancelled fixture")
            val remote = RecordingAccountRemote()
            remote.notificationsBlock = { _, _ -> throw cancellation }
            val repository = repository(remote, authenticatedSession("42"))

            val thrown =
                assertFailsWith<CancellationException> {
                    repository.loadNotifications()
                }

            assertEquals(cancellation.message, thrown.message)
        }

    @Test
    fun sessionReplacementRemainsAStaleSessionFailure() =
        runTest {
            supervisorScope {
                val entered = CompletableDeferred<Unit>()
                val remote = RecordingAccountRemote()
                remote.notificationsBlock = { _, _ ->
                    entered.complete(Unit)
                    awaitCancellation()
                }
                val sessionManager = authenticatedSession("42")
                val repository = repository(remote, sessionManager)
                val request = async { repository.loadNotifications() }

                entered.await()
                sessionManager.logout()

                assertFailsWith<StaleDiscourseSessionException> {
                    request.await()
                }
            }
        }

    private fun repository(
        remote: DiscourseForumAccountRemoteDataSource,
        sessionManager: DiscourseSessionManager,
    ): DefaultDiscourseForumAccountRepository =
        DefaultDiscourseForumAccountRepository(
            remote = remote,
            mapper = mapper,
            sessionManager = sessionManager,
        )
}

private class RecordingAccountRemote : DiscourseForumAccountRemoteDataSource {
    var notificationCalls: Int = 0
    val notificationRequests = mutableListOf<Pair<DiscourseNotificationOffset, Int>>()
    val markReadCalls = mutableListOf<Long?>()
    var notificationResponse: DiscourseNotificationResponse = repositoryNotificationResponse()
    var userBlock: suspend (String) -> DiscourseUserResponse = { username ->
        DiscourseUserResponse(DiscourseUser(id = 42L, username = username))
    }
    var notificationsBlock: suspend (DiscourseNotificationOffset, Int) -> DiscourseNotificationResponse = { _, _ ->
        notificationResponse
    }
    var markReadBlock: suspend (Long?) -> Unit = {}

    override suspend fun user(username: String): DiscourseUserResponse = userBlock(username)

    override suspend fun userSummary(username: String): DiscourseUserSummaryResponse = DiscourseUserSummaryResponse(DiscourseUserSummary())

    override suspend fun userActions(
        username: String,
        offset: Int,
    ): DiscourseUserActionsResponse = DiscourseUserActionsResponse(emptyList())

    override suspend fun notifications(
        offset: DiscourseNotificationOffset,
        limit: Int,
    ): DiscourseNotificationResponse {
        notificationCalls += 1
        notificationRequests += offset to limit
        return notificationsBlock(offset, limit)
    }

    override suspend fun markNotificationsRead(notificationId: Long?) {
        markReadBlock(notificationId)
        markReadCalls += notificationId
    }
}

private suspend fun authenticatedSession(accountId: String): DiscourseSessionManager =
    DiscourseSessionManager().also { manager ->
        manager.startAuthenticatedSession(
            accountId = accountId,
            username = "member",
        )
    }

private fun notificationSnapshot(): DiscourseForumNotificationSnapshot =
    DiscourseForumNotificationSnapshot(
        items =
            listOf(
                domainNotification(id = 101L),
                domainNotification(id = 102L),
            ),
        totalRows = 2,
        seenNotificationId = 0L,
    )

private fun domainNotification(id: Long): DiscourseForumNotification =
    DiscourseForumNotification(
        id = id,
        recipientUserId = 42L,
        kind = DiscourseForumNotificationKind.Reply,
        read = false,
        highPriority = false,
        createdAtEpochMillis = null,
        topic = DiscourseTopicRef(topicId = 7L, postNumber = 2),
        topicSlug = "safe-topic",
        title = "Safe notification",
        actingUser = null,
        data = DiscourseForumNotificationData(),
    )

private fun repositoryNotificationResponse(vararg rows: DiscourseNotification): DiscourseNotificationResponse =
    DiscourseNotificationResponse(notifications = rows.toList())
