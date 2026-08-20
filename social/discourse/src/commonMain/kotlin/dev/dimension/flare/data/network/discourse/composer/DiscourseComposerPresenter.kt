package dev.dimension.flare.data.network.discourse.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.dimension.flare.data.network.discourse.DiscourseUploadRequest
import dev.dimension.flare.data.network.discourse.error.DiscourseException
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFailureKind
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumTopic
import dev.dimension.flare.data.network.discourse.forum.toForumFailureKind
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionState
import dev.dimension.flare.data.network.discourse.session.StaleDiscourseSessionException
import dev.dimension.flare.ui.model.UiArticle
import dev.dimension.flare.ui.presenter.PresenterBase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Shared Linux.do composer and optimistic post-action presenter.
 *
 * Commands, results, and session transitions are serialized by one actor. Body updates use their
 * own conflated channel, so typing cannot exhaust the bounded command queue. Network, upload, and
 * action jobs remain structured children of the Molecule lifecycle and are invalidated by both a
 * presenter close and a session-generation transition.
 */
public class DiscourseComposerPresenter(
    private val repository: DiscourseComposerRepository,
    private val draftStore: DiscourseDraftStore,
    private val postActionRepository: DiscoursePostActionRepository,
    private val sessionManager: DiscourseSessionManager,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowEpochMillis: () -> Long = {
        Clock.System
            .now()
            .toEpochMilliseconds()
    },
    private val autosaveDelayMillis: Long = DEFAULT_COMPOSER_AUTOSAVE_DELAY_MILLIS,
) : PresenterBase<DiscourseComposerState>(dispatcher) {
    private val commands = Channel<ComposerCommand>(capacity = COMMAND_CHANNEL_CAPACITY)
    private val draftUpdates = Channel<OwnedComposerDraftInput>(capacity = Channel.CONFLATED)
    private val actorCompleted = CompletableDeferred<Unit>()

    init {
        require(autosaveDelayMillis >= 0L) { "Composer autosave delay cannot be negative" }
    }

    /** Opens a new-topic editor after loading account-visible category and tag constraints. */
    public fun openNewTopic(categoryId: Long? = null): Boolean =
        dispatch(ComposerCommand.Open(DiscourseComposerTarget.NewTopic(categoryId)))

    /** Opens a reply editor, optionally linked to one visible post number. */
    public fun openReply(
        topicId: Long,
        replyToPostNumber: Int? = null,
    ): Boolean = dispatch(ComposerCommand.Open(DiscourseComposerTarget.Reply(topicId, replyToPostNumber)))

    /** Opens an edit editor whose Markdown is always fetched from the authoritative raw endpoint. */
    public fun openEdit(
        topicId: Long,
        postId: Long,
        postNumber: Int,
    ): Boolean = dispatch(ComposerCommand.Open(DiscourseComposerTarget.Edit(topicId, postId, postNumber)))

    /** Closes the editor after scheduling a cancellation-safe flush of unsaved local text. */
    public fun closeComposer(): Boolean = dispatch(ComposerCommand.Close)

    /** Explicitly removes the active local draft. Login/logout never dispatches this command. */
    public fun discardDraft(): Boolean = dispatch(ComposerCommand.Discard)

    /** Retries initialization without using sanitized cooked HTML as editable source. */
    public fun retryInitialization(): Boolean = dispatch(ComposerCommand.RetryInitialization)

    /**
     * Replaces the whole editable snapshot through a conflated path.
     *
     * This method intentionally bypasses [commands]. Even if the UI produces an update for every
     * keystroke, at most one unconsumed snapshot is retained.
     */
    public fun updateDraft(
        title: String?,
        raw: String,
        tags: List<String> = emptyList(),
    ): Boolean {
        val snapshot = models.value
        return updateDraft(
            title = title,
            raw = raw,
            tags = tags,
            expectedContentVersion = snapshot.contentVersion,
            expectedSessionGeneration = snapshot.sessionGeneration,
            expectedAccountId = snapshot.accountId,
            expectedTarget = snapshot.target,
        )
    }

    /** Strict owner-aware variant used by callbacks captured from an immutable host snapshot. */
    public fun updateDraft(
        title: String?,
        raw: String,
        tags: List<String>,
        expectedContentVersion: Long,
        expectedSessionGeneration: Long,
        expectedAccountId: String?,
        expectedTarget: DiscourseComposerTarget?,
    ): Boolean {
        require(expectedContentVersion >= 0L) { "Expected composer content version cannot be negative" }
        val owner =
            ComposerContentOwner.createOrNull(
                sessionGeneration = expectedSessionGeneration,
                accountId = expectedAccountId,
                target = expectedTarget,
            ) ?: return false
        return draftUpdates
            .trySend(
                OwnedComposerDraftInput(
                    value = DiscourseComposerDraftInput(title, raw, tags.toList()),
                    baseContentVersion = expectedContentVersion,
                    owner = owner,
                ),
            ).isSuccess
    }

    /** Persists the latest conflated input and submits exactly that durable draft. */
    public fun submit(): Boolean = dispatch(ComposerCommand.Submit)

    /**
     * Starts one transport upload only when the delayed picker result still owns this exact editor.
     *
     * The caller freezes all expected values before launching the platform picker. [expectedContentVersion]
     * also distinguishes closing and reopening the same target within one authenticated generation.
     */
    public fun startUpload(request: DiscourseUploadRequest): Boolean {
        val snapshot = models.value
        return startUpload(
            request = request,
            expectedSessionGeneration = snapshot.sessionGeneration,
            expectedAccountId = snapshot.accountId,
            expectedTarget = snapshot.target,
            expectedContentVersion = snapshot.contentVersion,
        )
    }

    /** Strict owner-aware variant for asynchronous platform picker results. */
    public fun startUpload(
        request: DiscourseUploadRequest,
        expectedSessionGeneration: Long,
        expectedAccountId: String?,
        expectedTarget: DiscourseComposerTarget?,
        expectedContentVersion: Long,
    ): Boolean {
        require(expectedContentVersion >= 0L) { "Expected composer content version cannot be negative" }
        val expectedOwner =
            ComposerContentOwner.createOrNull(
                sessionGeneration = expectedSessionGeneration,
                accountId = expectedAccountId,
                target = expectedTarget,
            ) ?: return false
        return dispatch(
            ComposerCommand.StartUpload(
                request = request,
                expectedOwner = expectedOwner,
                expectedContentVersion = expectedContentVersion,
            ),
        )
    }

    /**
     * Gracefully drains the actor, flushes its latest editor snapshot, and only then closes its scope.
     *
     * Desktop calls this before closing Koin/Room. Android retains ordinary [close], whose actor
     * `finally` still performs the same non-cancellable flush without blocking `ViewModel.onCleared`.
     */
    public suspend fun closeAndFlush() {
        // Force lazy Molecule startup before enqueuing the terminal command.
        models.value
        withContext(NonCancellable) {
            if (!actorCompleted.isCompleted) {
                val immediate = commands.trySend(ComposerCommand.Shutdown)
                if (immediate.isFailure && !immediate.isClosed) {
                    try {
                        commands.send(ComposerCommand.Shutdown)
                    } catch (_: ClosedSendChannelException) {
                        // A concurrently closed actor completes actorCompleted from runActor's finally.
                    }
                }
            }
            actorCompleted.await()
            close()
        }
    }

    /** Cancels the currently executing upload. */
    public fun cancelUpload(): Boolean = dispatch(ComposerCommand.CancelUpload)

    /** Explicitly retries the same failed or cancelled upload task. */
    public fun retryUpload(): Boolean = dispatch(ComposerCommand.RetryUpload)

    /** Seeds post permissions and server state before exposing like/bookmark controls. */
    public fun synchronizePostActions(article: UiArticle): Boolean = dispatch(ComposerCommand.SynchronizeArticle(article))

    /** Seeds topic bookmark permissions and server state. */
    public fun synchronizeTopicActions(topic: DiscourseForumTopic): Boolean = dispatch(ComposerCommand.SynchronizeTopic(topic))

    /** Starts a single-flight optimistic like toggle for one post. */
    public fun toggleLike(postId: Long): Boolean = dispatch(ComposerCommand.ToggleLike(DiscourseActionTarget.Post(postId)))

    /** Starts a single-flight optimistic bookmark toggle for one post. */
    public fun togglePostBookmark(postId: Long): Boolean = dispatch(ComposerCommand.ToggleBookmark(DiscourseActionTarget.Post(postId)))

    /** Starts a single-flight optimistic bookmark toggle for one topic. */
    public fun toggleTopicBookmark(topicId: Long): Boolean = dispatch(ComposerCommand.ToggleBookmark(DiscourseActionTarget.Topic(topicId)))

    @Composable
    override fun body(): DiscourseComposerState {
        var state by remember { mutableStateOf(DiscourseComposerState()) }
        LaunchedEffect(repository, draftStore, postActionRepository, sessionManager) {
            runActor(
                state = { state },
                setState = { state = it },
            )
        }
        return state
    }

    private fun dispatch(command: ComposerCommand): Boolean = commands.trySend(command).isSuccess

    private suspend fun runActor(
        state: () -> DiscourseComposerState,
        setState: (DiscourseComposerState) -> Unit,
    ) {
        try {
            runActorScope(state, setState)
        } finally {
            actorCompleted.complete(Unit)
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
    private suspend fun runActorScope(
        state: () -> DiscourseComposerState,
        setState: (DiscourseComposerState) -> Unit,
    ): Unit =
        coroutineScope {
            val events = Channel<ComposerEvent>(capacity = RESULT_CHANNEL_CAPACITY)
            val sessionChanges = Channel<DiscourseSessionState>(capacity = Channel.CONFLATED)
            val initialSession = sessionManager.state.value

            var observedSessionGeneration = initialSession.generation
            var composerEpoch = 0L
            var contentVersion = 0L
            var initializationJob: Job? = null
            var autosaveJob: Job? = null
            var submitJob: Job? = null
            var cleanupJob: Job? = null
            var uploadJob: Job? = null
            var uploadControlJob: Job? = null
            var uploadTask: DiscourseUploadTask? = null
            var uploadTaskEpoch = 0L
            var submitRequestId = 0L
            var actionRequestId = 0L
            var pendingTransitionDraftInput: OwnedComposerDraftInput? = null
            var shutdownRequested = false
            var latestActionSnapshot: DiscoursePostActionSnapshot? = null
            val contentMutations = mutableListOf<ComposerContentMutation>()
            val appliedUploadAttempts = mutableSetOf<UploadAttemptIdentity>()
            val actionFeedback = mutableMapOf<DiscourseActionTarget, ActionFeedback>()
            val actionJobs = mutableMapOf<ActionOperationKey, ActionOperation>()
            val actionSeedJobs = mutableMapOf<Long, Job>()

            fun update(transform: (DiscourseComposerState) -> DiscourseComposerState) {
                setState(transform(state()))
            }

            fun nextComposerEpoch(): Long {
                composerEpoch = composerEpoch.nextEpoch("Composer")
                return composerEpoch
            }

            fun nextSubmitRequestId(): Long {
                submitRequestId = submitRequestId.nextEpoch("Composer submit request")
                return submitRequestId
            }

            fun nextActionRequestId(): Long {
                actionRequestId = actionRequestId.nextEpoch("Post action request")
                return actionRequestId
            }

            fun resetContentTracking() {
                contentMutations.clear()
                appliedUploadAttempts.clear()
            }

            fun recordContentMutation(mutation: ComposerContentMutation) {
                check(mutation.toVersion == contentVersion) {
                    "Composer content mutation must end at the current version"
                }
                val previous = contentMutations.lastOrNull()
                if (
                    previous is ComposerContentMutation.DraftInputApplied &&
                    mutation is ComposerContentMutation.DraftInputApplied &&
                    previous.toVersion == mutation.fromVersion &&
                    previous.inputBaseVersion == mutation.inputBaseVersion
                ) {
                    // Compose can deliver many callbacks from one rendered snapshot before it
                    // observes the presenter's next StateFlow value. They are replacement snapshots,
                    // not independent ancestry. Collapse that run so the bounded journal never
                    // discards the rendered base that is still required to accept the latest input.
                    contentMutations[contentMutations.lastIndex] =
                        previous.copy(toVersion = mutation.toVersion)
                } else {
                    contentMutations += mutation
                }
                if (contentMutations.size > MAX_COMPOSER_CONTENT_MUTATIONS) {
                    contentMutations.removeAt(0)
                }
            }

            fun resolveDraftInput(
                snapshot: DiscourseComposerState,
                input: OwnedComposerDraftInput,
            ): DiscourseComposerDraftInput? {
                if (snapshot.contentOwnerOrNull() != input.owner || !snapshot.canEdit) return null
                if (input.baseContentVersion == contentVersion) return input.value
                if (input.baseContentVersion > contentVersion) return null

                var cursor = input.baseContentVersion
                val attachmentMarkdown = mutableListOf<String>()
                val collectedAttempts = mutableSetOf<UploadAttemptIdentity>()
                val firstMutation =
                    contentMutations.indexOfFirst { mutation ->
                        mutation.fromVersion == input.baseContentVersion
                    }
                if (firstMutation < 0) return null
                for (index in firstMutation until contentMutations.size) {
                    val mutation = contentMutations[index]
                    if (mutation.fromVersion != cursor) return null
                    when (mutation) {
                        is ComposerContentMutation.DraftInputApplied -> {
                            // A later callback from the same rendered base supersedes the earlier
                            // whole-editor snapshot. An input derived from any other base is stale.
                            if (mutation.inputBaseVersion != input.baseContentVersion) return null
                        }

                        is ComposerContentMutation.UploadApplied -> {
                            if (collectedAttempts.add(mutation.identity)) {
                                attachmentMarkdown += mutation.composerMarkdown
                            }
                        }
                    }
                    cursor = mutation.toVersion
                    if (cursor == contentVersion) break
                }
                if (cursor != contentVersion) return null

                return try {
                    input.value.copy(
                        raw =
                            attachmentMarkdown.fold(input.value.raw) { raw, markdown ->
                                raw.appendComposerMarkdown(markdown)
                            },
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The upload is already represented by the current valid state. A stale input
                    // that cannot be safely rebased must not overwrite it or remove its attachment.
                    null
                }
            }

            fun renderPostActions() {
                val snapshot = latestActionSnapshot
                val visible =
                    if (
                        snapshot != null &&
                        snapshot.accountId == state().accountId &&
                        snapshot.sessionGeneration == state().sessionGeneration
                    ) {
                        snapshot.items.values
                            .sortedWith(actionStateComparator)
                            .map { value -> value.toPresentation(actionFeedback[value.target]) }
                    } else {
                        emptyList()
                    }
                update { it.copy(postActions = visible) }
            }

            fun cancelComposerOperationJobs() {
                initializationJob?.cancel()
                autosaveJob?.cancel()
                submitJob?.cancel()
                uploadJob?.cancel()
                uploadControlJob?.cancel()
                initializationJob = null
                autosaveJob = null
                submitJob = null
                uploadJob = null
                uploadControlJob = null
                nextSubmitRequestId()
            }

            fun cancelActionJobs() {
                actionJobs.values.forEach { it.job.cancel() }
                actionSeedJobs.values.forEach(Job::cancel)
                actionJobs.clear()
                actionSeedJobs.clear()
                nextActionRequestId()
            }

            suspend fun cancelUploadTask() {
                val task = uploadTask ?: return
                try {
                    withContext(NonCancellable) { task.cancel() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Transport cancellation is best effort; cancelling the structured job above
                    // remains the authoritative lifecycle boundary.
                }
                uploadTask = null
            }

            suspend fun flushDraft(snapshot: DiscourseComposerState): DiscourseComposerDraft? {
                if (!snapshot.needsDurableFlush()) return null
                val accountId = snapshot.accountId ?: return null
                val target = snapshot.target ?: return null
                return draftStore.save(
                    accountId = accountId,
                    target = target,
                    title = snapshot.title,
                    raw = snapshot.raw,
                    tags = snapshot.tags,
                    updatedAtEpochMillis = checkedNowEpochMillis(),
                )
            }

            suspend fun flushDraftIgnoringFailure(snapshot: DiscourseComposerState) {
                try {
                    withContext(NonCancellable) { flushDraft(snapshot) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Closing and session replacement must complete even if local persistence is
                    // unavailable. While the editor is open, ordinary autosave exposes the error.
                }
            }

            fun startCleanup(block: suspend () -> Unit) {
                val preceding = cleanupJob
                cleanupJob =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        // Enter the non-cancellable region before returning to the actor. This
                        // ensures an immediately-following Presenter.close() cannot prevent an
                        // already accepted close/discard command from reaching durable storage.
                        withContext(NonCancellable) {
                            preceding?.join()
                            block()
                        }
                    }
            }

            fun startSilentFlush(snapshot: DiscourseComposerState) {
                if (!snapshot.needsDurableFlush()) return
                startCleanup { flushDraftIgnoringFailure(snapshot) }
            }

            fun startInitialization(target: DiscourseComposerTarget) {
                val session = sessionManager.state.value
                val authenticated = session as? DiscourseSessionState.Authenticated
                initializationJob?.cancel()
                val epoch = nextComposerEpoch()
                contentVersion = contentVersion.nextEpoch("Composer content")
                resetContentTracking()
                val retainedActions = state().postActions
                setState(
                    DiscourseComposerState(
                        mode = target.toPresentationMode(),
                        sessionGeneration = session.generation,
                        contentVersion = contentVersion,
                        accountId = authenticated?.accountId,
                        target = target,
                        isInitializing = authenticated != null,
                        initializationFailure =
                            if (authenticated == null) DiscourseForumFailureKind.Authentication else null,
                        draftStatus =
                            if (authenticated == null) {
                                DiscourseComposerDraftStatus.None
                            } else {
                                DiscourseComposerDraftStatus.Loading
                            },
                        postActions = retainedActions,
                    ),
                )
                if (authenticated == null) return
                val accountId = authenticated.accountId
                val generation = authenticated.generation
                val pendingCleanup = cleanupJob
                initializationJob =
                    launch {
                        // Closing and immediately reopening the same target must observe the
                        // accepted close snapshot, not race its asynchronous durable flush.
                        pendingCleanup?.join()
                        loadInitializationEvent(
                            epoch = epoch,
                            generation = generation,
                            accountId = accountId,
                            target = target,
                        )?.let { events.send(it) }
                    }
            }

            fun scheduleAutosave() {
                val snapshot = state()
                val accountId = snapshot.accountId ?: return
                val target = snapshot.target ?: return
                val epoch = composerEpoch
                val generation = snapshot.sessionGeneration
                val version = contentVersion
                autosaveJob?.cancel()
                autosaveJob =
                    launch {
                        if (autosaveDelayMillis > 0L) delay(autosaveDelayMillis)
                        saveDraftEvent(
                            epoch = epoch,
                            generation = generation,
                            version = version,
                            accountId = accountId,
                            target = target,
                            title = snapshot.title,
                            raw = snapshot.raw,
                            tags = snapshot.tags,
                        ).let { events.send(it) }
                    }
            }

            fun applyUploadedAttachment(
                attachment: DiscourseUploadedAttachment,
                succeededUpload: DiscourseComposerUploadState,
                identity: UploadAttemptIdentity,
            ) {
                val current = state()
                if (!current.canEdit) return
                if (identity in appliedUploadAttempts) return
                val composerMarkdown =
                    try {
                        attachment.composerMarkdown
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                val updatedRaw =
                    try {
                        composerMarkdown?.let(current.raw::appendComposerMarkdown)?.also { candidate ->
                            validateComposerDraftStorage(
                                title = current.title,
                                raw = candidate,
                                tags = current.tags,
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                if (updatedRaw == null) {
                    // The remote upload succeeded, so retain only its validated descriptor. Retry
                    // re-attempts this local insertion after the user shortens the body; it must not
                    // invoke the already terminal upload task or permit an attachment-less submit.
                    setState(
                        current.copy(
                            upload =
                                succeededUpload.copy(
                                    status = DiscourseComposerUploadStatus.Failed,
                                    attachment = attachment,
                                    failure = DiscourseForumFailureKind.InvalidResponse,
                                ),
                        ),
                    )
                    return
                }
                val previousVersion = contentVersion
                contentVersion = contentVersion.nextEpoch("Composer content")
                appliedUploadAttempts += identity
                setState(
                    current.copy(
                        contentVersion = contentVersion,
                        raw = updatedRaw,
                        draftStatus = DiscourseComposerDraftStatus.Dirty,
                        draftFailure = null,
                        submitStatus = DiscourseComposerSubmitStatus.Idle,
                        publishedPost = null,
                        pendingModeration = null,
                        submitFailure = null,
                        validationFailure = null,
                        upload = succeededUpload,
                    ),
                )
                recordContentMutation(
                    ComposerContentMutation.UploadApplied(
                        fromVersion = previousVersion,
                        toVersion = contentVersion,
                        identity = identity,
                        composerMarkdown = checkNotNull(composerMarkdown),
                    ),
                )
                scheduleAutosave()
            }

            fun handleDraftUpdate(input: OwnedComposerDraftInput) {
                // A session transition can win outside the actor immediately before its StateFlow
                // event is selected. Never apply another private snapshot under the old account;
                // the pending session event (or next command) performs the full durable cleanup.
                if (sessionManager.state.value.generation != observedSessionGeneration) {
                    pendingTransitionDraftInput = input
                    return
                }
                val current = state()
                val resolved = resolveDraftInput(current, input) ?: return
                val previousVersion = contentVersion
                if (input.baseContentVersion == previousVersion) {
                    // This callback was derived from the exact current state, so no earlier content
                    // ancestry is needed to interpret callbacks rendered after this point.
                    contentMutations.clear()
                }
                contentVersion = contentVersion.nextEpoch("Composer content")
                val updated = current.withDraftInput(resolved, contentVersion) ?: return
                setState(updated)
                recordContentMutation(
                    ComposerContentMutation.DraftInputApplied(
                        fromVersion = previousVersion,
                        toVersion = contentVersion,
                        inputBaseVersion = input.baseContentVersion,
                    ),
                )
                scheduleAutosave()
            }

            fun drainLatestDraftUpdate() {
                draftUpdates.tryReceive().getOrNull()?.let(::handleDraftUpdate)
            }

            fun consumeLatestDraftSnapshot(snapshot: DiscourseComposerState): DiscourseComposerState {
                val transitionInput = pendingTransitionDraftInput
                pendingTransitionDraftInput = null
                val withTransitionInput =
                    transitionInput
                        ?.let { input ->
                            resolveDraftInput(snapshot, input)?.let { resolved ->
                                snapshot.withDraftInput(resolved, snapshot.contentVersion)
                            }
                        } ?: snapshot
                val latestInput = draftUpdates.tryReceive().getOrNull() ?: return withTransitionInput
                val resolved = resolveDraftInput(withTransitionInput, latestInput) ?: return withTransitionInput
                return withTransitionInput.withDraftInput(resolved, withTransitionInput.contentVersion)
                    ?: withTransitionInput
            }

            fun startSubmit() {
                drainLatestDraftUpdate()
                val snapshot = state()
                val accountId = snapshot.accountId ?: return
                val target = snapshot.target ?: return
                if (!snapshot.canSubmit || submitJob?.isActive == true) return
                autosaveJob?.cancel()
                val epoch = composerEpoch
                val generation = snapshot.sessionGeneration
                val version = contentVersion
                val requestId = nextSubmitRequestId()
                update {
                    it.copy(
                        draftStatus = DiscourseComposerDraftStatus.Saving,
                        draftFailure = null,
                        submitStatus = DiscourseComposerSubmitStatus.Submitting,
                        publishedPost = null,
                        pendingModeration = null,
                        submitFailure = null,
                        validationFailure = null,
                    )
                }
                submitJob =
                    launch {
                        submitEvent(
                            requestId = requestId,
                            epoch = epoch,
                            generation = generation,
                            version = version,
                            accountId = accountId,
                            target = target,
                            title = snapshot.title,
                            raw = snapshot.raw,
                            tags = snapshot.tags,
                        ).forEach { events.send(it) }
                    }
            }

            fun startUpload(command: ComposerCommand.StartUpload) {
                val current = state()
                val accountId = current.accountId ?: return
                if (
                    current.contentOwnerOrNull() != command.expectedOwner ||
                    current.contentVersion != command.expectedContentVersion ||
                    !current.canEdit ||
                    current.upload.status in
                    setOf(
                        DiscourseComposerUploadStatus.Ready,
                        DiscourseComposerUploadStatus.Uploading,
                    ) ||
                    uploadControlJob?.isActive == true
                ) {
                    return
                }
                uploadJob?.cancel()
                uploadControlJob?.cancel()
                // Once a replacement selection is accepted, retry must never be able to reach the
                // previous file. In particular, task construction can fail before assigning the new
                // instance; clearing first makes that failure terminal instead of retaining old bytes.
                uploadTask = null
                uploadTaskEpoch = uploadTaskEpoch.nextEpoch("Upload task")
                val taskEpoch = uploadTaskEpoch
                val generation = current.sessionGeneration
                val task =
                    try {
                        repository.createUploadTask(accountId, command.request)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        update {
                            it.copy(
                                upload =
                                    DiscourseComposerUploadState(
                                        status = DiscourseComposerUploadStatus.Failed,
                                        taskEpoch = taskEpoch,
                                        failure = DiscourseForumFailureKind.InvalidResponse,
                                    ),
                            )
                        }
                        return
                    }
                uploadTask = task
                update {
                    it.copy(
                        upload =
                            DiscourseComposerUploadState(
                                status = DiscourseComposerUploadStatus.Ready,
                                taskEpoch = taskEpoch,
                            ),
                    )
                }
                uploadJob = launchUpload(task, taskEpoch, generation, retry = false, events = events)
            }

            fun retryUpload() {
                val current = state()
                val pendingAttachment = current.upload.attachment
                if (current.upload.isComposerInsertionPending && pendingAttachment != null) {
                    val attempt = current.upload.attempt ?: return
                    applyUploadedAttachment(
                        attachment = pendingAttachment,
                        succeededUpload =
                            current.upload.copy(
                                status = DiscourseComposerUploadStatus.Succeeded,
                                failure = null,
                            ),
                        identity = UploadAttemptIdentity(current.upload.taskEpoch, attempt),
                    )
                    return
                }
                val task = uploadTask ?: return
                if (
                    current.upload.taskEpoch != uploadTaskEpoch ||
                    current.upload.status !in
                    setOf(
                        DiscourseComposerUploadStatus.Failed,
                        DiscourseComposerUploadStatus.Cancelled,
                    )
                ) {
                    return
                }
                val precedingUpload = uploadJob
                val precedingControl = uploadControlJob
                val taskEpoch = uploadTaskEpoch
                val generation = current.sessionGeneration
                // Leave the retryable terminal state synchronously so another queued command cannot
                // start a duplicate attempt while cancellation is still finishing.
                update {
                    it.copy(
                        upload =
                            it.upload.copy(
                                status = DiscourseComposerUploadStatus.Ready,
                                attachment = null,
                                failure = null,
                            ),
                    )
                }
                uploadJob =
                    if (
                        precedingUpload?.isCompleted == false ||
                        precedingControl?.isCompleted == false
                    ) {
                        launch {
                            // DiscourseUploadTask.cancel() only requests Deferred cancellation. The
                            // task becomes retryable after its structured attempt reaches `finally`.
                            precedingControl?.join()
                            precedingUpload?.join()
                            launchUpload(
                                task = task,
                                taskEpoch = taskEpoch,
                                generation = generation,
                                retry = true,
                                events = events,
                            ).join()
                        }
                    } else {
                        launchUpload(
                            task = task,
                            taskEpoch = taskEpoch,
                            generation = generation,
                            retry = true,
                            events = events,
                        )
                    }
            }

            fun cancelUpload() {
                val task = uploadTask ?: return
                val current = state().upload
                if (current.status != DiscourseComposerUploadStatus.Uploading) return
                update {
                    it.copy(
                        upload =
                            current.copy(
                                status = DiscourseComposerUploadStatus.Cancelled,
                                attachment = null,
                                failure = null,
                            ),
                    )
                }
                // Cancelling the structured caller aborts Ktor promptly. task.cancel() below also
                // advances task-owned state, but deliberately does not wait for its Deferred cleanup.
                uploadJob?.cancel()
                uploadControlJob?.cancel()
                uploadControlJob =
                    launch {
                        try {
                            task.cancel()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // The structured upload job is still cancelled by presenter/session
                            // lifecycle; a control failure cannot fabricate transport progress.
                        }
                    }
            }

            fun startActionSeed(block: suspend (String) -> Unit) {
                val current = state()
                val accountId = current.accountId ?: return
                val generation = current.sessionGeneration
                val requestId = nextActionRequestId()
                actionSeedJobs.entries.removeAll { !it.value.isActive }
                actionSeedJobs[requestId] =
                    launch {
                        try {
                            block(accountId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: StaleDiscourseSessionException) {
                            // SessionChanged clears the visible action partition.
                        } catch (_: DiscourseException) {
                            // Seeding failures leave controls hidden instead of guessing permission.
                        } catch (_: Exception) {
                            // Malformed metadata also fails closed.
                        } finally {
                            events.trySend(ComposerEvent.ActionSeedFinished(requestId, generation))
                        }
                    }
            }

            fun startActionMutation(
                key: ActionOperationKey,
                block: suspend (String) -> DiscourseOptimisticMutationResult,
            ) {
                val current = state()
                val accountId = current.accountId ?: return
                val existing = actionJobs[key]
                if (existing?.job?.isActive == true) return
                val requestId = nextActionRequestId()
                val generation = current.sessionGeneration
                actionFeedback[key.target] = actionFeedback[key.target].orEmpty().clear(key.kind)
                renderPostActions()
                val job =
                    launch {
                        actionMutationEvent(
                            requestId = requestId,
                            generation = generation,
                            key = key,
                            block = { block(accountId) },
                        )?.let { events.send(it) }
                    }
                actionJobs[key] = ActionOperation(requestId, job)
            }

            suspend fun handleSessionChanged(session: DiscourseSessionState) {
                if (session.generation == observedSessionGeneration) return
                // The session manager changes before its StateFlow event reaches this actor. Drain
                // the latest conflated editor value into the old account's cleanup snapshot so a
                // final keystroke is not silently reassigned or dropped during logout.
                val previous = consumeLatestDraftSnapshot(state())
                cancelComposerOperationJobs()
                cancelActionJobs()
                withContext(NonCancellable) {
                    cancelUploadTask()
                    cleanupJob?.join()
                    flushDraftIgnoringFailure(previous)
                    try {
                        postActionRepository.clearForSessionChange()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The visible list is already cleared below; repository synchronization is
                        // retried when the replacement account seeds server state.
                    }
                }
                nextComposerEpoch()
                contentVersion = contentVersion.nextEpoch("Composer content")
                resetContentTracking()
                latestActionSnapshot = null
                actionFeedback.clear()
                observedSessionGeneration = session.generation
                setState(
                    DiscourseComposerState(
                        sessionGeneration = session.generation,
                        contentVersion = contentVersion,
                        accountId = (session as? DiscourseSessionState.Authenticated)?.accountId,
                    ),
                )
            }

            suspend fun synchronizeCurrentSession() {
                while (true) {
                    val current = sessionManager.state.value
                    if (current.generation == observedSessionGeneration) return
                    handleSessionChanged(current)
                }
            }

            fun handleActionSnapshot(snapshot: DiscoursePostActionSnapshot?) {
                val session = sessionManager.state.value
                val authenticated = session as? DiscourseSessionState.Authenticated
                if (snapshot == null) {
                    if (authenticated != null) return
                    latestActionSnapshot = null
                    actionFeedback.clear()
                    renderPostActions()
                    return
                }
                if (
                    authenticated == null ||
                    snapshot.accountId != authenticated.accountId ||
                    snapshot.sessionGeneration != authenticated.generation ||
                    snapshot.accountId != state().accountId ||
                    snapshot.sessionGeneration != state().sessionGeneration
                ) {
                    return
                }
                latestActionSnapshot = snapshot
                renderPostActions()
            }

            suspend fun handleCommand(command: ComposerCommand) {
                if (shutdownRequested) return
                // Commands are allowed to race the session StateFlow forwarder. Synchronizing here
                // preserves the user's command for the replacement account while still running all
                // generation cleanup exactly once.
                synchronizeCurrentSession()
                if (
                    state().submitStatus == DiscourseComposerSubmitStatus.Submitting &&
                    command.blocksWhileSubmitting
                ) {
                    return
                }
                when (command) {
                    is ComposerCommand.Open -> {
                        drainLatestDraftUpdate()
                        val previous = state()
                        cancelComposerOperationJobs()
                        cancelUploadTask()
                        startSilentFlush(previous)
                        startInitialization(command.target)
                    }

                    ComposerCommand.Close -> {
                        drainLatestDraftUpdate()
                        val previous = state()
                        cancelComposerOperationJobs()
                        cancelUploadTask()
                        startSilentFlush(previous)
                        nextComposerEpoch()
                        contentVersion = contentVersion.nextEpoch("Composer content")
                        resetContentTracking()
                        setState(
                            DiscourseComposerState(
                                sessionGeneration = previous.sessionGeneration,
                                contentVersion = contentVersion,
                                accountId = previous.accountId,
                                postActions = previous.postActions,
                            ),
                        )
                    }

                    ComposerCommand.Discard -> {
                        val previous = state()
                        val accountId = previous.accountId
                        val target = previous.target
                        cancelComposerOperationJobs()
                        cancelUploadTask()
                        nextComposerEpoch()
                        contentVersion = contentVersion.nextEpoch("Composer content")
                        resetContentTracking()
                        setState(
                            DiscourseComposerState(
                                sessionGeneration = previous.sessionGeneration,
                                contentVersion = contentVersion,
                                accountId = previous.accountId,
                                postActions = previous.postActions,
                            ),
                        )
                        if (accountId != null && target != null) {
                            startCleanup {
                                try {
                                    draftStore.delete(accountId, target)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    // The UI is already closed; reopening exposes whether the
                                    // durable deletion actually completed.
                                }
                            }
                        }
                    }

                    ComposerCommand.RetryInitialization -> {
                        state().target?.let(::startInitialization)
                    }

                    ComposerCommand.Submit -> {
                        startSubmit()
                    }

                    is ComposerCommand.StartUpload -> {
                        startUpload(command)
                    }

                    ComposerCommand.CancelUpload -> {
                        cancelUpload()
                    }

                    ComposerCommand.RetryUpload -> {
                        retryUpload()
                    }

                    is ComposerCommand.SynchronizeArticle -> {
                        startActionSeed { accountId ->
                            postActionRepository.synchronizeFromServer(accountId, command.article)
                        }
                    }

                    is ComposerCommand.SynchronizeTopic -> {
                        startActionSeed { accountId ->
                            postActionRepository.synchronizeFromServer(accountId, command.topic)
                        }
                    }

                    is ComposerCommand.ToggleLike -> {
                        startActionMutation(
                            ActionOperationKey(command.target, ActionKind.Like),
                        ) { accountId ->
                            postActionRepository.toggleLike(accountId, command.target.postId)
                        }
                    }

                    is ComposerCommand.ToggleBookmark -> {
                        startActionMutation(
                            ActionOperationKey(command.target, ActionKind.Bookmark),
                        ) { accountId ->
                            postActionRepository.toggleBookmark(accountId, command.target)
                        }
                    }

                    ComposerCommand.Shutdown -> {
                        // Keep the actor alive until the already accepted submit child has emitted
                        // and the actor has reduced every terminal event into presentation state.
                        shutdownRequested = true
                    }
                }
            }

            fun handleEvent(event: ComposerEvent) {
                // Do not render even one old-account frame after the session manager has advanced.
                // Request/epoch matching below remains the second line of defense after cleanup.
                if (sessionManager.state.value.generation != observedSessionGeneration) return
                when (event) {
                    is ComposerEvent.Initialized -> {
                        if (!event.matches(state(), composerEpoch)) return
                        val draft = event.draft
                        contentVersion = contentVersion.nextEpoch("Composer content")
                        resetContentTracking()
                        update {
                            it.copy(
                                contentVersion = contentVersion,
                                title = draft?.title,
                                raw = draft?.raw ?: event.authoritativeRaw.orEmpty(),
                                tags = draft?.tags.orEmpty(),
                                constraints = event.constraints,
                                isInitializing = false,
                                initializationFailure = null,
                                draftStatus =
                                    if (draft == null) {
                                        DiscourseComposerDraftStatus.Clean
                                    } else {
                                        DiscourseComposerDraftStatus.Saved
                                    },
                                draftRevision = draft?.revision,
                                draftUpdatedAtEpochMillis = draft?.updatedAtEpochMillis,
                                draftFailure = null,
                                validationFailure = null,
                            )
                        }
                    }

                    is ComposerEvent.InitializationFailed -> {
                        if (!event.matches(state(), composerEpoch)) return
                        update {
                            it.copy(
                                isInitializing = false,
                                initializationFailure = event.failure,
                                draftStatus = DiscourseComposerDraftStatus.None,
                                validationFailure = event.validationFailure,
                            )
                        }
                    }

                    is ComposerEvent.DraftSaved -> {
                        if (!event.matches(state(), composerEpoch, contentVersion)) return
                        update {
                            it.copy(
                                draftStatus = DiscourseComposerDraftStatus.Saved,
                                draftRevision = event.draft.revision,
                                draftUpdatedAtEpochMillis = event.draft.updatedAtEpochMillis,
                                draftFailure = null,
                            )
                        }
                    }

                    is ComposerEvent.DraftSaveFailed -> {
                        if (!event.matches(state(), composerEpoch, contentVersion)) return
                        update {
                            it.copy(
                                draftStatus = DiscourseComposerDraftStatus.Failed,
                                draftFailure = event.failure,
                            )
                        }
                    }

                    is ComposerEvent.SubmitDraftSaved -> {
                        if (!event.matches(state(), composerEpoch, submitRequestId, contentVersion)) return
                        update {
                            it.copy(
                                draftStatus = DiscourseComposerDraftStatus.Saved,
                                draftRevision = event.draft.revision,
                                draftUpdatedAtEpochMillis = event.draft.updatedAtEpochMillis,
                                draftFailure = null,
                            )
                        }
                    }

                    is ComposerEvent.Submitted -> {
                        if (!event.matches(state(), composerEpoch, submitRequestId, contentVersion)) return
                        when (val outcome = event.outcome) {
                            is DiscoursePostSubmissionOutcome.Published -> {
                                update {
                                    it.copy(
                                        draftStatus = DiscourseComposerDraftStatus.None,
                                        draftRevision = null,
                                        draftUpdatedAtEpochMillis = null,
                                        submitStatus = DiscourseComposerSubmitStatus.Published,
                                        publishedPost = outcome.post,
                                        pendingModeration = null,
                                        submitFailure = null,
                                        validationFailure = null,
                                    )
                                }
                            }

                            is DiscoursePostSubmissionOutcome.PendingModeration -> {
                                update {
                                    it.copy(
                                        submitStatus = DiscourseComposerSubmitStatus.PendingModeration,
                                        publishedPost = null,
                                        pendingModeration =
                                            DiscourseComposerPendingModeration(
                                                pendingCount = outcome.pendingCount,
                                                pendingPostId = outcome.pendingPostId,
                                                topicId = outcome.topicId,
                                            ),
                                        submitFailure = null,
                                        validationFailure = null,
                                    )
                                }
                            }
                        }
                    }

                    is ComposerEvent.SubmitFailed -> {
                        if (!event.matches(state(), composerEpoch, submitRequestId, contentVersion)) return
                        update {
                            it.copy(
                                draftStatus =
                                    if (event.draft == null) {
                                        DiscourseComposerDraftStatus.Failed
                                    } else {
                                        DiscourseComposerDraftStatus.Saved
                                    },
                                draftRevision = event.draft?.revision ?: it.draftRevision,
                                draftUpdatedAtEpochMillis =
                                    event.draft?.updatedAtEpochMillis ?: it.draftUpdatedAtEpochMillis,
                                draftFailure = if (event.draft == null) event.failure else null,
                                submitStatus = DiscourseComposerSubmitStatus.Failed,
                                submitFailure = event.failure,
                                validationFailure = event.validationFailure,
                            )
                        }
                    }

                    is ComposerEvent.UploadStateChanged -> {
                        val current = state()
                        if (
                            event.generation != current.sessionGeneration ||
                            event.taskEpoch != uploadTaskEpoch ||
                            event.taskEpoch != current.upload.taskEpoch
                        ) {
                            return
                        }
                        val succeeded = event.value as? DiscourseUploadTaskState.Succeeded
                        val identity =
                            succeeded?.let { value ->
                                UploadAttemptIdentity(event.taskEpoch, value.attempt)
                            }
                        if (identity != null && identity in appliedUploadAttempts) return
                        val merged = current.upload.merge(event.value) ?: return
                        if (succeeded != null && identity != null) {
                            // The remote request is terminal. Cancel any collector still suspended
                            // after publishing StateFlow, then release the task that retains bytes.
                            uploadJob?.cancel()
                            uploadJob = null
                            uploadControlJob?.cancel()
                            uploadControlJob = null
                            uploadTask = null
                            // Body mutation and the visible Succeeded terminal state are one actor
                            // update. Compose and SwiftUI can therefore submit immediately without a
                            // platform effect racing to append the attachment later.
                            applyUploadedAttachment(succeeded.attachment, merged, identity)
                        } else {
                            setState(current.copy(upload = merged))
                        }
                    }

                    is ComposerEvent.UploadExecutionFailed -> {
                        if (
                            event.generation != state().sessionGeneration ||
                            event.taskEpoch != uploadTaskEpoch ||
                            event.taskEpoch != state().upload.taskEpoch
                        ) {
                            return
                        }
                        update {
                            it.copy(
                                upload =
                                    it.upload.copy(
                                        status = DiscourseComposerUploadStatus.Failed,
                                        attachment = null,
                                        failure = event.failure,
                                    ),
                            )
                        }
                    }

                    is ComposerEvent.ActionSnapshotChanged -> {
                        handleActionSnapshot(event.value)
                    }

                    is ComposerEvent.ActionSeedFinished -> {
                        actionSeedJobs.remove(event.requestId)
                    }

                    is ComposerEvent.ActionMutationCompleted -> {
                        val operation = actionJobs[event.key]
                        if (
                            operation?.requestId != event.requestId ||
                            event.generation != state().sessionGeneration
                        ) {
                            return
                        }
                        actionJobs.remove(event.key)
                        actionFeedback[event.key.target] =
                            actionFeedback[event.key.target].orEmpty().withResult(event.key.kind, event.result)
                        renderPostActions()
                    }

                    is ComposerEvent.ActionMutationFailed -> {
                        val operation = actionJobs[event.key]
                        if (
                            operation?.requestId != event.requestId ||
                            event.generation != state().sessionGeneration
                        ) {
                            return
                        }
                        actionJobs.remove(event.key)
                        actionFeedback[event.key.target] =
                            actionFeedback[event.key.target].orEmpty().withFailure(event.key.kind, event.failure)
                        renderPostActions()
                    }
                }
            }

            setState(
                DiscourseComposerState(
                    sessionGeneration = initialSession.generation,
                    contentVersion = contentVersion,
                    accountId = (initialSession as? DiscourseSessionState.Authenticated)?.accountId,
                ),
            )

            val sessionForwarder =
                launch {
                    sessionManager.state.collect { session -> sessionChanges.send(session) }
                }
            val actionStateForwarder =
                launch {
                    postActionRepository.state.collect { snapshot ->
                        events.send(ComposerEvent.ActionSnapshotChanged(snapshot))
                    }
                }

            fun drainQueuedEvents() {
                while (true) {
                    val event = events.tryReceive().getOrNull() ?: return
                    handleEvent(event)
                }
            }

            try {
                while (true) {
                    if (shutdownRequested && submitJob?.isActive != true) {
                        // A child can complete immediately after its terminal send. Reduce every
                        // queued result before the final flush decides whether a draft is durable.
                        synchronizeCurrentSession()
                        drainQueuedEvents()
                        if (submitJob?.isActive != true) break
                    }
                    select<Unit> {
                        if (!shutdownRequested) {
                            commands.onReceive { command -> handleCommand(command) }
                        }
                        draftUpdates.onReceive { input -> handleDraftUpdate(input) }
                        sessionChanges.onReceive { session -> handleSessionChanged(session) }
                        events.onReceive { event -> handleEvent(event) }
                        if (shutdownRequested) {
                            submitJob?.onJoin { }
                        }
                    }
                }
            } finally {
                // Presenter.close() can cancel the actor before the conflated typing channel wins a
                // select. Capture that last whole-editor value before closing the channel.
                val finalSnapshot = consumeLatestDraftSnapshot(state())
                cancelComposerOperationJobs()
                cancelActionJobs()
                sessionForwarder.cancel()
                actionStateForwarder.cancel()
                withContext(NonCancellable) {
                    cancelUploadTask()
                    cleanupJob?.join()
                    flushDraftIgnoringFailure(finalSnapshot)
                }
                commands.close()
                draftUpdates.close()
                sessionChanges.close()
                events.close()
            }
        }

    private suspend fun loadInitializationEvent(
        epoch: Long,
        generation: Long,
        accountId: String,
        target: DiscourseComposerTarget,
    ): ComposerEvent? =
        try {
            val constraints =
                (target as? DiscourseComposerTarget.NewTopic)?.let {
                    repository.loadNewTopicConstraints(accountId, it.categoryId)
                }
            // An edit must reach the authenticated raw endpoint even when a local draft exists.
            val authoritativeRaw =
                (target as? DiscourseComposerTarget.Edit)?.let {
                    repository.loadEditableSource(accountId, it).raw
                }
            val draft = draftStore.load(accountId, target)
            ComposerEvent.Initialized(
                epoch = epoch,
                generation = generation,
                accountId = accountId,
                target = target,
                constraints = constraints,
                authoritativeRaw = authoritativeRaw,
                draft = draft,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: StaleDiscourseSessionException) {
            null
        } catch (validation: DiscourseComposerValidationException) {
            ComposerEvent.InitializationFailed(
                epoch,
                generation,
                accountId,
                target,
                failure = DiscourseForumFailureKind.InvalidResponse,
                validationFailure = validation.failure,
            )
        } catch (failure: DiscourseException) {
            ComposerEvent.InitializationFailed(
                epoch,
                generation,
                accountId,
                target,
                failure = failure.toForumFailureKind(),
            )
        } catch (_: Exception) {
            ComposerEvent.InitializationFailed(
                epoch,
                generation,
                accountId,
                target,
                failure = DiscourseForumFailureKind.InvalidResponse,
            )
        }

    private suspend fun saveDraftEvent(
        epoch: Long,
        generation: Long,
        version: Long,
        accountId: String,
        target: DiscourseComposerTarget,
        title: String?,
        raw: String,
        tags: List<String>,
    ): ComposerEvent =
        try {
            val draft =
                draftStore.save(
                    accountId = accountId,
                    target = target,
                    title = title,
                    raw = raw,
                    tags = tags,
                    updatedAtEpochMillis = checkedNowEpochMillis(),
                )
            ComposerEvent.DraftSaved(epoch, generation, version, accountId, target, draft)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ComposerEvent.DraftSaveFailed(
                epoch,
                generation,
                version,
                accountId,
                target,
                DiscourseForumFailureKind.InvalidResponse,
            )
        }

    private suspend fun submitEvent(
        requestId: Long,
        epoch: Long,
        generation: Long,
        version: Long,
        accountId: String,
        target: DiscourseComposerTarget,
        title: String?,
        raw: String,
        tags: List<String>,
    ): List<ComposerEvent> {
        var saved: DiscourseComposerDraft? = null
        return try {
            saved =
                draftStore.save(
                    accountId = accountId,
                    target = target,
                    title = title,
                    raw = raw,
                    tags = tags,
                    updatedAtEpochMillis = checkedNowEpochMillis(),
                )
            listOf(
                ComposerEvent.SubmitDraftSaved(
                    requestId,
                    epoch,
                    generation,
                    version,
                    accountId,
                    target,
                    saved,
                ),
                ComposerEvent.Submitted(
                    requestId,
                    epoch,
                    generation,
                    version,
                    accountId,
                    target,
                    repository.submit(accountId, target),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: StaleDiscourseSessionException) {
            emptyList()
        } catch (validation: DiscourseComposerValidationException) {
            listOf(
                ComposerEvent.SubmitFailed(
                    requestId,
                    epoch,
                    generation,
                    version,
                    accountId,
                    target,
                    draft = saved,
                    failure = DiscourseForumFailureKind.InvalidResponse,
                    validationFailure = validation.failure,
                ),
            )
        } catch (failure: DiscourseException) {
            listOf(
                ComposerEvent.SubmitFailed(
                    requestId,
                    epoch,
                    generation,
                    version,
                    accountId,
                    target,
                    draft = saved,
                    failure = failure.toForumFailureKind(),
                ),
            )
        } catch (_: Exception) {
            listOf(
                ComposerEvent.SubmitFailed(
                    requestId,
                    epoch,
                    generation,
                    version,
                    accountId,
                    target,
                    draft = saved,
                    failure = DiscourseForumFailureKind.InvalidResponse,
                ),
            )
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.launchUpload(
        task: DiscourseUploadTask,
        taskEpoch: Long,
        generation: Long,
        retry: Boolean,
        events: Channel<ComposerEvent>,
    ): Job =
        launch {
            val stateCollector =
                launch {
                    task.state.collect { value ->
                        events.send(ComposerEvent.UploadStateChanged(taskEpoch, generation, value))
                    }
                }
            try {
                val terminal = if (retry) task.retry() else task.execute()
                events.send(ComposerEvent.UploadStateChanged(taskEpoch, generation, terminal))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: DiscourseException) {
                events.send(
                    ComposerEvent.UploadExecutionFailed(
                        taskEpoch,
                        generation,
                        failure.toForumFailureKind(),
                    ),
                )
            } catch (_: StaleDiscourseSessionException) {
                // SessionChanged owns invalidation and clears attachment bytes/state.
            } catch (_: Exception) {
                events.send(
                    ComposerEvent.UploadExecutionFailed(
                        taskEpoch,
                        generation,
                        DiscourseForumFailureKind.InvalidResponse,
                    ),
                )
            } finally {
                stateCollector.cancel()
            }
        }

    private suspend fun actionMutationEvent(
        requestId: Long,
        generation: Long,
        key: ActionOperationKey,
        block: suspend () -> DiscourseOptimisticMutationResult,
    ): ComposerEvent? =
        try {
            ComposerEvent.ActionMutationCompleted(requestId, generation, key, block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: StaleDiscourseSessionException) {
            null
        } catch (failure: DiscourseException) {
            ComposerEvent.ActionMutationFailed(requestId, generation, key, failure.toForumFailureKind())
        } catch (_: Exception) {
            ComposerEvent.ActionMutationFailed(
                requestId,
                generation,
                key,
                DiscourseForumFailureKind.InvalidResponse,
            )
        }

    private fun checkedNowEpochMillis(): Long = nowEpochMillis().also { require(it >= 0L) { "Composer clock cannot be negative" } }
}

private sealed interface ComposerCommand {
    data class Open(
        val target: DiscourseComposerTarget,
    ) : ComposerCommand

    data object Close : ComposerCommand

    data object Discard : ComposerCommand

    data object RetryInitialization : ComposerCommand

    data object Submit : ComposerCommand

    data class StartUpload(
        val request: DiscourseUploadRequest,
        val expectedOwner: ComposerContentOwner,
        val expectedContentVersion: Long,
    ) : ComposerCommand

    data object CancelUpload : ComposerCommand

    data object RetryUpload : ComposerCommand

    data class SynchronizeArticle(
        val article: UiArticle,
    ) : ComposerCommand

    data class SynchronizeTopic(
        val topic: DiscourseForumTopic,
    ) : ComposerCommand

    data class ToggleLike(
        val target: DiscourseActionTarget.Post,
    ) : ComposerCommand

    data class ToggleBookmark(
        val target: DiscourseActionTarget,
    ) : ComposerCommand

    data object Shutdown : ComposerCommand
}

private sealed interface ComposerEvent {
    data class Initialized(
        val epoch: Long,
        val generation: Long,
        val accountId: String,
        val target: DiscourseComposerTarget,
        val constraints: DiscourseNewTopicConstraints?,
        val authoritativeRaw: String?,
        val draft: DiscourseComposerDraft?,
    ) : ComposerEvent

    data class InitializationFailed(
        val epoch: Long,
        val generation: Long,
        val accountId: String,
        val target: DiscourseComposerTarget,
        val failure: DiscourseForumFailureKind,
        val validationFailure: DiscourseComposerValidationFailure? = null,
    ) : ComposerEvent

    data class DraftSaved(
        val epoch: Long,
        val generation: Long,
        val version: Long,
        val accountId: String,
        val target: DiscourseComposerTarget,
        val draft: DiscourseComposerDraft,
    ) : ComposerEvent

    data class DraftSaveFailed(
        val epoch: Long,
        val generation: Long,
        val version: Long,
        val accountId: String,
        val target: DiscourseComposerTarget,
        val failure: DiscourseForumFailureKind,
    ) : ComposerEvent

    data class SubmitDraftSaved(
        val requestId: Long,
        val epoch: Long,
        val generation: Long,
        val version: Long,
        val accountId: String,
        val target: DiscourseComposerTarget,
        val draft: DiscourseComposerDraft,
    ) : ComposerEvent

    data class Submitted(
        val requestId: Long,
        val epoch: Long,
        val generation: Long,
        val version: Long,
        val accountId: String,
        val target: DiscourseComposerTarget,
        val outcome: DiscoursePostSubmissionOutcome,
    ) : ComposerEvent

    data class SubmitFailed(
        val requestId: Long,
        val epoch: Long,
        val generation: Long,
        val version: Long,
        val accountId: String,
        val target: DiscourseComposerTarget,
        val draft: DiscourseComposerDraft?,
        val failure: DiscourseForumFailureKind,
        val validationFailure: DiscourseComposerValidationFailure? = null,
    ) : ComposerEvent

    data class UploadStateChanged(
        val taskEpoch: Long,
        val generation: Long,
        val value: DiscourseUploadTaskState,
    ) : ComposerEvent

    data class UploadExecutionFailed(
        val taskEpoch: Long,
        val generation: Long,
        val failure: DiscourseForumFailureKind,
    ) : ComposerEvent

    data class ActionSnapshotChanged(
        val value: DiscoursePostActionSnapshot?,
    ) : ComposerEvent

    data class ActionSeedFinished(
        val requestId: Long,
        val generation: Long,
    ) : ComposerEvent

    data class ActionMutationCompleted(
        val requestId: Long,
        val generation: Long,
        val key: ActionOperationKey,
        val result: DiscourseOptimisticMutationResult,
    ) : ComposerEvent

    data class ActionMutationFailed(
        val requestId: Long,
        val generation: Long,
        val key: ActionOperationKey,
        val failure: DiscourseForumFailureKind,
    ) : ComposerEvent
}

private enum class ActionKind {
    Like,
    Bookmark,
}

private data class ActionOperationKey(
    val target: DiscourseActionTarget,
    val kind: ActionKind,
)

private data class ActionOperation(
    val requestId: Long,
    val job: Job,
)

private data class ActionFeedback(
    val likeFailure: DiscourseForumFailureKind? = null,
    val bookmarkFailure: DiscourseForumFailureKind? = null,
    val likeNotAllowedReason: DiscourseActionNotAllowedReason? = null,
    val bookmarkNotAllowedReason: DiscourseActionNotAllowedReason? = null,
) {
    fun clear(kind: ActionKind): ActionFeedback =
        when (kind) {
            ActionKind.Like -> copy(likeFailure = null, likeNotAllowedReason = null)
            ActionKind.Bookmark -> copy(bookmarkFailure = null, bookmarkNotAllowedReason = null)
        }

    fun withFailure(
        kind: ActionKind,
        failure: DiscourseForumFailureKind,
    ): ActionFeedback =
        when (kind) {
            ActionKind.Like -> copy(likeFailure = failure, likeNotAllowedReason = null)
            ActionKind.Bookmark -> copy(bookmarkFailure = failure, bookmarkNotAllowedReason = null)
        }

    fun withResult(
        kind: ActionKind,
        result: DiscourseOptimisticMutationResult,
    ): ActionFeedback =
        when (result) {
            is DiscourseOptimisticMutationResult.Rejected -> {
                withFailure(kind, result.failure)
            }

            is DiscourseOptimisticMutationResult.NotAllowed -> {
                when (kind) {
                    ActionKind.Like -> {
                        copy(likeFailure = null, likeNotAllowedReason = result.reason)
                    }

                    ActionKind.Bookmark -> {
                        copy(bookmarkFailure = null, bookmarkNotAllowedReason = result.reason)
                    }
                }
            }

            is DiscourseOptimisticMutationResult.Busy,
            is DiscourseOptimisticMutationResult.Confirmed,
            is DiscourseOptimisticMutationResult.Superseded,
            -> {
                clear(kind)
            }
        }
}

private fun ActionFeedback?.orEmpty(): ActionFeedback = this ?: ActionFeedback()

private fun DiscourseOptimisticActionState.toPresentation(feedback: ActionFeedback?): DiscoursePostActionPresentationState {
    val resolved = feedback.orEmpty()
    return DiscoursePostActionPresentationState(
        target = target,
        liked = liked,
        likeCount = likeCount,
        canLike = canLike,
        bookmarked = bookmarked,
        bookmarkId = bookmarkId,
        canBookmark = canBookmark,
        isLikeInFlight = isLikeInFlight,
        isBookmarkInFlight = isBookmarkInFlight,
        likeFailure = resolved.likeFailure,
        bookmarkFailure = resolved.bookmarkFailure,
        likeNotAllowedReason = resolved.likeNotAllowedReason,
        bookmarkNotAllowedReason = resolved.bookmarkNotAllowedReason,
    )
}

private val actionStateComparator: Comparator<DiscourseOptimisticActionState> =
    compareBy<DiscourseOptimisticActionState>({ it.target.sortOrder }, { it.target.serverId })

private val DiscourseActionTarget.sortOrder: Int
    get() = if (this is DiscourseActionTarget.Topic) 0 else 1

private val DiscourseActionTarget.serverId: Long
    get() =
        when (this) {
            is DiscourseActionTarget.Post -> postId
            is DiscourseActionTarget.Topic -> topicId
        }

private data class ComposerContentOwner(
    val sessionGeneration: Long,
    val accountId: String,
    val target: DiscourseComposerTarget,
) {
    init {
        require(sessionGeneration >= 0L) { "Composer owner generation cannot be negative" }
        require(accountId.isNotBlank()) { "Composer owner account cannot be blank" }
    }

    companion object {
        fun createOrNull(
            sessionGeneration: Long,
            accountId: String?,
            target: DiscourseComposerTarget?,
        ): ComposerContentOwner? {
            if (sessionGeneration < 0L || accountId.isNullOrBlank() || target == null) return null
            return ComposerContentOwner(sessionGeneration, accountId, target)
        }
    }
}

private data class OwnedComposerDraftInput(
    val value: DiscourseComposerDraftInput,
    val baseContentVersion: Long,
    val owner: ComposerContentOwner,
) {
    init {
        require(baseContentVersion >= 0L) { "Composer input base version cannot be negative" }
    }
}

private data class UploadAttemptIdentity(
    val taskEpoch: Long,
    val attempt: Long,
) {
    init {
        require(taskEpoch > 0L) { "Upload task epoch must be positive" }
        require(attempt > 0L) { "Upload attempt must be positive" }
    }
}

private sealed interface ComposerContentMutation {
    val fromVersion: Long
    val toVersion: Long

    data class DraftInputApplied(
        override val fromVersion: Long,
        override val toVersion: Long,
        val inputBaseVersion: Long,
    ) : ComposerContentMutation

    data class UploadApplied(
        override val fromVersion: Long,
        override val toVersion: Long,
        val identity: UploadAttemptIdentity,
        val composerMarkdown: String,
    ) : ComposerContentMutation
}

private fun DiscourseComposerState.contentOwnerOrNull(): ComposerContentOwner? =
    ComposerContentOwner.createOrNull(sessionGeneration, accountId, target)

private val ComposerCommand.blocksWhileSubmitting: Boolean
    get() =
        this is ComposerCommand.Open ||
            this === ComposerCommand.Close ||
            this === ComposerCommand.Discard ||
            this === ComposerCommand.RetryInitialization ||
            this === ComposerCommand.Submit

private fun String.appendComposerMarkdown(markdown: String): String {
    val separator = if (isBlank() || endsWith('\n')) "" else "\n\n"
    return this + separator + markdown
}

private fun DiscourseComposerState.needsDurableFlush(): Boolean =
    mode != DiscourseComposerMode.Closed &&
        accountId != null &&
        target != null &&
        draftStatus in
        setOf(
            DiscourseComposerDraftStatus.Dirty,
            DiscourseComposerDraftStatus.Saving,
            DiscourseComposerDraftStatus.Failed,
        )

/** Applies one atomic editor snapshot without starting work or crossing an account boundary. */
private fun DiscourseComposerState.withDraftInput(
    input: DiscourseComposerDraftInput,
    contentVersion: Long,
): DiscourseComposerState? {
    val currentTarget = target ?: return null
    if (!canEdit) return null
    val newTopic = currentTarget is DiscourseComposerTarget.NewTopic
    return copy(
        contentVersion = contentVersion,
        title = input.title.takeIf { newTopic },
        raw = input.raw,
        tags = input.tags.takeIf { newTopic }.orEmpty(),
        draftStatus = DiscourseComposerDraftStatus.Dirty,
        draftFailure = null,
        submitStatus = DiscourseComposerSubmitStatus.Idle,
        publishedPost = null,
        pendingModeration = null,
        submitFailure = null,
        validationFailure = null,
    )
}

private fun ComposerEvent.Initialized.matches(
    state: DiscourseComposerState,
    currentEpoch: Long,
): Boolean =
    epoch == currentEpoch &&
        generation == state.sessionGeneration &&
        accountId == state.accountId &&
        target == state.target

private fun ComposerEvent.InitializationFailed.matches(
    state: DiscourseComposerState,
    currentEpoch: Long,
): Boolean =
    epoch == currentEpoch &&
        generation == state.sessionGeneration &&
        accountId == state.accountId &&
        target == state.target

private fun ComposerEvent.DraftSaved.matches(
    state: DiscourseComposerState,
    currentEpoch: Long,
    currentVersion: Long,
): Boolean =
    epoch == currentEpoch &&
        version == currentVersion &&
        generation == state.sessionGeneration &&
        accountId == state.accountId &&
        target == state.target

private fun ComposerEvent.DraftSaveFailed.matches(
    state: DiscourseComposerState,
    currentEpoch: Long,
    currentVersion: Long,
): Boolean =
    epoch == currentEpoch &&
        version == currentVersion &&
        generation == state.sessionGeneration &&
        accountId == state.accountId &&
        target == state.target

private fun ComposerEvent.SubmitDraftSaved.matches(
    state: DiscourseComposerState,
    currentEpoch: Long,
    currentRequestId: Long,
    currentVersion: Long,
): Boolean =
    requestId == currentRequestId &&
        epoch == currentEpoch &&
        version == currentVersion &&
        generation == state.sessionGeneration &&
        accountId == state.accountId &&
        target == state.target

private fun ComposerEvent.Submitted.matches(
    state: DiscourseComposerState,
    currentEpoch: Long,
    currentRequestId: Long,
    currentVersion: Long,
): Boolean =
    requestId == currentRequestId &&
        epoch == currentEpoch &&
        version == currentVersion &&
        generation == state.sessionGeneration &&
        accountId == state.accountId &&
        target == state.target

private fun ComposerEvent.SubmitFailed.matches(
    state: DiscourseComposerState,
    currentEpoch: Long,
    currentRequestId: Long,
    currentVersion: Long,
): Boolean =
    requestId == currentRequestId &&
        epoch == currentEpoch &&
        version == currentVersion &&
        generation == state.sessionGeneration &&
        accountId == state.accountId &&
        target == state.target

internal fun DiscourseComposerUploadState.merge(incoming: DiscourseUploadTaskState): DiscourseComposerUploadState? {
    val incomingAttempt = incoming.attemptOrNull
    if (incomingAttempt != null && attempt != null && incomingAttempt < attempt) return null
    return when (incoming) {
        DiscourseUploadTaskState.Ready -> {
            if (status == DiscourseComposerUploadStatus.Ready) this else null
        }

        is DiscourseUploadTaskState.Uploading -> {
            val continuesCurrentAttempt =
                status == DiscourseComposerUploadStatus.Uploading &&
                    attempt == incoming.attempt
            val startsNewAttempt =
                status == DiscourseComposerUploadStatus.Ready &&
                    (attempt == null || incoming.attempt > attempt)
            if (!continuesCurrentAttempt && !startsNewAttempt) return null
            if (continuesCurrentAttempt && incoming.bytesSent < bytesSent) return null
            copy(
                status = DiscourseComposerUploadStatus.Uploading,
                attempt = incoming.attempt,
                bytesSent = incoming.bytesSent,
                totalBytes = incoming.totalBytes ?: totalBytes.takeIf { attempt == incoming.attempt },
                attachment = null,
                failure = null,
            )
        }

        is DiscourseUploadTaskState.Succeeded -> {
            // Cancelled/failed attempts are tombstones. A terminal StateFlow value queued before
            // cancellation must never attach a file after the actor accepted Cancel. StateFlow may
            // conflate an unobserved Uploading value, so Ready may accept only a genuinely newer
            // attempt (or the first attempt of a newly selected task), never its retained old one.
            val completesCurrentAttempt =
                status == DiscourseComposerUploadStatus.Uploading &&
                    attempt == incoming.attempt
            val completesUnobservedNewAttempt =
                status == DiscourseComposerUploadStatus.Ready &&
                    (attempt == null || incoming.attempt > attempt)
            if (!completesCurrentAttempt && !completesUnobservedNewAttempt) return null
            copy(
                status = DiscourseComposerUploadStatus.Succeeded,
                attempt = incoming.attempt,
                attachment = incoming.attachment,
                failure = null,
            )
        }

        is DiscourseUploadTaskState.Failed -> {
            val failsCurrentAttempt =
                status == DiscourseComposerUploadStatus.Uploading &&
                    attempt == incoming.attempt
            val failsUnobservedNewAttempt =
                status == DiscourseComposerUploadStatus.Ready &&
                    (attempt == null || incoming.attempt > attempt)
            if (!failsCurrentAttempt && !failsUnobservedNewAttempt) return null
            copy(
                status = DiscourseComposerUploadStatus.Failed,
                attempt = incoming.attempt,
                attachment = null,
                failure = incoming.failure,
            )
        }

        is DiscourseUploadTaskState.Cancelled -> {
            val cancelsCurrentAttempt =
                status == DiscourseComposerUploadStatus.Uploading &&
                    attempt == incoming.attempt
            val cancelsUnobservedNewAttempt =
                status == DiscourseComposerUploadStatus.Ready &&
                    (attempt == null || incoming.attempt > attempt)
            if (!cancelsCurrentAttempt && !cancelsUnobservedNewAttempt) return null
            copy(
                status = DiscourseComposerUploadStatus.Cancelled,
                attempt = incoming.attempt,
                attachment = null,
                failure = null,
            )
        }
    }
}

private val DiscourseUploadTaskState.attemptOrNull: Long?
    get() =
        when (this) {
            DiscourseUploadTaskState.Ready -> null
            is DiscourseUploadTaskState.Uploading -> attempt
            is DiscourseUploadTaskState.Succeeded -> attempt
            is DiscourseUploadTaskState.Failed -> attempt
            is DiscourseUploadTaskState.Cancelled -> attempt
        }

private fun Long.nextEpoch(label: String): Long {
    check(this < Long.MAX_VALUE) { "$label epoch space is exhausted" }
    return this + 1L
}

private const val COMMAND_CHANNEL_CAPACITY: Int = 64
private const val RESULT_CHANNEL_CAPACITY: Int = 64
private const val MAX_COMPOSER_CONTENT_MUTATIONS: Int = 128
public const val DEFAULT_COMPOSER_AUTOSAVE_DELAY_MILLIS: Long = 750L
