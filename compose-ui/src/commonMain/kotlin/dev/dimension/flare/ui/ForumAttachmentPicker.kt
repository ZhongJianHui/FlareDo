package dev.dimension.flare.ui

import androidx.compose.runtime.Composable
import dev.dimension.flare.data.network.discourse.DiscourseUploadRequest

/**
 * Platform-selected attachment copied into an application-owned, bounded byte array.
 *
 * A file-system path or Android content URI is deliberately absent. The shared composer must not
 * retain a host capability after the picker has returned, and every platform checks the 16 MiB
 * allocation bound before constructing this value.
 */
internal data class ForumPickedAttachment(
    val bytes: ByteArray,
    val fileName: String,
    val contentType: String?,
) {
    fun toUploadRequest(): DiscourseUploadRequest =
        DiscourseUploadRequest(
            bytes = bytes,
            fileName = fileName,
            contentType = contentType,
        )
}

/** Converts untrusted provider metadata to a bounded multipart display name, never a path. */
internal fun normalizeForumAttachmentFileName(candidate: String?): String {
    val leaf =
        candidate
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.map { character -> if (character.isForumAttachmentControl()) '_' else character }
            ?.joinToString(separator = "")
            ?.trim()
            ?.take(MAX_FORUM_ATTACHMENT_FILE_NAME_CHARS)
            .orEmpty()
    return leaf.ifEmpty { DEFAULT_FORUM_ATTACHMENT_FILE_NAME }
}

/** MIME metadata is optional; malformed provider values are safer to omit than to repair. */
internal fun normalizeForumAttachmentContentType(candidate: String?): String? =
    candidate?.takeIf { value ->
        value.isNotBlank() &&
            value.length <= MAX_FORUM_ATTACHMENT_CONTENT_TYPE_CHARS &&
            value.none(Char::isForumAttachmentControl)
    }

/** A cancelled system picker is an ordinary no-op and never maps to an error banner. */
internal sealed interface ForumAttachmentPickResult {
    data class Selected(
        val attachment: ForumPickedAttachment,
    ) : ForumAttachmentPickResult

    data object Cancelled : ForumAttachmentPickResult

    data object TooLarge : ForumAttachmentPickResult

    data object ReadFailed : ForumAttachmentPickResult
}

/**
 * Host capability used by common Compose without exposing Activity, URI, File, or Path values.
 * Implementations invoke [onResult] at most once for each accepted launch.
 */
internal fun interface ForumAttachmentPicker {
    fun launch(onResult: (ForumAttachmentPickResult) -> Unit)

    companion object {
        val Unavailable: ForumAttachmentPicker =
            ForumAttachmentPicker { callback -> callback(ForumAttachmentPickResult.Cancelled) }
    }
}

/** Remembers the native single-file picker for the current Android or desktop composition. */
@Composable
internal expect fun rememberForumAttachmentPicker(): ForumAttachmentPicker

private const val DEFAULT_FORUM_ATTACHMENT_FILE_NAME: String = "attachment"
private const val MAX_FORUM_ATTACHMENT_FILE_NAME_CHARS: Int = 512
private const val MAX_FORUM_ATTACHMENT_CONTENT_TYPE_CHARS: Int = 256

private fun Char.isForumAttachmentControl(): Boolean = code < 0x20 || code == 0x7f
