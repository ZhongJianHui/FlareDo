package dev.dimension.flare.data.network.discourse.composer

import dev.dimension.flare.data.network.discourse.DiscourseUploadRequest
import dev.dimension.flare.data.network.discourse.error.DiscourseNetworkException
import dev.dimension.flare.data.network.discourse.error.DiscourseNetworkFailureKind
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFailureKind
import dev.dimension.flare.data.network.discourse.model.DiscourseUploadResponse
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

internal class DiscourseUploadTaskTest {
    @Test
    fun composerMarkdownUsesExplicitDiscourseMediaAndAttachmentForms() {
        val image =
            uploadedAttachment(
                originalFilename = "fixture.PNG",
                extension = "PNG",
                width = 640,
                height = 480,
            )
        val audio = uploadedAttachment(originalFilename = "fixture.MP3", extension = "MP3")
        val video = uploadedAttachment(originalFilename = "fixture.WeBm", extension = ".WeBm")
        val document = uploadedAttachment(originalFilename = "fixture.pdf", extension = "PDF")

        assertEquals("![fixture.PNG|640x480](upload://safe-fixture)", image.composerMarkdown)
        assertEquals("![fixture.MP3|audio](upload://safe-fixture)", audio.composerMarkdown)
        assertEquals("![fixture.WeBm|video](upload://safe-fixture)", video.composerMarkdown)
        assertEquals("[fixture.pdf|attachment](upload://safe-fixture)", document.composerMarkdown)
    }

    @Test
    fun imageWithoutBothPositiveDimensionsFallsBackToAttachment() {
        val missingHeight =
            uploadedAttachment(
                originalFilename = "fixture.png",
                extension = "png",
                width = 640,
                height = null,
            )
        val zeroWidth = missingHeight.copy(width = 0, height = 480)

        assertEquals("[fixture.png|attachment](upload://safe-fixture)", missingHeight.composerMarkdown)
        assertEquals("[fixture.png|attachment](upload://safe-fixture)", zeroWidth.composerMarkdown)
    }

    @Test
    fun responseUsesOnlyCompleteOriginalOrThumbnailDimensionPairs() {
        val thumbnailFallback =
            DiscourseUploadResponse(
                shortUrl = "upload://safe-fixture",
                originalFilename = "fixture.png",
                width = 640,
                height = null,
                thumbnailWidth = 320,
                thumbnailHeight = 240,
                extension = "png",
            ).toAttachment()
        val bothPairsIncomplete =
            DiscourseUploadResponse(
                shortUrl = "upload://safe-fixture",
                originalFilename = "fixture.png",
                width = 640,
                height = null,
                thumbnailWidth = null,
                thumbnailHeight = 240,
                extension = "png",
            ).toAttachment()

        assertEquals("![fixture.png|320x240](upload://safe-fixture)", thumbnailFallback.composerMarkdown)
        assertEquals("[fixture.png|attachment](upload://safe-fixture)", bothPairsIncomplete.composerMarkdown)
    }

    @Test
    fun attachmentSizeIsLocallyDerivedAndArbitraryServerHumanSizeIsIgnored() {
        val sized =
            uploadedAttachment(
                originalFilename = "fixture.pdf",
                extension = "pdf",
                fileSizeBytes = 4_096L,
            )
        val withoutSize = uploadedAttachment(originalFilename = "fixture.pdf", extension = "pdf")
        val hostileHumanSize =
            DiscourseUploadResponse(
                shortUrl = "upload://safe-fixture",
                originalFilename = "fixture.pdf",
                filesize = 2_048L,
                humanFilesize = "] (javascript:fixture)",
                extension = "pdf",
            ).toAttachment()

        assertEquals("[fixture.pdf|attachment](upload://safe-fixture) (4 KB)", sized.composerMarkdown)
        assertEquals("[fixture.pdf|attachment](upload://safe-fixture)", withoutSize.composerMarkdown)
        assertEquals("[fixture.pdf|attachment](upload://safe-fixture) (2 KB)", hostileHumanSize.composerMarkdown)
        assertFalse(hostileHumanSize.composerMarkdown.contains("javascript"))
    }

    @Test
    fun composerMarkdownSanitizesMaliciousLabelsAndUsesSafeFallback() {
        val malicious =
            uploadedAttachment(
                originalFilename = "[x]|audio\\`<script>.PDF",
                extension = "pdf",
            )

        assertEquals("[xaudioscript.PDF|attachment](upload://safe-fixture)", malicious.composerMarkdown)
        assertEquals("upload", "\u0000[\n]|\\`<>".toSafeDiscourseUploadLabel())
    }

    @Test
    fun progressIsMonotonicAndSuccessCarriesAttemptIdentity() =
        runTest {
            val sessionManager = authenticatedSession()
            lateinit var task: DefaultDiscourseUploadTask
            val source =
                DiscourseUploadProgressSource { request, progress ->
                    progress(5L, 10L)
                    assertEquals(5L, assertIs<DiscourseUploadTaskState.Uploading>(task.state.value).bytesSent)
                    progress(3L, 10L)
                    assertEquals(5L, assertIs<DiscourseUploadTaskState.Uploading>(task.state.value).bytesSent)
                    progress(10L, 10L)
                    successfulUpload(request)
                }
            task = uploadTask(source, sessionManager)

            val result = assertIs<DiscourseUploadTaskState.Succeeded>(task.execute())

            assertEquals(1L, result.attempt)
            assertEquals("upload://self-authored", result.attachment.markdownReference)
            assertEquals(result, task.state.value)
        }

    @Test
    fun ordinaryFailureCanRetryWithANewAttemptEpoch() =
        runTest {
            val sessionManager = authenticatedSession()
            var invocation = 0
            val source =
                DiscourseUploadProgressSource { request, progress ->
                    invocation += 1
                    if (invocation == 1) {
                        throw DiscourseNetworkException(DiscourseNetworkFailureKind.Connection)
                    }
                    progress(4L, 4L)
                    successfulUpload(request)
                }
            val task = uploadTask(source, sessionManager)

            val failed = assertIs<DiscourseUploadTaskState.Failed>(task.execute())
            val succeeded = assertIs<DiscourseUploadTaskState.Succeeded>(task.retry())

            assertEquals(1L, failed.attempt)
            assertEquals(DiscourseForumFailureKind.Network, failed.failure)
            assertEquals(2L, succeeded.attempt)
            assertEquals(2, invocation)
        }

    @Test
    fun unknownFailureBecomesRetryableInvalidResponseInsteadOfStrandingInFlightState() =
        runTest {
            val sessionManager = authenticatedSession()
            var invocation = 0
            val source =
                DiscourseUploadProgressSource { request, _ ->
                    invocation += 1
                    if (invocation == 1) throw IllegalStateException("Fixture mapper failure")
                    successfulUpload(request)
                }
            val task = uploadTask(source, sessionManager)

            val failed = assertIs<DiscourseUploadTaskState.Failed>(task.execute())
            val succeeded = assertIs<DiscourseUploadTaskState.Succeeded>(task.retry())

            assertEquals(DiscourseForumFailureKind.InvalidResponse, failed.failure)
            assertEquals(2L, succeeded.attempt)
        }

    @Test
    fun explicitCancelStopsOnlyTheStructuredChildAndPublishesCancelledState() =
        runTest {
            supervisorScope {
                val sessionManager = authenticatedSession()
                val started = CompletableDeferred<Unit>()
                val source =
                    DiscourseUploadProgressSource { _, progress ->
                        progress(1L, null)
                        started.complete(Unit)
                        awaitCancellation()
                    }
                val task = uploadTask(source, sessionManager)
                val running = async { task.execute() }
                started.await()

                task.cancel()

                assertFailsWith<CancellationException> { running.await() }
                val cancelled = assertIs<DiscourseUploadTaskState.Cancelled>(task.state.value)
                assertEquals(1L, cancelled.attempt)
            }
        }

    @Test
    fun lateProgressFromCancelledAttemptCannotOverwriteRetry() =
        runTest {
            supervisorScope {
                val sessionManager = authenticatedSession()
                val callbacks = mutableListOf<suspend (Long, Long?) -> Unit>()
                val firstStarted = CompletableDeferred<Unit>()
                val retryStarted = CompletableDeferred<Unit>()
                val finishRetry = CompletableDeferred<Unit>()
                var invocation = 0
                val source =
                    DiscourseUploadProgressSource { request, progress ->
                        invocation += 1
                        callbacks += progress
                        if (invocation == 1) {
                            firstStarted.complete(Unit)
                            awaitCancellation()
                        } else {
                            progress(2L, 10L)
                            retryStarted.complete(Unit)
                            finishRetry.await()
                            progress(10L, 10L)
                            successfulUpload(request)
                        }
                    }
                val task = uploadTask(source, sessionManager)
                val first = async { task.execute() }
                firstStarted.await()
                task.cancel()
                assertFailsWith<CancellationException> { first.await() }

                val retry = async { task.retry() }
                retryStarted.await()
                callbacks.first().invoke(9L, 10L)

                val active = assertIs<DiscourseUploadTaskState.Uploading>(task.state.value)
                assertEquals(2L, active.attempt)
                assertEquals(2L, active.bytesSent)
                finishRetry.complete(Unit)
                assertIs<DiscourseUploadTaskState.Succeeded>(retry.await())
            }
        }

    private fun uploadTask(
        source: DiscourseUploadProgressSource,
        sessionManager: DiscourseSessionManager,
    ): DefaultDiscourseUploadTask =
        DefaultDiscourseUploadTask(
            accountId = ACCOUNT_ID,
            request =
                DiscourseUploadRequest(
                    bytes = byteArrayOf(1, 2, 3, 4),
                    fileName = "sample.png",
                    contentType = "image/png",
                ),
            remote = source,
            sessionManager = sessionManager,
        )

    private suspend fun authenticatedSession(): DiscourseSessionManager =
        DiscourseSessionManager().also {
            it.startAuthenticatedSession(accountId = ACCOUNT_ID)
        }

    private companion object {
        const val ACCOUNT_ID: String = "42"
    }
}

private fun uploadedAttachment(
    originalFilename: String,
    extension: String?,
    width: Int? = null,
    height: Int? = null,
    fileSizeBytes: Long? = null,
): DiscourseUploadedAttachment =
    DiscourseUploadedAttachment(
        uploadId = 90L,
        markdownReference = "upload://safe-fixture",
        originalFilename = originalFilename,
        width = width,
        height = height,
        fileSizeBytes = fileSizeBytes,
        extension = extension,
    )

private fun successfulUpload(request: DiscourseUploadRequest): DiscourseUploadResponse =
    DiscourseUploadResponse(
        id = 90L,
        shortUrl = "upload://self-authored",
        originalFilename = request.fileName,
        filesize = request.bytes.size.toLong(),
    )
