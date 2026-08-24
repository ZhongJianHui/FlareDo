package dev.dimension.flare

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Result of claiming the per-user Desktop process lock. */
internal sealed interface DesktopInstanceClaim : AutoCloseable {
    /** The process may host the UI. A null broker means locking was unavailable and we failed open. */
    class Primary(
        val broker: DesktopAuthCallbackBroker?,
    ) : DesktopInstanceClaim {
        override fun close() {
            broker?.close()
        }
    }

    /** Another process owns the UI; any valid startup callback has already been forwarded. */
    data object Secondary : DesktopInstanceClaim {
        override fun close() = Unit
    }

    /** A running primary was found, but it never acknowledged the sensitive callback. */
    data object CallbackDeliveryFailed : DesktopInstanceClaim {
        override fun close() = Unit
    }
}

/**
 * Claims one Desktop UI process and forwards a protocol callback from a secondary invocation.
 *
 * Only the ephemeral loopback port is written to [lockFile]. The URI, encrypted payload, and OTP
 * are transferred in one bounded TCP frame and are never persisted or included in diagnostics.
 * Cryptographic nonce validation still happens in the shared login service after this transport
 * boundary, so a local process cannot manufacture an accepted callback by discovering the port.
 */
internal fun claimDesktopInstance(
    lockFile: Path,
    startupCallback: String?,
): DesktopInstanceClaim {
    val callback =
        startupCallback?.takeIf { raw ->
            desktopAuthCallbackFromArguments(arrayOf(raw)) != null
        }
    val channel =
        try {
            prepareRestrictedLockFile(lockFile)
            FileChannel.open(
                lockFile,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: IOException) {
            return DesktopInstanceClaim.Primary(broker = null)
        } catch (_: SecurityException) {
            return DesktopInstanceClaim.Primary(broker = null)
        } catch (_: UnsupportedOperationException) {
            return DesktopInstanceClaim.Primary(broker = null)
        }

    val lock =
        try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (_: IOException) {
            runCatching { channel.close() }
            return DesktopInstanceClaim.Primary(broker = null)
        }

    if (lock == null) {
        runCatching { channel.close() }
        return when {
            callback == null -> DesktopInstanceClaim.Secondary
            forwardDesktopCallback(lockFile, callback) -> DesktopInstanceClaim.Secondary
            else -> DesktopInstanceClaim.CallbackDeliveryFailed
        }
    }

    return try {
        DesktopInstanceClaim.Primary(
            DesktopAuthCallbackBroker.start(
                lockFile = lockFile,
                lockChannel = channel,
                lock = lock,
            ),
        )
    } catch (_: IOException) {
        runCatching { lock.release() }
        runCatching { channel.close() }
        DesktopInstanceClaim.Primary(broker = null)
    } catch (_: SecurityException) {
        runCatching { lock.release() }
        runCatching { channel.close() }
        DesktopInstanceClaim.Primary(broker = null)
    }
}

/**
 * In-memory primary-instance receiver for validated Desktop authentication callbacks.
 *
 * A callback arriving during dependency initialization is retained only in a small process-local
 * queue. Registration drains that queue before future frames are delivered directly.
 */
internal class DesktopAuthCallbackBroker private constructor(
    private val lockFile: Path,
    private val lockChannel: FileChannel,
    private val lock: FileLock,
    private val serverSocket: ServerSocket,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val callbackMonitor = Any()
    private val pendingCallbacks = ArrayDeque<PendingDesktopCallback>(MAX_PENDING_CALLBACKS)
    private val inFlightCallbacks = mutableSetOf<CompletableFuture<Unit>>()
    private var acceptingCallbacks = true
    private var callbackHandler: ((String) -> Boolean)? = null
    private val openCallbackSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val callbackWorkers =
        ThreadPoolExecutor(
            MAX_CONCURRENT_CALLBACKS,
            MAX_CONCURRENT_CALLBACKS,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(MAX_QUEUED_CALLBACK_CONNECTIONS),
            { runnable ->
                Thread(runnable, "flaredo-auth-callback-worker").apply { isDaemon = true }
            },
            ThreadPoolExecutor.AbortPolicy(),
        )
    private val acceptThread =
        Thread(::acceptLoop, "flaredo-auth-callback").apply {
            isDaemon = true
            start()
        }

    /** Installs the presenter boundary and drains callbacks received during cold startup. */
    fun registerCallbackHandler(handler: (String) -> Boolean): Boolean {
        val pending =
            synchronized(callbackMonitor) {
                if (!acceptingCallbacks || closed.get()) return false
                callbackHandler = handler
                buildList {
                    while (pendingCallbacks.isNotEmpty()) add(pendingCallbacks.removeFirst())
                }
            }
        pending.forEach { callback ->
            callback.handler.complete(handler)
        }
        return true
    }

    /**
     * Irreversibly closes the callback admission gate without releasing the process lock.
     *
     * The application calls this before presenter teardown. Frames that crossed the gate first
     * remain in [inFlightCallbacks], so this method waits for their handler invocation and final
     * ACK decision outside [callbackMonitor]. The server socket and file lock intentionally remain
     * live until [close], preventing a replacement process from starting against half-closed
     * dependencies.
     */
    fun stopAcceptingCallbacks() {
        val inFlight =
            synchronized(callbackMonitor) {
                acceptingCallbacks = false
                callbackHandler = null
                while (pendingCallbacks.isNotEmpty()) {
                    pendingCallbacks.removeFirst().handler.complete(null)
                }
                inFlightCallbacks.toList()
            }
        inFlight.forEach { callback -> callback.join() }
    }

    override fun close() {
        stopAcceptingCallbacks()
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverSocket.close() }
        openCallbackSockets.forEach { socket -> runCatching { socket.close() } }
        callbackWorkers.shutdownNow()
        runCatching {
            lockChannel.truncate(0L)
            lockChannel.force(true)
        }
        runCatching { lock.release() }
        runCatching { lockChannel.close() }
        runCatching { acceptThread.interrupt() }
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            try {
                val socket = serverSocket.accept()
                // Queue residence counts against the same absolute frame-read budget as blocking
                // I/O. Otherwise four running and four queued slow clients could each receive a
                // fresh timeout when a worker eventually starts them.
                val frameDeadlineNanos =
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CALLBACK_FRAME_TIMEOUT_MILLIS.toLong())
                openCallbackSockets += socket
                try {
                    callbackWorkers.execute {
                        try {
                            try {
                                socket.use { accepted -> receiveCallback(accepted, frameDeadlineNanos) }
                            } catch (_: IOException) {
                                // Closing the broker can race a queued task before it obtains streams.
                            } catch (_: SecurityException) {
                                // Treat all peer and local socket-policy failures as rejected frames.
                            }
                        } finally {
                            openCallbackSockets -= socket
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    openCallbackSockets -= socket
                    runCatching { socket.close() }
                }
            } catch (_: SocketException) {
                if (!closed.get()) continue
            } catch (_: IOException) {
                if (!closed.get()) continue
            } catch (_: SecurityException) {
                if (!closed.get()) continue
            }
        }
    }

    private fun receiveCallback(
        socket: Socket,
        frameDeadlineNanos: Long,
    ) {
        if (!socket.inetAddress.isLoopbackAddress) return
        val input = socket.getInputStream()
        val lengthBytes = ByteArray(Int.SIZE_BYTES)
        if (!readFullyBeforeDeadline(socket, input, lengthBytes, frameDeadlineNanos)) return
        val length = ByteBuffer.wrap(lengthBytes).getInt()
        if (length !in 1..MAX_DESKTOP_CALLBACK_BYTES) return
        val bytes = ByteArray(length)
        if (!readFullyBeforeDeadline(socket, input, bytes, frameDeadlineNanos)) return
        val callback =
            try {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                return
            }
        val validated = desktopAuthCallbackFromArguments(arrayOf(callback)) ?: return
        val delivery = beginDelivery() ?: return
        try {
            val handler = delivery.handler ?: awaitPendingHandler(requireNotNull(delivery.pending))
            if (handler == null || !runCatching { handler(validated) }.getOrDefault(false)) return
            synchronized(callbackMonitor) {
                if (!acceptingCallbacks || closed.get()) return
                // Keep the final admission check and one-byte ACK atomic with the stop gate. This
                // makes stopAcceptingCallbacks wait for an ACK that was already being committed.
                DataOutputStream(socket.getOutputStream()).let { output ->
                    output.writeByte(CALLBACK_ACCEPTED_ACK)
                    output.flush()
                }
            }
        } finally {
            synchronized(callbackMonitor) {
                delivery.finished.complete(Unit)
                inFlightCallbacks.remove(delivery.finished)
            }
        }
    }

    private fun beginDelivery(): CallbackDelivery? =
        synchronized(callbackMonitor) {
            if (!acceptingCallbacks || closed.get()) return null
            val handler = callbackHandler
            val pending =
                if (handler == null) {
                    if (pendingCallbacks.size >= MAX_PENDING_CALLBACKS) return null
                    PendingDesktopCallback().also(pendingCallbacks::addLast)
                } else {
                    null
                }
            CallbackDelivery(handler = handler, pending = pending).also { delivery ->
                inFlightCallbacks += delivery.finished
            }
        }

    private fun awaitPendingHandler(pending: PendingDesktopCallback): ((String) -> Boolean)? =
        try {
            pending.handler.get(PENDING_CALLBACK_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: TimeoutException) {
            null
        } finally {
            synchronized(callbackMonitor) { pendingCallbacks.remove(pending) }
        }

    companion object {
        @Throws(IOException::class)
        fun start(
            lockFile: Path,
            lockChannel: FileChannel,
            lock: FileLock,
        ): DesktopAuthCallbackBroker {
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            val server = ServerSocket()
            try {
                server.reuseAddress = false
                server.bind(InetSocketAddress(loopback, 0), CALLBACK_SOCKET_BACKLOG)
                writePort(lockChannel, server.localPort)
                return DesktopAuthCallbackBroker(lockFile, lockChannel, lock, server)
            } catch (failure: Throwable) {
                runCatching { server.close() }
                throw failure
            }
        }

        private fun writePort(
            channel: FileChannel,
            port: Int,
        ) {
            val buffer = StandardCharsets.US_ASCII.encode(port.toString())
            channel.truncate(0L)
            channel.position(0L)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }
}

/** Reads one exact frame segment without allowing byte-by-byte traffic to reset its total budget. */
private fun readFullyBeforeDeadline(
    socket: Socket,
    input: InputStream,
    destination: ByteArray,
    deadlineNanos: Long,
): Boolean {
    var offset = 0
    while (offset < destination.size) {
        val remainingMillis = remainingDeadlineMillis(deadlineNanos) ?: return false
        val read =
            try {
                socket.soTimeout = remainingMillis
                input.read(destination, offset, destination.size - offset)
            } catch (_: IOException) {
                return false
            } catch (_: SecurityException) {
                return false
            }
        if (read <= 0) return false
        offset += read
    }
    return true
}

/** An admitted callback remains in-flight until its handler and ACK decision both finish. */
private class CallbackDelivery(
    val handler: ((String) -> Boolean)?,
    val pending: PendingDesktopCallback?,
) {
    val finished: CompletableFuture<Unit> = CompletableFuture()
}

/** A bounded cold-start callback waiting for the presenter handler to become available. */
private class PendingDesktopCallback {
    val handler: CompletableFuture<((String) -> Boolean)?> = CompletableFuture()
}

private fun prepareRestrictedLockFile(lockFile: Path) {
    require(lockFile.isAbsolute) { "Desktop instance lock path must be absolute" }
    val parent = requireNotNull(lockFile.parent) { "Desktop instance lock must have a parent" }
    Files.createDirectories(parent)
    if (Files.isSymbolicLink(lockFile)) throw IOException("Desktop instance lock cannot be a symbolic link")
    try {
        Files.createFile(lockFile)
    } catch (_: FileAlreadyExistsException) {
        if (!Files.isRegularFile(lockFile, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Desktop instance lock must be a regular file")
        }
    }
    Files
        .getFileAttributeView(lockFile, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        ?.setPermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
}

internal fun forwardDesktopCallback(
    lockFile: Path,
    callback: String,
    totalBudgetMillis: Int = CALLBACK_FORWARD_TIMEOUT_MILLIS,
): Boolean {
    val bytes = callback.toByteArray(StandardCharsets.UTF_8)
    if (bytes.size !in 1..MAX_DESKTOP_CALLBACK_BYTES || totalBudgetMillis <= 0) return false
    val deadlineNanos =
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalBudgetMillis.toLong())
    repeat(PORT_READ_ATTEMPTS) { attempt ->
        if (remainingDeadlineMillis(deadlineNanos) == null) return false
        val port = readPrimaryPort(lockFile)
        if (port != null && sendCallbackFrame(port, bytes, deadlineNanos)) return true
        if (attempt < PORT_READ_ATTEMPTS - 1 && !sleepWithinDeadline(deadlineNanos)) {
            return false
        }
    }
    return false
}

private fun readPrimaryPort(lockFile: Path): Int? {
    return try {
        val size = Files.size(lockFile)
        if (size !in 1L..MAX_PORT_TEXT_LENGTH.toLong()) return null
        val raw = Files.readString(lockFile, StandardCharsets.US_ASCII)
        if (!PORT_PATTERN.matches(raw)) return null
        raw.toIntOrNull()?.takeIf { port -> port in 1..MAX_TCP_PORT }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

internal fun sendCallbackFrame(
    port: Int,
    bytes: ByteArray,
): Boolean {
    val deadlineNanos =
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CALLBACK_FORWARD_TIMEOUT_MILLIS.toLong())
    return sendCallbackFrame(port, bytes, deadlineNanos)
}

private fun sendCallbackFrame(
    port: Int,
    bytes: ByteArray,
    deadlineNanos: Long,
): Boolean =
    try {
        Socket().use { socket ->
            val connectTimeout = remainingDeadlineMillis(deadlineNanos) ?: return false
            socket.connect(
                InetSocketAddress(LOOPBACK_HOST, port),
                minOf(CALLBACK_CONNECT_TIMEOUT_MILLIS, connectTimeout),
            )
            DataOutputStream(socket.getOutputStream()).let { output ->
                output.writeInt(bytes.size)
                output.write(bytes)
                output.flush()
            }
            DataInputStream(socket.getInputStream()).let { input ->
                val ackTimeout = remainingDeadlineMillis(deadlineNanos) ?: return false
                socket.soTimeout = minOf(CALLBACK_ACK_TIMEOUT_MILLIS, ackTimeout)
                if (input.readUnsignedByte() != CALLBACK_ACCEPTED_ACK) return false

                // EOF is part of the exact one-byte ACK contract. Recompute the timeout so a peer
                // cannot consume a fresh full ACK budget by sending one byte and keeping the socket open.
                val eofTimeout = remainingDeadlineMillis(deadlineNanos) ?: return false
                socket.soTimeout = minOf(CALLBACK_ACK_TIMEOUT_MILLIS, eofTimeout)
                input.read() == -1
            }
        }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

private fun remainingDeadlineMillis(deadlineNanos: Long): Int? {
    val remainingNanos = deadlineNanos - System.nanoTime()
    if (remainingNanos <= 0L) return null
    val wholeMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos)
    val roundedMillis =
        wholeMillis +
            if (TimeUnit.MILLISECONDS.toNanos(wholeMillis) < remainingNanos) {
                1L
            } else {
                0L
            }
    return roundedMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

private fun sleepWithinDeadline(deadlineNanos: Long): Boolean {
    val remainingNanos = deadlineNanos - System.nanoTime()
    if (remainingNanos <= 0L) return false
    val sleepNanos =
        minOf(
            TimeUnit.MILLISECONDS.toNanos(PORT_READ_RETRY_MILLIS),
            remainingNanos,
        )
    return try {
        val sleepMillis = TimeUnit.NANOSECONDS.toMillis(sleepNanos)
        val nanosRemainder = (sleepNanos - TimeUnit.MILLISECONDS.toNanos(sleepMillis)).toInt()
        Thread.sleep(sleepMillis, nanosRemainder)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}

private val PORT_PATTERN = Regex("[1-9][0-9]{0,4}")
private const val LOOPBACK_HOST: String = "127.0.0.1"
private const val MAX_TCP_PORT: Int = 65_535
private const val MAX_PORT_TEXT_LENGTH: Int = 5
private const val MAX_DESKTOP_CALLBACK_BYTES: Int = 16 * 1024
private const val MAX_PENDING_CALLBACKS: Int = 4
private const val MAX_CONCURRENT_CALLBACKS: Int = 4
private const val MAX_QUEUED_CALLBACK_CONNECTIONS: Int = 4
private const val CALLBACK_SOCKET_BACKLOG: Int = 4
private const val CALLBACK_CONNECT_TIMEOUT_MILLIS: Int = 500
private const val CALLBACK_FRAME_TIMEOUT_MILLIS: Int = 1_000
private const val PENDING_CALLBACK_TIMEOUT_MILLIS: Int = 650
internal const val DESKTOP_REDIRECT_RECEIPT_TIMEOUT_MILLIS: Int = 500
private const val CALLBACK_ACK_WRITE_MARGIN_MILLIS: Int = 250
private const val CALLBACK_ACK_TIMEOUT_MILLIS: Int =
    PENDING_CALLBACK_TIMEOUT_MILLIS +
        DESKTOP_REDIRECT_RECEIPT_TIMEOUT_MILLIS +
        CALLBACK_ACK_WRITE_MARGIN_MILLIS
private const val CALLBACK_FORWARD_TIMEOUT_MILLIS: Int =
    CALLBACK_CONNECT_TIMEOUT_MILLIS + CALLBACK_ACK_TIMEOUT_MILLIS
private const val CALLBACK_ACCEPTED_ACK: Int = 0x06
private const val PORT_READ_ATTEMPTS: Int = 8
private const val PORT_READ_RETRY_MILLIS: Long = 50L
