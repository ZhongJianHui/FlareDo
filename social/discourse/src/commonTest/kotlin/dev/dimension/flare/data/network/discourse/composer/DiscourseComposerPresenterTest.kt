package dev.dimension.flare.data.network.discourse.composer

import dev.dimension.flare.data.network.discourse.DiscourseUploadRequest
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFailureKind
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumTopic
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.ui.model.UiArticle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class DiscourseComposerPresenterTest {
    @Test
    fun submitConsumesTheLatestConflatedSnapshotAndPublishedStateCannotSubmitTwice() =
        runTest {
            val sessionManager = authenticatedComposerSession()
            val draftStore = MemoryDiscourseDraftStore()
            val repository = RecordingComposerRepository(draftStore)
            val postActionRepository = NoOpPostActionRepository()
            val presenter =
                composerPresenter(
                    repository = repository,
                    draftStore = draftStore,
                    postActionRepository = postActionRepository,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    autosaveDelayMillis = 60_000L,
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertTrue(presenter.openNewTopic(categoryId = 7L))
                advanceUntilIdle()
                assertTrue(models.value.canEdit)

                repeat(100) { index ->
                    assertTrue(
                        presenter.updateDraft(
                            title = "Title $index",
                            raw = "Body $index",
                            tags = listOf("tag-$index"),
                        ),
                    )
                }
                assertTrue(presenter.submit())
                advanceUntilIdle()

                val submitted = repository.submittedDrafts.single()
                assertEquals("Title 99", submitted.title)
                assertEquals("Body 99", submitted.raw)
                assertEquals(listOf("tag-99"), submitted.tags)
                assertEquals(DiscourseComposerSubmitStatus.Published, models.value.submitStatus)
                assertFalse(models.value.canEdit)
                assertFalse(models.value.canSubmit)

                // Public commands are accepted into the bounded actor, but terminal state rejects
                // the duplicate before either persistence or transport is touched.
                assertTrue(presenter.submit())
                assertTrue(presenter.updateDraft("Duplicate", "Duplicate", emptyList()))
                advanceUntilIdle()
                assertEquals(1, repository.submitCalls)
                assertEquals("Body 99", models.value.raw)
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun manyCallbacksFromOneRenderedBaseKeepTheLatestWholeEditorSnapshot() =
        runTest {
            val sessionManager = authenticatedComposerSession()
            val draftStore = MemoryDiscourseDraftStore()
            val repository = RecordingComposerRepository(draftStore)
            val presenter =
                composerPresenter(
                    repository = repository,
                    draftStore = draftStore,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    autosaveDelayMillis = 60_000L,
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertTrue(presenter.openReply(topicId = 42L))
                advanceUntilIdle()
                val renderedBase = models.value

                // A busy main thread can invoke many callbacks before it observes the next StateFlow
                // frame. Every value is a newer replacement derived from the same rendered owner.
                repeat(256) { index ->
                    assertTrue(
                        presenter.updateDraft(
                            title = null,
                            raw = "Body $index",
                            tags = emptyList(),
                            expectedContentVersion = renderedBase.contentVersion,
                            expectedSessionGeneration = renderedBase.sessionGeneration,
                            expectedAccountId = renderedBase.accountId,
                            expectedTarget = renderedBase.target,
                        ),
                    )
                    runCurrent()
                }

                assertEquals("Body 255", models.value.raw)
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun reopeningTheSameTargetWaitsForTheAcceptedCloseFlush() =
        runTest {
            val sessionManager = authenticatedComposerSession()
            val draftStore = BlockingComposerDraftStore()
            val repository = RecordingComposerRepository(draftStore)
            val presenter =
                composerPresenter(
                    repository = repository,
                    draftStore = draftStore,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    autosaveDelayMillis = 60_000L,
                )
            val models = presenter.models

            try {
                runCurrent()
                assertTrue(presenter.openReply(topicId = 42L))
                advanceUntilIdle()
                assertEquals(1, draftStore.loadCalls)

                assertTrue(presenter.updateDraft(title = null, raw = "Retain this reply"))
                runCurrent()
                assertEquals(DiscourseComposerDraftStatus.Dirty, models.value.draftStatus)

                draftStore.blockNextSave = true
                assertTrue(presenter.closeComposer())
                runCurrent()
                assertTrue(draftStore.saveStarted.isCompleted)
                assertEquals(DiscourseComposerMode.Closed, models.value.mode)

                assertTrue(presenter.openReply(topicId = 42L))
                runCurrent()
                assertTrue(models.value.isInitializing)
                assertEquals(
                    1,
                    draftStore.loadCalls,
                    "Initialization must not race the close snapshot still being saved",
                )

                draftStore.allowSave.complete(Unit)
                advanceUntilIdle()

                assertEquals(2, draftStore.loadCalls)
                assertEquals("Retain this reply", models.value.raw)
                assertEquals(DiscourseComposerDraftStatus.Saved, models.value.draftStatus)
            } finally {
                draftStore.allowSave.complete(Unit)
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun sessionTransitionFlushesTheLatestConflatedInputEvenBeforeTheActorSelectsIt() =
        runTest {
            val sessionManager = authenticatedComposerSession()
            val draftStore = MemoryDiscourseDraftStore()
            val repository = RecordingComposerRepository(draftStore)
            val target = DiscourseComposerTarget.Reply(topicId = 42L)
            val presenter =
                composerPresenter(
                    repository = repository,
                    draftStore = draftStore,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    autosaveDelayMillis = 60_000L,
                )
            val models = presenter.models

            try {
                runCurrent()
                assertTrue(presenter.openReply(topicId = 42L))
                advanceUntilIdle()

                assertTrue(presenter.updateDraft(title = null, raw = "Last conflated keystroke"))
                sessionManager.logout()
                advanceUntilIdle()

                assertEquals(null, models.value.accountId)
                assertEquals(
                    "Last conflated keystroke",
                    assertNotNull(draftStore.load("42", target)).raw,
                )
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun sessionGenerationReplacementRejectsAnInitializationThatIgnoresCancellation() =
        runTest {
            val sessionManager = authenticatedComposerSession()
            val draftStore = MemoryDiscourseDraftStore()
            val repository = RecordingComposerRepository(draftStore)
            val postActionRepository = NoOpPostActionRepository()
            val oldRequestStarted = CompletableDeferred<Unit>()
            val oldSource = CompletableDeferred<DiscourseEditablePostSource>()
            repository.editableSource = { target ->
                oldRequestStarted.complete(Unit)
                withContext(NonCancellable) {
                    oldSource.await()
                }.also {
                    assertEquals(target.postId, it.postId)
                }
            }
            val presenter =
                composerPresenter(
                    repository = repository,
                    draftStore = draftStore,
                    postActionRepository = postActionRepository,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                runCurrent()
                assertTrue(presenter.openEdit(topicId = 42L, postId = 501L, postNumber = 3))
                runCurrent()
                assertTrue(oldRequestStarted.isCompleted)

                val clearsBeforeReplacement = postActionRepository.clearCalls
                sessionManager.logout()
                sessionManager.startAuthenticatedSession(accountId = "84", username = "replacement")
                // Deliberately queue the command before the conflated session forwarder runs. The
                // actor must perform generation cleanup and then preserve this replacement command.
                assertTrue(presenter.openReply(topicId = 84L))
                advanceUntilIdle()

                assertEquals(3L, models.value.sessionGeneration)
                assertEquals("84", models.value.accountId)
                assertEquals(clearsBeforeReplacement + 1, postActionRepository.clearCalls)
                assertEquals(DiscourseComposerTarget.Reply(84L), models.value.target)
                assertEquals("", models.value.raw)

                oldSource.complete(
                    DiscourseEditablePostSource(
                        postId = 501L,
                        topicId = 42L,
                        postNumber = 3,
                        raw = "stale private Markdown",
                    ),
                )
                advanceUntilIdle()

                assertEquals(DiscourseComposerTarget.Reply(84L), models.value.target)
                assertEquals("", models.value.raw)
                assertFalse(models.value.raw.contains("stale"))
            } finally {
                oldSource.complete(
                    DiscourseEditablePostSource(501L, 42L, 3, "cleanup"),
                )
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun replacementUploadEpochAndSessionChangeIgnoreLateTaskState() =
        runTest {
            val sessionManager = authenticatedComposerSession()
            val draftStore = MemoryDiscourseDraftStore()
            val repository = RecordingComposerRepository(draftStore)
            val firstTask = ControllableUploadTask(finishImmediatelyAsFailure = true)
            val secondTask = ControllableUploadTask(finishImmediatelyAsFailure = false)
            repository.uploadTasks += firstTask
            repository.uploadTasks += secondTask
            val presenter =
                composerPresenter(
                    repository = repository,
                    draftStore = draftStore,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertTrue(presenter.openReply(topicId = 42L))
                advanceUntilIdle()

                assertTrue(presenter.startUpload(uploadRequest("first.png")))
                advanceUntilIdle()
                assertEquals(DiscourseComposerUploadStatus.Failed, models.value.upload.status)
                assertEquals(1L, models.value.upload.taskEpoch)
                assertFalse(models.value.upload.isComposerInsertionPending)
                assertTrue(models.value.canSubmit, "A network failure does not require the attachment")

                assertTrue(presenter.startUpload(uploadRequest("second.png")))
                runCurrent()
                assertTrue(secondTask.started.isCompleted)
                assertEquals(DiscourseComposerUploadStatus.Uploading, models.value.upload.status)
                assertEquals(2L, models.value.upload.taskEpoch)

                firstTask.publish(
                    DiscourseUploadTaskState.Succeeded(
                        attempt = 1L,
                        attachment = uploadedAttachment("late-first.png"),
                    ),
                )
                runCurrent()
                assertEquals(2L, models.value.upload.taskEpoch)
                assertEquals(DiscourseComposerUploadStatus.Uploading, models.value.upload.status)

                sessionManager.logout()
                advanceUntilIdle()
                assertEquals(1, secondTask.cancelCalls)
                assertEquals(DiscourseComposerUploadStatus.None, models.value.upload.status)
                assertEquals(null, models.value.accountId)

                secondTask.publish(
                    DiscourseUploadTaskState.Succeeded(
                        attempt = 1L,
                        attachment = uploadedAttachment("late-second.png"),
                    ),
                )
                runCurrent()
                assertEquals(DiscourseComposerUploadStatus.None, models.value.upload.status)
                assertEquals(null, models.value.upload.attachment)
            } finally {
                secondTask.release.complete(Unit)
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun uploadSelectionIsSingleFlightBeforeProgressAndFailedConstructionCannotRetryOldBytes() =
        runTest {
            val sessionManager = authenticatedComposerSession()
            val draftStore = MemoryDiscourseDraftStore()
            val repository = RecordingComposerRepository(draftStore)
            val firstTask = ControllableUploadTask(finishImmediatelyAsFailure = false)
            val secondTask = ControllableUploadTask(finishImmediatelyAsFailure = true)
            repository.uploadTasks += firstTask
            repository.uploadTasks += secondTask
            val presenter =
                composerPresenter(
                    repository = repository,
                    draftStore = draftStore,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertTrue(presenter.openReply(topicId = 42L))
                advanceUntilIdle()

                // Both commands reach the actor before the launched task can publish Uploading.
                // Ready is still non-terminal and must protect the first selected file.
                assertTrue(presenter.startUpload(uploadRequest("first.png")))
                assertTrue(presenter.startUpload(uploadRequest("second.png")))
                runCurrent()

                assertEquals(1, repository.createUploadTaskCalls)
                assertTrue(firstTask.started.isCompleted)
                assertFalse(secondTask.started.isCompleted)
                assertEquals(DiscourseComposerUploadStatus.Uploading, models.value.upload.status)

                assertTrue(presenter.cancelUpload())
                advanceUntilIdle()
                assertEquals(DiscourseComposerUploadStatus.Cancelled, models.value.upload.status)

                // The remaining queued task is consumed as a valid replacement and fails. A third
                // selection then fails during construction; Retry must not target that old task.
                assertTrue(presenter.startUpload(uploadRequest("second.png")))
                advanceUntilIdle()
                assertEquals(DiscourseComposerUploadStatus.Failed, models.value.upload.status)

                assertTrue(presenter.startUpload(uploadRequest("construction-fails.png")))
                advanceUntilIdle()
                assertEquals(3, repository.createUploadTaskCalls)
                assertEquals(DiscourseComposerUploadStatus.Failed, models.value.upload.status)
                assertEquals(DiscourseForumFailureKind.InvalidResponse, models.value.upload.failure)

                assertTrue(presenter.retryUpload())
                advanceUntilIdle()
                assertEquals(0, secondTask.retryCalls)
            } finally {
                firstTask.release.complete(Unit)
                secondTask.release.complete(Unit)
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun retryQueuedImmediatelyAfterCancelWaitsForTheCancelledAttemptCleanup() =
        runTest {
            val sessionManager = authenticatedComposerSession()
            val draftStore = MemoryDiscourseDraftStore()
            val repository = RecordingComposerRepository(draftStore)
            val task = CancellationRetryUploadTask()
            repository.uploadTasks += task
            val presenter =
                composerPresenter(
                    repository = repository,
                    draftStore = draftStore,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertTrue(presenter.openReply(topicId = 42L))
                advanceUntilIdle()
                assertTrue(presenter.startUpload(uploadRequest("retry.png")))
                runCurrent()
                assertTrue(task.started.isCompleted)
                assertEquals(DiscourseComposerUploadStatus.Uploading, models.value.upload.status)

                // These commands are queued back-to-back. The first retry waits for cancellation
                // cleanup; the user must not need to press Retry a second time.
                assertTrue(presenter.cancelUpload())
                assertTrue(presenter.retryUpload())
                advanceUntilIdle()

                assertEquals(1, task.cancelCalls)
                assertEquals(1, task.retryCalls)
                assertTrue(task.cancelledAttemptCleanup.isCompleted)
                assertTrue(task.retryObservedCleanup)
                assertEquals(DiscourseComposerUploadStatus.Succeeded, models.value.upload.status)
                assertEquals(2L, models.value.upload.attempt)
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun uploadSuccessAtomicallyInsertsComposerMarkdownBeforeSubmitAndIgnoresTheTerminalDuplicate() =
        runTest {
            val sessionManager = authenticatedComposerSession()
            val draftStore = MemoryDiscourseDraftStore()
            val repository = RecordingComposerRepository(draftStore)
            val attachment = uploadedAttachment("atomic.png")
            val task = SuspendedSuccessUploadTask(attachment)
            repository.uploadTasks += task
            val presenter =
                composerPresenter(
                    repository = repository,
                    draftStore = draftStore,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    autosaveDelayMillis = 60_000L,
                )
            val models = presenter.models
            val expectedRaw = "Body\n\n${attachment.composerMarkdown}"

            try {
                advanceUntilIdle()
                assertTrue(presenter.openReply(topicId = 42L))
                advanceUntilIdle()
                assertTrue(presenter.updateDraft(title = null, raw = "Body"))
                runCurrent()

                assertTrue(presenter.startUpload(uploadRequest("atomic.png")))
                runCurrent()

                assertEquals(DiscourseComposerUploadStatus.Succeeded, models.value.upload.status)
                assertEquals(expectedRaw, models.value.raw)
                assertTrue(models.value.canSubmit)

                // The transport execute call is still suspended. Submission therefore proves the
                // safe Markdown and terminal status were published by one presenter event, not a
                // later platform UI effect.
                assertTrue(presenter.submit())
                advanceUntilIdle()
                assertEquals(expectedRaw, repository.submittedDrafts.single().raw)
                assertEquals(DiscourseComposerSubmitStatus.Published, models.value.submitStatus)

                task.releaseTerminal.complete(Unit)
                advanceUntilIdle()
                assertEquals(expectedRaw, models.value.raw, "The execute return event is a duplicate")
            } finally {
                task.releaseTerminal.complete(Unit)
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun successfulUploadThatExceedsTheDraftBoundBlocksSubmitAndRetriesOnlyLocalInsertion() =
        runTest {
            val sessionManager = authenticatedComposerSession()
            val draftStore = MemoryDiscourseDraftStore()
            val repository = RecordingComposerRepository(draftStore)
            val attachment = uploadedAttachment("boundary.pdf")
            val task = SuspendedSuccessUploadTask(attachment)
            repository.uploadTasks += task
            val presenter =
                composerPresenter(
                    repository = repository,
                    draftStore = draftStore,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    autosaveDelayMillis = 60_000L,
                )
            val models = presenter.models
            val tooLargeAfterInsertion =
                "x".repeat(MAX_COMPOSER_RAW_CHARS - attachment.composerMarkdown.length - 1)

            try {
                advanceUntilIdle()
                assertTrue(presenter.openReply(topicId = 42L))
                advanceUntilIdle()
                assertTrue(presenter.updateDraft(title = null, raw = tooLargeAfterInsertion))
                runCurrent()
                assertTrue(presenter.startUpload(uploadRequest("boundary.pdf")))
                runCurrent()

                assertEquals(DiscourseComposerUploadStatus.Failed, models.value.upload.status)
                assertEquals(DiscourseForumFailureKind.InvalidResponse, models.value.upload.failure)
                assertEquals(attachment, models.value.upload.attachment)
                assertTrue(models.value.upload.isComposerInsertionPending)
                assertFalse(models.value.canSubmit)
                assertEquals(tooLargeAfterInsertion, models.value.raw)

                assertTrue(presenter.submit())
                runCurrent()
                assertEquals(0, repository.submitCalls, "Pending insertion must fail closed")

                assertTrue(presenter.updateDraft(title = null, raw = "Shortened"))
                runCurrent()
                assertFalse(models.value.canSubmit)
                assertTrue(presenter.retryUpload())
                runCurrent()

                assertEquals(0, task.retryCalls, "A successful remote upload is not sent twice")
                assertEquals(DiscourseComposerUploadStatus.Succeeded, models.value.upload.status)
                assertEquals("Shortened\n\n${attachment.composerMarkdown}", models.value.raw)
                assertTrue(models.value.canSubmit)
            } finally {
                task.releaseTerminal.complete(Unit)
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun completeMarkdownDeduplicationDoesNotConfuseUploadReferencePrefixes() =
        runTest {
            val sessionManager = authenticatedComposerSession()
            val draftStore = MemoryDiscourseDraftStore()
            val repository = RecordingComposerRepository(draftStore)
            val attachment =
                DiscourseUploadedAttachment(
                    uploadId = 2L,
                    markdownReference = "upload://abc",
                    originalFilename = "prefix.pdf",
                )
            val task = SuspendedSuccessUploadTask(attachment)
            repository.uploadTasks += task
            val presenter =
                composerPresenter(
                    repository = repository,
                    draftStore = draftStore,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertTrue(presenter.openReply(topicId = 42L))
                advanceUntilIdle()
                assertTrue(
                    presenter.updateDraft(
                        title = null,
                        raw = "Existing [other.pdf|attachment](upload://abcd)",
                    ),
                )
                runCurrent()
                assertTrue(presenter.startUpload(uploadRequest("prefix.pdf")))
                runCurrent()

                assertEquals(
                    "Existing [other.pdf|attachment](upload://abcd)\n\n${attachment.composerMarkdown}",
                    models.value.raw,
                )
            } finally {
                task.releaseTerminal.complete(Unit)
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun draftInputSelectedBeforeUploadSuccessKeepsBothTheLatestTextAndAttachment() =
        runTest {
            val sessionManager = authenticatedComposerSession()
            val draftStore = MemoryDiscourseDraftStore()
            val repository = RecordingComposerRepository(draftStore)
            val attachment = uploadedAttachment("input-first.png")
            val task = ControllableUploadTask(finishImmediatelyAsFailure = false)
            repository.uploadTasks += task
            val presenter =
                composerPresenter(
                    repository = repository,
                    draftStore = draftStore,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    autosaveDelayMillis = 60_000L,
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertTrue(presenter.openReply(topicId = 42L))
                advanceUntilIdle()
                val base = models.value

                assertTrue(
                    presenter.startUpload(
                        request = uploadRequest("input-first.png"),
                        expectedSessionGeneration = base.sessionGeneration,
                        expectedAccountId = base.accountId,
                        expectedTarget = base.target,
                        expectedContentVersion = base.contentVersion,
                    ),
                )
                runCurrent()
                assertTrue(
                    presenter.updateDraft(
                        title = null,
                        raw = "Latest text",
                        tags = emptyList(),
                        expectedContentVersion = base.contentVersion,
                        expectedSessionGeneration = base.sessionGeneration,
                        expectedAccountId = base.accountId,
                        expectedTarget = base.target,
                    ),
                )
                runCurrent()

                task.publish(DiscourseUploadTaskState.Succeeded(attempt = 1L, attachment = attachment))
                runCurrent()

                assertEquals("Latest text\n\n${attachment.composerMarkdown}", models.value.raw)
            } finally {
                task.release.complete(Unit)
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun cancelledUploadAttemptIsATerminalTombstoneForLateTaskStates() {
        val attachment = uploadedAttachment("cancelled.png")
        val cancelled =
            DiscourseComposerUploadState(
                status = DiscourseComposerUploadStatus.Cancelled,
                taskEpoch = 7L,
                attempt = 1L,
            )

        assertEquals(
            null,
            cancelled.merge(
                DiscourseUploadTaskState.Succeeded(
                    attempt = 1L,
                    attachment = attachment,
                ),
            ),
        )

        val retryReady = cancelled.copy(status = DiscourseComposerUploadStatus.Ready)
        assertEquals(
            null,
            retryReady.merge(
                DiscourseUploadTaskState.Succeeded(
                    attempt = 1L,
                    attachment = attachment,
                ),
            ),
        )
        val nextAttempt =
            assertNotNull(
                retryReady.merge(
                    // StateFlow is allowed to conflate the intermediate Uploading value. A newer
                    // retry attempt remains valid, while the retained attempt above is rejected.
                    DiscourseUploadTaskState.Succeeded(
                        attempt = 2L,
                        attachment = attachment,
                    ),
                ),
            )
        assertEquals(DiscourseComposerUploadStatus.Succeeded, nextAttempt.status)
        assertEquals(2L, nextAttempt.attempt)
        assertEquals(
            null,
            nextAttempt.merge(DiscourseUploadTaskState.Cancelled(attempt = 2L)),
        )
    }

    @Test
    fun uploadSuccessSelectedBeforeItsBaseInputRebasesTheLatestTextAroundTheAttachment() =
        runTest {
            val sessionManager = authenticatedComposerSession()
            val draftStore = MemoryDiscourseDraftStore()
            val repository = RecordingComposerRepository(draftStore)
            val attachment = uploadedAttachment("upload-first.png")
            val task = SuspendedSuccessUploadTask(attachment)
            repository.uploadTasks += task
            val presenter =
                composerPresenter(
                    repository = repository,
                    draftStore = draftStore,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    autosaveDelayMillis = 60_000L,
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertTrue(presenter.openReply(topicId = 42L))
                advanceUntilIdle()
                val base = models.value
                assertTrue(
                    presenter.startUpload(
                        request = uploadRequest("upload-first.png"),
                        expectedSessionGeneration = base.sessionGeneration,
                        expectedAccountId = base.accountId,
                        expectedTarget = base.target,
                        expectedContentVersion = base.contentVersion,
                    ),
                )
                runCurrent()
                assertEquals(DiscourseComposerUploadStatus.Succeeded, models.value.upload.status)

                assertTrue(
                    presenter.updateDraft(
                        title = null,
                        raw = "Newest callback",
                        tags = emptyList(),
                        expectedContentVersion = base.contentVersion,
                        expectedSessionGeneration = base.sessionGeneration,
                        expectedAccountId = base.accountId,
                        expectedTarget = base.target,
                    ),
                )
                runCurrent()

                assertEquals("Newest callback\n\n${attachment.composerMarkdown}", models.value.raw)
            } finally {
                task.releaseTerminal.complete(Unit)
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun separateUploadTasksMayInsertTheSameServerReferenceTwice() =
        runTest {
            val sessionManager = authenticatedComposerSession()
            val draftStore = MemoryDiscourseDraftStore()
            val repository = RecordingComposerRepository(draftStore)
            val attachment = uploadedAttachment("same.png")
            val first = SuspendedSuccessUploadTask(attachment)
            val second = SuspendedSuccessUploadTask(attachment)
            repository.uploadTasks += first
            repository.uploadTasks += second
            val presenter =
                composerPresenter(
                    repository = repository,
                    draftStore = draftStore,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertTrue(presenter.openReply(topicId = 42L))
                advanceUntilIdle()

                assertTrue(presenter.startUpload(uploadRequest("same.png")))
                runCurrent()
                assertTrue(presenter.startUpload(uploadRequest("same-again.png")))
                runCurrent()

                assertEquals(
                    "${attachment.composerMarkdown}\n\n${attachment.composerMarkdown}",
                    models.value.raw,
                )
                assertEquals(2, repository.createUploadTaskCalls)
                assertTrue(presenter.retryUpload())
                runCurrent()
                assertEquals(0, first.retryCalls + second.retryCalls)
            } finally {
                first.releaseTerminal.complete(Unit)
                second.releaseTerminal.complete(Unit)
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun oldOwnerCallbacksCannotMutateAReopenedEditorOrCreateAnUploadTask() =
        runTest {
            val sessionManager = authenticatedComposerSession()
            val draftStore = MemoryDiscourseDraftStore()
            val repository = RecordingComposerRepository(draftStore)
            val presenter =
                composerPresenter(
                    repository = repository,
                    draftStore = draftStore,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertTrue(presenter.openReply(topicId = 42L))
                advanceUntilIdle()
                val stale = models.value

                assertTrue(presenter.closeComposer())
                assertTrue(presenter.openReply(topicId = 42L))
                advanceUntilIdle()
                assertTrue(models.value.contentVersion > stale.contentVersion)

                assertTrue(
                    presenter.updateDraft(
                        title = null,
                        raw = "stale picker-era text",
                        tags = emptyList(),
                        expectedContentVersion = stale.contentVersion,
                        expectedSessionGeneration = stale.sessionGeneration,
                        expectedAccountId = stale.accountId,
                        expectedTarget = stale.target,
                    ),
                )
                assertTrue(
                    presenter.startUpload(
                        request = uploadRequest("stale.png"),
                        expectedSessionGeneration = stale.sessionGeneration,
                        expectedAccountId = stale.accountId,
                        expectedTarget = stale.target,
                        expectedContentVersion = stale.contentVersion,
                    ),
                )
                runCurrent()

                assertEquals("", models.value.raw)
                assertEquals(0, repository.createUploadTaskCalls)

                sessionManager.logout()
                sessionManager.startAuthenticatedSession(accountId = "42", username = "member-again")
                assertTrue(presenter.openReply(topicId = 42L))
                advanceUntilIdle()
                assertTrue(
                    presenter.startUpload(
                        request = uploadRequest("old-generation.png"),
                        expectedSessionGeneration = stale.sessionGeneration,
                        expectedAccountId = stale.accountId,
                        expectedTarget = stale.target,
                        expectedContentVersion = stale.contentVersion,
                    ),
                )
                runCurrent()
                assertEquals(0, repository.createUploadTaskCalls)
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun closeAndFlushDrainsTheLatestInputBeforeClosingThePresenterScope() =
        runTest {
            val sessionManager = authenticatedComposerSession()
            val draftStore = BlockingComposerDraftStore()
            val repository = RecordingComposerRepository(draftStore)
            val target = DiscourseComposerTarget.Reply(topicId = 42L)
            val presenter =
                composerPresenter(
                    repository = repository,
                    draftStore = draftStore,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    autosaveDelayMillis = 60_000L,
                )
            val models = presenter.models

            try {
                runCurrent()
                assertTrue(presenter.openReply(topicId = 42L))
                advanceUntilIdle()
                draftStore.blockNextSave = true
                assertTrue(presenter.updateDraft(title = null, raw = "final conflated input"))

                val shutdown = launch { presenter.closeAndFlush() }
                runCurrent()
                assertTrue(draftStore.saveStarted.isCompleted)
                assertFalse(shutdown.isCompleted)

                draftStore.allowSave.complete(Unit)
                advanceUntilIdle()

                assertTrue(shutdown.isCompleted)
                assertEquals(
                    "final conflated input",
                    assertNotNull(draftStore.load("42", target)).raw,
                )
            } finally {
                draftStore.allowSave.complete(Unit)
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun shutdownWaitsForSubmittingMutationAndRejectsEditorLifecycleCommands() =
        runTest {
            val sessionManager = authenticatedComposerSession()
            val draftStore = MemoryDiscourseDraftStore()
            val repository = RecordingComposerRepository(draftStore)
            val submitStarted = CompletableDeferred<Unit>()
            val allowSubmit = CompletableDeferred<Unit>()
            repository.beforeSubmit = {
                submitStarted.complete(Unit)
                allowSubmit.await()
            }
            val target = DiscourseComposerTarget.Reply(topicId = 42L)
            val presenter =
                composerPresenter(
                    repository = repository,
                    draftStore = draftStore,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    autosaveDelayMillis = 60_000L,
                )
            val models = presenter.models

            try {
                runCurrent()
                assertTrue(presenter.openReply(topicId = 42L))
                advanceUntilIdle()
                assertTrue(presenter.updateDraft(title = null, raw = "publish once"))
                runCurrent()
                assertTrue(presenter.submit())
                runCurrent()
                assertTrue(submitStarted.isCompleted)
                assertEquals(DiscourseComposerSubmitStatus.Submitting, models.value.submitStatus)

                assertTrue(presenter.closeComposer())
                assertTrue(presenter.discardDraft())
                assertTrue(presenter.openNewTopic())
                assertTrue(presenter.submit())
                runCurrent()
                assertEquals(target, models.value.target)
                assertEquals(1, repository.submitCalls)

                val shutdown = launch { presenter.closeAndFlush() }
                runCurrent()
                assertFalse(shutdown.isCompleted)

                allowSubmit.complete(Unit)
                advanceUntilIdle()

                assertTrue(shutdown.isCompleted)
                assertEquals(DiscourseComposerSubmitStatus.Published, models.value.submitStatus)
                assertEquals(1, repository.submitCalls)
                assertEquals(null, draftStore.load("42", target))
            } finally {
                allowSubmit.complete(Unit)
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun postActionIsSingleFlightAndLateOldGenerationResultStaysHidden() =
        runTest {
            val sessionManager = authenticatedComposerSession()
            val draftStore = MemoryDiscourseDraftStore()
            val repository = RecordingComposerRepository(draftStore)
            val actionRepository = ControllablePostActionRepository()
            actionRepository.seed(accountId = "42", generation = 1L)
            val presenter =
                composerPresenter(
                    repository = repository,
                    draftStore = draftStore,
                    postActionRepository = actionRepository,
                    sessionManager = sessionManager,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val models = presenter.models

            try {
                advanceUntilIdle()
                assertEquals(1, models.value.postActions.size)
                assertTrue(
                    models.value.postActions
                        .single()
                        .canLike,
                )

                assertTrue(presenter.toggleLike(postId = 501L))
                assertTrue(presenter.toggleLike(postId = 501L))
                runCurrent()

                assertEquals(1, actionRepository.toggleLikeCalls)
                assertTrue(actionRepository.started.isCompleted)
                assertTrue(
                    models.value.postActions
                        .single()
                        .liked,
                )
                assertTrue(
                    models.value.postActions
                        .single()
                        .isLikeInFlight,
                )

                sessionManager.logout()
                sessionManager.startAuthenticatedSession(accountId = "84", username = "replacement")
                advanceUntilIdle()
                assertEquals("84", models.value.accountId)
                assertTrue(models.value.postActions.isEmpty())

                actionRepository.finish.complete(Unit)
                advanceUntilIdle()
                assertTrue(models.value.postActions.isEmpty())
            } finally {
                actionRepository.finish.complete(Unit)
                presenter.close()
                runCurrent()
            }
        }
}

private fun composerPresenter(
    repository: DiscourseComposerRepository,
    draftStore: DiscourseDraftStore,
    postActionRepository: DiscoursePostActionRepository = NoOpPostActionRepository(),
    sessionManager: DiscourseSessionManager,
    dispatcher: CoroutineDispatcher,
    autosaveDelayMillis: Long = DEFAULT_COMPOSER_AUTOSAVE_DELAY_MILLIS,
): DiscourseComposerPresenter =
    DiscourseComposerPresenter(
        repository = repository,
        draftStore = draftStore,
        postActionRepository = postActionRepository,
        sessionManager = sessionManager,
        dispatcher = dispatcher,
        nowEpochMillis = { 1_000L },
        autosaveDelayMillis = autosaveDelayMillis,
    )

private suspend fun authenticatedComposerSession(): DiscourseSessionManager =
    DiscourseSessionManager().also {
        it.startAuthenticatedSession(accountId = "42", username = "member")
    }

private class RecordingComposerRepository(
    private val draftStore: DiscourseDraftStore,
) : DiscourseComposerRepository {
    val submittedDrafts = mutableListOf<DiscourseComposerDraft>()
    val uploadTasks = mutableListOf<DiscourseUploadTask>()
    var submitCalls: Int = 0
    var createUploadTaskCalls: Int = 0
    var beforeSubmit: suspend () -> Unit = {}
    var editableSource: suspend (DiscourseComposerTarget.Edit) -> DiscourseEditablePostSource = { target ->
        DiscourseEditablePostSource(
            postId = target.postId,
            topicId = target.topicId,
            postNumber = target.postNumber,
            raw = "authoritative source",
        )
    }

    override suspend fun loadNewTopicConstraints(
        accountId: String,
        categoryId: Long?,
    ): DiscourseNewTopicConstraints =
        DiscourseNewTopicConstraints(
            categoryId = categoryId,
            minimumRequiredTags = 0,
            requiredTagGroups = emptyList(),
        )

    override suspend fun loadEditableSource(
        accountId: String,
        target: DiscourseComposerTarget.Edit,
    ): DiscourseEditablePostSource = editableSource(target)

    override suspend fun submit(
        accountId: String,
        target: DiscourseComposerTarget,
    ): DiscoursePostSubmissionOutcome {
        submitCalls += 1
        val draft = assertNotNull(draftStore.load(accountId, target))
        submittedDrafts += draft
        beforeSubmit()
        draftStore.deleteIfRevision(accountId, target, draft.revision)
        return DiscoursePostSubmissionOutcome.Published(
            DiscoursePublishedPostRef(
                postId = 900L,
                topicId = target.topicIdOrTestDefault(),
                postNumber = 1,
            ),
        )
    }

    override fun createUploadTask(
        accountId: String,
        request: DiscourseUploadRequest,
    ): DiscourseUploadTask {
        createUploadTaskCalls += 1
        return uploadTasks.removeAt(0)
    }
}

private class NoOpPostActionRepository : DiscoursePostActionRepository {
    private val mutableState = MutableStateFlow<DiscoursePostActionSnapshot?>(null)

    override val state: StateFlow<DiscoursePostActionSnapshot?> = mutableState
    var clearCalls: Int = 0

    override suspend fun synchronizeFromServer(
        accountId: String,
        article: UiArticle,
    ) = Unit

    override suspend fun synchronizeFromServer(
        accountId: String,
        topic: DiscourseForumTopic,
    ) = Unit

    override suspend fun clearForSessionChange() {
        clearCalls += 1
        mutableState.value = null
    }

    override suspend fun toggleLike(
        accountId: String,
        postId: Long,
    ): DiscourseOptimisticMutationResult =
        DiscourseOptimisticMutationResult.NotAllowed(
            state = null,
            reason = DiscourseActionNotAllowedReason.MissingServerState,
        )

    override suspend fun toggleBookmark(
        accountId: String,
        target: DiscourseActionTarget,
    ): DiscourseOptimisticMutationResult =
        DiscourseOptimisticMutationResult.NotAllowed(
            state = null,
            reason = DiscourseActionNotAllowedReason.MissingServerState,
        )
}

private class ControllablePostActionRepository : DiscoursePostActionRepository {
    private val target = DiscourseActionTarget.Post(postId = 501L)
    private val mutableState = MutableStateFlow<DiscoursePostActionSnapshot?>(null)

    override val state: StateFlow<DiscoursePostActionSnapshot?> = mutableState
    val started = CompletableDeferred<Unit>()
    val finish = CompletableDeferred<Unit>()
    var toggleLikeCalls: Int = 0

    fun seed(
        accountId: String,
        generation: Long,
    ) {
        val value =
            DiscourseOptimisticActionState(
                target = target,
                liked = false,
                likeCount = 4,
                canLike = true,
            )
        mutableState.value = DiscoursePostActionSnapshot(accountId, generation, mapOf(target to value))
    }

    override suspend fun synchronizeFromServer(
        accountId: String,
        article: UiArticle,
    ) = Unit

    override suspend fun synchronizeFromServer(
        accountId: String,
        topic: DiscourseForumTopic,
    ) = Unit

    override suspend fun clearForSessionChange() {
        mutableState.value = null
    }

    override suspend fun toggleLike(
        accountId: String,
        postId: Long,
    ): DiscourseOptimisticMutationResult {
        toggleLikeCalls += 1
        val snapshot = assertNotNull(mutableState.value)
        val current = snapshot.items.getValue(target)
        val optimistic =
            current.copy(
                liked = true,
                likeCount = current.likeCount + 1,
                likeEpoch = current.likeEpoch + 1L,
                isLikeInFlight = true,
            )
        mutableState.value = snapshot.copy(items = mapOf(target to optimistic))
        started.complete(Unit)
        withContext(NonCancellable) { finish.await() }
        return DiscourseOptimisticMutationResult.Confirmed(
            optimistic.copy(isLikeInFlight = false),
        )
    }

    override suspend fun toggleBookmark(
        accountId: String,
        target: DiscourseActionTarget,
    ): DiscourseOptimisticMutationResult =
        DiscourseOptimisticMutationResult.NotAllowed(
            state = null,
            reason = DiscourseActionNotAllowedReason.MissingServerState,
        )
}

private class BlockingComposerDraftStore : DiscourseDraftStore {
    private val delegate = MemoryDiscourseDraftStore()

    var blockNextSave: Boolean = false
    var loadCalls: Int = 0
    val saveStarted = CompletableDeferred<Unit>()
    val allowSave = CompletableDeferred<Unit>()

    override suspend fun load(
        accountId: String,
        target: DiscourseComposerTarget,
    ): DiscourseComposerDraft? {
        loadCalls += 1
        return delegate.load(accountId, target)
    }

    override suspend fun list(accountId: String): List<DiscourseComposerDraft> = delegate.list(accountId)

    override suspend fun save(
        accountId: String,
        target: DiscourseComposerTarget,
        title: String?,
        raw: String,
        tags: List<String>,
        updatedAtEpochMillis: Long,
    ): DiscourseComposerDraft {
        if (blockNextSave) {
            blockNextSave = false
            saveStarted.complete(Unit)
            allowSave.await()
        }
        return delegate.save(accountId, target, title, raw, tags, updatedAtEpochMillis)
    }

    override suspend fun delete(
        accountId: String,
        target: DiscourseComposerTarget,
    ) = delegate.delete(accountId, target)

    override suspend fun deleteIfRevision(
        accountId: String,
        target: DiscourseComposerTarget,
        expectedRevision: Long,
    ): Boolean = delegate.deleteIfRevision(accountId, target, expectedRevision)
}

private class ControllableUploadTask(
    private val finishImmediatelyAsFailure: Boolean,
) : DiscourseUploadTask {
    private val mutableState = MutableStateFlow<DiscourseUploadTaskState>(DiscourseUploadTaskState.Ready)

    override val state: StateFlow<DiscourseUploadTaskState> = mutableState
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var cancelCalls: Int = 0
    var retryCalls: Int = 0

    override suspend fun execute(): DiscourseUploadTaskState {
        publish(DiscourseUploadTaskState.Uploading(attempt = 1L, bytesSent = 0L, totalBytes = 10L))
        started.complete(Unit)
        if (finishImmediatelyAsFailure) {
            val failed =
                DiscourseUploadTaskState.Failed(
                    attempt = 1L,
                    failure = DiscourseForumFailureKind.Network,
                )
            publish(failed)
            return failed
        }
        release.await()
        return mutableState.value
    }

    override suspend fun retry(): DiscourseUploadTaskState {
        retryCalls += 1
        error("Retry is not expected")
    }

    override suspend fun cancel() {
        cancelCalls += 1
        publish(DiscourseUploadTaskState.Cancelled(attempt = 1L))
        release.complete(Unit)
    }

    fun publish(value: DiscourseUploadTaskState) {
        mutableState.value = value
    }
}

private class SuspendedSuccessUploadTask(
    private val attachment: DiscourseUploadedAttachment,
) : DiscourseUploadTask {
    private val mutableState = MutableStateFlow<DiscourseUploadTaskState>(DiscourseUploadTaskState.Ready)
    private val terminal = DiscourseUploadTaskState.Succeeded(attempt = 1L, attachment = attachment)

    override val state: StateFlow<DiscourseUploadTaskState> = mutableState
    val releaseTerminal = CompletableDeferred<Unit>()
    var retryCalls: Int = 0

    override suspend fun execute(): DiscourseUploadTaskState {
        mutableState.value =
            DiscourseUploadTaskState.Uploading(
                attempt = 1L,
                bytesSent = 3L,
                totalBytes = 3L,
            )
        mutableState.value = terminal
        releaseTerminal.await()
        return terminal
    }

    override suspend fun retry(): DiscourseUploadTaskState {
        retryCalls += 1
        error("A remotely successful upload must not be retried")
    }

    override suspend fun cancel() {
        releaseTerminal.complete(Unit)
    }
}

private class CancellationRetryUploadTask : DiscourseUploadTask {
    private val mutableState = MutableStateFlow<DiscourseUploadTaskState>(DiscourseUploadTaskState.Ready)

    override val state: StateFlow<DiscourseUploadTaskState> = mutableState
    val started = CompletableDeferred<Unit>()
    val cancelledAttemptCleanup = CompletableDeferred<Unit>()
    var cancelCalls: Int = 0
    var retryCalls: Int = 0
    var retryObservedCleanup: Boolean = false

    override suspend fun execute(): DiscourseUploadTaskState {
        mutableState.value =
            DiscourseUploadTaskState.Uploading(
                attempt = 1L,
                bytesSent = 0L,
                totalBytes = 10L,
            )
        started.complete(Unit)
        return try {
            awaitCancellation()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                mutableState.value = DiscourseUploadTaskState.Cancelled(attempt = 1L)
                cancelledAttemptCleanup.complete(Unit)
            }
            throw cancelled
        }
    }

    override suspend fun retry(): DiscourseUploadTaskState {
        retryCalls += 1
        retryObservedCleanup = cancelledAttemptCleanup.isCompleted
        check(retryObservedCleanup) { "Retry started before cancelled attempt cleanup" }
        mutableState.value =
            DiscourseUploadTaskState.Uploading(
                attempt = 2L,
                bytesSent = 0L,
                totalBytes = 10L,
            )
        return DiscourseUploadTaskState
            .Succeeded(
                attempt = 2L,
                attachment = uploadedAttachment("retry.png"),
            ).also {
                mutableState.value = it
            }
    }

    override suspend fun cancel() {
        cancelCalls += 1
        // Deliberately returns before execute() reaches its cancellation cleanup, matching the
        // production task contract that originally exposed this presenter race.
    }
}

private fun uploadRequest(fileName: String): DiscourseUploadRequest =
    DiscourseUploadRequest(
        bytes = byteArrayOf(1, 2, 3),
        fileName = fileName,
        contentType = "image/png",
    )

private fun uploadedAttachment(fileName: String): DiscourseUploadedAttachment =
    DiscourseUploadedAttachment(
        uploadId = 1L,
        markdownReference = "upload://$fileName",
        originalFilename = fileName,
    )

private fun DiscourseComposerTarget.topicIdOrTestDefault(): Long =
    when (this) {
        is DiscourseComposerTarget.NewTopic -> 70L
        is DiscourseComposerTarget.Reply -> topicId
        is DiscourseComposerTarget.Edit -> topicId
    }
