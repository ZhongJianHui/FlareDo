package dev.dimension.flare.data.network.discourse.realtime

import dev.dimension.flare.data.network.discourse.error.DiscourseAuthenticationException
import dev.dimension.flare.data.network.discourse.error.DiscourseCloudflareChallengeException
import dev.dimension.flare.data.network.discourse.error.DiscourseCsrfException
import dev.dimension.flare.data.network.discourse.error.DiscourseHttpException
import dev.dimension.flare.data.network.discourse.error.DiscourseNetworkException
import dev.dimension.flare.data.network.discourse.error.DiscoursePermissionException
import dev.dimension.flare.data.network.discourse.error.DiscourseRateLimitException
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationException
import dev.dimension.flare.data.network.discourse.error.DiscourseServerException
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.floor
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.TimeSource

private const val DEFAULT_RETRY_BASE_MILLIS: Long = 1_000L
private const val MAX_RETRY_DELAY_MILLIS: Long = 180_000L
private const val RETRY_JITTER_FLOOR: Double = 0.5
private const val DEFAULT_EMPTY_POLL_START_INTERVAL_MILLIS: Long = 15_000L
private const val DEFAULT_NON_EMPTY_POLL_START_INTERVAL_MILLIS: Long = 100L

/** Supplies a deterministic unit value for retry jitter. */
public fun interface DiscourseMessageBusRandomSource {
    /** Returns a value in the half-open range `[0, 1)`. */
    public fun nextUnitDouble(): Double
}

/** Injectable cancellation-cooperative delay used by polling and catch-up retries. */
public fun interface DiscourseMessageBusDelay {
    public suspend fun await(delayMillis: Long)
}

/** Injectable wall clock used only to expose a bounded retry deadline to diagnostics. */
public fun interface DiscourseMessageBusClock {
    public fun nowEpochMillis(): Long
}

/** Injectable monotonic time source used exclusively for start-to-start poll pacing. */
public fun interface DiscourseMessageBusMonotonicClock {
    public fun nowMillis(): Long
}

/** Creates the stable browser-compatible client id used by one [DiscourseMessageBus] instance. */
public fun interface DiscourseMessageBusClientIdFactory {
    public fun create(): String
}

/**
 * Resolves the polling origin and optional shared-session key for one immutable session lease.
 *
 * Implementations may obtain Discourse's ephemeral `long_polling_base_url` configuration, but must
 * not persist or cache its shared key outside the supplied session generation. Each invocation must
 * return a newly owned [DiscourseMessageBusEndpoint.SharedSession], because the MessageBus erases
 * its defensive key copy when that run exits. The coordinator invokes this provider from inside
 * `DiscourseSessionManager.runForCurrentSession`; a generation change therefore cancels an in-flight
 * resolution and resolves a new endpoint for the replacement lease.
 */
public fun interface DiscourseMessageBusEndpointProvider {
    public suspend fun endpoint(session: DiscourseSessionState): DiscourseMessageBusEndpoint
}

/**
 * Successful-poll pacing copied from Discourse's browser MessageBus client.
 *
 * An empty response targets a 15-second start-to-start interval (`callbackInterval`) while always
 * waiting at least `minPollInterval` after completion. A response containing an event may reconnect
 * after that 100-ms minimum. Measuring empty polls from request start avoids adding latency to a real
 * long poll while preventing a proxy or server that returns immediately from causing a hot loop.
 */
public data class DiscourseMessageBusPollPacing(
    public val callbackIntervalMillis: Long = DEFAULT_EMPTY_POLL_START_INTERVAL_MILLIS,
    public val minPollIntervalMillis: Long = DEFAULT_NON_EMPTY_POLL_START_INTERVAL_MILLIS,
) {
    init {
        require(callbackIntervalMillis in 1L..MAX_RETRY_DELAY_MILLIS) {
            "Empty MessageBus poll interval is invalid"
        }
        require(minPollIntervalMillis in 1L..callbackIntervalMillis) {
            "Non-empty MessageBus poll interval is invalid"
        }
    }
}

/** Retry source, allowing diagnostics to distinguish a long poll from a REST reconciliation. */
public enum class DiscourseMessageBusRetryPhase {
    Poll,
    Refresh,
    CatchUp,
}

/** Sanitized retry metadata; it contains no URL, payload, cookie, or server-provided message. */
public data class DiscourseMessageBusRetrySchedule(
    public val phase: DiscourseMessageBusRetryPhase,
    public val attempt: Int,
    public val delayMillis: Long,
    public val retryAtEpochMillis: Long,
) {
    init {
        require(attempt > 0) { "MessageBus retry attempt must be positive" }
        require(delayMillis in 0L..MAX_RETRY_DELAY_MILLIS) {
            "MessageBus retry delay exceeds the foreground limit"
        }
        require(retryAtEpochMillis >= 0L) { "MessageBus retry deadline must not be negative" }
    }
}

/** Optional bounded diagnostics hook for retry state. */
public fun interface DiscourseMessageBusRetryObserver {
    public fun onRetryScheduled(schedule: DiscourseMessageBusRetrySchedule)
}

/**
 * Capped exponential backoff with multiplicative jitter.
 *
 * A valid `Retry-After` is a lower bound whenever it is within the foreground polling ceiling. The
 * hard 180-second ceiling also applies to hostile or accidental day-scale headers, so a foreground
 * task never becomes an unobservable multi-hour sleeper. The next foreground resume performs REST
 * catch-up before opening another subscription and therefore remains the recovery boundary.
 */
public class DiscourseMessageBusRetryPolicy(
    private val randomSource: DiscourseMessageBusRandomSource =
        DiscourseMessageBusRandomSource { Random.Default.nextDouble() },
    private val baseDelayMillis: Long = DEFAULT_RETRY_BASE_MILLIS,
    private val maxDelayMillis: Long = MAX_RETRY_DELAY_MILLIS,
) {
    init {
        require(baseDelayMillis in 1L..MAX_RETRY_DELAY_MILLIS) {
            "MessageBus retry base is invalid"
        }
        require(maxDelayMillis in baseDelayMillis..MAX_RETRY_DELAY_MILLIS) {
            "MessageBus retry maximum is invalid"
        }
    }

    internal fun delayMillis(
        attempt: Int,
        retryAfterSeconds: Long?,
    ): Long {
        require(attempt > 0) { "MessageBus retry attempt must be positive" }
        require(retryAfterSeconds == null || retryAfterSeconds >= 0L) {
            "MessageBus Retry-After must not be negative"
        }
        val random = randomSource.nextUnitDouble()
        require(random >= 0.0 && random < 1.0 && random.isFinite()) {
            "MessageBus random source must return a finite unit value"
        }

        var exponential = baseDelayMillis
        repeat((attempt - 1).coerceAtMost(62)) {
            exponential = (exponential * 2L).coerceAtMost(maxDelayMillis)
        }
        val jitterFloor = floor(exponential * RETRY_JITTER_FLOOR).toLong()
        val jittered =
            jitterFloor +
                floor((exponential - jitterFloor + 1L).toDouble() * random).toLong()
        val retryAfterMillis =
            retryAfterSeconds
                ?.coerceAtMost(maxDelayMillis / 1_000L)
                ?.times(1_000L)
                ?: 0L
        return maxOf(jittered, retryAfterMillis).coerceAtMost(maxDelayMillis)
    }
}

/**
 * Foreground Linux.do MessageBus loop.
 *
 * This class deliberately owns no [kotlinx.coroutines.CoroutineScope]. [run] remains a structured
 * child of its caller, and cancelling that caller immediately cancels collection of the in-flight
 * Ktor response. One instance keeps one client id and one monotonic `__seq` across session and
 * subscription rebuilds. A second concurrent call is serialized by [runMutex], although the host
 * normally resolves this class as a singleton and runs it from exactly one presenter lifecycle.
 */
public class DiscourseMessageBus(
    private val transport: DiscourseMessageBusTransport,
    private val cursorStore: DiscourseMessageBusCursorStore,
    clientIdFactory: DiscourseMessageBusClientIdFactory =
        DiscourseMessageBusClientIdFactory(::createDiscourseMessageBusClientId),
    retryPolicy: DiscourseMessageBusRetryPolicy = DiscourseMessageBusRetryPolicy(),
    private val retryDelay: DiscourseMessageBusDelay = DiscourseMessageBusDelay { delay(it) },
    private val clock: DiscourseMessageBusClock =
        DiscourseMessageBusClock {
            Clock.System.now().toEpochMilliseconds()
        },
    private val monotonicClock: DiscourseMessageBusMonotonicClock =
        DiscourseMessageBusMonotonicClock(::defaultMonotonicMillis),
    retryObserver: DiscourseMessageBusRetryObserver = DiscourseMessageBusRetryObserver {},
    private val pollPacing: DiscourseMessageBusPollPacing = DiscourseMessageBusPollPacing(),
) {
    private val clientId: String =
        clientIdFactory.create().also { generated ->
            require(isValidDiscourseMessageBusClientId(generated)) {
                "MessageBus client ID factory returned an invalid value"
            }
        }
    private val sequence: MutableStateFlow<Long> = MutableStateFlow(0L)
    private val runMutex: Mutex = Mutex()
    private var lastPollAbortedAt: Long? = null
    private val retrier =
        DiscourseMessageBusRetrier(
            policy = retryPolicy,
            delay = retryDelay,
            clock = clock,
            observer = retryObserver,
        )

    /**
     * Polls until its caller cancels it or a terminal exception is encountered.
     *
     * The caller must wrap this function in `DiscourseSessionManager.runForCurrentSession`; that
     * lease supplies the generation/revision context consumed by the protected Cookie client.
     * [subscription] contains only locally constructed, allowlisted channels. Event payloads are
     * never trusted as cache data: the CAS winner invokes [refresh] with a typed REST refresh route.
     * Ownership of a shared-session [endpoint] is transferred to this call and its defensive key
     * buffer is erased in `finally`; callers must not reuse that endpoint instance.
     */
    public suspend fun run(
        subscription: DiscourseRealtimeSubscription,
        endpoint: DiscourseMessageBusEndpoint = DiscourseMessageBusEndpoint.SameOrigin,
        refresh: suspend (DiscourseRealtimeRefresh) -> Unit,
    ) {
        try {
            runMutex.withLock {
                runLoop(
                    subscription = subscription,
                    endpoint = endpoint,
                    refresh = refresh,
                )
            }
        } finally {
            (endpoint as? DiscourseMessageBusEndpoint.SharedSession)?.clear()
        }
    }

    private suspend fun runLoop(
        subscription: DiscourseRealtimeSubscription,
        endpoint: DiscourseMessageBusEndpoint,
        refresh: suspend (DiscourseRealtimeRefresh) -> Unit,
    ) {
        awaitAbortedPollPacing()
        val cursors =
            subscription.routes.keys.associateWithTo(linkedMapOf()) { channel ->
                cursorStore.read(subscription.cursorAccountId, channel)
                    ?: DISCOURSE_MESSAGE_BUS_INITIAL_CURSOR
            }
        var consecutiveFailures = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            val pollStartedAt = checkedMonotonicNow()
            var receivedEvents = false
            val request =
                DiscourseMessageBusPollRequest(
                    clientId = clientId,
                    sequence = nextSequence(),
                    channels = cursors,
                    endpoint = endpoint,
                )
            try {
                transport.poll(request).collect { batch ->
                    currentCoroutineContext().ensureActive()
                    if (batch.events.isNotEmpty()) {
                        receivedEvents = true
                        consecutiveFailures = 0
                    }
                    batch.events.forEach { event ->
                        processEvent(
                            event = event,
                            subscription = subscription,
                            cursors = cursors,
                            refresh = refresh,
                        )
                    }
                }
                // An empty, normally completed long poll is still a successful connection.
                consecutiveFailures = 0
                awaitSuccessfulPollPacing(
                    pollStartedAt = pollStartedAt,
                    receivedEvents = receivedEvents,
                )
            } catch (cancellation: CancellationException) {
                lastPollAbortedAt = checkedMonotonicNow()
                throw cancellation
            } catch (failure: Throwable) {
                if (failure.toSessionRecoveryReasonOrNull() != null) throw failure
                if (!failure.isRetryableDiscourseRealtimeFailure()) throw failure
                consecutiveFailures = consecutiveFailures.nextRetryAttempt()
                retrier.awaitRetry(
                    phase = DiscourseMessageBusRetryPhase.Poll,
                    attempt = consecutiveFailures,
                    retryAfterSeconds = failure.discourseRetryAfterSecondsOrNull(),
                )
            }
        }
    }

    private suspend fun awaitAbortedPollPacing() {
        val abortedAt = lastPollAbortedAt ?: return
        val now = checkedMonotonicNow()
        val elapsed = if (now >= abortedAt) now - abortedAt else 0L
        val remaining = (pollPacing.minPollIntervalMillis - elapsed).coerceAtLeast(0L)
        if (remaining > 0L) retryDelay.await(remaining)
        lastPollAbortedAt = null
    }

    private suspend fun awaitSuccessfulPollPacing(
        pollStartedAt: Long,
        receivedEvents: Boolean,
    ) {
        val finishedAt = checkedMonotonicNow()
        val elapsed = if (finishedAt >= pollStartedAt) finishedAt - pollStartedAt else 0L
        val remaining =
            if (receivedEvents) {
                pollPacing.minPollIntervalMillis
            } else {
                maxOf(
                    pollPacing.minPollIntervalMillis,
                    pollPacing.callbackIntervalMillis - elapsed,
                )
            }
        retryDelay.await(remaining)
    }

    private fun checkedMonotonicNow(): Long =
        monotonicClock.nowMillis().also {
            require(it >= 0L) { "MessageBus monotonic clock must not be negative" }
        }

    private suspend fun processEvent(
        event: DiscourseMessageBusEvent,
        subscription: DiscourseRealtimeSubscription,
        cursors: MutableMap<String, Long>,
        refresh: suspend (DiscourseRealtimeRefresh) -> Unit,
    ) {
        when (event) {
            is DiscourseMessageBusMessage -> {
                val route = subscription.routes[event.channel] ?: return
                val advance =
                    cursorStore.advance(
                        accountId = subscription.cursorAccountId,
                        channel = event.channel,
                        messageId = event.messageId,
                    )
                cursors[event.channel] = advance.cursor
                if (advance.advanced) {
                    refreshWithRetry(route.refresh, refresh)
                }
            }

            is DiscourseMessageBusStatus -> {
                // Never persist `/__status` itself and never accept a status-supplied unknown key.
                // The status frame is also the first-poll handshake. A cursor that moved between
                // REST catch-up and this frame represents a real reconciliation gap, so every CAS
                // winner must refresh its allowlisted route before polling continues. Collect the
                // typed routes first so one status frame cannot request the same refresh twice.
                val advancedRefreshes = linkedSetOf<DiscourseRealtimeRefresh>()
                subscription.routes.forEach { (subscribedChannel, route) ->
                    val candidate = event.cursors[subscribedChannel] ?: return@forEach
                    val advance =
                        cursorStore.advance(
                            accountId = subscription.cursorAccountId,
                            channel = subscribedChannel,
                            messageId = candidate,
                        )
                    cursors[subscribedChannel] = advance.cursor
                    if (advance.advanced) advancedRefreshes += route.refresh
                }
                advancedRefreshes.forEach { request ->
                    refreshWithRetry(request, refresh)
                }
            }
        }
    }

    private suspend fun refreshWithRetry(
        request: DiscourseRealtimeRefresh,
        refresh: suspend (DiscourseRealtimeRefresh) -> Unit,
    ) {
        var consecutiveFailures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                refresh(request)
                return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                if (failure.toSessionRecoveryReasonOrNull() != null) throw failure
                if (!failure.isRetryableDiscourseRealtimeFailure()) throw failure
                consecutiveFailures = consecutiveFailures.nextRetryAttempt()
                retrier.awaitRetry(
                    phase = DiscourseMessageBusRetryPhase.Refresh,
                    attempt = consecutiveFailures,
                    retryAfterSeconds = failure.discourseRetryAfterSecondsOrNull(),
                )
            }
        }
    }

    private fun nextSequence(): Long {
        while (true) {
            val current = sequence.value
            check(current < MAX_DISCOURSE_MESSAGE_BUS_SAFE_INTEGER) {
                "MessageBus sequence space is exhausted"
            }
            val next = current + 1L
            if (sequence.compareAndSet(current, next)) return next
        }
    }
}

internal class DiscourseMessageBusRetrier(
    private val policy: DiscourseMessageBusRetryPolicy,
    private val delay: DiscourseMessageBusDelay,
    private val clock: DiscourseMessageBusClock,
    private val observer: DiscourseMessageBusRetryObserver,
) {
    suspend fun awaitRetry(
        phase: DiscourseMessageBusRetryPhase,
        attempt: Int,
        retryAfterSeconds: Long?,
    ) {
        val scheduledAt = clock.nowEpochMillis()
        require(scheduledAt >= 0L) { "MessageBus clock must not be negative" }
        val delayMillis = policy.delayMillis(attempt, retryAfterSeconds)
        val schedule =
            DiscourseMessageBusRetrySchedule(
                phase = phase,
                attempt = attempt,
                delayMillis = delayMillis,
                retryAtEpochMillis = scheduledAt.saturatedPlus(delayMillis),
            )
        observer.onRetryScheduled(schedule)
        delay.await(delayMillis)
    }
}

internal fun Throwable.toSessionRecoveryReasonOrNull(): DiscourseSessionRecoveryReason? =
    when (this) {
        is DiscourseAuthenticationException -> {
            DiscourseSessionRecoveryReason.AuthenticationRequired
        }

        is DiscoursePermissionException, is DiscourseCsrfException -> {
            DiscourseSessionRecoveryReason.PermissionDenied
        }

        is DiscourseCloudflareChallengeException -> {
            DiscourseSessionRecoveryReason.ManualChallengeRequired
        }

        is DiscourseHttpException -> {
            when (statusCode) {
                401 -> DiscourseSessionRecoveryReason.AuthenticationRequired
                403 -> DiscourseSessionRecoveryReason.PermissionDenied
                else -> null
            }
        }

        else -> {
            null
        }
    }

internal fun Throwable.isRetryableDiscourseRealtimeFailure(): Boolean =
    this is DiscourseRateLimitException ||
        this is DiscourseNetworkException ||
        this is DiscourseServerException ||
        this is DiscourseSerializationException ||
        (this is DiscourseHttpException && statusCode in RETRYABLE_HTTP_STATUS_CODES)

internal fun Throwable.discourseRetryAfterSecondsOrNull(): Long? = (this as? DiscourseRateLimitException)?.retryAfterSeconds

private fun Int.nextRetryAttempt(): Int {
    check(this < Int.MAX_VALUE) { "MessageBus retry attempt space is exhausted" }
    return this + 1
}

private fun Long.saturatedPlus(other: Long): Long = if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

private fun createDiscourseMessageBusClientId(): String {
    val bytes = Random.Default.nextBytes(16)
    return try {
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        bytes.joinToString(separator = "") { byte ->
            byte.toUByte().toString(radix = 16).padStart(length = 2, padChar = '0')
        }
    } finally {
        bytes.fill(0)
    }
}

private val MESSAGE_BUS_MONOTONIC_ORIGIN = TimeSource.Monotonic.markNow()

private fun defaultMonotonicMillis(): Long = MESSAGE_BUS_MONOTONIC_ORIGIN.elapsedNow().inWholeMilliseconds

private val RETRYABLE_HTTP_STATUS_CODES: Set<Int> = setOf(408, 425, 502, 503, 504)
