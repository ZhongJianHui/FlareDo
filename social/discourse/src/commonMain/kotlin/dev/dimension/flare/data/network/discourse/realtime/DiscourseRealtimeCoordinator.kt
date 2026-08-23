package dev.dimension.flare.data.network.discourse.realtime

import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionState
import dev.dimension.flare.data.network.discourse.session.StaleDiscourseSessionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.time.Clock

private const val ANONYMOUS_CURSOR_ACCOUNT_ID: String = "anonymous"
private const val LATEST_CHANNEL: String = "/latest"
private const val NEW_CHANNEL: String = "/new"

/** Host-controlled foreground and navigation state. */
public data class DiscourseRealtimeHostState(
    public val isForeground: Boolean = false,
    public val activeTopicId: Long? = null,
) {
    init {
        require(activeTopicId == null || activeTopicId > 0L) {
            "Active realtime topic id must be positive"
        }
    }
}

/** Immutable REST reconciliation boundary captured before a subscription is opened. */
public data class DiscourseRealtimeCatchUp(
    public val expectedSessionGeneration: Long,
    public val accountId: String?,
    public val activeTopicId: Long?,
    public val channels: Set<String>,
) {
    init {
        require(expectedSessionGeneration >= 0L) { "Realtime generation must not be negative" }
        require(activeTopicId == null || activeTopicId > 0L) {
            "Realtime catch-up topic id must be positive"
        }
        require(channels.isNotEmpty()) { "Realtime catch-up channels must not be empty" }
    }
}

/**
 * A request to re-fetch authoritative REST state after a MessageBus signal.
 *
 * No MessageBus JSON payload is present in these types. In particular, topic and reaction signals
 * must call the topic REST endpoint instead of writing their incomplete plugin payload to cache.
 */
public sealed interface DiscourseRealtimeRefresh {
    public data object Latest : DiscourseRealtimeRefresh

    public data object NewTopics : DiscourseRealtimeRefresh

    public data class Notifications(
        public val userId: Long,
    ) : DiscourseRealtimeRefresh {
        init {
            require(userId > 0L) { "Notification refresh user id must be positive" }
        }
    }

    public data class Topic(
        public val topicId: Long,
        public val reason: DiscourseRealtimeTopicRefreshReason,
    ) : DiscourseRealtimeRefresh {
        init {
            require(topicId > 0L) { "Topic refresh id must be positive" }
        }
    }
}

public enum class DiscourseRealtimeTopicRefreshReason {
    Posts,
    Reactions,
}

/** Sanitized terminal reason that contains no server response or credential material. */
public enum class DiscourseSessionRecoveryReason {
    AuthenticationRequired,
    PermissionDenied,
    ManualChallengeRequired,
}

/** Generation-CAS input for a host's login/session recovery flow. */
public data class DiscourseSessionRecoveryRequest(
    public val expectedSessionGeneration: Long,
    public val reason: DiscourseSessionRecoveryReason,
) {
    init {
        require(expectedSessionGeneration >= 0L) {
            "Session recovery generation must not be negative"
        }
    }
}

/**
 * Host callbacks used for authoritative reconciliation and terminal session recovery.
 *
 * [catchUp] runs after every foreground/session/subscription transition and must finish before the
 * first poll for that snapshot. [refresh] must fetch REST state or enqueue that exact idempotent
 * fetch. [recoverSession] receives the generation it may replace, allowing login/logout code to use
 * generation CAS and avoid recovering an already replaced account.
 */
public interface DiscourseRealtimeCallbacks {
    public suspend fun catchUp(request: DiscourseRealtimeCatchUp)

    public suspend fun refresh(request: DiscourseRealtimeRefresh)

    public suspend fun recoverSession(request: DiscourseSessionRecoveryRequest)

    /**
     * Reports a sanitized, non-terminal pipeline failure for the supplied session generation.
     *
     * The failed subscription remains dormant until foreground, topic, or session state changes.
     * This prevents a malformed response from creating a hot restart loop while keeping the
     * presenter capable of rebuilding realtime work without reconstructing the whole screen.
     */
    public suspend fun pipelineFailed(expectedSessionGeneration: Long) {}
}

internal data class DiscourseRealtimeRoute(
    val channel: String,
    val refresh: DiscourseRealtimeRefresh,
)

/** Validated subscriptions for one session generation and one navigation snapshot. */
public class DiscourseRealtimeSubscription internal constructor(
    public val expectedSessionGeneration: Long,
    public val accountId: String?,
    public val activeTopicId: Long?,
    internal val cursorAccountId: String,
    routes: Map<String, DiscourseRealtimeRoute>,
) {
    internal val routes: Map<String, DiscourseRealtimeRoute> = routes.toMap()
    public val channels: Set<String> = this.routes.keys.toSet()

    internal fun toCatchUp(): DiscourseRealtimeCatchUp =
        DiscourseRealtimeCatchUp(
            expectedSessionGeneration = expectedSessionGeneration,
            accountId = accountId,
            activeTopicId = activeTopicId,
            channels = channels,
        )

    internal companion object {
        fun create(
            session: DiscourseSessionState,
            activeTopicId: Long?,
        ): DiscourseRealtimeSubscription {
            val authenticated = session as? DiscourseSessionState.Authenticated
            val userId = authenticated?.accountId?.toPositiveUserId()
            val routes =
                buildList {
                    add(DiscourseRealtimeRoute(LATEST_CHANNEL, DiscourseRealtimeRefresh.Latest))
                    add(DiscourseRealtimeRoute(NEW_CHANNEL, DiscourseRealtimeRefresh.NewTopics))
                    userId?.let {
                        add(
                            DiscourseRealtimeRoute(
                                channel = "/notification/$it",
                                refresh = DiscourseRealtimeRefresh.Notifications(it),
                            ),
                        )
                    }
                    activeTopicId?.let { topicId ->
                        require(topicId > 0L) { "Active realtime topic id must be positive" }
                        add(
                            DiscourseRealtimeRoute(
                                channel = "/topic/$topicId",
                                refresh =
                                    DiscourseRealtimeRefresh.Topic(
                                        topicId = topicId,
                                        reason = DiscourseRealtimeTopicRefreshReason.Posts,
                                    ),
                            ),
                        )
                        add(
                            DiscourseRealtimeRoute(
                                channel = "/topic/$topicId/reactions",
                                refresh =
                                    DiscourseRealtimeRefresh.Topic(
                                        topicId = topicId,
                                        reason = DiscourseRealtimeTopicRefreshReason.Reactions,
                                    ),
                            ),
                        )
                    }
                }.associateBy(DiscourseRealtimeRoute::channel)
            return DiscourseRealtimeSubscription(
                expectedSessionGeneration = session.generation,
                accountId = authenticated?.accountId,
                activeTopicId = activeTopicId,
                cursorAccountId = authenticated?.accountId ?: ANONYMOUS_CURSOR_ACCOUNT_ID,
                routes = routes,
            )
        }
    }
}

/**
 * Lifecycle bridge for foreground-only Linux.do realtime updates.
 *
 * The coordinator owns no coroutine scope. A Compose/Molecule or SwiftUI host calls [run] from its
 * lifecycle scope and cancels that structured child when the presenter disappears. [updateHostState]
 * is non-blocking and cancels the active `collectLatest` branch when foreground, topic selection, or
 * session generation changes. Background state creates no polling child at all. Hosts must create
 * this coordinator with factory lifetime because [mutableHostState] belongs to one presenter.
 */
public class DiscourseRealtimeCoordinator(
    private val sessionManager: DiscourseSessionManager,
    private val messageBus: DiscourseMessageBus,
    private val endpointProvider: DiscourseMessageBusEndpointProvider =
        DiscourseMessageBusEndpointProvider { DiscourseMessageBusEndpoint.SameOrigin },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    catchUpRetryPolicy: DiscourseMessageBusRetryPolicy = DiscourseMessageBusRetryPolicy(),
    retryDelay: DiscourseMessageBusDelay = DiscourseMessageBusDelay { delay(it) },
    clock: DiscourseMessageBusClock =
        DiscourseMessageBusClock {
            Clock.System.now().toEpochMilliseconds()
        },
    retryObserver: DiscourseMessageBusRetryObserver = DiscourseMessageBusRetryObserver {},
) {
    private val mutableHostState = MutableStateFlow(DiscourseRealtimeHostState())
    private val catchUpRetrier =
        DiscourseMessageBusRetrier(
            policy = catchUpRetryPolicy,
            delay = retryDelay,
            clock = clock,
            observer = retryObserver,
        )

    /** Atomically replaces foreground and active-topic state without launching unstructured work. */
    public fun updateHostState(state: DiscourseRealtimeHostState) {
        mutableHostState.value = state
    }

    /** Convenience update for Android/Desktop foreground lifecycle callbacks. */
    public fun setForeground(isForeground: Boolean) {
        mutableHostState.update { it.copy(isForeground = isForeground) }
    }

    /** Convenience update for list-detail navigation on every host. */
    public fun setActiveTopic(topicId: Long?) {
        require(topicId == null || topicId > 0L) { "Active realtime topic id must be positive" }
        mutableHostState.update { it.copy(activeTopicId = topicId) }
    }

    /** Runs with callbacks owned by exactly one presenter until that presenter is cancelled. */
    public suspend fun run(callbacks: DiscourseRealtimeCallbacks): Unit =
        withContext(dispatcher) {
            var recoveryBlockedGeneration: Long? = null
            combine(mutableHostState, sessionManager.state) { host, session ->
                if (host.isForeground) {
                    DiscourseRealtimeSubscription.create(session, host.activeTopicId)
                } else {
                    null
                }
            }.distinctUntilChanged { previous, current ->
                (previous == null && current == null) || previous?.sameTargetAs(current) == true
            }.collectLatest { subscription ->
                if (subscription == null) return@collectLatest
                if (recoveryBlockedGeneration == subscription.expectedSessionGeneration) {
                    return@collectLatest
                }

                val recoveryReason =
                    try {
                        runSubscription(subscription, callbacks)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        callbacks.pipelineFailed(subscription.expectedSessionGeneration)
                        // collectLatest cancels this dormant branch as soon as the host target or
                        // session changes, then builds a fresh catch-up + subscription pipeline.
                        // Waiting here avoids repeatedly polling a deterministically bad response.
                        awaitCancellation()
                    }
                if (recoveryReason != null) {
                    // Set the terminal gate before invoking host code. Topic or foreground changes
                    // cannot reopen the same failed generation while recovery UI is still running.
                    recoveryBlockedGeneration = subscription.expectedSessionGeneration
                    callbacks.recoverSession(
                        DiscourseSessionRecoveryRequest(
                            expectedSessionGeneration = subscription.expectedSessionGeneration,
                            reason = recoveryReason,
                        ),
                    )
                }
            }
        }

    private suspend fun runSubscription(
        subscription: DiscourseRealtimeSubscription,
        callbacks: DiscourseRealtimeCallbacks,
    ): DiscourseSessionRecoveryReason? =
        try {
            sessionManager.runForCurrentSession {
                check(generation == subscription.expectedSessionGeneration) {
                    "Realtime subscription entered a different session generation"
                }
                val actualAccountId = (this as? DiscourseSessionState.Authenticated)?.accountId
                check(actualAccountId == subscription.accountId) {
                    "Realtime subscription entered a different account"
                }
                catchUpWithRetry(subscription.toCatchUp(), callbacks)
                // Resolve shared-session material only inside this immutable generation lease. The
                // endpoint remains a local stack value and is discarded when this child is cancelled.
                val endpoint = endpointProvider.endpoint(this)
                try {
                    messageBus.run(
                        subscription = subscription,
                        endpoint = endpoint,
                        refresh = callbacks::refresh,
                    )
                } finally {
                    // Idempotent with MessageBus.run's cleanup and covers cancellation in the small
                    // handoff boundary between a suspending provider and MessageBus ownership.
                    (endpoint as? DiscourseMessageBusEndpoint.SharedSession)?.clear()
                }
            }
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: StaleDiscourseSessionException) {
            // The session flow owns the replacement generation and immediately rebuilds this child.
            null
        } catch (failure: Throwable) {
            failure.toSessionRecoveryReasonOrNull() ?: throw failure
        }

    private suspend fun catchUpWithRetry(
        request: DiscourseRealtimeCatchUp,
        callbacks: DiscourseRealtimeCallbacks,
    ) {
        var consecutiveFailures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                callbacks.catchUp(request)
                return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                if (failure.toSessionRecoveryReasonOrNull() != null) throw failure
                if (!failure.isRetryableDiscourseRealtimeFailure()) throw failure
                consecutiveFailures = consecutiveFailures.nextCatchUpAttempt()
                catchUpRetrier.awaitRetry(
                    phase = DiscourseMessageBusRetryPhase.CatchUp,
                    attempt = consecutiveFailures,
                    retryAfterSeconds = failure.discourseRetryAfterSecondsOrNull(),
                )
            }
        }
    }
}

private fun DiscourseRealtimeSubscription.sameTargetAs(other: DiscourseRealtimeSubscription?): Boolean =
    other != null &&
        expectedSessionGeneration == other.expectedSessionGeneration &&
        accountId == other.accountId &&
        activeTopicId == other.activeTopicId

private fun String.toPositiveUserId(): Long {
    require(isNotEmpty() && all { it in '0'..'9' }) {
        "Authenticated Linux.do account id must be numeric"
    }
    val userId =
        toLongOrNull()?.takeIf { it > 0L }
            ?: throw IllegalArgumentException("Authenticated Linux.do account id is invalid")
    require(userId.toString() == this) {
        "Authenticated Linux.do account id must use its canonical decimal representation"
    }
    return userId
}

private fun Int.nextCatchUpAttempt(): Int {
    check(this < Int.MAX_VALUE) { "Realtime catch-up retry attempt space is exhausted" }
    return this + 1
}
