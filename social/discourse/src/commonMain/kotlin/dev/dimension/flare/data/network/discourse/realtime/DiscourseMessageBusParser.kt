package dev.dimension.flare.data.network.discourse.realtime

import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationException
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationPhase
import dev.dimension.flare.data.network.discourse.model.discourseJson
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

internal const val MAX_DISCOURSE_MESSAGE_BUS_FRAME_BYTES: Int = 2 * 1_024 * 1_024
internal const val MAX_DISCOURSE_MESSAGE_BUS_RESPONSE_BYTES: Long = 8L * 1_024L * 1_024L
internal const val MAX_DISCOURSE_MESSAGE_BUS_EVENTS_PER_BATCH: Int = 2_048

private val FRAME_SEPARATOR: ByteArray = byteArrayOf(13, 10, 124, 13, 10)
private val ESCAPED_FRAME_SEPARATOR: ByteArray = byteArrayOf(13, 10, 124, 124, 13, 10)

/**
 * Streaming decoder for MessageBus' application-level chunk delimiter.
 *
 * HTTP intermediaries may split or re-chunk at any byte, including inside `\r\n|\r\n`. This
 * decoder therefore carries at most six candidate delimiter bytes between reads. It buffers only
 * the current JSON frame, caps both a frame and cumulative response bytes, and reverses the server's
 * `\r\n||\r\n` escaping before handing a frame to JSON decoding.
 */
internal class DiscourseMessageBusFrameDecoder(
    private val maxFrameBytes: Int = MAX_DISCOURSE_MESSAGE_BUS_FRAME_BYTES,
    private val maxResponseBytes: Long = MAX_DISCOURSE_MESSAGE_BUS_RESPONSE_BYTES,
) {
    init {
        require(maxFrameBytes > 0) { "MessageBus frame limit must be positive" }
        require(maxResponseBytes >= maxFrameBytes) { "MessageBus response limit cannot be smaller than a frame" }
    }

    suspend fun decode(
        channel: ByteReadChannel,
        framed: Boolean,
        onFrame: suspend (ByteArray) -> Unit,
    ) {
        val readBuffer = ByteArray(8 * 1_024)
        val frame = BoundedByteAccumulator(maxFrameBytes)
        val candidate = mutableListOf<Byte>()
        var totalBytes = 0L
        var deliveredFrames = 0

        while (true) {
            val count = channel.readAvailable(readBuffer)
            if (count < 0) break
            if (count == 0) continue
            totalBytes += count
            if (totalBytes > maxResponseBytes) protocolFailure()

            if (!framed) {
                frame.append(readBuffer, count)
                continue
            }

            repeat(count) { index ->
                candidate += readBuffer[index]
                var resolved = false
                while (!resolved) {
                    when {
                        candidate.matches(ESCAPED_FRAME_SEPARATOR) -> {
                            frame.append(FRAME_SEPARATOR, FRAME_SEPARATOR.size)
                            candidate.clear()
                            resolved = true
                        }

                        candidate.matches(FRAME_SEPARATOR) -> {
                            onFrame(frame.takeBytes())
                            deliveredFrames += 1
                            candidate.clear()
                            resolved = true
                        }

                        candidate.isPrefixOf(FRAME_SEPARATOR) || candidate.isPrefixOf(ESCAPED_FRAME_SEPARATOR) -> {
                            resolved = true
                        }

                        else -> {
                            frame.append(candidate.removeAt(0))
                        }
                    }
                }
            }
        }

        channel.closedCause?.let { throw it }
        if (framed) {
            // A framed response must terminate at the application delimiter. Accepting a partial
            // final JSON document would hide proxy truncation as a clean long-poll completion.
            if (candidate.isNotEmpty() || !frame.isEmpty || deliveredFrames == 0) protocolFailure()
        } else {
            if (frame.isEmpty) protocolFailure()
            onFrame(frame.takeBytes())
        }
    }
}

/** Decodes and validates only the fixed MessageBus envelope fields. */
internal class DiscourseMessageBusBatchDecoder {
    fun decode(
        bytes: ByteArray,
        expectedChannels: Set<String>,
    ): DiscourseMessageBusBatch {
        val text =
            try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                protocolFailure()
            }
        val envelope =
            try {
                discourseJson.parseToJsonElement(text) as? JsonArray ?: protocolFailure()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: SerializationException) {
                protocolFailure()
            } catch (_: IllegalArgumentException) {
                protocolFailure()
            }
        if (envelope.size > MAX_DISCOURSE_MESSAGE_BUS_EVENTS_PER_BATCH) protocolFailure()

        val wireMessages =
            envelope.map { element ->
                val objectValue = element as? JsonObject ?: protocolFailure()
                // kotlinx.serialization intentionally accepts quoted JSON numbers for numeric
                // properties. MessageBus identities are security-sensitive ordering keys, so the
                // wire contract requires actual integer primitives before typed deserialization.
                objectValue.requireUnquotedLong("global_id")
                objectValue.requireUnquotedLong("message_id")
                try {
                    discourseJson.decodeFromJsonElement(DiscourseMessageBusWireMessage.serializer(), objectValue)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: SerializationException) {
                    protocolFailure()
                } catch (_: IllegalArgumentException) {
                    protocolFailure()
                }
            }

        val events =
            wireMessages.map { wire ->
                if (wire.channel == DISCOURSE_MESSAGE_BUS_STATUS_CHANNEL) {
                    wire.toStatus(expectedChannels)
                } else {
                    wire.toMessage(expectedChannels)
                }
            }
        return DiscourseMessageBusBatch(events)
    }
}

private fun JsonObject.requireUnquotedLong(name: String) {
    val primitive = this[name] as? JsonPrimitive ?: protocolFailure()
    if (primitive.isString || primitive.longOrNull == null) protocolFailure()
}

@Serializable
private data class DiscourseMessageBusWireMessage(
    @SerialName("global_id")
    val globalId: Long,
    @SerialName("message_id")
    val messageId: Long,
    val channel: String,
    val data: JsonElement,
)

private fun DiscourseMessageBusWireMessage.toMessage(expectedChannels: Set<String>): DiscourseMessageBusMessage {
    if (channel !in expectedChannels || !isValidDiscourseMessageBusChannel(channel)) protocolFailure()
    if (globalId !in 1L..MAX_DISCOURSE_MESSAGE_BUS_SAFE_INTEGER) protocolFailure()
    if (messageId !in 1L..MAX_DISCOURSE_MESSAGE_BUS_SAFE_INTEGER) protocolFailure()
    return DiscourseMessageBusMessage(
        globalId = globalId,
        messageId = messageId,
        channel = channel,
        data = data,
    )
}

private fun DiscourseMessageBusWireMessage.toStatus(expectedChannels: Set<String>): DiscourseMessageBusStatus {
    if (globalId != -1L || messageId != -1L) protocolFailure()
    val statusObject = data as? JsonObject ?: protocolFailure()
    if (statusObject.size > expectedChannels.size || statusObject.size > MAX_DISCOURSE_MESSAGE_BUS_CHANNELS) {
        protocolFailure()
    }
    val cursors =
        buildMap {
            statusObject.forEach { (channel, value) ->
                if (channel !in expectedChannels || !isValidDiscourseMessageBusChannel(channel)) protocolFailure()
                val primitive = value as? JsonPrimitive ?: protocolFailure()
                val cursor = primitive.takeUnless(JsonPrimitive::isString)?.longOrNull ?: protocolFailure()
                if (cursor !in 0L..MAX_DISCOURSE_MESSAGE_BUS_SAFE_INTEGER) protocolFailure()
                put(channel, cursor)
            }
        }
    return DiscourseMessageBusStatus(
        globalId = globalId,
        messageId = messageId,
        cursors = cursors,
    )
}

private class BoundedByteAccumulator(
    private val maximumSize: Int,
) {
    private var bytes: ByteArray = ByteArray(minOf(4_096, maximumSize))
    private var size: Int = 0

    val isEmpty: Boolean
        get() = size == 0

    fun append(value: Byte) {
        ensureCapacity(size + 1)
        bytes[size] = value
        size += 1
    }

    fun append(
        source: ByteArray,
        count: Int,
    ) {
        if (count < 0 || count > source.size) protocolFailure()
        ensureCapacity(size + count)
        source.copyInto(bytes, destinationOffset = size, startIndex = 0, endIndex = count)
        size += count
    }

    fun takeBytes(): ByteArray {
        val result = bytes.copyOf(size)
        size = 0
        return result
    }

    private fun ensureCapacity(required: Int) {
        if (required < 0 || required > maximumSize) protocolFailure()
        if (required <= bytes.size) return
        var expanded = bytes.size.coerceAtLeast(1)
        while (expanded < required) {
            expanded = minOf(maximumSize, expanded * 2)
            if (expanded < required && expanded == maximumSize) protocolFailure()
        }
        bytes = bytes.copyOf(expanded)
    }
}

private fun List<Byte>.matches(pattern: ByteArray): Boolean = size == pattern.size && indices.all { this[it] == pattern[it] }

private fun List<Byte>.isPrefixOf(pattern: ByteArray): Boolean = size <= pattern.size && indices.all { this[it] == pattern[it] }

private fun protocolFailure(): Nothing = throw DiscourseSerializationException(DiscourseSerializationPhase.ResponseDecoding)
