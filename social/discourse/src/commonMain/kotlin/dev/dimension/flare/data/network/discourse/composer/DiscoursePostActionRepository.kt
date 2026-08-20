package dev.dimension.flare.data.network.discourse.composer

import dev.dimension.flare.data.network.discourse.DiscourseDataSource
import dev.dimension.flare.data.network.discourse.error.DiscourseException
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFailureKind
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumTopic
import dev.dimension.flare.data.network.discourse.forum.toForumFailureKind
import dev.dimension.flare.data.network.discourse.model.DiscourseActionResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseActionResponseKind
import dev.dimension.flare.data.network.discourse.model.DiscourseBookmarkResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseCreateBookmarkRequest
import dev.dimension.flare.data.network.discourse.model.DiscoursePostActionRequest
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionState
import dev.dimension.flare.data.network.discourse.session.StaleDiscourseSessionException
import dev.dimension.flare.ui.model.UiArticle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Stable mutation identity used by post likes and topic/post bookmarks. */
public sealed interface DiscourseActionTarget {
    public data class Post(
        val postId: Long,
    ) : DiscourseActionTarget {
        init {
            require(postId > 0L) { "Action post id must be positive" }
        }
    }

    public data class Topic(
        val topicId: Long,
    ) : DiscourseActionTarget {
        init {
            require(topicId > 0L) { "Action topic id must be positive" }
        }
    }
}

/**
 * Optimistic action snapshot for one topic or post.
 *
 * Like and bookmark epochs are independent. A concurrent bookmark must not suppress conditional
 * rollback of a failed like, and vice versa. The in-flight flags implement one request at a time per
 * target/action while still allowing the two independent action families to run concurrently.
 */
public data class DiscourseOptimisticActionState(
    val target: DiscourseActionTarget,
    val liked: Boolean = false,
    val likeCount: Int = 0,
    val canLike: Boolean = false,
    val bookmarked: Boolean = false,
    val bookmarkId: Long? = null,
    val canBookmark: Boolean = false,
    val likeEpoch: Long = 0L,
    val bookmarkEpoch: Long = 0L,
    val isLikeInFlight: Boolean = false,
    val isBookmarkInFlight: Boolean = false,
) {
    init {
        require(likeCount >= 0) { "Like count cannot be negative" }
        require(bookmarkId == null || bookmarkId > 0L) { "Bookmark id must be positive" }
        require(likeEpoch >= 0L) { "Like epoch cannot be negative" }
        require(bookmarkEpoch >= 0L) { "Bookmark epoch cannot be negative" }
        require(target is DiscourseActionTarget.Post || (!liked && likeCount == 0 && !canLike)) {
            "Topic action state cannot carry a post like"
        }
    }
}

/** Account/generation partition that presenters compare with their current session state. */
public data class DiscoursePostActionSnapshot(
    val accountId: String,
    val sessionGeneration: Long,
    val items: Map<DiscourseActionTarget, DiscourseOptimisticActionState>,
) {
    init {
        requireValidComposerAccountId(accountId)
        require(sessionGeneration >= 0L) { "Action session generation cannot be negative" }
        require(items.all { (target, value) -> target == value.target }) {
            "Action snapshot keys must match their values"
        }
    }
}

/** Why an optimistic request was not started. */
public enum class DiscourseActionNotAllowedReason {
    MissingServerState,
    PermissionDenied,
    MissingBookmarkId,
}

/** Result of a single-flight optimistic action. */
public sealed interface DiscourseOptimisticMutationResult {
    public val state: DiscourseOptimisticActionState?

    public data class Confirmed(
        override val state: DiscourseOptimisticActionState,
    ) : DiscourseOptimisticMutationResult

    /** A newer authoritative seed replaced this attempt before its response arrived. */
    public data class Superseded(
        override val state: DiscourseOptimisticActionState?,
    ) : DiscourseOptimisticMutationResult

    public data class Rejected(
        override val state: DiscourseOptimisticActionState?,
        val failure: DiscourseForumFailureKind,
        val rolledBack: Boolean,
    ) : DiscourseOptimisticMutationResult

    public data class Busy(
        override val state: DiscourseOptimisticActionState,
    ) : DiscourseOptimisticMutationResult

    public data class NotAllowed(
        override val state: DiscourseOptimisticActionState?,
        val reason: DiscourseActionNotAllowedReason,
    ) : DiscourseOptimisticMutationResult
}

/** Account-scoped optimistic like/bookmark mutations consumed by the shared presenter. */
public interface DiscoursePostActionRepository {
    public val state: StateFlow<DiscoursePostActionSnapshot?>

    public suspend fun synchronizeFromServer(
        accountId: String,
        article: UiArticle,
    )

    public suspend fun synchronizeFromServer(
        accountId: String,
        topic: DiscourseForumTopic,
    )

    /** Called by the presentation session observer before rendering a replacement account. */
    public suspend fun clearForSessionChange()

    public suspend fun toggleLike(
        accountId: String,
        postId: Long,
    ): DiscourseOptimisticMutationResult

    public suspend fun toggleBookmark(
        accountId: String,
        target: DiscourseActionTarget,
    ): DiscourseOptimisticMutationResult
}

/** Shared optimistic repository with per-action epoch CAS and no internal coroutine scope. */
public class DefaultDiscoursePostActionRepository internal constructor(
    private val remote: DiscoursePostActionRemoteDataSource,
    private val sessionManager: DiscourseSessionManager,
) : DiscoursePostActionRepository {
    public constructor(
        dataSource: DiscourseDataSource,
        sessionManager: DiscourseSessionManager,
    ) : this(
        remote = DefaultDiscoursePostActionRemoteDataSource(dataSource),
        sessionManager = sessionManager,
    )

    private val stateMutex = Mutex()
    private val mutableState = MutableStateFlow<DiscoursePostActionSnapshot?>(null)

    override val state: StateFlow<DiscoursePostActionSnapshot?> = mutableState.asStateFlow()

    override suspend fun synchronizeFromServer(
        accountId: String,
        article: UiArticle,
    ) {
        requireValidComposerAccountId(accountId)
        val metadata = article.discourse ?: return
        val target = DiscourseActionTarget.Post(metadata.postId)
        sessionManager.runForAuthenticatedAccount(accountId) {
            stateMutex.withLock {
                val snapshot = partitionFor(this)
                val previous = snapshot.items[target]
                val seeded =
                    DiscourseOptimisticActionState(
                        target = target,
                        liked = metadata.liked,
                        likeCount = metadata.likeCount.coerceAtLeast(0),
                        canLike = metadata.canLike,
                        bookmarked = metadata.bookmarked,
                        bookmarkId = metadata.bookmarkId?.takeIf { it > 0L },
                        canBookmark = metadata.canBookmark,
                        likeEpoch = previous?.likeEpoch.nextActionEpoch(),
                        bookmarkEpoch = previous?.bookmarkEpoch.nextActionEpoch(),
                    )
                mutableState.value = snapshot.withItem(seeded)
            }
        }
    }

    override suspend fun synchronizeFromServer(
        accountId: String,
        topic: DiscourseForumTopic,
    ) {
        requireValidComposerAccountId(accountId)
        val metadata = topic.discourse ?: return
        val target = DiscourseActionTarget.Topic(topic.topicId)
        require(metadata.ref.topicId == topic.topicId) { "Topic action identity does not match its detail" }
        sessionManager.runForAuthenticatedAccount(accountId) {
            stateMutex.withLock {
                val snapshot = partitionFor(this)
                val previous = snapshot.items[target]
                val seeded =
                    DiscourseOptimisticActionState(
                        target = target,
                        bookmarked = metadata.bookmarked,
                        bookmarkId = metadata.bookmarkId?.takeIf { it > 0L },
                        canBookmark = metadata.canBookmark,
                        likeEpoch = previous?.likeEpoch.nextActionEpoch(),
                        bookmarkEpoch = previous?.bookmarkEpoch.nextActionEpoch(),
                    )
                mutableState.value = snapshot.withItem(seeded)
            }
        }
    }

    override suspend fun clearForSessionChange() {
        val current = sessionManager.state.value
        stateMutex.withLock {
            mutableState.value =
                (current as? DiscourseSessionState.Authenticated)?.let {
                    DiscoursePostActionSnapshot(
                        accountId = it.accountId,
                        sessionGeneration = it.generation,
                        items = emptyMap(),
                    )
                }
        }
    }

    override suspend fun toggleLike(
        accountId: String,
        postId: Long,
    ): DiscourseOptimisticMutationResult {
        requireValidComposerAccountId(accountId)
        val target = DiscourseActionTarget.Post(postId)
        return sessionManager.runForAuthenticatedAccount(accountId) {
            val start = beginLike(this, target)
            if (start.result != null) return@runForAuthenticatedAccount start.result
            val token = checkNotNull(start.token)
            try {
                val response =
                    if (token.desired) {
                        remote.createPostAction(
                            DiscoursePostActionRequest(
                                id = postId,
                                postActionTypeId = DISCOURSE_LIKE_ACTION_TYPE_ID,
                            ),
                        )
                    } else {
                        remote.deletePostAction(postId, DISCOURSE_LIKE_ACTION_TYPE_ID)
                    }
                confirmLike(token, response)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { rollbackLike(token) }
                throw cancelled
            } catch (stale: StaleDiscourseSessionException) {
                withContext(NonCancellable) { rollbackLike(token) }
                throw stale
            } catch (failure: DiscourseException) {
                withContext(NonCancellable) {
                    rejectLike(token, failure.toForumFailureKind())
                }
            } catch (_: Exception) {
                // A mapper/plugin invariant must not strand an optimistic action in-flight. The
                // response is not trustworthy enough to classify more specifically.
                withContext(NonCancellable) {
                    rejectLike(token, DiscourseForumFailureKind.InvalidResponse)
                }
            }
        }
    }

    override suspend fun toggleBookmark(
        accountId: String,
        target: DiscourseActionTarget,
    ): DiscourseOptimisticMutationResult {
        requireValidComposerAccountId(accountId)
        return sessionManager.runForAuthenticatedAccount(accountId) {
            val start = beginBookmark(this, target)
            if (start.result != null) return@runForAuthenticatedAccount start.result
            val token = checkNotNull(start.token)
            try {
                val createdBookmarkId =
                    if (token.desired) {
                        remote
                            .createBookmark(
                                DiscourseCreateBookmarkRequest(
                                    bookmarkableId = target.serverId,
                                    bookmarkableType = target.bookmarkableType,
                                ),
                            ).id
                    } else {
                        remote.deleteBookmark(checkNotNull(token.previousBookmarkId))
                        null
                    }
                confirmBookmark(token, createdBookmarkId)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { rollbackBookmark(token) }
                throw cancelled
            } catch (stale: StaleDiscourseSessionException) {
                withContext(NonCancellable) { rollbackBookmark(token) }
                throw stale
            } catch (failure: DiscourseException) {
                withContext(NonCancellable) {
                    rejectBookmark(token, failure.toForumFailureKind())
                }
            } catch (_: Exception) {
                withContext(NonCancellable) {
                    rejectBookmark(token, DiscourseForumFailureKind.InvalidResponse)
                }
            }
        }
    }

    private suspend fun beginLike(
        session: DiscourseSessionState.Authenticated,
        target: DiscourseActionTarget.Post,
    ): MutationStart<LikeMutationToken> =
        stateMutex.withLock {
            val snapshot = partitionFor(session)
            val current =
                snapshot.items[target]
                    ?: return@withLock MutationStart.notAllowed(
                        reason = DiscourseActionNotAllowedReason.MissingServerState,
                        state = null,
                    )
            if (current.isLikeInFlight) return@withLock MutationStart.busy(current)
            if (!current.canLike) {
                return@withLock MutationStart.notAllowed(
                    reason = DiscourseActionNotAllowedReason.PermissionDenied,
                    state = current,
                )
            }
            val epoch = current.likeEpoch.nextActionEpoch()
            val desired = !current.liked
            val optimistic =
                current.copy(
                    liked = desired,
                    likeCount = optimisticLikeCount(current.likeCount, desired),
                    likeEpoch = epoch,
                    isLikeInFlight = true,
                )
            mutableState.value = snapshot.withItem(optimistic)
            MutationStart.started(
                LikeMutationToken(
                    accountId = session.accountId,
                    sessionGeneration = session.generation,
                    target = target,
                    epoch = epoch,
                    desired = desired,
                    previousLiked = current.liked,
                    previousLikeCount = current.likeCount,
                ),
            )
        }

    private suspend fun beginBookmark(
        session: DiscourseSessionState.Authenticated,
        target: DiscourseActionTarget,
    ): MutationStart<BookmarkMutationToken> =
        stateMutex.withLock {
            val snapshot = partitionFor(session)
            val current =
                snapshot.items[target]
                    ?: return@withLock MutationStart.notAllowed(
                        reason = DiscourseActionNotAllowedReason.MissingServerState,
                        state = null,
                    )
            if (current.isBookmarkInFlight) return@withLock MutationStart.busy(current)
            if (!current.canBookmark && !current.bookmarked) {
                return@withLock MutationStart.notAllowed(
                    reason = DiscourseActionNotAllowedReason.PermissionDenied,
                    state = current,
                )
            }
            if (current.bookmarked && current.bookmarkId == null) {
                return@withLock MutationStart.notAllowed(
                    reason = DiscourseActionNotAllowedReason.MissingBookmarkId,
                    state = current,
                )
            }
            val epoch = current.bookmarkEpoch.nextActionEpoch()
            val desired = !current.bookmarked
            val optimistic =
                current.copy(
                    bookmarked = desired,
                    bookmarkId = current.bookmarkId.takeIf { desired },
                    bookmarkEpoch = epoch,
                    isBookmarkInFlight = true,
                )
            mutableState.value = snapshot.withItem(optimistic)
            MutationStart.started(
                BookmarkMutationToken(
                    accountId = session.accountId,
                    sessionGeneration = session.generation,
                    target = target,
                    epoch = epoch,
                    desired = desired,
                    previousBookmarked = current.bookmarked,
                    previousBookmarkId = current.bookmarkId,
                ),
            )
        }

    private suspend fun confirmLike(
        token: LikeMutationToken,
        response: DiscourseActionResponse,
    ): DiscourseOptimisticMutationResult {
        require(
            response.postId == token.target.postId &&
                response.postActionTypeId == DISCOURSE_LIKE_ACTION_TYPE_ID &&
                response.acted == token.desired,
        ) {
            "Post action response does not match the requested like mutation"
        }
        return stateMutex.withLock {
            val current = currentMatching(token.accountId, token.sessionGeneration, token.target)
            if (current == null || current.likeEpoch != token.epoch || !current.isLikeInFlight) {
                return@withLock DiscourseOptimisticMutationResult.Superseded(current)
            }
            val hasAuthoritativeState = response.kind == DiscourseActionResponseKind.FullPost
            val confirmed =
                current.copy(
                    liked = response.acted,
                    likeCount = response.count ?: current.likeCount,
                    // HTTP 204 proves deletion but supplies no permission state. Disable the next
                    // toggle until a topic refresh reseeds it instead of guessing from stale data.
                    canLike =
                        if (hasAuthoritativeState) {
                            if (response.acted) checkNotNull(response.canUndo) else checkNotNull(response.canAct)
                        } else {
                            false
                        },
                    isLikeInFlight = false,
                )
            mutableState.value = checkNotNull(mutableState.value).withItem(confirmed)
            DiscourseOptimisticMutationResult.Confirmed(confirmed)
        }
    }

    private suspend fun confirmBookmark(
        token: BookmarkMutationToken,
        createdBookmarkId: Long?,
    ): DiscourseOptimisticMutationResult =
        stateMutex.withLock {
            val current = currentMatching(token.accountId, token.sessionGeneration, token.target)
            if (current == null || current.bookmarkEpoch != token.epoch || !current.isBookmarkInFlight) {
                return@withLock DiscourseOptimisticMutationResult.Superseded(current)
            }
            val confirmed =
                current.copy(
                    bookmarkId = createdBookmarkId?.also { require(it > 0L) },
                    isBookmarkInFlight = false,
                )
            mutableState.value = checkNotNull(mutableState.value).withItem(confirmed)
            DiscourseOptimisticMutationResult.Confirmed(confirmed)
        }

    private suspend fun rejectLike(
        token: LikeMutationToken,
        failure: DiscourseForumFailureKind,
    ): DiscourseOptimisticMutationResult {
        val rollback = rollbackLike(token)
        return DiscourseOptimisticMutationResult.Rejected(
            state = rollback.state,
            failure = failure,
            rolledBack = rollback.rolledBack,
        )
    }

    private suspend fun rejectBookmark(
        token: BookmarkMutationToken,
        failure: DiscourseForumFailureKind,
    ): DiscourseOptimisticMutationResult {
        val rollback = rollbackBookmark(token)
        return DiscourseOptimisticMutationResult.Rejected(
            state = rollback.state,
            failure = failure,
            rolledBack = rollback.rolledBack,
        )
    }

    private suspend fun rollbackLike(token: LikeMutationToken): ConditionalRollback =
        stateMutex.withLock {
            val current = currentMatching(token.accountId, token.sessionGeneration, token.target)
            if (current == null || current.likeEpoch != token.epoch || !current.isLikeInFlight) {
                return@withLock ConditionalRollback(current, rolledBack = false)
            }
            val restored =
                current.copy(
                    liked = token.previousLiked,
                    likeCount = token.previousLikeCount,
                    isLikeInFlight = false,
                )
            mutableState.value = checkNotNull(mutableState.value).withItem(restored)
            ConditionalRollback(restored, rolledBack = true)
        }

    private suspend fun rollbackBookmark(token: BookmarkMutationToken): ConditionalRollback =
        stateMutex.withLock {
            val current = currentMatching(token.accountId, token.sessionGeneration, token.target)
            if (current == null || current.bookmarkEpoch != token.epoch || !current.isBookmarkInFlight) {
                return@withLock ConditionalRollback(current, rolledBack = false)
            }
            val restored =
                current.copy(
                    bookmarked = token.previousBookmarked,
                    bookmarkId = token.previousBookmarkId,
                    isBookmarkInFlight = false,
                )
            mutableState.value = checkNotNull(mutableState.value).withItem(restored)
            ConditionalRollback(restored, rolledBack = true)
        }

    private fun partitionFor(session: DiscourseSessionState.Authenticated): DiscoursePostActionSnapshot {
        val current = mutableState.value
        return if (
            current?.accountId == session.accountId &&
            current.sessionGeneration == session.generation
        ) {
            current
        } else {
            DiscoursePostActionSnapshot(
                accountId = session.accountId,
                sessionGeneration = session.generation,
                items = emptyMap(),
            )
        }
    }

    private fun currentMatching(
        accountId: String,
        generation: Long,
        target: DiscourseActionTarget,
    ): DiscourseOptimisticActionState? {
        val snapshot = mutableState.value
        if (snapshot?.accountId != accountId || snapshot.sessionGeneration != generation) return null
        return snapshot.items[target]
    }
}

internal interface DiscoursePostActionRemoteDataSource {
    suspend fun createPostAction(request: DiscoursePostActionRequest): DiscourseActionResponse

    suspend fun deletePostAction(
        postId: Long,
        actionTypeId: Long,
    ): DiscourseActionResponse

    suspend fun createBookmark(request: DiscourseCreateBookmarkRequest): DiscourseBookmarkResponse

    suspend fun deleteBookmark(bookmarkId: Long)
}

private class DefaultDiscoursePostActionRemoteDataSource(
    private val dataSource: DiscourseDataSource,
) : DiscoursePostActionRemoteDataSource {
    override suspend fun createPostAction(request: DiscoursePostActionRequest): DiscourseActionResponse =
        dataSource.api.createPostAction(request)

    override suspend fun deletePostAction(
        postId: Long,
        actionTypeId: Long,
    ): DiscourseActionResponse = dataSource.api.deletePostAction(postId, actionTypeId)

    override suspend fun createBookmark(request: DiscourseCreateBookmarkRequest): DiscourseBookmarkResponse =
        dataSource.api.createBookmark(request)

    override suspend fun deleteBookmark(bookmarkId: Long) {
        dataSource.api.deleteBookmark(bookmarkId)
    }
}

private data class LikeMutationToken(
    val accountId: String,
    val sessionGeneration: Long,
    val target: DiscourseActionTarget.Post,
    val epoch: Long,
    val desired: Boolean,
    val previousLiked: Boolean,
    val previousLikeCount: Int,
)

private data class BookmarkMutationToken(
    val accountId: String,
    val sessionGeneration: Long,
    val target: DiscourseActionTarget,
    val epoch: Long,
    val desired: Boolean,
    val previousBookmarked: Boolean,
    val previousBookmarkId: Long?,
)

private data class MutationStart<T>(
    val token: T? = null,
    val result: DiscourseOptimisticMutationResult? = null,
) {
    companion object {
        fun <T> started(token: T): MutationStart<T> = MutationStart(token = token)

        fun <T> busy(state: DiscourseOptimisticActionState): MutationStart<T> =
            MutationStart(result = DiscourseOptimisticMutationResult.Busy(state))

        fun <T> notAllowed(
            reason: DiscourseActionNotAllowedReason,
            state: DiscourseOptimisticActionState?,
        ): MutationStart<T> =
            MutationStart(
                result = DiscourseOptimisticMutationResult.NotAllowed(state = state, reason = reason),
            )
    }
}

private data class ConditionalRollback(
    val state: DiscourseOptimisticActionState?,
    val rolledBack: Boolean,
)

private val DiscourseActionTarget.serverId: Long
    get() =
        when (this) {
            is DiscourseActionTarget.Post -> postId
            is DiscourseActionTarget.Topic -> topicId
        }

private val DiscourseActionTarget.bookmarkableType: String
    get() =
        when (this) {
            is DiscourseActionTarget.Post -> "Post"
            is DiscourseActionTarget.Topic -> "Topic"
        }

private fun DiscoursePostActionSnapshot.withItem(value: DiscourseOptimisticActionState): DiscoursePostActionSnapshot =
    copy(items = items + (value.target to value))

private fun optimisticLikeCount(
    current: Int,
    desiredLiked: Boolean,
): Int =
    if (desiredLiked) {
        (current.toLong() + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    } else {
        (current - 1).coerceAtLeast(0)
    }

private fun Long?.nextActionEpoch(): Long {
    val current = this ?: 0L
    check(current < Long.MAX_VALUE) { "Action epoch space is exhausted" }
    return current + 1L
}

private const val DISCOURSE_LIKE_ACTION_TYPE_ID: Long = 2L
