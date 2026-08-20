package dev.dimension.flare.data.network.discourse.composer

import dev.dimension.flare.data.network.discourse.error.DiscourseNetworkException
import dev.dimension.flare.data.network.discourse.error.DiscourseNetworkFailureKind
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumContentSource
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFailureKind
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumTopic
import dev.dimension.flare.data.network.discourse.model.DiscourseActionResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseActionResponseKind
import dev.dimension.flare.data.network.discourse.model.DiscourseBookmarkResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseCreateBookmarkRequest
import dev.dimension.flare.data.network.discourse.model.DiscoursePostActionRequest
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.ui.model.DiscoursePostMeta
import dev.dimension.flare.ui.model.DiscourseTopicMeta
import dev.dimension.flare.ui.model.DiscourseTopicRef
import dev.dimension.flare.ui.model.UiArticle
import dev.dimension.flare.ui.model.UiAuthor
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class DiscoursePostActionRepositoryTest {
    @Test
    fun likeIsSingleFlightAndConfirmsOneOptimisticMutation() =
        runTest {
            val sessionManager = authenticatedSession()
            val remote = FakePostActionRemote()
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            remote.createPostActionBlock = {
                started.complete(Unit)
                finish.await()
                actionResponse(acted = true)
            }
            val repository = DefaultDiscoursePostActionRepository(remote, sessionManager)
            repository.synchronizeFromServer(ACCOUNT_ID, article(liked = false, likeCount = 4))

            val first = async { repository.toggleLike(ACCOUNT_ID, POST_ID) }
            started.await()
            val optimistic = checkNotNull(repository.state.value).items.getValue(POST_TARGET)
            assertTrue(optimistic.liked)
            assertEquals(5, optimistic.likeCount)
            assertTrue(optimistic.isLikeInFlight)

            val duplicate = assertIs<DiscourseOptimisticMutationResult.Busy>(repository.toggleLike(ACCOUNT_ID, POST_ID))
            assertEquals(optimistic.likeEpoch, duplicate.state.likeEpoch)
            finish.complete(Unit)
            val confirmed = assertIs<DiscourseOptimisticMutationResult.Confirmed>(first.await())
            assertTrue(confirmed.state.liked)
            assertFalse(confirmed.state.isLikeInFlight)
            assertEquals(1, remote.createPostActionCalls)
        }

    @Test
    fun failedLikeRollsBackOnlyWhenItsEpochStillOwnsTheState() =
        runTest {
            val sessionManager = authenticatedSession()
            val remote = FakePostActionRemote()
            remote.createPostActionBlock = {
                throw DiscourseNetworkException(DiscourseNetworkFailureKind.Connection)
            }
            val repository = DefaultDiscoursePostActionRepository(remote, sessionManager)
            repository.synchronizeFromServer(ACCOUNT_ID, article(liked = false, likeCount = 4))

            val rejected =
                assertIs<DiscourseOptimisticMutationResult.Rejected>(
                    repository.toggleLike(ACCOUNT_ID, POST_ID),
                )

            assertTrue(rejected.rolledBack)
            assertEquals(DiscourseForumFailureKind.Network, rejected.failure)
            assertFalse(checkNotNull(rejected.state).liked)
            assertEquals(4, rejected.state.likeCount)
        }

    @Test
    fun authoritativeReseedPreventsLateFailureFromRollingBackNewerState() =
        runTest {
            val sessionManager = authenticatedSession()
            val remote = FakePostActionRemote()
            val started = CompletableDeferred<Unit>()
            val fail = CompletableDeferred<Unit>()
            remote.createPostActionBlock = {
                started.complete(Unit)
                fail.await()
                throw DiscourseNetworkException(DiscourseNetworkFailureKind.Connection)
            }
            val repository = DefaultDiscoursePostActionRepository(remote, sessionManager)
            repository.synchronizeFromServer(ACCOUNT_ID, article(liked = false, likeCount = 1))
            val mutation = async { repository.toggleLike(ACCOUNT_ID, POST_ID) }
            started.await()

            repository.synchronizeFromServer(ACCOUNT_ID, article(liked = true, likeCount = 20))
            fail.complete(Unit)
            val rejected = assertIs<DiscourseOptimisticMutationResult.Rejected>(mutation.await())

            assertFalse(rejected.rolledBack)
            val current = checkNotNull(repository.state.value).items.getValue(POST_TARGET)
            assertTrue(current.liked)
            assertEquals(20, current.likeCount)
            assertFalse(current.isLikeInFlight)
        }

    @Test
    fun bookmarkConfirmationDoesNotInterfereWithLikeRollbackEpoch() =
        runTest {
            val sessionManager = authenticatedSession()
            val remote = FakePostActionRemote()
            val likeStarted = CompletableDeferred<Unit>()
            val failLike = CompletableDeferred<Unit>()
            remote.createPostActionBlock = {
                likeStarted.complete(Unit)
                failLike.await()
                throw DiscourseNetworkException(DiscourseNetworkFailureKind.Connection)
            }
            remote.createBookmarkBlock = { DiscourseBookmarkResponse(id = 900L) }
            val repository = DefaultDiscoursePostActionRepository(remote, sessionManager)
            repository.synchronizeFromServer(
                ACCOUNT_ID,
                article(liked = false, likeCount = 2, bookmarked = false, bookmarkId = null),
            )
            val liking = async { repository.toggleLike(ACCOUNT_ID, POST_ID) }
            likeStarted.await()

            val bookmark =
                assertIs<DiscourseOptimisticMutationResult.Confirmed>(
                    repository.toggleBookmark(ACCOUNT_ID, POST_TARGET),
                )
            assertTrue(bookmark.state.bookmarked)
            assertEquals(900L, bookmark.state.bookmarkId)
            failLike.complete(Unit)
            val likeFailure = assertIs<DiscourseOptimisticMutationResult.Rejected>(liking.await())

            assertTrue(likeFailure.rolledBack)
            val current = checkNotNull(repository.state.value).items.getValue(POST_TARGET)
            assertFalse(current.liked)
            assertEquals(2, current.likeCount)
            assertTrue(current.bookmarked)
            assertEquals(900L, current.bookmarkId)
        }

    @Test
    fun topicBookmarkUsesServerBookmarkIdAndSessionClearRemovesAccountState() =
        runTest {
            val sessionManager = authenticatedSession()
            val remote = FakePostActionRemote()
            val repository = DefaultDiscoursePostActionRepository(remote, sessionManager)
            repository.synchronizeFromServer(ACCOUNT_ID, topic(bookmarked = true, bookmarkId = 700L))

            val removed =
                assertIs<DiscourseOptimisticMutationResult.Confirmed>(
                    repository.toggleBookmark(ACCOUNT_ID, TOPIC_TARGET),
                )

            assertFalse(removed.state.bookmarked)
            assertNull(removed.state.bookmarkId)
            assertEquals(listOf(700L), remote.deletedBookmarkIds)
            sessionManager.logout()
            repository.clearForSessionChange()
            assertNull(repository.state.value)
        }

    @Test
    fun absentServerPermissionsRejectActionsBeforeRemoteMutation() =
        runTest {
            val sessionManager = authenticatedSession()
            val remote = FakePostActionRemote()
            val repository = DefaultDiscoursePostActionRepository(remote, sessionManager)
            repository.synchronizeFromServer(
                ACCOUNT_ID,
                article(
                    liked = false,
                    likeCount = 0,
                    canLike = false,
                    bookmarked = false,
                    bookmarkId = null,
                    canBookmark = false,
                ),
            )

            val like = assertIs<DiscourseOptimisticMutationResult.NotAllowed>(repository.toggleLike(ACCOUNT_ID, POST_ID))
            val bookmark =
                assertIs<DiscourseOptimisticMutationResult.NotAllowed>(
                    repository.toggleBookmark(ACCOUNT_ID, POST_TARGET),
                )

            assertEquals(DiscourseActionNotAllowedReason.PermissionDenied, like.reason)
            assertEquals(DiscourseActionNotAllowedReason.PermissionDenied, bookmark.reason)
            assertEquals(0, remote.createPostActionCalls)
            assertEquals(0, remote.createBookmarkCalls)
        }

    @Test
    fun likedPostWithoutCanUndoRejectsUnlikeBeforeRemoteMutation() =
        runTest {
            val sessionManager = authenticatedSession()
            val remote = FakePostActionRemote()
            val repository = DefaultDiscoursePostActionRepository(remote, sessionManager)
            repository.synchronizeFromServer(
                ACCOUNT_ID,
                article(liked = true, likeCount = 4, canLike = false),
            )

            val result = assertIs<DiscourseOptimisticMutationResult.NotAllowed>(repository.toggleLike(ACCOUNT_ID, POST_ID))

            assertEquals(DiscourseActionNotAllowedReason.PermissionDenied, result.reason)
            assertEquals(0, remote.deletePostActionCalls)
        }

    @Test
    fun fullPostResponseOverridesOptimisticCountAndNextTogglePermission() =
        runTest {
            val sessionManager = authenticatedSession()
            val remote =
                FakePostActionRemote().apply {
                    createPostActionBlock = {
                        actionResponse(
                            acted = true,
                            count = 19,
                            canAct = false,
                            canUndo = false,
                        )
                    }
                }
            val repository = DefaultDiscoursePostActionRepository(remote, sessionManager)
            repository.synchronizeFromServer(ACCOUNT_ID, article(liked = false, likeCount = 4))

            val confirmed = assertIs<DiscourseOptimisticMutationResult.Confirmed>(repository.toggleLike(ACCOUNT_ID, POST_ID))

            assertTrue(confirmed.state.liked)
            assertEquals(19, confirmed.state.likeCount)
            assertFalse(confirmed.state.canLike)
            assertIs<DiscourseOptimisticMutationResult.NotAllowed>(repository.toggleLike(ACCOUNT_ID, POST_ID))
            assertEquals(1, remote.createPostActionCalls)
            assertEquals(0, remote.deletePostActionCalls)
        }

    @Test
    fun deleteNoContentConfirmsUnlikeButFailsClosedUntilAuthoritativeReseed() =
        runTest {
            val sessionManager = authenticatedSession()
            val remote = FakePostActionRemote()
            val repository = DefaultDiscoursePostActionRepository(remote, sessionManager)
            repository.synchronizeFromServer(ACCOUNT_ID, article(liked = true, likeCount = 4))

            val confirmed = assertIs<DiscourseOptimisticMutationResult.Confirmed>(repository.toggleLike(ACCOUNT_ID, POST_ID))

            assertFalse(confirmed.state.liked)
            assertEquals(3, confirmed.state.likeCount)
            assertFalse(confirmed.state.canLike)
            assertIs<DiscourseOptimisticMutationResult.NotAllowed>(repository.toggleLike(ACCOUNT_ID, POST_ID))
            assertEquals(1, remote.deletePostActionCalls)
            assertEquals(0, remote.createPostActionCalls)
        }

    @Test
    fun mismatchedFullPostActionResponseRollsBackAsInvalid() =
        runTest {
            val sessionManager = authenticatedSession()
            val remote =
                FakePostActionRemote().apply {
                    createPostActionBlock = { actionResponse(acted = true).copy(postId = 999L) }
                }
            val repository = DefaultDiscoursePostActionRepository(remote, sessionManager)
            repository.synchronizeFromServer(ACCOUNT_ID, article(liked = false, likeCount = 4))

            val rejected = assertIs<DiscourseOptimisticMutationResult.Rejected>(repository.toggleLike(ACCOUNT_ID, POST_ID))

            assertEquals(DiscourseForumFailureKind.InvalidResponse, rejected.failure)
            assertTrue(rejected.rolledBack)
            assertFalse(checkNotNull(rejected.state).liked)
            assertEquals(4, rejected.state.likeCount)
        }

    @Test
    fun callerCancellationIsRethrownAfterNonCancellableOptimisticRollback() =
        runTest {
            supervisorScope {
                val sessionManager = authenticatedSession()
                val remote = FakePostActionRemote()
                val started = CompletableDeferred<Unit>()
                remote.createPostActionBlock = {
                    started.complete(Unit)
                    awaitCancellation()
                }
                val repository = DefaultDiscoursePostActionRepository(remote, sessionManager)
                repository.synchronizeFromServer(ACCOUNT_ID, article(liked = false, likeCount = 4))
                val mutation = async { repository.toggleLike(ACCOUNT_ID, POST_ID) }
                started.await()

                mutation.cancel()

                assertFailsWith<CancellationException> { mutation.await() }
                val restored = checkNotNull(repository.state.value).items.getValue(POST_TARGET)
                assertFalse(restored.liked)
                assertEquals(4, restored.likeCount)
                assertFalse(restored.isLikeInFlight)
            }
        }

    @Test
    fun unknownRemoteFailuresRollbackBothActionFamiliesAsInvalidResponses() =
        runTest {
            val sessionManager = authenticatedSession()
            val remote =
                FakePostActionRemote().apply {
                    createPostActionBlock = { throw IllegalStateException("Fixture mapper failure") }
                    createBookmarkBlock = { throw IllegalArgumentException("Fixture identity failure") }
                }
            val repository = DefaultDiscoursePostActionRepository(remote, sessionManager)
            repository.synchronizeFromServer(
                ACCOUNT_ID,
                article(liked = false, likeCount = 4, bookmarked = false, bookmarkId = null),
            )

            val like = assertIs<DiscourseOptimisticMutationResult.Rejected>(repository.toggleLike(ACCOUNT_ID, POST_ID))
            val bookmark =
                assertIs<DiscourseOptimisticMutationResult.Rejected>(
                    repository.toggleBookmark(ACCOUNT_ID, POST_TARGET),
                )

            assertEquals(DiscourseForumFailureKind.InvalidResponse, like.failure)
            assertTrue(like.rolledBack)
            assertFalse(checkNotNull(like.state).isLikeInFlight)
            assertEquals(DiscourseForumFailureKind.InvalidResponse, bookmark.failure)
            assertTrue(bookmark.rolledBack)
            assertFalse(checkNotNull(bookmark.state).isBookmarkInFlight)
        }

    private suspend fun authenticatedSession(): DiscourseSessionManager =
        DiscourseSessionManager().also {
            it.startAuthenticatedSession(accountId = ACCOUNT_ID)
        }

    private companion object {
        const val ACCOUNT_ID: String = "42"
        const val POST_ID: Long = 501L
        const val TOPIC_ID: Long = 42L
        val POST_TARGET: DiscourseActionTarget.Post = DiscourseActionTarget.Post(POST_ID)
        val TOPIC_TARGET: DiscourseActionTarget.Topic = DiscourseActionTarget.Topic(TOPIC_ID)
    }
}

private class FakePostActionRemote : DiscoursePostActionRemoteDataSource {
    var createPostActionCalls: Int = 0
    var deletePostActionCalls: Int = 0
    var createBookmarkCalls: Int = 0
    val deletedBookmarkIds = mutableListOf<Long>()
    var createPostActionBlock: suspend (DiscoursePostActionRequest) -> DiscourseActionResponse = {
        actionResponse(acted = true)
    }
    var deletePostActionBlock: suspend (Long, Long) -> DiscourseActionResponse = { _, _ ->
        actionResponse(acted = false, kind = DiscourseActionResponseKind.NoContent)
    }
    var createBookmarkBlock: suspend (DiscourseCreateBookmarkRequest) -> DiscourseBookmarkResponse = {
        DiscourseBookmarkResponse(900L)
    }
    var deleteBookmarkBlock: suspend (Long) -> Unit = {}

    override suspend fun createPostAction(request: DiscoursePostActionRequest): DiscourseActionResponse {
        createPostActionCalls += 1
        return createPostActionBlock(request)
    }

    override suspend fun deletePostAction(
        postId: Long,
        actionTypeId: Long,
    ): DiscourseActionResponse {
        deletePostActionCalls += 1
        return deletePostActionBlock(postId, actionTypeId)
    }

    override suspend fun createBookmark(request: DiscourseCreateBookmarkRequest): DiscourseBookmarkResponse {
        createBookmarkCalls += 1
        return createBookmarkBlock(request)
    }

    override suspend fun deleteBookmark(bookmarkId: Long) {
        deletedBookmarkIds += bookmarkId
        deleteBookmarkBlock(bookmarkId)
    }
}

private fun actionResponse(
    acted: Boolean,
    kind: DiscourseActionResponseKind = DiscourseActionResponseKind.FullPost,
    count: Int? = if (kind == DiscourseActionResponseKind.FullPost) 4 else null,
    canAct: Boolean? = if (kind == DiscourseActionResponseKind.FullPost) !acted else null,
    canUndo: Boolean? = if (kind == DiscourseActionResponseKind.FullPost) acted else null,
): DiscourseActionResponse =
    DiscourseActionResponse(
        postId = 501L,
        postActionTypeId = 2L,
        acted = acted,
        kind = kind,
        count = count,
        canAct = canAct,
        canUndo = canUndo,
    )

private fun article(
    liked: Boolean,
    likeCount: Int,
    canLike: Boolean = true,
    bookmarked: Boolean = false,
    bookmarkId: Long? = null,
    canBookmark: Boolean = true,
): UiArticle =
    UiArticle(
        itemKey = "discourse-post:501",
        title = "Topic",
        author = UiAuthor(username = "writer", displayName = "Writer"),
        createdAtEpochMillis = 0L,
        blocks = emptyList(),
        discourse =
            DiscoursePostMeta(
                topicId = 42L,
                postId = 501L,
                postNumber = 3,
                canLike = canLike,
                liked = liked,
                likeCount = likeCount,
                canBookmark = canBookmark,
                bookmarked = bookmarked,
                bookmarkId = bookmarkId,
            ),
    )

private fun topic(
    bookmarked: Boolean,
    bookmarkId: Long?,
): DiscourseForumTopic =
    DiscourseForumTopic(
        topicId = 42L,
        title = "Topic",
        slug = "topic",
        articles = emptyList(),
        canReply = true,
        discourse =
            DiscourseTopicMeta(
                ref = DiscourseTopicRef(topicId = 42L),
                slug = "topic",
                canBookmark = true,
                bookmarked = bookmarked,
                bookmarkId = bookmarkId,
            ),
        source = DiscourseForumContentSource.Network,
        updatedAtEpochMillis = 0L,
    )
