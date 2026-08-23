package dev.dimension.flare.data.network.discourse.realtime

import dev.dimension.flare.data.network.discourse.error.DiscourseAuthenticationException
import dev.dimension.flare.data.network.discourse.error.DiscoursePermissionException
import dev.dimension.flare.data.network.discourse.error.DiscourseRateLimitException
import dev.dimension.flare.data.network.discourse.error.DiscourseServerException
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieRevisionContext
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class DiscourseMessageBusTest {
    @Test
    fun backgroundDoesNotPollAndForegroundCatchesUpBeforeSubscribing() =
        runTest {
            val timeline = mutableListOf<String>()
            val transport =
                RecordingTransport { request ->
                    flow {
                        timeline += "poll:${request.sequence}"
                        awaitCancellation()
                    }
                }
            val coordinator =
                coordinator(
                    sessionManager = DiscourseSessionManager(),
                    transport = transport,
                    callbacks =
                        RecordingCallbacks(
                            onCatchUp = { timeline += "catch-up" },
                        ),
                )

            coordinator.value.setActiveTopic(7L)
            backgroundScope.launch { coordinator.value.run(coordinator.callbacks) }
            runCurrent()
            assertTrue(transport.requests.isEmpty())

            coordinator.value.setForeground(true)
            runCurrent()

            assertEquals(listOf("catch-up", "poll:1"), timeline)
            assertEquals(
                setOf(
                    "/latest",
                    "/new",
                    "/topic/7",
                    "/topic/7/reactions",
                ),
                transport.requests
                    .single()
                    .channels.keys,
            )
            assertTrue(
                transport.requests
                    .single()
                    .channels.values
                    .all { it == -1L },
            )

            coordinator.value.setForeground(false)
            runCurrent()
            assertEquals(1, transport.cancelledPolls)
        }

    @Test
    fun topicSessionAndForegroundChangesCancelPollAndKeepSequenceMonotonic() =
        runTest {
            val manager = DiscourseSessionManager()
            manager.startAuthenticatedSession(accountId = "42", username = "fixture")
            val timeline = mutableListOf<String>()
            val transport =
                RecordingTransport { request ->
                    flow {
                        timeline += "poll:${request.sequence}"
                        awaitCancellation()
                    }
                }
            val holder =
                coordinator(
                    sessionManager = manager,
                    transport = transport,
                    callbacks = RecordingCallbacks(onCatchUp = { timeline += "catch-up" }),
                )
            backgroundScope.launch { holder.value.run(holder.callbacks) }
            holder.value.updateHostState(
                DiscourseRealtimeHostState(isForeground = true, activeTopicId = 7L),
            )
            runCurrent()

            holder.value.setActiveTopic(8L)
            runCurrent()
            advanceTimeBy(100L)
            runCurrent()
            manager.logout()
            runCurrent()
            advanceTimeBy(100L)
            runCurrent()
            holder.value.setForeground(false)
            runCurrent()
            holder.value.setForeground(true)
            runCurrent()
            advanceTimeBy(100L)
            runCurrent()

            assertEquals(listOf(1L, 2L, 3L, 4L), transport.requests.map { it.sequence })
            assertEquals(3, transport.cancelledPolls)
            assertEquals(4, holder.callbacks.catchUps.size)
            assertTrue("/notification/42" in transport.requests[0].channels)
            assertTrue("/topic/7" in transport.requests[0].channels)
            assertTrue("/topic/8" in transport.requests[1].channels)
            assertFalse("/notification/42" in transport.requests[2].channels)
            assertEquals(
                listOf(
                    "catch-up",
                    "poll:1",
                    "catch-up",
                    "poll:2",
                    "catch-up",
                    "poll:3",
                    "catch-up",
                    "poll:4",
                ),
                timeline,
            )
        }

    @Test
    fun messageIdCasDeduplicatesAndStatusHandshakeRefreshesOnlyAdvancedSubscribedChannels() =
        runTest {
            val store = RecordingCursorStore()
            val transport =
                RecordingTransport {
                    flow {
                        emit(
                            DiscourseMessageBusBatch(
                                listOf(
                                    message(
                                        globalId = 900L,
                                        messageId = 5L,
                                        channel = "/topic/7",
                                    ),
                                    message(
                                        globalId = 901L,
                                        messageId = 5L,
                                        channel = "/topic/7",
                                    ),
                                    DiscourseMessageBusStatus(
                                        globalId = 902L,
                                        messageId = 77L,
                                        cursors =
                                            mapOf(
                                                "/latest" to 10L,
                                                "/plugin/untrusted" to 99L,
                                            ),
                                    ),
                                    DiscourseMessageBusStatus(
                                        globalId = 903L,
                                        messageId = 78L,
                                        cursors = mapOf("/latest" to 10L),
                                    ),
                                ),
                            ),
                        )
                        awaitCancellation()
                    }
                }
            val refreshes = mutableListOf<DiscourseRealtimeRefresh>()
            val bus = messageBus(transport = transport, cursorStore = store)
            val subscription =
                DiscourseRealtimeSubscription.create(
                    session = DiscourseSessionState.Guest(generation = 0L),
                    activeTopicId = 7L,
                )

            backgroundScope.launch { bus.run(subscription) { refreshes += it } }
            runCurrent()

            assertEquals(
                listOf<DiscourseRealtimeRefresh>(
                    DiscourseRealtimeRefresh.Topic(
                        topicId = 7L,
                        reason = DiscourseRealtimeTopicRefreshReason.Posts,
                    ),
                    DiscourseRealtimeRefresh.Latest,
                ),
                refreshes,
            )
            assertEquals(5L, store.read("anonymous", "/topic/7"))
            assertEquals(10L, store.read("anonymous", "/latest"))
            assertFalse(store.advancedChannels.any { it == "/plugin/untrusted" })
        }

    @Test
    fun topicAndReactionSignalsRequestRestRefreshWithoutUsingPayload() =
        runTest {
            val transport =
                RecordingTransport {
                    flow {
                        emit(
                            DiscourseMessageBusBatch(
                                listOf(
                                    message(1L, 2L, "/topic/7"),
                                    message(2L, 3L, "/topic/7/reactions"),
                                ),
                            ),
                        )
                        awaitCancellation()
                    }
                }
            val refreshes = mutableListOf<DiscourseRealtimeRefresh>()
            val bus = messageBus(transport)

            backgroundScope.launch {
                bus.run(
                    subscription =
                        DiscourseRealtimeSubscription.create(
                            session = DiscourseSessionState.Guest(0L),
                            activeTopicId = 7L,
                        ),
                    refresh = { refreshes += it },
                )
            }
            runCurrent()

            assertEquals(
                listOf<DiscourseRealtimeRefresh>(
                    DiscourseRealtimeRefresh.Topic(7L, DiscourseRealtimeTopicRefreshReason.Posts),
                    DiscourseRealtimeRefresh.Topic(7L, DiscourseRealtimeTopicRefreshReason.Reactions),
                ),
                refreshes,
            )
        }

    @Test
    fun nonRetryableFailureWaitsForTargetChangeBeforeRebuildingSubscription() =
        runTest {
            val manager = DiscourseSessionManager()
            val transport =
                RecordingTransport { request ->
                    if (request.sequence == 1L) {
                        flow { throw IllegalStateException("fixture protocol failure") }
                    } else {
                        flow { awaitCancellation() }
                    }
                }
            val callbacks = RecordingCallbacks()
            val holder =
                coordinator(
                    sessionManager = manager,
                    transport = transport,
                    callbacks = callbacks,
                )

            backgroundScope.launch { holder.value.run(callbacks) }
            holder.value.setForeground(true)
            runCurrent()

            assertEquals(1, transport.requests.size)
            assertEquals(listOf(0L), callbacks.pipelineFailures)
            runCurrent()
            assertEquals(1, transport.requests.size, "A failed target must not hot-loop")

            holder.value.setActiveTopic(7L)
            runCurrent()

            assertEquals(2, transport.requests.size)
            assertTrue("/topic/7" in transport.requests.last().channels)
            assertEquals(2, callbacks.catchUps.size)
        }

    @Test
    fun authenticationFailureStopsGenerationBeforeRecoveryAndDoesNotReconnect() =
        runTest {
            val manager = DiscourseSessionManager()
            val transport =
                RecordingTransport { request ->
                    if (request.sequence == 1L) {
                        flow { throw DiscourseAuthenticationException() }
                    } else {
                        flow { awaitCancellation() }
                    }
                }
            val holder =
                coordinator(
                    sessionManager = manager,
                    transport = transport,
                    callbacks = RecordingCallbacks(),
                )
            backgroundScope.launch { holder.value.run(holder.callbacks) }
            holder.value.setForeground(true)
            runCurrent()

            assertEquals(
                listOf(
                    DiscourseSessionRecoveryRequest(
                        expectedSessionGeneration = 0L,
                        reason = DiscourseSessionRecoveryReason.AuthenticationRequired,
                    ),
                ),
                holder.callbacks.recoveries,
            )
            assertEquals(1, transport.requests.size)

            holder.value.setActiveTopic(9L)
            holder.value.setForeground(false)
            holder.value.setForeground(true)
            runCurrent()
            assertEquals(1, transport.requests.size)

            manager.startAuthenticatedSession(accountId = "42")
            runCurrent()
            assertEquals(2, transport.requests.size)
            assertEquals(2L, transport.requests.last().sequence)
        }

    @Test
    fun permissionFailureAlsoEntersTerminalSessionRecovery() =
        runTest {
            val transport =
                RecordingTransport {
                    flow { throw DiscoursePermissionException() }
                }
            val callbacks = RecordingCallbacks()
            val holder =
                coordinator(
                    sessionManager = DiscourseSessionManager(),
                    transport = transport,
                    callbacks = callbacks,
                )
            backgroundScope.launch { holder.value.run(callbacks) }
            holder.value.setForeground(true)
            runCurrent()

            assertEquals(
                listOf(
                    DiscourseSessionRecoveryRequest(
                        expectedSessionGeneration = 0L,
                        reason = DiscourseSessionRecoveryReason.PermissionDenied,
                    ),
                ),
                callbacks.recoveries,
            )
            assertEquals(1, transport.requests.size)
        }

    @Test
    fun endpointIsResolvedInsideEachGenerationLeaseAndSharedKeysAreErased() =
        runTest {
            val manager = DiscourseSessionManager()
            val endpoints = mutableListOf<DiscourseMessageBusEndpoint.SharedSession>()
            val resolvedGenerations = mutableListOf<Long>()
            val endpointProvider =
                DiscourseMessageBusEndpointProvider { session ->
                    assertEquals(
                        session.generation,
                        currentCoroutineContext()[DiscourseCookieRevisionContext]?.generation,
                    )
                    resolvedGenerations += session.generation
                    DiscourseMessageBusEndpoint
                        .SharedSession(
                            pollingOrigin = "https://bus.example.test",
                            sharedSessionKey = if (session.generation == 0L) ZERO_KEY else ONE_KEY,
                        ).also(endpoints::add)
                }
            val transport = RecordingTransport { flow { awaitCancellation() } }
            val callbacks = RecordingCallbacks()
            val holder =
                coordinator(
                    sessionManager = manager,
                    transport = transport,
                    callbacks = callbacks,
                    endpointProvider = endpointProvider,
                    nowEpochMillis = { testScheduler.currentTime },
                )
            backgroundScope.launch { holder.value.run(callbacks) }
            holder.value.setForeground(true)
            runCurrent()

            val guestEndpoint = assertIs<DiscourseMessageBusEndpoint.SharedSession>(transport.requests.single().endpoint)
            assertSame(endpoints.single(), guestEndpoint)
            assertEquals(ZERO_KEY, guestEndpoint.headerValue())

            manager.startAuthenticatedSession(accountId = "42")
            runCurrent()
            assertTrue(guestEndpoint.isCleared())
            assertEquals(listOf(0L, 1L), resolvedGenerations)
            advanceTimeBy(100L)
            runCurrent()

            val authenticatedEndpoint =
                assertIs<DiscourseMessageBusEndpoint.SharedSession>(transport.requests.last().endpoint)
            assertSame(endpoints.last(), authenticatedEndpoint)
            assertEquals(ONE_KEY, authenticatedEndpoint.headerValue())
            holder.value.setForeground(false)
            runCurrent()
            assertTrue(authenticatedEndpoint.isCleared())
        }

    @Test
    fun cancellationAtEndpointResolutionHandoffStillErasesSharedKey() =
        runTest {
            val endpoint =
                DiscourseMessageBusEndpoint.SharedSession(
                    pollingOrigin = "https://bus.example.test",
                    sharedSessionKey = ZERO_KEY,
                )
            val transport = RecordingTransport { flow { awaitCancellation() } }
            val callbacks = RecordingCallbacks()
            val holder =
                coordinator(
                    sessionManager = DiscourseSessionManager(),
                    transport = transport,
                    callbacks = callbacks,
                    endpointProvider =
                        DiscourseMessageBusEndpointProvider {
                            currentCoroutineContext().job.cancel()
                            endpoint
                        },
                )
            backgroundScope.launch { holder.value.run(callbacks) }
            holder.value.setForeground(true)
            runCurrent()

            assertTrue(endpoint.isCleared())
            assertTrue(transport.requests.isEmpty())
        }

    @Test
    fun crossOriginEndpointsRejectLiteralIpOrigins() {
        listOf(
            "https://8.8.8.8",
            "https://127.0.0.1",
            "https://10.0.0.1",
            "https://169.254.1.1",
            "https://192.168.1.1",
            "https://[::1]",
            "https://[fe80::1]",
            "https://[::ffff:127.0.0.1]",
        ).forEach { origin ->
            assertFailsWith<IllegalArgumentException>(origin) {
                DiscourseMessageBusEndpoint.CrossOrigin(origin)
            }
            assertFailsWith<IllegalArgumentException>(origin) {
                DiscourseMessageBusEndpoint.SharedSession(origin, ZERO_KEY)
            }
        }
    }

    @Test
    fun rateLimitUsesRetryAfterAndVirtualTimeBeforeReconnecting() =
        runTest {
            val schedules = mutableListOf<DiscourseMessageBusRetrySchedule>()
            val transport =
                RecordingTransport { request ->
                    when (request.sequence) {
                        1L -> flow { throw DiscourseRateLimitException(retryAfterSeconds = 7L) }
                        else -> flow { awaitCancellation() }
                    }
                }
            val bus =
                messageBus(
                    transport = transport,
                    retryObserver = DiscourseMessageBusRetryObserver { schedules += it },
                    nowEpochMillis = { testScheduler.currentTime },
                )

            backgroundScope.launch {
                bus.run(
                    subscription =
                        DiscourseRealtimeSubscription.create(
                            DiscourseSessionState.Guest(0L),
                            activeTopicId = null,
                        ),
                    refresh = { _ -> },
                )
            }
            runCurrent()

            assertEquals(1, transport.requests.size)
            assertEquals(
                DiscourseMessageBusRetrySchedule(
                    phase = DiscourseMessageBusRetryPhase.Poll,
                    attempt = 1,
                    delayMillis = 7_000L,
                    retryAtEpochMillis = 7_000L,
                ),
                schedules.single(),
            )
            advanceTimeBy(6_999L)
            runCurrent()
            assertEquals(1, transport.requests.size)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(2, transport.requests.size)
        }

    @Test
    fun emptySuccessfulPollUsesOfficialFifteenSecondStartToStartInterval() =
        runTest {
            val transport =
                RecordingTransport { request ->
                    if (request.sequence == 1L) {
                        flow { delay(14_000L) }
                    } else {
                        flow { awaitCancellation() }
                    }
                }
            val bus =
                messageBus(
                    transport = transport,
                    nowEpochMillis = { testScheduler.currentTime },
                )
            backgroundScope.launch {
                bus.run(guestSubscription(), refresh = {})
            }
            runCurrent()

            assertEquals(1, transport.requests.size)
            advanceTimeBy(14_999L)
            runCurrent()
            assertEquals(1, transport.requests.size)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(2, transport.requests.size)
        }

    @Test
    fun nonEmptySuccessfulPollWaitsOneHundredMillisecondsAfterCompletion() =
        runTest {
            val transport =
                RecordingTransport { request ->
                    if (request.sequence == 1L) {
                        flow {
                            delay(80L)
                            emit(DiscourseMessageBusBatch(listOf(message(1L, 1L, "/latest"))))
                        }
                    } else {
                        flow { awaitCancellation() }
                    }
                }
            val bus =
                messageBus(
                    transport = transport,
                    nowEpochMillis = { testScheduler.currentTime },
                )
            backgroundScope.launch {
                bus.run(guestSubscription(), refresh = {})
            }
            runCurrent()

            advanceTimeBy(179L)
            runCurrent()
            assertEquals(1, transport.requests.size)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(2, transport.requests.size)
        }

    @Test
    fun emptyChunkDoesNotResetConsecutiveFailureBackoff() =
        runTest {
            val schedules = mutableListOf<DiscourseMessageBusRetrySchedule>()
            val transport =
                RecordingTransport { request ->
                    when (request.sequence) {
                        1L -> {
                            flow { throw DiscourseServerException(503) }
                        }

                        2L -> {
                            flow {
                                emit(DiscourseMessageBusBatch(emptyList()))
                                throw DiscourseServerException(503)
                            }
                        }

                        else -> {
                            flow { awaitCancellation() }
                        }
                    }
                }
            val bus =
                messageBus(
                    transport = transport,
                    retryObserver = DiscourseMessageBusRetryObserver { schedules += it },
                    nowEpochMillis = { testScheduler.currentTime },
                )
            backgroundScope.launch {
                bus.run(guestSubscription(), refresh = {})
            }
            runCurrent()
            advanceTimeBy(500L)
            runCurrent()

            assertEquals(listOf(1, 2), schedules.map { it.attempt })
            assertEquals(listOf(500L, 1_000L), schedules.map { it.delayMillis })
        }

    @Test
    fun catchUpRetriesBeforeFirstPollUsingVirtualTime() =
        runTest {
            var attempts = 0
            val transport = RecordingTransport { flow { awaitCancellation() } }
            val callbacks =
                RecordingCallbacks(
                    onCatchUp = {
                        attempts += 1
                        if (attempts == 1) throw DiscourseRateLimitException(2L)
                    },
                )
            val holder =
                coordinator(
                    sessionManager = DiscourseSessionManager(),
                    transport = transport,
                    callbacks = callbacks,
                    nowEpochMillis = { testScheduler.currentTime },
                )
            backgroundScope.launch { holder.value.run(callbacks) }
            holder.value.setForeground(true)
            runCurrent()

            assertTrue(transport.requests.isEmpty())
            advanceTimeBy(1_999L)
            runCurrent()
            assertTrue(transport.requests.isEmpty())
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(2, attempts)
            assertEquals(1, transport.requests.size)
        }

    @Test
    fun cancellationFromRefreshIsRethrownWithoutRetry() =
        runTest {
            val retrySchedules = mutableListOf<DiscourseMessageBusRetrySchedule>()
            val bus =
                messageBus(
                    transport =
                        RecordingTransport {
                            flow {
                                emit(
                                    DiscourseMessageBusBatch(
                                        listOf(message(1L, 1L, "/latest")),
                                    ),
                                )
                            }
                        },
                    retryObserver = DiscourseMessageBusRetryObserver { retrySchedules += it },
                )

            val failure =
                assertFailsWith<CancellationException> {
                    bus.run(
                        DiscourseRealtimeSubscription.create(
                            DiscourseSessionState.Guest(0L),
                            activeTopicId = null,
                        ),
                    ) {
                        throw CancellationException("fixture cancellation")
                    }
                }

            assertEquals("fixture cancellation", failure.message)
            assertTrue(retrySchedules.isEmpty())
        }

    @Test
    fun retryPolicyIsJitteredExponentialAndNeverExceedsOneHundredEightySeconds() {
        val policy =
            DiscourseMessageBusRetryPolicy(
                randomSource = DiscourseMessageBusRandomSource { 0.0 },
            )

        assertEquals(500L, policy.delayMillis(attempt = 1, retryAfterSeconds = null))
        assertEquals(1_000L, policy.delayMillis(attempt = 2, retryAfterSeconds = null))
        assertEquals(120_000L, policy.delayMillis(attempt = 2, retryAfterSeconds = 120L))
        assertEquals(180_000L, policy.delayMillis(attempt = 100, retryAfterSeconds = 86_400L))
    }

    private fun TestScope.coordinator(
        sessionManager: DiscourseSessionManager,
        transport: DiscourseMessageBusTransport,
        callbacks: RecordingCallbacks,
        endpointProvider: DiscourseMessageBusEndpointProvider =
            DiscourseMessageBusEndpointProvider { DiscourseMessageBusEndpoint.SameOrigin },
        nowEpochMillis: () -> Long = { 0L },
    ): CoordinatorHolder {
        val retryPolicy =
            DiscourseMessageBusRetryPolicy(
                randomSource = DiscourseMessageBusRandomSource { 0.0 },
            )
        val retryDelay = DiscourseMessageBusDelay { delay(it) }
        val clock = DiscourseMessageBusClock(nowEpochMillis)
        val bus =
            messageBus(
                transport = transport,
                retryPolicy = retryPolicy,
                retryDelay = retryDelay,
                nowEpochMillis = nowEpochMillis,
            )
        return CoordinatorHolder(
            value =
                DiscourseRealtimeCoordinator(
                    sessionManager = sessionManager,
                    messageBus = bus,
                    endpointProvider = endpointProvider,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    catchUpRetryPolicy = retryPolicy,
                    retryDelay = retryDelay,
                    clock = clock,
                ),
            callbacks = callbacks,
        )
    }

    private fun messageBus(
        transport: DiscourseMessageBusTransport,
        cursorStore: DiscourseMessageBusCursorStore = MemoryDiscourseMessageBusCursorStore(),
        retryPolicy: DiscourseMessageBusRetryPolicy =
            DiscourseMessageBusRetryPolicy(
                randomSource = DiscourseMessageBusRandomSource { 0.0 },
            ),
        retryDelay: DiscourseMessageBusDelay = DiscourseMessageBusDelay { delay(it) },
        retryObserver: DiscourseMessageBusRetryObserver = DiscourseMessageBusRetryObserver {},
        nowEpochMillis: () -> Long = { 0L },
    ): DiscourseMessageBus =
        DiscourseMessageBus(
            transport = transport,
            cursorStore = cursorStore,
            clientIdFactory = DiscourseMessageBusClientIdFactory { FIXED_CLIENT_ID },
            retryPolicy = retryPolicy,
            retryDelay = retryDelay,
            clock = DiscourseMessageBusClock(nowEpochMillis),
            monotonicClock = DiscourseMessageBusMonotonicClock(nowEpochMillis),
            retryObserver = retryObserver,
        )

    private companion object {
        const val FIXED_CLIENT_ID: String = "00000000000040008000000000000000"
        const val ZERO_KEY: String = "00000000000000000000000000000000"
        const val ONE_KEY: String = "11111111111111111111111111111111"
    }

    private fun guestSubscription(): DiscourseRealtimeSubscription =
        DiscourseRealtimeSubscription.create(
            session = DiscourseSessionState.Guest(0L),
            activeTopicId = null,
        )
}

private data class CoordinatorHolder(
    val value: DiscourseRealtimeCoordinator,
    val callbacks: RecordingCallbacks,
)

private class RecordingCallbacks(
    private val onCatchUp: suspend (DiscourseRealtimeCatchUp) -> Unit = {},
) : DiscourseRealtimeCallbacks {
    val catchUps = mutableListOf<DiscourseRealtimeCatchUp>()
    val refreshes = mutableListOf<DiscourseRealtimeRefresh>()
    val recoveries = mutableListOf<DiscourseSessionRecoveryRequest>()
    val pipelineFailures = mutableListOf<Long>()

    override suspend fun catchUp(request: DiscourseRealtimeCatchUp) {
        catchUps += request
        onCatchUp(request)
    }

    override suspend fun refresh(request: DiscourseRealtimeRefresh) {
        refreshes += request
    }

    override suspend fun recoverSession(request: DiscourseSessionRecoveryRequest) {
        recoveries += request
    }

    override suspend fun pipelineFailed(expectedSessionGeneration: Long) {
        pipelineFailures += expectedSessionGeneration
    }
}

private class RecordingTransport(
    private val response: (DiscourseMessageBusPollRequest) -> Flow<DiscourseMessageBusBatch>,
) : DiscourseMessageBusTransport {
    val requests = mutableListOf<DiscourseMessageBusPollRequest>()
    var cancelledPolls: Int = 0
        private set

    override fun poll(request: DiscourseMessageBusPollRequest): Flow<DiscourseMessageBusBatch> =
        flow {
            requests += request
            try {
                response(request).collect { emit(it) }
            } catch (cancellation: CancellationException) {
                cancelledPolls += 1
                throw cancellation
            }
        }
}

private class RecordingCursorStore : DiscourseMessageBusCursorStore {
    private val delegate = MemoryDiscourseMessageBusCursorStore()
    val advancedChannels = mutableListOf<String>()

    override suspend fun read(
        accountId: String,
        channel: String,
    ): Long? = delegate.read(accountId, channel)

    override suspend fun advance(
        accountId: String,
        channel: String,
        messageId: Long,
    ): DiscourseMessageBusCursorAdvance {
        advancedChannels += channel
        return delegate.advance(accountId, channel, messageId)
    }

    override suspend fun clearAccount(accountId: String) {
        delegate.clearAccount(accountId)
    }
}

private fun message(
    globalId: Long,
    messageId: Long,
    channel: String,
): DiscourseMessageBusMessage =
    DiscourseMessageBusMessage(
        globalId = globalId,
        messageId = messageId,
        channel = channel,
        data = JsonObject(mapOf("ignored" to JsonPrimitive("untrusted payload"))),
    )
