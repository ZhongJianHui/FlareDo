package dev.dimension.flare

import kotlinx.coroutines.test.runTest
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class DesktopAuthCallbackTest {
    @Test
    fun acceptsOneStrictBoundedProtocolArgument() {
        val callback =
            "discourse://auth_redirect?payload=YWJjZA==&oneTimePassword=ZWZnaA=="

        assertEquals(callback, desktopAuthCallbackFromArguments(arrayOf(callback)))
    }

    @Test
    fun rejectsLookalikeAuthorityPortsPathsFragmentsAndExtraArguments() {
        listOf(
            "discourse://auth_redirect.evil.invalid?payload=YWJjZA==&oneTimePassword=ZWZnaA==",
            "discourse://auth_redirect:42?payload=YWJjZA==&oneTimePassword=ZWZnaA==",
            "discourse://user@auth_redirect?payload=YWJjZA==&oneTimePassword=ZWZnaA==",
            "discourse://auth_redirect/path?payload=YWJjZA==&oneTimePassword=ZWZnaA==",
            "discourse://auth_redirect?payload=YWJjZA==&oneTimePassword=ZWZnaA==#fragment",
            "https://linux.do/auth_redirect?payload=YWJjZA==&oneTimePassword=ZWZnaA==",
        ).forEach { callback -> assertNull(desktopAuthCallbackFromArguments(arrayOf(callback))) }
        assertNull(desktopAuthCallbackFromArguments(emptyArray()))
        assertNull(desktopAuthCallbackFromArguments(arrayOf("one", "two")))
    }

    @Test
    fun rejectsUnknownOrNonCanonicalQueryFieldsWithoutEchoingThem() {
        assertNull(
            desktopAuthCallbackFromArguments(
                arrayOf(
                    "discourse://auth_redirect?payload=secret&oneTimePassword=secret&extra=value",
                ),
            ),
        )
        assertNull(desktopAuthCallbackFromArguments(arrayOf("x".repeat(16 * 1024 + 1))))
    }

    @Test
    fun taoBridgeRoutesOnlyAValidatedCallbackWithoutTransformingItsSecrets() {
        val callback =
            "discourse://auth_redirect?payload=YWJjZA==&oneTimePassword=ZWZnaA=="
        var delivered: String? = null

        assertTrue(
            routeDesktopTaoAuthRedirect(URI(callback)) { raw ->
                delivered = raw
                true
            },
        )
        assertEquals(callback, delivered)

        delivered = null
        assertFalse(
            routeDesktopTaoAuthRedirect(
                URI("discourse://auth_redirect.evil.invalid?payload=YWJjZA==&oneTimePassword=ZWZnaA=="),
            ) { raw ->
                delivered = raw
                true
            },
        )
        assertNull(delivered)
    }

    @Test
    fun externalUriHostRejectsAnythingOutsideTheFixedAuthorizationEndpoint() =
        runTest {
            assertFalse(openDesktopExternalUri("https://linux.do/latest"))
            assertFalse(openDesktopExternalUri("https://evil.invalid/user-api-key/new"))
            assertFalse(openDesktopExternalUri("https://linux.do:443/user-api-key/new"))
        }

    @Test
    fun secondaryInstanceForwardsCallbackWithoutPersistingIt() {
        val directory = createTempDirectory("flaredo-instance-test")
        val lockFile = directory.resolve("instance.lock")
        val callback =
            "discourse://auth_redirect?payload=YWJjZA==&oneTimePassword=ZWZnaA=="
        val received = CountDownLatch(1)
        val primary = assertIs<DesktopInstanceClaim.Primary>(claimDesktopInstance(lockFile, null))
        try {
            val broker = requireNotNull(primary.broker)
            broker.registerCallbackHandler { raw ->
                assertEquals(callback, raw)
                received.countDown()
                true
            }

            val secondary = claimDesktopInstance(lockFile, callback)
            assertIs<DesktopInstanceClaim.Secondary>(secondary)
            assertTrue(received.await(2, TimeUnit.SECONDS))

            val persisted = Files.readString(lockFile)
            assertTrue(Regex("[1-9][0-9]{0,4}").matches(persisted))
            assertFalse(persisted.contains("payload"))
            assertFalse(persisted.contains("oneTimePassword"))
            Files.getFileAttributeView(lockFile, PosixFileAttributeView::class.java)?.let { view ->
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    view.readAttributes().permissions(),
                )
            }
        } finally {
            primary.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun callbackFrameWithoutPositiveAckIsRejected() {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val server = ServerSocket(0, 1, loopback)
        val accepted = CountDownLatch(1)
        val receiver =
            Thread {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val size = input.readInt()
                    input.readNBytes(size)
                    accepted.countDown()
                    // Closing without the exact one-byte ACK must not be treated as delivery.
                }
            }.apply {
                isDaemon = true
                start()
            }

        try {
            assertFalse(sendCallbackFrame(server.localPort, "bounded-callback".encodeToByteArray()))
            assertTrue(accepted.await(1, TimeUnit.SECONDS))
        } finally {
            server.close()
            receiver.join(1_000L)
        }
    }

    @Test
    fun callbackFrameWithTrailingAckBytesIsRejected() {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val server = ServerSocket(0, 1, loopback)
        val receiver =
            Thread {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val size = input.readInt()
                    input.readNBytes(size)
                    socket.getOutputStream().write(byteArrayOf(0x06, 0x06))
                    socket.getOutputStream().flush()
                }
            }.apply {
                isDaemon = true
                start()
            }

        try {
            assertFalse(sendCallbackFrame(server.localPort, "bounded-callback".encodeToByteArray()))
        } finally {
            server.close()
            receiver.join(1_000L)
        }
    }

    @Test
    fun missingPortRetriesAndSleepsShareOneMonotonicBudget() {
        val directory = createTempDirectory("flaredo-forward-budget-port-test")
        val lockFile = directory.resolve("instance.lock")
        Files.writeString(lockFile, "not-a-port")
        val callback =
            "discourse://auth_redirect?payload=YWJjZA==&oneTimePassword=ZWZnaA=="
        val budgetMillis = 25

        try {
            val startedAt = System.nanoTime()
            assertFalse(forwardDesktopCallback(lockFile, callback, budgetMillis))
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            // Eight independent 50 ms sleeps would take roughly 350 ms. The absolute deadline
            // truncates the first retry sleep and prevents every later attempt from resetting it.
            assertTrue(
                elapsedMillis < 250L,
                "Port retry path exceeded its $budgetMillis ms budget: ${elapsedMillis}ms",
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun ackAndEofValidationShareOneMonotonicBudget() {
        val directory = createTempDirectory("flaredo-forward-budget-ack-test")
        val lockFile = directory.resolve("instance.lock")
        val callback =
            "discourse://auth_redirect?payload=YWJjZA==&oneTimePassword=ZWZnaA=="
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val server = ServerSocket(0, 1, loopback)
        val frameReceived = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val receiver =
            Thread {
                runCatching {
                    server.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream())
                        val size = input.readInt()
                        input.readNBytes(size)
                        frameReceived.countDown()
                        socket.getOutputStream().apply {
                            write(0x06)
                            flush()
                        }
                        // A valid ACK byte without EOF is not the exact protocol response. Holding
                        // the socket open verifies that EOF cannot receive a fresh 1400 ms timeout.
                        releaseServer.await(2, TimeUnit.SECONDS)
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        Files.writeString(lockFile, server.localPort.toString())
        val budgetMillis = 300

        try {
            val startedAt = System.nanoTime()
            assertFalse(forwardDesktopCallback(lockFile, callback, budgetMillis))
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertTrue(frameReceived.await(1, TimeUnit.SECONDS))
            assertTrue(
                elapsedMillis >= budgetMillis - 75L,
                "ACK path ended before exercising the configured budget: ${elapsedMillis}ms",
            )
            assertTrue(
                elapsedMillis < budgetMillis + 500L,
                "ACK/EOF path reset the $budgetMillis ms forwarding budget: ${elapsedMillis}ms",
            )
        } finally {
            releaseServer.countDown()
            server.close()
            receiver.join(1_000L)
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun saturatedPresenterQueueNeverAcknowledgesOrReportsSecondarySuccess() {
        val directory = createTempDirectory("flaredo-instance-rejected-test")
        val lockFile = directory.resolve("instance.lock")
        val callback =
            "discourse://auth_redirect?payload=YWJjZA==&oneTimePassword=ZWZnaA=="
        val primary = assertIs<DesktopInstanceClaim.Primary>(claimDesktopInstance(lockFile, null))
        try {
            requireNotNull(primary.broker).registerCallbackHandler { false }

            assertIs<DesktopInstanceClaim.CallbackDeliveryFailed>(
                claimDesktopInstance(lockFile, callback),
            )
            assertFalse(Files.readString(lockFile).contains("payload"))
        } finally {
            primary.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun stoppedBrokerRejectsNewCallbacksWhileKeepingTheInstanceLock() {
        val directory = createTempDirectory("flaredo-instance-stopped-test")
        val lockFile = directory.resolve("instance.lock")
        val callback =
            "discourse://auth_redirect?payload=YWJjZA==&oneTimePassword=ZWZnaA=="
        val handlerCalls = AtomicInteger()
        val primary = assertIs<DesktopInstanceClaim.Primary>(claimDesktopInstance(lockFile, null))
        try {
            val broker = requireNotNull(primary.broker)
            assertTrue(
                broker.registerCallbackHandler {
                    handlerCalls.incrementAndGet()
                    true
                },
            )

            broker.stopAcceptingCallbacks()

            assertFalse(broker.registerCallbackHandler { true })
            assertIs<DesktopInstanceClaim.CallbackDeliveryFailed>(
                claimDesktopInstance(lockFile, callback),
            )
            assertIs<DesktopInstanceClaim.Secondary>(claimDesktopInstance(lockFile, null))
            assertEquals(0, handlerCalls.get())
        } finally {
            primary.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun stopWaitsForRunningHandlerAndSuppressesItsLateAck() {
        val directory = createTempDirectory("flaredo-instance-in-flight-test")
        val lockFile = directory.resolve("instance.lock")
        val callback =
            "discourse://auth_redirect?payload=YWJjZA==&oneTimePassword=ZWZnaA=="
        val handlerStarted = CountDownLatch(1)
        val allowHandlerToFinish = CountDownLatch(1)
        val stopStarted = CountDownLatch(1)
        val stopFinished = CountDownLatch(1)
        val forwardingFinished = CountDownLatch(1)
        val handlerCalls = AtomicInteger()
        var secondaryClaim: DesktopInstanceClaim? = null
        val primary = assertIs<DesktopInstanceClaim.Primary>(claimDesktopInstance(lockFile, null))
        try {
            val broker = requireNotNull(primary.broker)
            assertTrue(
                broker.registerCallbackHandler {
                    handlerCalls.incrementAndGet()
                    handlerStarted.countDown()
                    allowHandlerToFinish.await(2, TimeUnit.SECONDS)
                    true
                },
            )
            val forwardingThread =
                Thread {
                    secondaryClaim = claimDesktopInstance(lockFile, callback)
                    forwardingFinished.countDown()
                }.apply {
                    isDaemon = true
                    start()
                }
            assertTrue(handlerStarted.await(1, TimeUnit.SECONDS))

            val stopThread =
                Thread {
                    stopStarted.countDown()
                    broker.stopAcceptingCallbacks()
                    stopFinished.countDown()
                }.apply {
                    isDaemon = true
                    start()
                }
            assertTrue(stopStarted.await(1, TimeUnit.SECONDS))
            assertTrue(waitForCallbackRegistrationRejection(broker))
            assertFalse(stopFinished.await(100, TimeUnit.MILLISECONDS))

            allowHandlerToFinish.countDown()

            assertTrue(stopFinished.await(1, TimeUnit.SECONDS))
            assertTrue(forwardingFinished.await(2, TimeUnit.SECONDS))
            assertIs<DesktopInstanceClaim.CallbackDeliveryFailed>(secondaryClaim)
            assertEquals(1, handlerCalls.get())
            stopThread.join(1_000L)
            forwardingThread.join(1_000L)
        } finally {
            allowHandlerToFinish.countDown()
            primary.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun stopRejectsPendingColdStartCallbackWithoutWaitingForItsTimeout() {
        val directory = createTempDirectory("flaredo-instance-pending-stop-test")
        val lockFile = directory.resolve("instance.lock")
        val callback =
            "discourse://auth_redirect?payload=YWJjZA==&oneTimePassword=ZWZnaA=="
        val sendStarted = CountDownLatch(1)
        val sendFinished = CountDownLatch(1)
        var delivered = true
        val primary = assertIs<DesktopInstanceClaim.Primary>(claimDesktopInstance(lockFile, null))
        try {
            val broker = requireNotNull(primary.broker)
            val sender =
                Thread {
                    sendStarted.countDown()
                    delivered =
                        sendCallbackFrame(
                            port = Files.readString(lockFile).toInt(),
                            bytes = callback.encodeToByteArray(),
                        )
                    sendFinished.countDown()
                }.apply {
                    isDaemon = true
                    start()
                }
            assertTrue(sendStarted.await(1, TimeUnit.SECONDS))
            assertFalse(sendFinished.await(100, TimeUnit.MILLISECONDS))

            broker.stopAcceptingCallbacks()

            assertTrue(sendFinished.await(300, TimeUnit.MILLISECONDS))
            assertFalse(delivered)
            sender.join(1_000L)
        } finally {
            primary.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun ackBudgetCoversColdStartHandoffAndBoundedActorReceipt() {
        val directory = createTempDirectory("flaredo-instance-ack-budget-test")
        val lockFile = directory.resolve("instance.lock")
        val callback =
            "discourse://auth_redirect?payload=YWJjZA==&oneTimePassword=ZWZnaA=="
        val sendStarted = CountDownLatch(1)
        val sendFinished = CountDownLatch(1)
        var delivered = false
        val primary = assertIs<DesktopInstanceClaim.Primary>(claimDesktopInstance(lockFile, null))
        try {
            val broker = requireNotNull(primary.broker)
            val sender =
                Thread {
                    sendStarted.countDown()
                    delivered =
                        sendCallbackFrame(
                            port = Files.readString(lockFile).toInt(),
                            bytes = callback.encodeToByteArray(),
                        )
                    sendFinished.countDown()
                }.apply {
                    isDaemon = true
                    start()
                }
            assertTrue(sendStarted.await(1, TimeUnit.SECONDS))
            Thread.sleep(500L)

            assertTrue(
                broker.registerCallbackHandler {
                    Thread.sleep(DESKTOP_REDIRECT_RECEIPT_TIMEOUT_MILLIS.toLong() - 100L)
                    true
                },
            )

            assertTrue(sendFinished.await(1, TimeUnit.SECONDS))
            assertTrue(delivered)
            sender.join(1_000L)
        } finally {
            primary.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun slowClientCannotSeriallyBlockAnAcknowledgedCallback() {
        val directory = createTempDirectory("flaredo-instance-slow-test")
        val lockFile = directory.resolve("instance.lock")
        val callback =
            "discourse://auth_redirect?payload=YWJjZA==&oneTimePassword=ZWZnaA=="
        val primary = assertIs<DesktopInstanceClaim.Primary>(claimDesktopInstance(lockFile, null))
        val slowSocket = Socket("127.0.0.1", Files.readString(lockFile).toInt())
        try {
            requireNotNull(primary.broker).registerCallbackHandler { true }
            // Two bytes leave the first worker blocked on the four-byte frame length.
            slowSocket.getOutputStream().write(byteArrayOf(0, 0))
            slowSocket.getOutputStream().flush()
            Thread.sleep(50L)

            val completed = CountDownLatch(1)
            var claim: DesktopInstanceClaim? = null
            val forwardingThread =
                Thread {
                    claim = claimDesktopInstance(lockFile, callback)
                    completed.countDown()
                }.apply {
                    isDaemon = true
                    start()
                }

            assertTrue(completed.await(600L, TimeUnit.MILLISECONDS))
            assertIs<DesktopInstanceClaim.Secondary>(claim)
            forwardingThread.join(1_000L)
        } finally {
            slowSocket.close()
            primary.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun eightSlowFramesShareAcceptedAtDeadlineAndWorkerCapacityRecovers() {
        val directory = createTempDirectory("flaredo-instance-slow-drip-test")
        val lockFile = directory.resolve("instance.lock")
        val callback =
            "discourse://auth_redirect?payload=YWJjZA==&oneTimePassword=ZWZnaA=="
        val primary = assertIs<DesktopInstanceClaim.Primary>(claimDesktopInstance(lockFile, null))
        var slowClients: SlowCallbackClients? = null
        try {
            val broker = requireNotNull(primary.broker)
            assertTrue(broker.registerCallbackHandler { true })
            val workers = callbackWorkerPool(broker)
            slowClients =
                SlowCallbackClients.connect(
                    port = Files.readString(lockFile).toInt(),
                    count = SLOW_CLIENT_COUNT,
                )

            assertTrue(
                waitForWorkerLoad(
                    workers = workers,
                    activeCount = ACTIVE_CALLBACK_WORKERS,
                    queuedCount = QUEUED_CALLBACK_WORKERS,
                ),
                "Eight slow clients did not occupy all callback workers and queue slots",
            )

            val forwardingStartedAt = System.nanoTime()
            val saturatedClaim = claimDesktopInstance(lockFile, callback)
            val forwardingMillis =
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - forwardingStartedAt)

            assertIs<DesktopInstanceClaim.CallbackDeliveryFailed>(saturatedClaim)
            assertTrue(
                forwardingMillis < FORWARDING_ASSERTION_TIMEOUT_MILLIS,
                "Saturated callback forwarding exceeded its total budget: ${forwardingMillis}ms",
            )

            // The dripper remains active here. A per-read SO_TIMEOUT implementation never drains:
            // each byte resets its timeout. Accepted-at deadlines expire all running and queued
            // frames together, allowing a later valid callback to use the recovered capacity.
            assertTrue(
                waitForWorkerLoad(
                    workers = workers,
                    activeCount = 0,
                    queuedCount = 0,
                    timeoutMillis = FRAME_DRAIN_ASSERTION_TIMEOUT_MILLIS,
                ),
                "Slow frames retained callback capacity beyond their accepted-at deadline",
            )
            assertIs<DesktopInstanceClaim.Secondary>(claimDesktopInstance(lockFile, callback))
        } finally {
            slowClients?.close()
            primary.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun closeReturnsPromptlyWithEightSlowFramesSaturatingTheBroker() {
        val directory = createTempDirectory("flaredo-instance-slow-close-test")
        val lockFile = directory.resolve("instance.lock")
        val primary = assertIs<DesktopInstanceClaim.Primary>(claimDesktopInstance(lockFile, null))
        var slowClients: SlowCallbackClients? = null
        var closeThread: Thread? = null
        try {
            val broker = requireNotNull(primary.broker)
            val workers = callbackWorkerPool(broker)
            slowClients =
                SlowCallbackClients.connect(
                    port = Files.readString(lockFile).toInt(),
                    count = SLOW_CLIENT_COUNT,
                )
            assertTrue(
                waitForWorkerLoad(
                    workers = workers,
                    activeCount = ACTIVE_CALLBACK_WORKERS,
                    queuedCount = QUEUED_CALLBACK_WORKERS,
                ),
                "Eight slow clients did not saturate the callback broker before close",
            )

            val closeFinished = CountDownLatch(1)
            closeThread =
                Thread {
                    primary.close()
                    closeFinished.countDown()
                }.apply {
                    isDaemon = true
                    start()
                }

            assertTrue(
                closeFinished.await(CLOSE_ASSERTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                "Closing a broker with active and queued slow frames did not return promptly",
            )
            closeThread.join(CLOSE_ASSERTION_TIMEOUT_MILLIS)
        } finally {
            slowClients?.close()
            primary.close()
            closeThread?.interrupt()
            closeThread?.join(CLOSE_ASSERTION_TIMEOUT_MILLIS)
            directory.toFile().deleteRecursively()
        }
    }

    private fun callbackWorkerPool(broker: DesktopAuthCallbackBroker): ThreadPoolExecutor {
        val field = DesktopAuthCallbackBroker::class.java.getDeclaredField("callbackWorkers")
        field.isAccessible = true
        return field.get(broker) as ThreadPoolExecutor
    }

    private fun waitForWorkerLoad(
        workers: ThreadPoolExecutor,
        activeCount: Int,
        queuedCount: Int,
        timeoutMillis: Long = WORKER_LOAD_ASSERTION_TIMEOUT_MILLIS,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (workers.activeCount == activeCount && workers.queue.size == queuedCount) return true
            Thread.sleep(WORKER_LOAD_POLL_MILLIS)
        }
        return workers.activeCount == activeCount && workers.queue.size == queuedCount
    }

    private fun waitForCallbackRegistrationRejection(broker: DesktopAuthCallbackBroker): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (System.nanoTime() < deadline) {
            if (!broker.registerCallbackHandler { false }) return true
            Thread.yield()
        }
        return false
    }

    private class SlowCallbackClients private constructor(
        private val sockets: List<Socket>,
        private val running: AtomicBoolean,
        private val dripThread: Thread,
    ) : AutoCloseable {
        override fun close() {
            if (running.compareAndSet(true, false)) {
                dripThread.interrupt()
            }
            sockets.forEach { socket -> runCatching { socket.close() } }
            dripThread.join(SLOW_CLIENT_JOIN_TIMEOUT_MILLIS)
        }

        companion object {
            fun connect(
                port: Int,
                count: Int,
            ): SlowCallbackClients {
                val sockets = mutableListOf<Socket>()
                try {
                    repeat(count) {
                        Socket(LOOPBACK_ADDRESS, port).also { socket ->
                            sockets += socket
                            DataOutputStream(socket.getOutputStream()).apply {
                                writeInt(SLOW_FRAME_BYTES)
                                writeByte(0)
                                flush()
                            }
                        }
                    }
                } catch (failure: Throwable) {
                    sockets.forEach { socket -> runCatching { socket.close() } }
                    throw failure
                }

                val running = AtomicBoolean(true)
                val dripThread =
                    Thread {
                        while (running.get()) {
                            sockets.forEach { socket ->
                                runCatching {
                                    socket.getOutputStream().apply {
                                        write(0)
                                        flush()
                                    }
                                }
                            }
                            try {
                                Thread.sleep(SLOW_DRIP_INTERVAL_MILLIS)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                return@Thread
                            }
                        }
                    }.apply {
                        isDaemon = true
                        name = "flaredo-auth-callback-slow-drip-test"
                        start()
                    }
                return SlowCallbackClients(sockets, running, dripThread)
            }
        }
    }

    private companion object {
        const val LOOPBACK_ADDRESS: String = "127.0.0.1"
        const val SLOW_CLIENT_COUNT: Int = 8
        const val ACTIVE_CALLBACK_WORKERS: Int = 4
        const val QUEUED_CALLBACK_WORKERS: Int = 4
        const val SLOW_FRAME_BYTES: Int = 16 * 1024
        const val SLOW_DRIP_INTERVAL_MILLIS: Long = 100L
        const val WORKER_LOAD_POLL_MILLIS: Long = 5L
        const val WORKER_LOAD_ASSERTION_TIMEOUT_MILLIS: Long = 2_000L
        const val FRAME_DRAIN_ASSERTION_TIMEOUT_MILLIS: Long = 2_500L
        const val FORWARDING_ASSERTION_TIMEOUT_MILLIS: Long = 2_500L
        const val CLOSE_ASSERTION_TIMEOUT_MILLIS: Long = 1_000L
        const val SLOW_CLIENT_JOIN_TIMEOUT_MILLIS: Long = 1_000L
    }
}
