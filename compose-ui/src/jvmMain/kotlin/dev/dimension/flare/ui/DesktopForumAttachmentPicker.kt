package dev.dimension.flare.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.dimension.flare.data.network.discourse.MAX_DISCOURSE_UPLOAD_BYTES
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/** Desktop picker keeps native Path values inside the JVM source set and copies only bounded data. */
@Composable
internal actual fun rememberForumAttachmentPicker(): ForumAttachmentPicker {
    val scope = rememberCoroutineScope()
    val isOpen = remember { AtomicBoolean(false) }
    return remember(scope) {
        ForumAttachmentPicker { callback ->
            if (!isOpen.compareAndSet(false, true)) {
                callback(ForumAttachmentPickResult.Cancelled)
                return@ForumAttachmentPicker
            }
            if (GraphicsEnvironment.isHeadless()) {
                isOpen.set(false)
                callback(ForumAttachmentPickResult.Cancelled)
                return@ForumAttachmentPicker
            }
            SwingUtilities.invokeLater {
                val chooser = JFileChooser().apply { isMultiSelectionEnabled = false }
                val result = chooser.showOpenDialog(null)
                val selected = chooser.selectedFile?.toPath()
                if (result != JFileChooser.APPROVE_OPTION || selected == null) {
                    isOpen.set(false)
                    callback(ForumAttachmentPickResult.Cancelled)
                } else {
                    scope.launch {
                        val pickResult =
                            try {
                                withContext(Dispatchers.IO) {
                                    readDesktopForumAttachment(selected)
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                ForumAttachmentPickResult.ReadFailed
                            }
                        isOpen.set(false)
                        callback(pickResult)
                    }
                }
            }
        }
    }
}

/**
 * Reads a selected desktop file with a transport-sized allocation guard.
 *
 * The second in-stream check closes a TOCTOU gap where a file grows after [Files.size] succeeds.
 */
internal fun readDesktopForumAttachment(path: Path): ForumAttachmentPickResult {
    if (!Files.isRegularFile(path)) return ForumAttachmentPickResult.ReadFailed
    val declaredSize = Files.size(path)
    if (declaredSize > MAX_DISCOURSE_UPLOAD_BYTES.toLong()) {
        return ForumAttachmentPickResult.TooLarge
    }
    val readResult =
        Files.newInputStream(path).use { stream ->
            stream.readDesktopForumAttachmentBytes(declaredSize.toInt())
        }
    val bytes =
        when (readResult) {
            is DesktopAttachmentReadResult.Complete -> readResult.bytes
            DesktopAttachmentReadResult.TooLarge -> return ForumAttachmentPickResult.TooLarge
            DesktopAttachmentReadResult.Invalid -> return ForumAttachmentPickResult.ReadFailed
        }
    return ForumAttachmentPickResult.Selected(
        ForumPickedAttachment(
            bytes = bytes,
            fileName = normalizeForumAttachmentFileName(path.fileName?.toString()),
            contentType = normalizeForumAttachmentContentType(Files.probeContentType(path)),
        ),
    )
}

/**
 * Reads an exactly sized desktop file without a second full-size copy.
 *
 * [Files.size] is only a snapshot. Filling the exact allocation rejects a file that shrank, while
 * the final one-byte probe rejects growth between the metadata read and stream consumption. Growth
 * from an already maximum-sized file is specifically reported as an oversize upload.
 */
private fun InputStream.readDesktopForumAttachmentBytes(declaredSize: Int?): DesktopAttachmentReadResult =
    if (declaredSize != null) {
        readDesktopForumAttachmentWithKnownSize(declaredSize)
    } else {
        readDesktopForumAttachmentWithUnknownSize()
    }

private fun InputStream.readDesktopForumAttachmentWithKnownSize(declaredSize: Int): DesktopAttachmentReadResult {
    val bytes = ByteArray(declaredSize)
    var offset = 0
    while (offset < bytes.size) {
        val read = read(bytes, offset, bytes.size - offset)
        if (read <= 0) return DesktopAttachmentReadResult.Invalid
        offset += read
    }
    if (read() >= 0) {
        return if (declaredSize == MAX_DISCOURSE_UPLOAD_BYTES) {
            DesktopAttachmentReadResult.TooLarge
        } else {
            DesktopAttachmentReadResult.Invalid
        }
    }
    if (bytes.isEmpty()) return DesktopAttachmentReadResult.Invalid
    return DesktopAttachmentReadResult.Complete(bytes)
}

/**
 * Strict fallback retained for streams whose size cannot be established by a future desktop host.
 * The buffer requests only one byte after reaching the cap, so the growable output never exceeds
 * [MAX_DISCOURSE_UPLOAD_BYTES].
 */
private fun InputStream.readDesktopForumAttachmentWithUnknownSize(): DesktopAttachmentReadResult {
    val output = ByteArrayOutputStream(DEFAULT_ATTACHMENT_BUFFER_SIZE)
    val buffer = ByteArray(DEFAULT_ATTACHMENT_BUFFER_SIZE)
    var totalBytes = 0
    while (true) {
        val readLimit = minOf(buffer.size, MAX_DISCOURSE_UPLOAD_BYTES - totalBytes + 1)
        val read = read(buffer, 0, readLimit)
        if (read < 0) break
        if (read == 0) return DesktopAttachmentReadResult.Invalid
        if (read > MAX_DISCOURSE_UPLOAD_BYTES - totalBytes) {
            return DesktopAttachmentReadResult.TooLarge
        }
        output.write(buffer, 0, read)
        totalBytes += read
    }
    if (totalBytes == 0) return DesktopAttachmentReadResult.Invalid
    return DesktopAttachmentReadResult.Complete(output.toByteArray())
}

private sealed interface DesktopAttachmentReadResult {
    data class Complete(
        val bytes: ByteArray,
    ) : DesktopAttachmentReadResult

    data object TooLarge : DesktopAttachmentReadResult

    data object Invalid : DesktopAttachmentReadResult
}

private const val DEFAULT_ATTACHMENT_BUFFER_SIZE: Int = 8 * 1024
