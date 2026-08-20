package dev.dimension.flare.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.dimension.flare.data.network.discourse.MAX_DISCOURSE_UPLOAD_BYTES
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Android OpenDocument bridge.
 *
 * The content URI is consumed inside this host and never placed in common state. Reading occurs on
 * Dispatchers.IO and stops after one byte beyond the shared 16 MiB bound, covering providers that
 * omit or lie about OpenableColumns.SIZE.
 */
@Composable
internal actual fun rememberForumAttachmentPicker(): ForumAttachmentPicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingCallback by remember {
        mutableStateOf<((ForumAttachmentPickResult) -> Unit)?>(null)
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val callback = pendingCallback ?: return@rememberLauncherForActivityResult
            pendingCallback = null
            if (uri == null) {
                callback(ForumAttachmentPickResult.Cancelled)
            } else {
                scope.launch {
                    val result =
                        try {
                            withContext(Dispatchers.IO) {
                                context.contentResolver.readForumAttachment(uri)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            ForumAttachmentPickResult.ReadFailed
                        }
                    callback(result)
                }
            }
        }

    return remember(launcher) {
        ForumAttachmentPicker { callback ->
            // The common UI disables the attach button while a launch is outstanding. Treat a
            // duplicate call as a benign no-op instead of replacing the callback owner.
            if (pendingCallback == null) {
                pendingCallback = callback
                launcher.launch(arrayOf("*/*"))
            } else {
                callback(ForumAttachmentPickResult.Cancelled)
            }
        }
    }
}

private fun ContentResolver.readForumAttachment(uri: Uri): ForumAttachmentPickResult {
    val metadata = queryForumAttachmentMetadata(uri)
    if (metadata.size != null && metadata.size > MAX_DISCOURSE_UPLOAD_BYTES.toLong()) {
        return ForumAttachmentPickResult.TooLarge
    }
    val input = openInputStream(uri) ?: return ForumAttachmentPickResult.ReadFailed
    val readResult =
        input.use { stream ->
            metadata.size?.let { declaredSize ->
                stream.readForumAttachmentWithKnownSize(declaredSize.toInt())
            } ?: stream.readForumAttachmentWithUnknownSize()
        }
    val bytes =
        when (readResult) {
            is AndroidAttachmentReadResult.Complete -> readResult.bytes
            AndroidAttachmentReadResult.TooLarge -> return ForumAttachmentPickResult.TooLarge
            AndroidAttachmentReadResult.Invalid -> return ForumAttachmentPickResult.ReadFailed
        }
    return ForumAttachmentPickResult.Selected(
        ForumPickedAttachment(
            bytes = bytes,
            fileName = normalizeForumAttachmentFileName(metadata.displayName),
            contentType = normalizeForumAttachmentContentType(getType(uri)),
        ),
    )
}

/**
 * Trusts provider size metadata only as an allocation bound, then verifies the stream exactly.
 *
 * Reading directly into the result avoids retaining both a growable buffer and its copied result.
 * A one-byte probe catches a provider that understates its size. An understated value below the
 * global cap is treated as invalid metadata rather than silently truncating or reallocating; when
 * the declared value is already at the cap, that probe proves the upload is too large.
 */
private fun InputStream.readForumAttachmentWithKnownSize(declaredSize: Int): AndroidAttachmentReadResult {
    val bytes = ByteArray(declaredSize)
    var offset = 0
    while (offset < bytes.size) {
        val read = read(bytes, offset, bytes.size - offset)
        if (read <= 0) return AndroidAttachmentReadResult.Invalid
        offset += read
    }
    if (read() >= 0) {
        return if (declaredSize == MAX_DISCOURSE_UPLOAD_BYTES) {
            AndroidAttachmentReadResult.TooLarge
        } else {
            AndroidAttachmentReadResult.Invalid
        }
    }
    if (bytes.isEmpty()) return AndroidAttachmentReadResult.Invalid
    return AndroidAttachmentReadResult.Complete(bytes)
}

/** Strict fallback for providers that omit size metadata; it never writes beyond the shared cap. */
private fun InputStream.readForumAttachmentWithUnknownSize(): AndroidAttachmentReadResult {
    val output = ByteArrayOutputStream(DEFAULT_ATTACHMENT_BUFFER_SIZE)
    val buffer = ByteArray(DEFAULT_ATTACHMENT_BUFFER_SIZE)
    var totalBytes = 0
    while (true) {
        // Once the cap is reached, request exactly one byte so EOF and overflow remain distinct.
        val readLimit = minOf(buffer.size, MAX_DISCOURSE_UPLOAD_BYTES - totalBytes + 1)
        val read = read(buffer, 0, readLimit)
        if (read < 0) break
        if (read == 0) return AndroidAttachmentReadResult.Invalid
        if (read > MAX_DISCOURSE_UPLOAD_BYTES - totalBytes) {
            return AndroidAttachmentReadResult.TooLarge
        }
        output.write(buffer, 0, read)
        totalBytes += read
    }
    if (totalBytes == 0) return AndroidAttachmentReadResult.Invalid
    return AndroidAttachmentReadResult.Complete(output.toByteArray())
}

private sealed interface AndroidAttachmentReadResult {
    data class Complete(
        val bytes: ByteArray,
    ) : AndroidAttachmentReadResult

    data object TooLarge : AndroidAttachmentReadResult

    data object Invalid : AndroidAttachmentReadResult
}

private fun ContentResolver.queryForumAttachmentMetadata(uri: Uri): AndroidAttachmentMetadata {
    var displayName: String? = null
    var size: Long? = null
    query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameColumn >= 0 && !cursor.isNull(nameColumn)) displayName = cursor.getString(nameColumn)
            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                size = cursor.getLong(sizeColumn).takeIf { it >= 0L }
            }
        }
    }
    return AndroidAttachmentMetadata(displayName = displayName, size = size)
}

private data class AndroidAttachmentMetadata(
    val displayName: String?,
    val size: Long?,
)

private const val DEFAULT_ATTACHMENT_BUFFER_SIZE: Int = 8 * 1024
