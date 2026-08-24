package dev.dimension.flare.apple.shared

import dev.dimension.flare.data.database.FlareDoDatabase
import dev.dimension.flare.data.database.createAppleFlareDoDatabase
import dev.dimension.flare.data.network.discourse.DiscourseUploadRequest
import dev.dimension.flare.data.network.discourse.MAX_DISCOURSE_UPLOAD_BYTES
import dev.dimension.flare.data.network.discourse.auth.AppleDiscourseRsaPkcs1Crypto
import dev.dimension.flare.data.network.discourse.auth.AppleDiscourseWebSessionCookieBridge
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthAttemptStore
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthExchangeException
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthExchangeFailure
import dev.dimension.flare.data.network.discourse.auth.DiscourseCloudflareChallengeHandler
import dev.dimension.flare.data.network.discourse.auth.DiscourseLoginResult
import dev.dimension.flare.data.network.discourse.auth.DiscourseLoginService
import dev.dimension.flare.data.network.discourse.auth.DiscourseManualChallengeCookieHandler
import dev.dimension.flare.data.network.discourse.auth.DiscourseManualChallengeCoordinator
import dev.dimension.flare.data.network.discourse.auth.DiscourseRsaPkcs1Decryptor
import dev.dimension.flare.data.network.discourse.auth.DiscourseRsaPkcs1KeyPairGenerator
import dev.dimension.flare.data.network.discourse.auth.DiscourseWebSessionCookieBridge
import dev.dimension.flare.data.network.discourse.auth.DiscourseWebSessionLogin
import dev.dimension.flare.data.network.discourse.auth.RoomDiscourseAuthAttemptStore
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerPresenter
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerTarget
import dev.dimension.flare.data.network.discourse.composer.DiscourseDraftStore
import dev.dimension.flare.data.network.discourse.composer.roomDiscourseDraftStore
import dev.dimension.flare.data.network.discourse.discourseAuthenticationModule
import dev.dimension.flare.data.network.discourse.discourseModule
import dev.dimension.flare.data.network.discourse.error.DiscourseAuthenticationException
import dev.dimension.flare.data.network.discourse.error.DiscourseCloudflareChallengeException
import dev.dimension.flare.data.network.discourse.error.DiscourseCsrfException
import dev.dimension.flare.data.network.discourse.error.DiscourseHttpException
import dev.dimension.flare.data.network.discourse.error.DiscourseNetworkException
import dev.dimension.flare.data.network.discourse.error.DiscoursePermissionException
import dev.dimension.flare.data.network.discourse.error.DiscourseRateLimitException
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationException
import dev.dimension.flare.data.network.discourse.error.DiscourseServerException
import dev.dimension.flare.data.network.discourse.error.DiscourseValidationException
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumAction
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumCache
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumDestination
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFeed
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumPresenter
import dev.dimension.flare.data.network.discourse.forum.roomDiscourseForumCache
import dev.dimension.flare.data.network.discourse.realtime.DiscourseMessageBusCursorStore
import dev.dimension.flare.data.network.discourse.realtime.roomDiscourseMessageBusCursorStore
import dev.dimension.flare.data.network.discourse.session.AppleCredentialStoreException
import dev.dimension.flare.data.network.discourse.session.AppleKeychainCredentialStore
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionLifecycle
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionStore
import dev.dimension.flare.data.network.discourse.session.RoomDiscourseSessionStore
import dev.dimension.flare.data.network.discourse.session.SecureCredentialStore
import dev.dimension.flare.data.network.discourse.session.StaleDiscourseSessionException
import dev.dimension.flare.di.sharedModule
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.dsl.onClose
import platform.Foundation.NSData
import platform.posix.memcpy

private const val MAX_APPLE_DATABASE_PATH_CHARS: Int = 4_096

/** Fixed host-creation failures; underlying path, Keychain, Room, and Koin messages are discarded. */
public enum class AppleForumHostCreationError {
    INVALID_DATABASE_PATH,
    INITIALIZATION_FAILED,
}

public data class AppleForumHostCreationResult(
    public val host: AppleForumHost?,
    public val error: AppleForumHostCreationError?,
) {
    init {
        require((host == null) != (error == null)) { "Apple host creation must contain exactly one result" }
    }
}

/** Fixed operational failures suitable for user-interface decisions and bounded diagnostics. */
public enum class AppleForumOperationError {
    HOST_CLOSED,
    INVALID_INPUT,
    AUTHENTICATION,
    PERMISSION,
    RATE_LIMITED,
    CHALLENGE_REQUIRED,
    NETWORK,
    SERVER,
    INVALID_RESPONSE,
    STALE_SESSION,
    SECURE_STORAGE,
    CANCELLED,
    INTERNAL,
}

public data class AppleBooleanResult(
    public val value: Boolean,
    public val error: AppleForumOperationError?,
)

public data class AppleAuthorizationResult(
    public val url: String?,
    public val expiresAtEpochMillis: Long?,
    public val error: AppleForumOperationError?,
)

public enum class AppleLoginStatus {
    AUTHENTICATED,
    STALE,
    EXPIRED,
    MALFORMED,
    FAILED,
}

public data class AppleLoginResult(
    public val status: AppleLoginStatus,
    public val accountId: String?,
    public val username: String?,
    public val displayName: String?,
    public val error: AppleForumOperationError?,
)

public data class AppleManualChallengeSnapshot(
    public val requestId: Long,
    public val origin: String,
)

/** Cancellable ownership returned for observers and one-shot asynchronous operations. */
public class AppleForumObservation internal constructor(
    private val job: Job,
) {
    public val isCancelled: Boolean
        get() = job.isCancelled

    public fun cancel() {
        job.cancel()
    }
}

private enum class AppleHostLifecycle {
    OPEN,
    CLOSING,
    CLOSED,
}

/**
 * Process-owned Apple facade for the shared Linux.do graph.
 *
 * Swift never resolves Koin objects, collects a raw StateFlow, or calls a throwing suspend function.
 * Every callback is invoked from [kotlinx.coroutines.Dispatchers.Main], every one-shot operation has
 * a cancellable structured child, and all exception text is replaced by a fixed enum before crossing
 * the Kotlin/Native boundary.
 */
public class AppleForumHost internal constructor(
    private val dependencies: KoinApplication,
    private val forumPresenter: DiscourseForumPresenter,
    private val composerPresenter: DiscourseComposerPresenter,
    private val loginService: DiscourseLoginService,
    private val webSessionLogin: DiscourseWebSessionLogin,
    private val sessionLifecycle: DiscourseSessionLifecycle,
    private val challengeCoordinator: DiscourseManualChallengeCoordinator,
) {
    public companion object {
        /**
         * Opens a production host at a final, absolute database file path inside the app container.
         *
         * Use this factory from Swift instead of constructing lower-level services. It closes a
         * partially built dependency graph before returning a fixed initialization failure.
         */
        public fun create(databasePath: String): AppleForumHostCreationResult {
            if (!databasePath.isValidAppleDatabasePath()) {
                return AppleForumHostCreationResult(null, AppleForumHostCreationError.INVALID_DATABASE_PATH)
            }
            return try {
                AppleForumHostCreationResult(createAppleForumHost(databasePath), null)
            } catch (_: Throwable) {
                AppleForumHostCreationResult(null, AppleForumHostCreationError.INITIALIZATION_FAILED)
            }
        }
    }

    private val lifecycle: MutableStateFlow<AppleHostLifecycle> = MutableStateFlow(AppleHostLifecycle.OPEN)
    private val operationJob: Job = SupervisorJob()
    private val operationScope: CoroutineScope = CoroutineScope(operationJob + Dispatchers.Main)
    private val mappingScope: CoroutineScope = CoroutineScope(operationJob + Dispatchers.Default)
    private val shutdownScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        // Starting both lazy Molecule presenters here makes shutdown deterministic even if Swift has
        // not installed its first observer before an early scene termination.
        forumPresenter.models.value
        composerPresenter.models.value
    }

    public fun observeForum(observer: (AppleForumSnapshot) -> Unit): AppleForumObservation =
        observeState(forumPresenter.models, DiscourseForumStateMapper, observer)

    public fun observeComposer(observer: (AppleComposerSnapshot) -> Unit): AppleForumObservation =
        observeState(composerPresenter.models, DiscourseComposerStateMapper, observer)

    /** Exposes only the fixed Linux.do origin and monotonic request id for a restricted WKWebView. */
    public fun observeManualChallenge(observer: (AppleManualChallengeSnapshot?) -> Unit): AppleForumObservation =
        observeState(
            flow = challengeCoordinator.request,
            mapper = { request -> request?.let { AppleManualChallengeSnapshot(it.requestId, it.origin) } },
            observer = observer,
        )

    public fun setForeground(isForeground: Boolean) {
        if (lifecycle.value == AppleHostLifecycle.OPEN) forumPresenter.setForeground(isForeground)
    }

    public fun selectDestination(destination: AppleForumDestination): Boolean =
        dispatchForum {
            DiscourseForumAction.SelectDestination(
                when (destination) {
                    AppleForumDestination.LATEST -> DiscourseForumDestination.Latest
                    AppleForumDestination.HOT -> DiscourseForumDestination.Hot
                    AppleForumDestination.SEARCH -> DiscourseForumDestination.Search
                    AppleForumDestination.NOTIFICATIONS -> DiscourseForumDestination.Notifications
                    AppleForumDestination.PROFILE -> DiscourseForumDestination.Profile
                },
            )
        }

    public fun selectLatest(): Boolean = dispatchForum { DiscourseForumAction.SelectFeed(DiscourseForumFeed.Latest) }

    public fun selectHot(): Boolean = dispatchForum { DiscourseForumAction.SelectFeed(DiscourseForumFeed.Hot) }

    public fun selectCategory(
        id: Long,
        slug: String,
        parentSlug: String?,
        name: String,
    ): Boolean =
        dispatchForum {
            DiscourseForumAction.SelectFeed(
                DiscourseForumFeed.Category(id = id, slug = slug, parentSlug = parentSlug, name = name),
            )
        }

    public fun selectTag(
        name: String,
        slug: String,
    ): Boolean = dispatchForum { DiscourseForumAction.SelectFeed(DiscourseForumFeed.Tag(name, slug)) }

    public fun refresh(): Boolean = dispatchForum { DiscourseForumAction.Refresh }

    public fun retryTaxonomy(): Boolean = dispatchForum { DiscourseForumAction.RetryTaxonomy }

    public fun loadNextPage(): Boolean = dispatchForum { DiscourseForumAction.LoadNextPage }

    public fun openTopic(
        topicId: Long,
        postNumber: Int?,
    ): Boolean = dispatchForum { DiscourseForumAction.OpenTopic(topicId, postNumber) }

    public fun closeTopic(): Boolean = dispatchForum { DiscourseForumAction.CloseTopic }

    public fun retryTopic(): Boolean = dispatchForum { DiscourseForumAction.RetryTopic }

    public fun updateSearchQuery(query: String): Boolean = dispatchForum { DiscourseForumAction.UpdateSearchQuery(query) }

    public fun submitSearch(): Boolean = dispatchForum { DiscourseForumAction.SubmitSearch }

    public fun retrySearch(): Boolean = dispatchForum { DiscourseForumAction.RetrySearch }

    public fun loadNextSearchPage(): Boolean = dispatchForum { DiscourseForumAction.LoadNextSearchPage }

    public fun openProfile(username: String): Boolean = dispatchForum { DiscourseForumAction.OpenProfile(username) }

    public fun retryProfile(): Boolean = dispatchForum { DiscourseForumAction.RetryProfile }

    public fun loadNextActivityPage(): Boolean = dispatchForum { DiscourseForumAction.LoadNextActivityPage }

    public fun refreshNotifications(): Boolean = dispatchForum { DiscourseForumAction.RefreshNotifications }

    public fun retryNotifications(): Boolean = dispatchForum { DiscourseForumAction.RetryNotifications }

    public fun loadNextNotificationsPage(): Boolean = dispatchForum { DiscourseForumAction.LoadNextNotificationsPage }

    public fun markNotificationsRead(
        notificationId: Long?,
        expectedSessionGeneration: Long,
        expectedAccountId: String?,
    ): Boolean =
        dispatchComposer {
            forumPresenter.markNotificationsRead(
                notificationId,
                expectedSessionGeneration,
                expectedAccountId,
            )
        }

    public fun openNewTopic(categoryId: Long?): Boolean = dispatchComposer { composerPresenter.openNewTopic(categoryId) }

    public fun openReply(
        topicId: Long,
        replyToPostNumber: Int?,
    ): Boolean = dispatchComposer { composerPresenter.openReply(topicId, replyToPostNumber) }

    public fun openEdit(
        topicId: Long,
        postId: Long,
        postNumber: Int,
    ): Boolean = dispatchComposer { composerPresenter.openEdit(topicId, postId, postNumber) }

    public fun closeComposer(
        expectedContentVersion: Long,
        expectedSessionGeneration: Long,
        expectedAccountId: String?,
        expectedTarget: AppleComposerTargetSnapshot?,
    ): Boolean =
        dispatchOwnedComposer(expectedTarget) { target ->
            composerPresenter.closeComposer(
                expectedContentVersion,
                expectedSessionGeneration,
                expectedAccountId,
                target,
            )
        }

    public fun discardDraft(
        expectedContentVersion: Long,
        expectedSessionGeneration: Long,
        expectedAccountId: String?,
        expectedTarget: AppleComposerTargetSnapshot?,
    ): Boolean =
        dispatchOwnedComposer(expectedTarget) { target ->
            composerPresenter.discardDraft(
                expectedContentVersion,
                expectedSessionGeneration,
                expectedAccountId,
                target,
            )
        }

    public fun retryComposerInitialization(): Boolean = dispatchComposer(composerPresenter::retryInitialization)

    /**
     * Replaces the whole editor only when the Swift snapshot still owns this exact composer.
     *
     * Swift must freeze all expected fields from one [AppleComposerSnapshot]. Reconstructing the
     * target and checking its stable key prevents a manually assembled bridge object from weakening
     * the presenter's generation/content-version CAS.
     */
    public fun updateDraft(
        title: String?,
        raw: String,
        tags: List<String>,
        expectedContentVersion: Long,
        expectedSessionGeneration: Long,
        expectedAccountId: String?,
        expectedTarget: AppleComposerTargetSnapshot?,
    ): Boolean =
        dispatchComposer {
            val target = expectedTarget?.toDiscourseTargetOrNull()
            if (expectedTarget != null && target == null) return@dispatchComposer false
            composerPresenter.updateDraft(
                title = title,
                raw = raw,
                tags = tags.toList(),
                expectedContentVersion = expectedContentVersion,
                expectedSessionGeneration = expectedSessionGeneration,
                expectedAccountId = expectedAccountId,
                expectedTarget = target,
            )
        }

    /** Submits only while all editor-owner fields still match the captured Swift snapshot. */
    public fun submitComposer(
        expectedContentVersion: Long,
        expectedSessionGeneration: Long,
        expectedAccountId: String?,
        expectedTarget: AppleComposerTargetSnapshot?,
    ): Boolean =
        dispatchComposer {
            val target = expectedTarget?.toDiscourseTargetOrNull()
            if (expectedTarget != null && target == null) return@dispatchComposer false
            composerPresenter.submit(
                expectedContentVersion = expectedContentVersion,
                expectedSessionGeneration = expectedSessionGeneration,
                expectedAccountId = expectedAccountId,
                expectedTarget = target,
            )
        }

    /**
     * Starts the bounded in-memory upload after Swift has completed security-scoped file access.
     *
     * The bridge checks the 16 MiB policy before allocating its defensive copy. The shared request
     * then takes its own immutable snapshot, after which this temporary copy is erased.
     */
    public fun startUpload(
        fileName: String,
        contentType: String?,
        bytes: ByteArray,
        expectedContentVersion: Long,
        expectedSessionGeneration: Long,
        expectedAccountId: String?,
        expectedTarget: AppleComposerTargetSnapshot?,
    ): Boolean =
        dispatchComposer {
            if (bytes.isEmpty() || bytes.size > MAX_DISCOURSE_UPLOAD_BYTES) return@dispatchComposer false
            val target = expectedTarget?.toDiscourseTargetOrNull()
            if (expectedTarget != null && target == null) return@dispatchComposer false
            val temporaryBytes = bytes.copyOf()
            val request =
                try {
                    DiscourseUploadRequest(
                        bytes = temporaryBytes,
                        fileName = fileName,
                        contentType = contentType,
                    )
                } finally {
                    temporaryBytes.fill(0)
                }
            composerPresenter.startUpload(
                request = request,
                expectedSessionGeneration = expectedSessionGeneration,
                expectedAccountId = expectedAccountId,
                expectedTarget = target,
                expectedContentVersion = expectedContentVersion,
            )
        }

    /**
     * Copies an Objective-C data buffer in one native operation before entering the shared upload.
     *
     * Swift uses this overload after a bounded file read. It avoids millions of per-byte Objective-C
     * calls while retaining the same defensive-copy, validation, and zeroing behavior as [startUpload].
     */
    @OptIn(ExperimentalForeignApi::class)
    public fun startUploadData(
        fileName: String,
        contentType: String?,
        data: NSData,
        expectedContentVersion: Long,
        expectedSessionGeneration: Long,
        expectedAccountId: String?,
        expectedTarget: AppleComposerTargetSnapshot?,
    ): Boolean {
        if (lifecycle.value != AppleHostLifecycle.OPEN) return false
        val byteCount = data.length
        if (byteCount == 0UL || byteCount > MAX_DISCOURSE_UPLOAD_BYTES.toULong()) return false
        val copiedBytes = ByteArray(byteCount.toInt())
        try {
            copiedBytes.usePinned { pinned ->
                memcpy(pinned.addressOf(0), data.bytes, byteCount)
            }
            return startUpload(
                fileName = fileName,
                contentType = contentType,
                bytes = copiedBytes,
                expectedContentVersion = expectedContentVersion,
                expectedSessionGeneration = expectedSessionGeneration,
                expectedAccountId = expectedAccountId,
                expectedTarget = expectedTarget,
            )
        } finally {
            copiedBytes.fill(0)
        }
    }

    public fun cancelUpload(
        expectedContentVersion: Long,
        expectedSessionGeneration: Long,
        expectedAccountId: String?,
        expectedTarget: AppleComposerTargetSnapshot?,
    ): Boolean =
        dispatchOwnedComposer(expectedTarget) { target ->
            composerPresenter.cancelUpload(
                expectedContentVersion,
                expectedSessionGeneration,
                expectedAccountId,
                target,
            )
        }

    public fun retryUpload(
        expectedContentVersion: Long,
        expectedSessionGeneration: Long,
        expectedAccountId: String?,
        expectedTarget: AppleComposerTargetSnapshot?,
    ): Boolean =
        dispatchOwnedComposer(expectedTarget) { target ->
            composerPresenter.retryUpload(
                expectedContentVersion,
                expectedSessionGeneration,
                expectedAccountId,
                target,
            )
        }

    public fun synchronizeSelectedTopicActions(
        expectedTopicId: Long,
        expectedSessionGeneration: Long,
        expectedAccountId: String?,
    ): Boolean =
        dispatchComposer {
            val topic =
                forumPresenter.models.value.selectedTopic
                    ?.takeIf { it.topicId == expectedTopicId }
                    ?: return@dispatchComposer false
            composerPresenter.synchronizeTopicActions(
                topic,
                expectedSessionGeneration,
                expectedAccountId,
            )
        }

    public fun synchronizePostActions(
        postId: Long,
        expectedSessionGeneration: Long,
        expectedAccountId: String?,
    ): Boolean =
        dispatchComposer {
            val article =
                forumPresenter.models.value.selectedTopic?.articles?.firstOrNull {
                    it.discourse?.postId == postId
                } ?: return@dispatchComposer false
            composerPresenter.synchronizePostActions(
                article,
                expectedSessionGeneration,
                expectedAccountId,
            )
        }

    public fun toggleLike(
        postId: Long,
        expectedSessionGeneration: Long,
        expectedAccountId: String?,
    ): Boolean =
        dispatchComposer {
            composerPresenter.toggleLike(postId, expectedSessionGeneration, expectedAccountId)
        }

    public fun togglePostBookmark(
        postId: Long,
        expectedSessionGeneration: Long,
        expectedAccountId: String?,
    ): Boolean =
        dispatchComposer {
            composerPresenter.togglePostBookmark(postId, expectedSessionGeneration, expectedAccountId)
        }

    public fun toggleTopicBookmark(
        topicId: Long,
        expectedSessionGeneration: Long,
        expectedAccountId: String?,
    ): Boolean =
        dispatchComposer {
            composerPresenter.toggleTopicBookmark(topicId, expectedSessionGeneration, expectedAccountId)
        }

    public fun restoreSession(callback: (AppleBooleanResult) -> Unit): AppleForumObservation =
        launchBooleanOperation(callback) { loginService.restoreSession() }

    /** Persists the current bounded Cookie jar when an Apple scene moves to the background. */
    public fun checkpointSession(callback: (AppleBooleanResult) -> Unit): AppleForumObservation =
        launchBooleanOperation(callback) { sessionLifecycle.checkpoint() }

    public fun beginAuthorization(callback: (AppleAuthorizationResult) -> Unit): AppleForumObservation {
        if (lifecycle.value != AppleHostLifecycle.OPEN) {
            return dispatchImmediateAppleCallback(
                value = AppleAuthorizationResult(null, null, AppleForumOperationError.HOST_CLOSED),
                callback = callback,
            )
        }
        val job =
            operationScope.launch {
                val result =
                    try {
                        val pending = loginService.beginAuthorization()
                        AppleAuthorizationResult(
                            url = pending.url.toString(),
                            expiresAtEpochMillis = pending.expiresAtEpochMillis,
                            error = null,
                        )
                    } catch (cancellation: CancellationException) {
                        if (!currentCoroutineContext().isActive) return@launch
                        AppleAuthorizationResult(null, null, AppleForumOperationError.CANCELLED)
                    } catch (failure: Throwable) {
                        AppleAuthorizationResult(null, null, failure.toAppleOperationError())
                    }
                currentCoroutineContext().ensureActive()
                if (lifecycle.value == AppleHostLifecycle.OPEN) callback(result)
            }
        return AppleForumObservation(job)
    }

    /** Accepts the raw callback once and never retains, logs, or returns its encrypted query values. */
    public fun completeAuthorization(
        rawUri: String,
        callback: (AppleLoginResult) -> Unit,
    ): AppleForumObservation = launchLoginOperation(callback) { loginService.completeRedirect(rawUri) }

    public fun cancelAuthorization(callback: (AppleBooleanResult) -> Unit): AppleForumObservation =
        launchBooleanOperation(callback) { loginService.cancelAuthorization() }

    /** Completes the fallback restricted-WKWebView cookie handoff after Swift synchronizes cookies. */
    public fun completeWebSession(callback: (AppleLoginResult) -> Unit): AppleForumObservation =
        launchLoginOperation(callback) { webSessionLogin.complete() }

    public fun logout(
        expectedSessionGeneration: Long,
        expectedAccountId: String,
        callback: (AppleBooleanResult) -> Unit,
    ): AppleForumObservation =
        launchBooleanOperation(callback) {
            loginService.logout(expectedSessionGeneration, expectedAccountId)
        }

    /** Resolves only the currently visible, matching Cloudflare request. */
    public fun resolveManualChallenge(
        requestId: Long,
        completed: Boolean,
        callback: (AppleBooleanResult) -> Unit,
    ): AppleForumObservation =
        launchBooleanOperation(callback) {
            if (completed) {
                challengeCoordinator.completeAfterCookieConsumption(requestId)
            } else {
                challengeCoordinator.cancel(requestId)
            }
        }

    /**
     * Stops callbacks and network work, flushes the draft actor, then closes presenters, Room, and Koin.
     *
     * The returned callback cannot cancel teardown. Cleanup runs under [NonCancellable] so a scene
     * transition cannot leave credentials, requests, or the database owned by an abandoned host.
     */
    public fun close(callback: (AppleBooleanResult) -> Unit) {
        if (!lifecycle.compareAndSet(AppleHostLifecycle.OPEN, AppleHostLifecycle.CLOSING)) {
            dispatchImmediateAppleCallback(
                value =
                    AppleBooleanResult(
                        value = lifecycle.value == AppleHostLifecycle.CLOSED,
                        error = AppleForumOperationError.HOST_CLOSED,
                    ),
                callback = callback,
            )
            return
        }
        shutdownScope.launch {
            var cleanupError: AppleForumOperationError? = null

            fun recordCleanupFailure(failure: Throwable) {
                if (cleanupError == null) cleanupError = failure.toAppleOperationError()
            }
            withContext(NonCancellable) {
                try {
                    forumPresenter.setForeground(false)
                } catch (failure: Throwable) {
                    recordCleanupFailure(failure)
                }
                try {
                    operationJob.cancelAndJoin()
                } catch (failure: Throwable) {
                    recordCleanupFailure(failure)
                }
                try {
                    sessionLifecycle.checkpoint()
                } catch (failure: Throwable) {
                    recordCleanupFailure(failure)
                }
                try {
                    composerPresenter.closeAndFlush()
                } catch (failure: Throwable) {
                    recordCleanupFailure(failure)
                }
                try {
                    forumPresenter.closeAndJoin()
                } catch (failure: Throwable) {
                    recordCleanupFailure(failure)
                }
                try {
                    dependencies.close()
                } catch (failure: Throwable) {
                    recordCleanupFailure(failure)
                } finally {
                    lifecycle.value = AppleHostLifecycle.CLOSED
                }
                try {
                    callback(AppleBooleanResult(value = cleanupError == null, error = cleanupError))
                } catch (_: Throwable) {
                    // A released or faulty host callback must not keep the shutdown scope alive.
                } finally {
                    shutdownScope.cancel()
                }
            }
        }
    }

    private fun dispatchForum(action: () -> DiscourseForumAction): Boolean {
        if (lifecycle.value != AppleHostLifecycle.OPEN) return false
        return try {
            forumPresenter.dispatch(action())
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
    }

    private inline fun dispatchComposer(action: () -> Boolean): Boolean {
        if (lifecycle.value != AppleHostLifecycle.OPEN) return false
        return try {
            action()
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
    }

    private inline fun dispatchOwnedComposer(
        expectedTarget: AppleComposerTargetSnapshot?,
        action: (DiscourseComposerTarget?) -> Boolean,
    ): Boolean =
        dispatchComposer {
            val target = expectedTarget?.toDiscourseTargetOrNull()
            if (expectedTarget != null && target == null) return@dispatchComposer false
            action(target)
        }

    private fun <Source, Snapshot> observeState(
        flow: Flow<Source>,
        mapper: (Source) -> Snapshot,
        observer: (Snapshot) -> Unit,
    ): AppleForumObservation {
        if (lifecycle.value != AppleHostLifecycle.OPEN) return cancelledObservation()
        return observeAppleState(
            scope = mappingScope,
            flow = flow,
            mapper = mapper,
            observer = { snapshot ->
                if (lifecycle.value == AppleHostLifecycle.OPEN) observer(snapshot)
            },
        )
    }

    private fun launchBooleanOperation(
        callback: (AppleBooleanResult) -> Unit,
        operation: suspend () -> Boolean,
    ): AppleForumObservation {
        if (lifecycle.value != AppleHostLifecycle.OPEN) {
            return dispatchImmediateAppleCallback(
                AppleBooleanResult(false, AppleForumOperationError.HOST_CLOSED),
                callback,
            )
        }
        val job =
            operationScope.launch {
                val result =
                    try {
                        AppleBooleanResult(operation(), null)
                    } catch (cancellation: CancellationException) {
                        if (!currentCoroutineContext().isActive) return@launch
                        AppleBooleanResult(false, AppleForumOperationError.CANCELLED)
                    } catch (failure: Throwable) {
                        AppleBooleanResult(false, failure.toAppleOperationError())
                    }
                currentCoroutineContext().ensureActive()
                if (lifecycle.value == AppleHostLifecycle.OPEN) callback(result)
            }
        return AppleForumObservation(job)
    }

    private fun launchLoginOperation(
        callback: (AppleLoginResult) -> Unit,
        operation: suspend () -> DiscourseLoginResult,
    ): AppleForumObservation {
        if (lifecycle.value != AppleHostLifecycle.OPEN) {
            return dispatchImmediateAppleCallback(
                failedAppleLoginResult(AppleForumOperationError.HOST_CLOSED),
                callback,
            )
        }
        val job =
            operationScope.launch {
                val result =
                    try {
                        operation().toAppleLoginResult()
                    } catch (cancellation: CancellationException) {
                        if (!currentCoroutineContext().isActive) return@launch
                        failedAppleLoginResult(AppleForumOperationError.CANCELLED)
                    } catch (failure: Throwable) {
                        failedAppleLoginResult(failure.toAppleOperationError())
                    }
                currentCoroutineContext().ensureActive()
                if (lifecycle.value == AppleHostLifecycle.OPEN) callback(result)
            }
        return AppleForumObservation(job)
    }
}

private val DiscourseForumStateMapper: (dev.dimension.flare.data.network.discourse.forum.DiscourseForumState) -> AppleForumSnapshot =
    { it.toAppleSnapshot() }

private val DiscourseComposerStateMapper:
    (dev.dimension.flare.data.network.discourse.composer.DiscourseComposerState) -> AppleComposerSnapshot =
    { it.toAppleSnapshot() }

internal fun <Source, Snapshot> observeAppleState(
    scope: CoroutineScope,
    flow: Flow<Source>,
    mapper: (Source) -> Snapshot,
    observer: (Snapshot) -> Unit,
    callbackDispatcher: CoroutineDispatcher = Dispatchers.Main,
): AppleForumObservation {
    val job =
        scope.launch {
            flow
                .map(mapper)
                .distinctUntilChanged()
                .collect { snapshot ->
                    withContext(callbackDispatcher) {
                        currentCoroutineContext().ensureActive()
                        observer(snapshot)
                    }
                }
        }
    return AppleForumObservation(job)
}

private fun cancelledObservation(): AppleForumObservation = AppleForumObservation(Job().apply { cancel() })

/**
 * Schedules a bounded terminal callback on Main even after the host-owned operation scope is closed.
 * The lazy start plus initial yield gives Swift the returned observation before delivery, so an
 * immediately cancelled observation cannot receive a callback on the following run-loop turn.
 */
private fun <T> dispatchImmediateAppleCallback(
    value: T,
    callback: (T) -> Unit,
): AppleForumObservation {
    val job =
        CoroutineScope(Dispatchers.Main).launch(start = CoroutineStart.LAZY) {
            yield()
            currentCoroutineContext().ensureActive()
            callback(value)
        }
    job.start()
    return AppleForumObservation(job)
}

private fun createAppleForumHost(databasePath: String): AppleForumHost {
    val dependencies =
        koinApplication {
            allowOverride(true)
            modules(
                sharedModule,
                discourseModule,
                discourseAuthenticationModule,
                createAppleDiscourseHostModule(databasePath),
            )
        }
    var forumPresenter: DiscourseForumPresenter? = null
    var composerPresenter: DiscourseComposerPresenter? = null
    return try {
        val loginService = dependencies.koin.get<DiscourseLoginService>()
        val webSessionLogin = dependencies.koin.get<DiscourseWebSessionLogin>()
        val sessionLifecycle = dependencies.koin.get<DiscourseSessionLifecycle>()
        val challengeCoordinator = dependencies.koin.get<DiscourseManualChallengeCoordinator>()
        forumPresenter = dependencies.koin.get()
        composerPresenter = dependencies.koin.get()
        AppleForumHost(
            dependencies = dependencies,
            forumPresenter = forumPresenter,
            composerPresenter = composerPresenter,
            loginService = loginService,
            webSessionLogin = webSessionLogin,
            sessionLifecycle = sessionLifecycle,
            challengeCoordinator = challengeCoordinator,
        )
    } catch (failure: Throwable) {
        try {
            composerPresenter?.close()
        } catch (_: Throwable) {
            // Preserve the original initialization failure while releasing any presenter already made.
        }
        try {
            forumPresenter?.close()
        } catch (_: Throwable) {
            // Preserve the original initialization failure while releasing any presenter already made.
        }
        try {
            dependencies.close()
        } catch (_: Throwable) {
            // The fixed factory result intentionally suppresses partial-cleanup implementation text.
        }
        throw failure
    }
}

private fun createAppleDiscourseHostModule(databasePath: String): Module =
    module {
        single { createAppleFlareDoDatabase(databasePath) } onClose { database ->
            database?.close()
        }
        single<DiscourseForumCache> {
            roomDiscourseForumCache(get<FlareDoDatabase>().forumCacheEntryDao())
        }
        single<DiscourseDraftStore> {
            roomDiscourseDraftStore(get<FlareDoDatabase>().composerDraftDao())
        }
        single<DiscourseMessageBusCursorStore> {
            roomDiscourseMessageBusCursorStore(get<FlareDoDatabase>().messageBusCursorDao())
        }
        single<SecureCredentialStore> { AppleKeychainCredentialStore() }
        single { AppleDiscourseRsaPkcs1Crypto() }
        single<DiscourseRsaPkcs1KeyPairGenerator> { get<AppleDiscourseRsaPkcs1Crypto>() }
        single<DiscourseRsaPkcs1Decryptor> { get<AppleDiscourseRsaPkcs1Crypto>() }
        single<DiscourseAuthAttemptStore> {
            RoomDiscourseAuthAttemptStore(
                dao = get<FlareDoDatabase>().secureVaultReferenceDao(),
                credentialStore = get(),
            )
        }
        single<DiscourseSessionStore> {
            RoomDiscourseSessionStore(
                dao = get<FlareDoDatabase>().secureVaultReferenceDao(),
                credentialStore = get(),
                cookieValidator = get<DiscourseSessionManager>().cookieStorage,
            )
        }
        single<DiscourseWebSessionCookieBridge> { AppleDiscourseWebSessionCookieBridge() }
        single<DiscourseCloudflareChallengeHandler> {
            DiscourseManualChallengeCookieHandler(
                presenter = get<DiscourseManualChallengeCoordinator>(),
                cookieBridge = get(),
                sessionManager = get(),
            )
        }
    }

internal fun String.isValidAppleDatabasePath(): Boolean {
    if (length !in 2..MAX_APPLE_DATABASE_PATH_CHARS || !startsWith('/') || endsWith('/')) return false
    if (any { it.code < 0x20 || it.code == 0x7f }) return false
    val components = split('/').drop(1)
    if (components.size < 2) return false
    return components.all { component -> component.isNotEmpty() && component != "." && component != ".." }
}

private fun DiscourseLoginResult.toAppleLoginResult(): AppleLoginResult =
    when (this) {
        is DiscourseLoginResult.Authenticated -> {
            AppleLoginResult(
                status = AppleLoginStatus.AUTHENTICATED,
                accountId = accountId,
                username = username,
                displayName = displayName,
                error = null,
            )
        }

        DiscourseLoginResult.Stale -> {
            AppleLoginResult(AppleLoginStatus.STALE, null, null, null, null)
        }

        DiscourseLoginResult.Expired -> {
            AppleLoginResult(AppleLoginStatus.EXPIRED, null, null, null, null)
        }

        is DiscourseLoginResult.Malformed -> {
            AppleLoginResult(AppleLoginStatus.MALFORMED, null, null, null, null)
        }
    }

private fun failedAppleLoginResult(error: AppleForumOperationError): AppleLoginResult =
    AppleLoginResult(AppleLoginStatus.FAILED, null, null, null, error)

private fun Throwable.toAppleOperationError(): AppleForumOperationError =
    when (this) {
        is CancellationException -> {
            AppleForumOperationError.CANCELLED
        }

        is StaleDiscourseSessionException -> {
            AppleForumOperationError.STALE_SESSION
        }

        is AppleCredentialStoreException -> {
            AppleForumOperationError.SECURE_STORAGE
        }

        is DiscourseAuthenticationException -> {
            AppleForumOperationError.AUTHENTICATION
        }

        is DiscoursePermissionException -> {
            AppleForumOperationError.PERMISSION
        }

        is DiscourseRateLimitException -> {
            AppleForumOperationError.RATE_LIMITED
        }

        is DiscourseCloudflareChallengeException -> {
            AppleForumOperationError.CHALLENGE_REQUIRED
        }

        is DiscourseNetworkException -> {
            AppleForumOperationError.NETWORK
        }

        is DiscourseServerException -> {
            AppleForumOperationError.SERVER
        }

        is DiscourseSerializationException -> {
            AppleForumOperationError.INVALID_RESPONSE
        }

        is DiscourseValidationException -> {
            AppleForumOperationError.INVALID_INPUT
        }

        is DiscourseCsrfException -> {
            AppleForumOperationError.AUTHENTICATION
        }

        is DiscourseHttpException -> {
            when (statusCode) {
                401 -> AppleForumOperationError.AUTHENTICATION
                403 -> AppleForumOperationError.PERMISSION
                429 -> AppleForumOperationError.RATE_LIMITED
                in 500..599 -> AppleForumOperationError.SERVER
                else -> AppleForumOperationError.INVALID_RESPONSE
            }
        }

        is DiscourseAuthExchangeException -> {
            when (reason) {
                DiscourseAuthExchangeFailure.ActiveSession -> AppleForumOperationError.AUTHENTICATION

                DiscourseAuthExchangeFailure.ChallengeHandler -> AppleForumOperationError.CHALLENGE_REQUIRED

                DiscourseAuthExchangeFailure.InvalidSecret,
                DiscourseAuthExchangeFailure.Csrf,
                DiscourseAuthExchangeFailure.OtpResponse,
                DiscourseAuthExchangeFailure.SessionCookie,
                DiscourseAuthExchangeFailure.RevokeResponse,
                DiscourseAuthExchangeFailure.Identity,
                -> AppleForumOperationError.INVALID_RESPONSE
            }
        }

        is IllegalArgumentException -> {
            AppleForumOperationError.INVALID_INPUT
        }

        else -> {
            AppleForumOperationError.INTERNAL
        }
    }
