package dev.dimension.flare.data.network.discourse.composer

import dev.dimension.flare.data.network.discourse.DiscourseApi
import dev.dimension.flare.data.network.discourse.DiscourseUploadProgressListener
import dev.dimension.flare.data.network.discourse.DiscourseUploadRequest
import dev.dimension.flare.data.network.discourse.error.DiscourseException
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFailureKind
import dev.dimension.flare.data.network.discourse.forum.toForumFailureKind
import dev.dimension.flare.data.network.discourse.isSafeDiscourseUploadReference
import dev.dimension.flare.data.network.discourse.model.DiscourseUploadResponse
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.StaleDiscourseSessionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Validated upload descriptor returned after a successful task.
 *
 * [markdownReference] is only the opaque server reference and is not complete composer Markdown.
 * Callers must insert [composerMarkdown], which selects Discourse's explicit image, audio, video,
 * or attachment form and sanitizes the untrusted display filename.
 */
public data class DiscourseUploadedAttachment(
    val uploadId: Long? = null,
    val markdownReference: String,
    val originalFilename: String,
    val width: Int? = null,
    val height: Int? = null,
    val fileSizeBytes: Long? = null,
    val extension: String? = null,
) {
    init {
        require(uploadId == null || uploadId > 0L) { "Upload id must be positive" }
        require(markdownReference.isNotBlank()) { "Upload reference must not be blank" }
        require(markdownReference.length <= MAX_UPLOAD_REFERENCE_CHARS) { "Upload reference is too long" }
        require(markdownReference.isSafeDiscourseUploadReference()) { "Upload reference is unsafe" }
        require(originalFilename.length <= MAX_UPLOAD_FILENAME_CHARS) { "Upload filename is too long" }
        require(originalFilename.none(Char::isControlCharacter)) {
            "Upload filename contains control characters"
        }
        require(width == null || width >= 0) { "Upload width cannot be negative" }
        require(height == null || height >= 0) { "Upload height cannot be negative" }
        require(fileSizeBytes == null || fileSizeBytes >= 0L) { "Upload size cannot be negative" }
        require(extension == null || extension.length <= MAX_UPLOAD_EXTENSION_CHARS) {
            "Upload extension is too long"
        }
        require(extension?.none(Char::isControlCharacter) != false) {
            "Upload extension contains control characters"
        }
    }

    /** Complete, injection-safe Markdown understood by the Discourse composer pipeline. */
    public val composerMarkdown: String
        get() {
            val label = originalFilename.toSafeDiscourseUploadLabel()
            val normalizedExtension = normalizedUploadExtension()
            return when {
                normalizedExtension in DISCOURSE_IMAGE_EXTENSIONS && width.isPositive() && height.isPositive() -> {
                    "![$label|${checkNotNull(width)}x${checkNotNull(height)}]($markdownReference)"
                }

                normalizedExtension in DISCOURSE_AUDIO_EXTENSIONS -> {
                    "![$label|audio]($markdownReference)"
                }

                normalizedExtension in DISCOURSE_VIDEO_EXTENSIONS -> {
                    "![$label|video]($markdownReference)"
                }

                else -> {
                    val safeSize = fileSizeBytes?.toSafeUploadSizeLabel()?.let { " ($it)" }.orEmpty()
                    "[$label|attachment]($markdownReference)$safeSize"
                }
            }
        }

    private fun normalizedUploadExtension(): String {
        val candidate = extension?.takeIf(String::isNotBlank) ?: originalFilename.substringAfterLast('.', missingDelimiterValue = "")
        return candidate
            .trim()
            .removePrefix(".")
            .takeIf { it.length in 1..MAX_CLASSIFIED_EXTENSION_CHARS }
            ?.takeIf { value -> value.all(Char::isAsciiLetterOrDigit) }
            ?.lowercase()
            .orEmpty()
    }
}

/**
 * Observable state of one upload task.
 *
 * Every execution or retry receives a monotonically increasing [attempt]. Progress callbacks from
 * an older attempt are ignored after cancellation or retry, so a late transport callback cannot
 * overwrite the current task. Byte counts are transport-body counts and may include multipart
 * overhead; [Uploading.totalBytes] remains null when Ktor cannot determine the complete body size.
 */
public sealed interface DiscourseUploadTaskState {
    public data object Ready : DiscourseUploadTaskState

    public data class Uploading(
        val attempt: Long,
        val bytesSent: Long,
        val totalBytes: Long?,
    ) : DiscourseUploadTaskState {
        init {
            require(attempt > 0L) { "Upload attempt must be positive" }
            require(bytesSent >= 0L) { "Uploaded bytes cannot be negative" }
            require(totalBytes == null || totalBytes >= bytesSent) {
                "Upload total cannot be below uploaded bytes"
            }
        }
    }

    public data class Succeeded(
        val attempt: Long,
        val attachment: DiscourseUploadedAttachment,
    ) : DiscourseUploadTaskState {
        init {
            require(attempt > 0L) { "Upload attempt must be positive" }
        }
    }

    public data class Failed(
        val attempt: Long,
        val failure: DiscourseForumFailureKind,
    ) : DiscourseUploadTaskState {
        init {
            require(attempt > 0L) { "Upload attempt must be positive" }
        }
    }

    public data class Cancelled(
        val attempt: Long,
    ) : DiscourseUploadTaskState {
        init {
            require(attempt > 0L) { "Upload attempt must be positive" }
        }
    }
}

/**
 * A single upload whose work is always a child of the coroutine calling [execute] or [retry].
 *
 * The task owns no background scope. Cancelling the caller cancels Ktor through structured
 * concurrency; [cancel] cancels only the active child operation. Cancellation is rethrown after the
 * state reaches [DiscourseUploadTaskState.Cancelled], while ordinary transport failures become a
 * terminal [DiscourseUploadTaskState.Failed] value that can be retried explicitly.
 */
public interface DiscourseUploadTask {
    public val state: StateFlow<DiscourseUploadTaskState>

    public suspend fun execute(): DiscourseUploadTaskState

    public suspend fun retry(): DiscourseUploadTaskState

    public suspend fun cancel()
}

internal class DefaultDiscourseUploadTask(
    private val accountId: String,
    private val request: DiscourseUploadRequest,
    private val remote: DiscourseUploadProgressSource,
    private val sessionManager: DiscourseSessionManager,
) : DiscourseUploadTask {
    private val lifecycleMutex = Mutex()
    private val mutableState = MutableStateFlow<DiscourseUploadTaskState>(DiscourseUploadTaskState.Ready)
    private var active: ActiveUpload? = null
    private var latestAttempt: Long = 0L

    override val state: StateFlow<DiscourseUploadTaskState> = mutableState.asStateFlow()

    override suspend fun execute(): DiscourseUploadTaskState = runAttempt(isRetry = false)

    override suspend fun retry(): DiscourseUploadTaskState = runAttempt(isRetry = true)

    override suspend fun cancel() {
        val operation =
            lifecycleMutex.withLock {
                val current = active ?: return@withLock null
                if (current.attempt == latestAttempt) {
                    mutableState.value = DiscourseUploadTaskState.Cancelled(current.attempt)
                }
                current.operation
            }
        operation?.cancel(DiscourseUploadCancellationException())
    }

    private suspend fun runAttempt(isRetry: Boolean): DiscourseUploadTaskState =
        supervisorScope {
            var attempt = 0L
            val operation: Deferred<DiscourseUploadResponse> =
                async(start = CoroutineStart.LAZY) {
                    sessionManager.runForAuthenticatedAccount(accountId) {
                        remote.upload(request) { bytesSent, totalBytes ->
                            updateProgress(attempt, bytesSent, totalBytes)
                        }
                    }
                }
            attempt =
                lifecycleMutex.withLock {
                    check(active == null) { "This upload already has an active attempt" }
                    val current = mutableState.value
                    if (isRetry) {
                        check(
                            current is DiscourseUploadTaskState.Failed ||
                                current is DiscourseUploadTaskState.Cancelled,
                        ) { "Only a failed or cancelled upload can be retried" }
                    } else {
                        check(current is DiscourseUploadTaskState.Ready) {
                            "An upload can be executed only once; use retry after failure or cancellation"
                        }
                    }
                    latestAttempt = latestAttempt.nextAttempt()
                    active = ActiveUpload(attempt = latestAttempt, operation = operation)
                    mutableState.value =
                        DiscourseUploadTaskState.Uploading(
                            attempt = latestAttempt,
                            bytesSent = 0L,
                            totalBytes = null,
                        )
                    latestAttempt
                }

            try {
                operation.start()
                val response = operation.await()
                withContext(NonCancellable) {
                    finishSucceeded(attempt, response.toAttachment())
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    finishCancelled(attempt)
                }
                throw cancelled
            } catch (failure: DiscourseException) {
                withContext(NonCancellable) {
                    finishFailed(attempt, failure.toForumFailureKind())
                }
            } catch (_: StaleDiscourseSessionException) {
                withContext(NonCancellable) {
                    finishFailed(attempt, DiscourseForumFailureKind.Authentication)
                }
            } catch (_: Exception) {
                withContext(NonCancellable) {
                    finishFailed(attempt, DiscourseForumFailureKind.InvalidResponse)
                }
            } finally {
                withContext(NonCancellable) {
                    lifecycleMutex.withLock {
                        if (active?.attempt == attempt) active = null
                    }
                }
            }
        }

    private suspend fun updateProgress(
        attempt: Long,
        bytesSent: Long,
        totalBytes: Long?,
    ) {
        require(bytesSent >= 0L) { "Upload progress bytes cannot be negative" }
        require(totalBytes == null || totalBytes >= bytesSent) {
            "Upload total cannot be below uploaded bytes"
        }
        lifecycleMutex.withLock {
            val current = mutableState.value as? DiscourseUploadTaskState.Uploading ?: return
            if (active?.attempt != attempt || current.attempt != attempt) return
            if (bytesSent < current.bytesSent) return
            if (current.totalBytes != null && totalBytes != null && current.totalBytes != totalBytes) return
            mutableState.value =
                current.copy(
                    bytesSent = bytesSent,
                    totalBytes = totalBytes ?: current.totalBytes,
                )
        }
    }

    private suspend fun finishSucceeded(
        attempt: Long,
        attachment: DiscourseUploadedAttachment,
    ): DiscourseUploadTaskState =
        lifecycleMutex.withLock {
            val current = mutableState.value
            if (active?.attempt == attempt && current is DiscourseUploadTaskState.Uploading && current.attempt == attempt) {
                val succeeded = DiscourseUploadTaskState.Succeeded(attempt, attachment)
                mutableState.value = succeeded
                succeeded
            } else {
                current
            }
        }

    private suspend fun finishFailed(
        attempt: Long,
        failure: DiscourseForumFailureKind,
    ): DiscourseUploadTaskState =
        lifecycleMutex.withLock {
            val current = mutableState.value
            if (active?.attempt == attempt && current is DiscourseUploadTaskState.Uploading && current.attempt == attempt) {
                val failed = DiscourseUploadTaskState.Failed(attempt, failure)
                mutableState.value = failed
                failed
            } else {
                current
            }
        }

    private suspend fun finishCancelled(attempt: Long) {
        lifecycleMutex.withLock {
            if (active?.attempt != attempt) return
            val current = mutableState.value
            if (current is DiscourseUploadTaskState.Uploading && current.attempt == attempt) {
                mutableState.value = DiscourseUploadTaskState.Cancelled(attempt)
            }
        }
    }
}

/**
 * Progress-capable source implemented by the Ktor transport bridge and replaced directly in tests.
 * Callback values must be monotonic for one invocation. Implementations must not retain the callback
 * after [upload] returns or is cancelled.
 */
internal fun interface DiscourseUploadProgressSource {
    suspend fun upload(
        request: DiscourseUploadRequest,
        reportProgress: suspend (bytesSent: Long, totalBytes: Long?) -> Unit,
    ): DiscourseUploadResponse
}

/**
 * Bridges Ktor BodyProgress into the domain callback without introducing a detached writer scope.
 */
internal suspend fun uploadWithProgress(
    api: DiscourseApi,
    request: DiscourseUploadRequest,
    reportProgress: suspend (bytesSent: Long, totalBytes: Long?) -> Unit,
): DiscourseUploadResponse =
    api.upload(
        request = request,
        progressListener =
            DiscourseUploadProgressListener { bytesSent, totalBytes ->
                reportProgress(bytesSent, totalBytes)
            },
    )

private data class ActiveUpload(
    val attempt: Long,
    val operation: Deferred<DiscourseUploadResponse>,
)

private class DiscourseUploadCancellationException : CancellationException("Discourse upload cancelled")

/**
 * Converts the validated wire response without retaining the server-provided human-size string.
 *
 * Discourse may return original and thumbnail dimensions. A complete positive original pair wins;
 * otherwise a complete positive thumbnail pair is used. Dimensions are never mixed across pairs,
 * because that could manufacture an incorrect aspect ratio and unsafe composer image metadata.
 */
internal fun DiscourseUploadResponse.toAttachment(): DiscourseUploadedAttachment {
    val safeDimensions =
        when {
            width.isPositive() && height.isPositive() -> {
                checkNotNull(width) to checkNotNull(height)
            }

            thumbnailWidth.isPositive() && thumbnailHeight.isPositive() -> {
                checkNotNull(thumbnailWidth) to checkNotNull(thumbnailHeight)
            }

            else -> {
                null
            }
        }
    return DiscourseUploadedAttachment(
        uploadId = id,
        markdownReference = resolvedReference,
        originalFilename = originalFilename,
        width = safeDimensions?.first,
        height = safeDimensions?.second,
        fileSizeBytes = filesize,
        extension = extension,
    )
}

private fun Long.nextAttempt(): Long {
    check(this < Long.MAX_VALUE) { "Upload attempt space is exhausted" }
    return this + 1L
}

/**
 * Removes every delimiter that could escape or alter Discourse's generated upload label.
 *
 * Whitespace is normalized to one ordinary space and the result is bounded. The raw filename is
 * still retained separately for display, but no raw filename character is interpolated directly
 * into [DiscourseUploadedAttachment.composerMarkdown].
 */
internal fun String.toSafeDiscourseUploadLabel(): String {
    val sanitized =
        buildString(capacity = minOf(length, MAX_UPLOAD_LABEL_CHARS)) {
            this@toSafeDiscourseUploadLabel.forEach { character ->
                if (length >= MAX_UPLOAD_LABEL_CHARS) return@forEach
                when {
                    character.isForbiddenUploadLabelCharacter() -> Unit
                    character.isWhitespace() -> if (isNotEmpty() && last() != ' ') append(' ')
                    else -> append(character)
                }
            }
        }.trim()
    return sanitized.ifEmpty { FALLBACK_UPLOAD_LABEL }
}

/** Locally derives a bounded numeric size label instead of trusting arbitrary server display text. */
private fun Long.toSafeUploadSizeLabel(): String =
    when {
        this >= EXBIBYTE -> "${this / EXBIBYTE} EB"
        this >= PEBIBYTE -> "${this / PEBIBYTE} PB"
        this >= TEBIBYTE -> "${this / TEBIBYTE} TB"
        this >= GIBIBYTE -> "${this / GIBIBYTE} GB"
        this >= MEBIBYTE -> "${this / MEBIBYTE} MB"
        this >= KIBIBYTE -> "${this / KIBIBYTE} KB"
        else -> "$this B"
    }

private fun Char.isForbiddenUploadLabelCharacter(): Boolean =
    isControlCharacter() ||
        this == '[' ||
        this == ']' ||
        this == '|' ||
        this == '\\' ||
        this == '`' ||
        this == '<' ||
        this == '>'

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun Char.isControlCharacter(): Boolean = code < 0x20 || code in 0x7f..0x9f

private fun Int?.isPositive(): Boolean = this != null && this > 0

private const val MAX_UPLOAD_REFERENCE_CHARS: Int = 4_096
private const val MAX_UPLOAD_FILENAME_CHARS: Int = 512
private const val MAX_UPLOAD_EXTENSION_CHARS: Int = 32
private const val MAX_CLASSIFIED_EXTENSION_CHARS: Int = 16
private const val MAX_UPLOAD_LABEL_CHARS: Int = 160
private const val FALLBACK_UPLOAD_LABEL: String = "upload"
private const val KIBIBYTE: Long = 1_024L
private const val MEBIBYTE: Long = KIBIBYTE * 1_024L
private const val GIBIBYTE: Long = MEBIBYTE * 1_024L
private const val TEBIBYTE: Long = GIBIBYTE * 1_024L
private const val PEBIBYTE: Long = TEBIBYTE * 1_024L
private const val EXBIBYTE: Long = PEBIBYTE * 1_024L
private val DISCOURSE_IMAGE_EXTENSIONS: Set<String> =
    setOf("avif", "bmp", "gif", "heic", "heif", "jpeg", "jpg", "png", "tif", "tiff", "webp")
private val DISCOURSE_AUDIO_EXTENSIONS: Set<String> =
    setOf("aac", "flac", "m4a", "mp3", "oga", "ogg", "opus", "wav")
private val DISCOURSE_VIDEO_EXTENSIONS: Set<String> =
    setOf("m4v", "mov", "mp4", "ogv", "webm")
