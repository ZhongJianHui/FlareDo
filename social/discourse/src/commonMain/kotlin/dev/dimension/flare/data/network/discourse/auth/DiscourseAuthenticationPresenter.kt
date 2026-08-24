package dev.dimension.flare.data.network.discourse.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.dimension.flare.data.network.discourse.DISCOURSE_ORIGIN
import dev.dimension.flare.data.network.discourse.error.DiscourseAuthenticationException
import dev.dimension.flare.data.network.discourse.error.DiscourseCloudflareChallengeException
import dev.dimension.flare.data.network.discourse.error.DiscourseException
import dev.dimension.flare.data.network.discourse.error.DiscourseNetworkException
import dev.dimension.flare.data.network.discourse.error.DiscoursePermissionException
import dev.dimension.flare.data.network.discourse.error.DiscourseRateLimitException
import dev.dimension.flare.data.network.discourse.error.DiscourseServerException
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionState
import dev.dimension.flare.ui.presenter.PresenterBase
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Coarse authentication failures that are safe to render and never retain protocol secrets. */
public enum class DiscourseAuthenticationFailureKind {
    Authentication,
    Permission,
    RateLimited,
    ChallengeRequired,
    Network,
    Server,
    InvalidResponse,
    BrowserUnavailable,
}

/** Purpose of the short-lived, fixed-origin browser owned by a platform host. */
public enum class DiscourseRestrictedBrowserMode {
    FallbackLogin,
    ManualChallenge,
}

/** One external authorization URL awaiting an explicit system-browser launch acknowledgement. */
public data class DiscourseExternalAuthorization(
    public val requestId: Long,
    public val url: String,
    public val expiresAtEpochMillis: Long,
) {
    init {
        require(requestId > 0L) { "An external authorization request id must be positive" }
        require(expiresAtEpochMillis >= 0L) { "Authorization expiry cannot be negative" }
        require(DiscourseBrowserUrlPolicy.isExternalAuthorizationUrl(url)) {
            "External authorization must use the fixed Linux.do authorization endpoint"
        }
    }

    /** Prevents nonce and public-key query values from leaking through state or test diagnostics. */
    override fun toString(): String =
        "DiscourseExternalAuthorization(" +
            "requestId=$requestId, url=<redacted>, expiresAtEpochMillis=$expiresAtEpochMillis)"
}

/**
 * Shared allowlist for every URL handed to a system browser or restricted embedded browser.
 *
 * Parsing is intentionally repeated at the platform boundary. A backend-created URL is not treated
 * as trusted forever: exact scheme, host, absent userinfo, and an omitted explicit port protect a
 * future refactor from turning a presentation value into an open redirect or credential sink.
 */
public object DiscourseBrowserUrlPolicy {
    /** Accepts a top-level Linux.do HTTPS URL only when its authority is exactly portless Linux.do. */
    public fun isAllowedTopLevelUrl(rawUrl: String): Boolean {
        if (
            rawUrl.isBlank() ||
            rawUrl.length > MAX_BROWSER_URL_LENGTH ||
            rawUrl.any { it == '\u0000' || it == '\r' || it == '\n' }
        ) {
            return false
        }
        val parsed =
            try {
                Url(rawUrl)
            } catch (_: IllegalArgumentException) {
                return false
            }
        return parsed.protocol == URLProtocol.HTTPS &&
            parsed.host == LINUX_DO_HOST &&
            parsed.specifiedPort == 0 &&
            parsed.user.isNullOrEmpty() &&
            parsed.password.isNullOrEmpty()
    }

    /** System authorization may open only Discourse's fixed User API Key creation path. */
    public fun isExternalAuthorizationUrl(rawUrl: String): Boolean {
        if (!isAllowedTopLevelUrl(rawUrl)) return false
        val parsed =
            try {
                Url(rawUrl)
            } catch (_: IllegalArgumentException) {
                return false
            }
        return parsed.encodedPath == USER_API_KEY_PATH && parsed.fragment.isEmpty()
    }
}

/** Browser request containing no Cookie, callback payload, response detail, or arbitrary URL. */
public data class DiscourseRestrictedBrowserRequest(
    public val requestId: Long,
    public val mode: DiscourseRestrictedBrowserMode,
    public val initialUrl: String,
) {
    init {
        require(requestId > 0L) { "A restricted browser request id must be positive" }
        require(
            initialUrl == DISCOURSE_ORIGIN ||
                (mode == DiscourseRestrictedBrowserMode.FallbackLogin && initialUrl == "$DISCOURSE_ORIGIN/login"),
        ) { "A restricted browser request must use an allowlisted Linux.do URL" }
    }
}

/** Immutable state consumed by Android and desktop Compose shells. */
public data class DiscourseAuthenticationState(
    public val isBusy: Boolean = false,
    public val externalAuthorization: DiscourseExternalAuthorization? = null,
    public val restrictedBrowser: DiscourseRestrictedBrowserRequest? = null,
    /** True only while the current browser request's one-use Cookie handoff is being committed. */
    public val restrictedBrowserHandoffInProgress: Boolean = false,
    public val failure: DiscourseAuthenticationFailureKind? = null,
) {
    init {
        require(!restrictedBrowserHandoffInProgress || restrictedBrowser != null) {
            "A restricted browser handoff must remain bound to its visible request"
        }
    }
}

/** UI commands accepted by [DiscourseAuthenticationPresenter]. */
public sealed interface DiscourseAuthenticationAction {
    public data object BeginAuthorization : DiscourseAuthenticationAction

    public data object BeginFallbackLogin : DiscourseAuthenticationAction

    public data class AuthorizationOpened(
        public val requestId: Long,
    ) : DiscourseAuthenticationAction

    public data class AuthorizationLaunchFailed(
        public val requestId: Long,
    ) : DiscourseAuthenticationAction

    /** The platform copied only allowlisted fixed-origin Cookies into its one-use bridge. */
    public data class CompleteRestrictedBrowser(
        public val requestId: Long,
        public val mode: DiscourseRestrictedBrowserMode,
    ) : DiscourseRestrictedBrowserTerminalAction {
        override val receipt: DiscourseRestrictedBrowserTerminalReceipt =
            DiscourseRestrictedBrowserTerminalReceipt()
    }

    public data class CancelRestrictedBrowser(
        public val requestId: Long,
        public val mode: DiscourseRestrictedBrowserMode,
    ) : DiscourseRestrictedBrowserTerminalAction {
        override val receipt: DiscourseRestrictedBrowserTerminalReceipt =
            DiscourseRestrictedBrowserTerminalReceipt()
    }

    public data class RestrictedBrowserFailed(
        public val requestId: Long,
        public val mode: DiscourseRestrictedBrowserMode,
    ) : DiscourseRestrictedBrowserTerminalAction {
        override val receipt: DiscourseRestrictedBrowserTerminalReceipt =
            DiscourseRestrictedBrowserTerminalReceipt()
    }

    public data object Logout : DiscourseAuthenticationAction

    public data object DismissFailure : DiscourseAuthenticationAction
}

/**
 * A browser terminal command whose ownership must be confirmed by the authentication actor.
 *
 * Successfully adding one of these commands to the bounded Channel is only transport acceptance.
 * Platforms must await [receipt] before retaining their local handoff lock or leaving one-use
 * Cookie cleanup to the presenter.
 */
public sealed interface DiscourseRestrictedBrowserTerminalAction : DiscourseAuthenticationAction {
    public val receipt: DiscourseRestrictedBrowserTerminalReceipt
}

/** One-use actor acknowledgement for a restricted-browser terminal command. */
public class DiscourseRestrictedBrowserTerminalReceipt internal constructor() {
    private val resolution = CompletableDeferred<Boolean>()
    private val resolutionMutex = Mutex()
    private val state = MutableStateFlow(TerminalReceiptState.Pending)

    /** True only after the actor has irreversibly accepted this exact terminal command. */
    public val ownershipTransferred: Boolean
        get() = state.value == TerminalReceiptState.Owned

    /**
     * Waits for semantic ownership for at most [timeoutMillis].
     *
     * Timeout and caller cancellation expire an untouched command under the same gate used by the
     * actor. Cancellation is never converted into a normal rejection: after fail-closing the
     * receipt in [NonCancellable], the original [CancellationException] is rethrown unchanged.
     */
    public suspend fun awaitOwnership(timeoutMillis: Long): Boolean {
        require(timeoutMillis > 0L) { "A restricted browser receipt timeout must be positive" }
        try {
            withTimeoutOrNull(timeoutMillis) { resolution.await() }?.let { return it }
            return expireAndReadOwnership()
        } catch (cancellation: CancellationException) {
            // CompletableDeferred.await has prompt cancellation: it may throw even if an actor
            // concurrently resolved true. Waiting for the shared gate gives the platform a stable
            // ownership value before it decides whether local Cookie cleanup is still permitted.
            withContext(NonCancellable) { expireAndReadOwnership() }
            throw cancellation
        }
    }

    /** Runs [accept] only if neither timeout nor cancellation has already expired this command. */
    internal suspend fun resolve(accept: () -> Boolean): Boolean {
        var accepted = false
        resolutionMutex.withLock {
            if (!state.compareAndSet(TerminalReceiptState.Pending, TerminalReceiptState.Claiming)) {
                accepted = state.value == TerminalReceiptState.Owned
                return@withLock
            }
            try {
                accepted = accept()
                state.value =
                    if (accepted) TerminalReceiptState.Owned else TerminalReceiptState.Rejected
                resolution.complete(accepted)
            } catch (failure: Throwable) {
                state.value = TerminalReceiptState.Rejected
                resolution.complete(false)
                throw failure
            }
        }
        return accepted
    }

    /** Failed sends and discarded queued commands have never reached the actor ownership gate. */
    internal fun reject() {
        if (state.compareAndSet(TerminalReceiptState.Pending, TerminalReceiptState.Rejected)) {
            resolution.complete(false)
        }
    }

    private suspend fun expireAndReadOwnership(): Boolean =
        resolutionMutex.withLock {
            if (state.compareAndSet(TerminalReceiptState.Pending, TerminalReceiptState.Rejected)) {
                resolution.complete(false)
            }
            state.value == TerminalReceiptState.Owned
        }
}

/**
 * One-use acknowledgement that a raw redirect crossed the presenter's actor boundary.
 *
 * Transport hosts must not acknowledge a sensitive callback merely because it fit in the bounded
 * Channel. [awaitAcceptance] returns true only after the session-transition child actually starts
 * and wins its atomic claim against timeout and presenter close. A timeout expires an unclaimed
 * command, preventing a late child from running it after the sender has already decided to retry.
 */
public class DiscourseRedirectReceipt internal constructor() {
    private val resolution = CompletableDeferred<Boolean>()
    private val resolutionMutex = Mutex()

    /** Waits for child ownership for at most [timeoutMillis], expiring untouched work on timeout. */
    public suspend fun awaitAcceptance(timeoutMillis: Long): Boolean {
        require(timeoutMillis > 0L) { "A redirect receipt timeout must be positive" }
        withTimeoutOrNull(timeoutMillis) { resolution.await() }?.let { return it }
        resolutionMutex.withLock { resolution.complete(false) }
        return resolution.await()
    }

    /** Resolves under the same gate as timeout expiry, so an expired command cannot launch later. */
    internal suspend fun resolve(accept: () -> Boolean): Boolean {
        var accepted = false
        resolutionMutex.withLock {
            if (resolution.isCompleted) return@withLock
            try {
                accepted = accept()
                resolution.complete(accepted)
            } catch (failure: Throwable) {
                resolution.complete(false)
                throw failure
            }
        }
        return accepted
    }

    /** Channel cancellation and failed sends cannot suspend, but only ever reject queued work. */
    internal fun reject() {
        resolution.complete(false)
    }
}

/**
 * Shared authentication actor for Compose hosts.
 *
 * The actor deliberately exposes an external URL only until its host acknowledges the launch. Raw
 * redirect URIs enter through [completeRedirect] and go directly to the one-use cryptographic
 * processor; they are never copied into presentation state or included in an event's `toString`.
 * Login, fallback completion, redirect completion, and logout are serialized, while a manual
 * Cloudflare resolution gets a separate child job so it can resume the suspended login operation.
 */
public class DiscourseAuthenticationPresenter private constructor(
    private val backend: DiscourseAuthenticationBackend,
    dispatcher: CoroutineDispatcher,
) : PresenterBase<DiscourseAuthenticationState>(dispatcher) {
    internal constructor(
        loginService: DiscourseLoginService,
        webSessionLogin: DiscourseWebSessionLogin,
        cookieBridge: DiscourseWebSessionCookieBridge,
        sessionManager: DiscourseSessionManager,
        challengeCoordinator: DiscourseManualChallengeCoordinator,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(
        backend =
            DefaultDiscourseAuthenticationBackend(
                loginService = loginService,
                webSessionLogin = webSessionLogin,
                cookieBridge = cookieBridge,
                sessionManager = sessionManager,
                challengeCoordinator = challengeCoordinator,
            ),
        dispatcher = dispatcher,
    )

    internal constructor(
        backend: DiscourseAuthenticationBackend,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        @Suppress("UNUSED_PARAMETER") testMarker: Unit,
    ) : this(backend, dispatcher)

    private val actions =
        Channel<QueuedAuthenticationCommand>(
            capacity = AUTH_ACTION_CAPACITY,
            onUndeliveredElement = { command -> command.rejectReceipt() },
        )
    private val actorCompleted = CompletableDeferred<Unit>()
    private val actorLifecycle = MutableStateFlow(AuthenticationActorLifecycle.NotStarted)
    private val redirectClaimAdmission = MutableStateFlow(RedirectClaimAdmission.Open)

    override fun onClose() {
        closeRedirectClaimAdmission()
        // Channel.cancel discards queued raw redirects immediately, including before models starts.
        actions.cancel()
        if (actorLifecycle.compareAndSet(AuthenticationActorLifecycle.NotStarted, AuthenticationActorLifecycle.Closed)) {
            actorCompleted.complete(Unit)
        }
    }

    /** Returns false after close, on queue saturation, or when logout has no exact session owner. */
    public fun dispatch(action: DiscourseAuthenticationAction): Boolean {
        val queued =
            if (action == DiscourseAuthenticationAction.Logout) {
                val owner =
                    (backend.sessionState.value as? DiscourseSessionState.Authenticated)
                        ?.let { AuthenticationOwner(it.generation, it.accountId) }
                        ?: return false
                QueuedAuthenticationCommand.Ui(action, owner)
            } else {
                QueuedAuthenticationCommand.Ui(action)
            }
        val result = actions.trySend(queued)
        if (result.isFailure) action.rejectTerminalReceipt()
        return result.isSuccess
    }

    /**
     * Queues a platform-delivered callback without retaining or logging its encrypted query values.
     *
     * Hosts must first enforce their scheme/host/action/component policy. These bounded structural
     * checks only keep accidental command-line input from occupying the actor with an arbitrary blob.
     */
    public fun completeRedirect(rawUri: String): Boolean = completeRedirectWithReceipt(rawUri) != null

    /** Enqueues a raw callback and exposes actor-level acceptance to acknowledgement transports. */
    public fun completeRedirectWithReceipt(rawUri: String): DiscourseRedirectReceipt? {
        if (
            rawUri.isBlank() ||
            rawUri.length > MAX_AUTH_REDIRECT_LENGTH ||
            rawUri.any { it == '\u0000' || it == '\r' || it == '\n' }
        ) {
            return null
        }
        val receipt = DiscourseRedirectReceipt()
        val result = actions.trySend(QueuedAuthenticationCommand.RawRedirect(rawUri, receipt))
        if (result.isFailure) {
            receipt.reject()
            return null
        }
        return receipt
    }

    @Composable
    override fun body(): DiscourseAuthenticationState {
        var state by remember { mutableStateOf(DiscourseAuthenticationState()) }
        LaunchedEffect(backend) {
            if (!actorLifecycle.compareAndSet(AuthenticationActorLifecycle.NotStarted, AuthenticationActorLifecycle.Running)) {
                return@LaunchedEffect
            }
            try {
                runActor(state = { state }, setState = { state = it })
            } finally {
                actorLifecycle.value = AuthenticationActorLifecycle.Finished
                actorCompleted.complete(Unit)
            }
        }
        return state
    }

    /** Cancels the actor and waits for every accepted request's non-cancellable cleanup to finish. */
    public suspend fun closeAndJoin() {
        withContext(NonCancellable) {
            close()
            actorCompleted.await()
        }
    }

    private fun closeRedirectClaimAdmission() {
        while (true) {
            val current = redirectClaimAdmission.value
            if (current == RedirectClaimAdmission.Closed) return
            if (redirectClaimAdmission.compareAndSet(current, RedirectClaimAdmission.Closed)) return
        }
    }

    /**
     * Linearizes a running child against both receipt timeout and presenter close.
     *
     * The child, rather than the actor that merely called `launch`, owns this claim. Closing changes
     * the admission state before PresenterBase cancels its scope, while receipt resolution shares the
     * timeout mutex. Whichever state transition wins is therefore final for this one-use callback.
     */
    private suspend fun claimRedirectOperation(receipt: DiscourseRedirectReceipt): Boolean {
        if (!redirectClaimAdmission.compareAndSet(RedirectClaimAdmission.Open, RedirectClaimAdmission.Claiming)) {
            receipt.reject()
            return false
        }
        return try {
            val operationContext = currentCoroutineContext()
            receipt.resolve {
                operationContext[Job]?.isActive == true &&
                    redirectClaimAdmission.value == RedirectClaimAdmission.Claiming
            }
        } finally {
            redirectClaimAdmission.compareAndSet(RedirectClaimAdmission.Claiming, RedirectClaimAdmission.Open)
        }
    }

    private suspend fun runActor(
        state: () -> DiscourseAuthenticationState,
        setState: (DiscourseAuthenticationState) -> Unit,
    ) {
        try {
            runActorLoop(state, setState)
        } finally {
            // A backend CancellationException may terminate the actor independently of close().
            // Closing the bounded queue makes every later dispatch fail instead of accepting work
            // that has no consumer, and immediately discards any queued raw redirect material.
            actions.cancel()
        }
    }

    private suspend fun runActorLoop(
        state: () -> DiscourseAuthenticationState,
        setState: (DiscourseAuthenticationState) -> Unit,
    ): Unit =
        coroutineScope {
            val events = Channel<AuthenticationEvent>(capacity = AUTH_EVENT_CAPACITY)
            var operationJob: Job? = null
            var challengeJob: Job? = null
            var nextOperationId = 1L
            var nextPresentationId = 1L
            var activeOperationId: Long? = null
            var activeOperationKind: AuthenticationOperationKind? = null
            var activeRestrictedBrowserOperation: RestrictedBrowserOperation? = null
            var observedGeneration = backend.sessionState.value.generation

            fun update(transform: (DiscourseAuthenticationState) -> DiscourseAuthenticationState) {
                setState(transform(state()))
            }

            fun nextOperation(): Long {
                check(nextOperationId > 0L) { "Authentication operation id space is exhausted" }
                val value = nextOperationId
                nextOperationId = if (value == Long.MAX_VALUE) 0L else value + 1L
                return value
            }

            fun nextPresentation(): Long {
                check(nextPresentationId > 0L) { "Authentication presentation id space is exhausted" }
                val value = nextPresentationId
                nextPresentationId = if (value == Long.MAX_VALUE) 0L else value + 1L
                return value
            }

            fun launchOperation(
                kind: AuthenticationOperationKind = AuthenticationOperationKind.Authentication,
                restrictedBrowserRequest: DiscourseRestrictedBrowserRequest? = null,
                redirectReceipt: DiscourseRedirectReceipt? = null,
                block: suspend () -> AuthenticationSuccess,
            ): Boolean {
                if (operationJob?.isActive == true) {
                    redirectReceipt?.reject()
                    return false
                }
                require(
                    restrictedBrowserRequest == null ||
                        kind == AuthenticationOperationKind.FallbackLogin,
                ) { "Only fallback login operations may bind a restricted browser request" }
                val operationId = nextOperation()
                val startGeneration = backend.sessionState.value.generation
                activeOperationId = operationId
                activeOperationKind = kind
                activeRestrictedBrowserOperation =
                    restrictedBrowserRequest?.let { request ->
                        RestrictedBrowserOperation(
                            operationId = operationId,
                            requestId = request.requestId,
                            mode = request.mode,
                        )
                    }
                update { it.copy(isBusy = true, failure = null) }
                val launchedOperation =
                    launch {
                        if (redirectReceipt != null && !claimRedirectOperation(redirectReceipt)) {
                            // A live actor must always reduce this terminal state. Suspending on a
                            // temporarily full result buffer is safe; presenter close cancels both
                            // this child and the actor, where no presentation cleanup is required.
                            events.send(
                                AuthenticationEvent.OperationCancelled(
                                    operationId = operationId,
                                    startGeneration = startGeneration,
                                ),
                            )
                            return@launch
                        }
                        try {
                            events.send(
                                AuthenticationEvent.OperationSucceeded(
                                    operationId = operationId,
                                    startGeneration = startGeneration,
                                    result = block(),
                                ),
                            )
                        } catch (cancellation: CancellationException) {
                            events.trySend(
                                AuthenticationEvent.OperationCancelled(
                                    operationId = operationId,
                                    startGeneration = startGeneration,
                                ),
                            )
                            throw cancellation
                        } catch (failure: Throwable) {
                            events.send(
                                AuthenticationEvent.OperationFailed(
                                    operationId = operationId,
                                    startGeneration = startGeneration,
                                    failure = failure.toAuthenticationFailure(),
                                ),
                            )
                        }
                    }
                redirectReceipt?.let { receipt ->
                    // DEFAULT launch may be cancelled before its block executes. In that case the
                    // child never reaches claimRedirectOperation, so completion must reject the
                    // still-pending receipt instead of letting the transport time out ambiguously.
                    launchedOperation.invokeOnCompletion { receipt.reject() }
                }
                operationJob = launchedOperation
                return true
            }

            suspend fun cancelRestrictedBrowser(request: DiscourseRestrictedBrowserRequest) =
                withContext(NonCancellable) {
                    val boundOperation = activeRestrictedBrowserOperation
                    if (
                        boundOperation?.matches(request) == true &&
                        boundOperation.operationId == activeOperationId
                    ) {
                        operationJob?.cancelAndJoin()
                        operationJob = null
                        activeOperationId = null
                        activeOperationKind = null
                        activeRestrictedBrowserOperation = null
                    }
                    challengeJob?.cancelAndJoin()
                    challengeJob = null
                    // Receipt acceptance transfers terminal cleanup to the actor. Keep the entire
                    // transition non-cancellable so presenter close cannot strand that ownership.
                    try {
                        when (request.mode) {
                            DiscourseRestrictedBrowserMode.FallbackLogin -> {
                                backend.clearBrowserCookies()
                            }

                            DiscourseRestrictedBrowserMode.ManualChallenge -> {
                                backend.cancelManualChallenge(request.requestId)
                            }
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        // The browser is already closed and its private profile fails closed.
                    }
                }

            launch {
                backend.sessionState.collect { events.send(AuthenticationEvent.SessionChanged(it)) }
            }
            launch {
                backend.manualChallenge.collect { events.send(AuthenticationEvent.ManualChallengeChanged(it)) }
            }

            while (true) {
                select<Unit> {
                    actions.onReceive { queued ->
                        when (queued) {
                            is QueuedAuthenticationCommand.RawRedirect -> {
                                launchOperation(
                                    kind = AuthenticationOperationKind.SessionTransition,
                                    redirectReceipt = queued.receipt,
                                ) {
                                    when (backend.completeRedirect(queued.rawUri)) {
                                        is DiscourseLoginResult.Authenticated -> AuthenticationSuccess.Authenticated

                                        DiscourseLoginResult.Expired,
                                        DiscourseLoginResult.Stale,
                                        is DiscourseLoginResult.Malformed,
                                        -> AuthenticationSuccess.RedirectRejected
                                    }
                                }
                            }

                            is QueuedAuthenticationCommand.Ui -> {
                                when (val action = queued.action) {
                                    DiscourseAuthenticationAction.BeginAuthorization -> {
                                        if (backend.sessionState.value is DiscourseSessionState.Guest) {
                                            val presentationId = nextPresentation()
                                            launchOperation {
                                                val pending = backend.beginAuthorization()
                                                AuthenticationSuccess.AuthorizationReady(
                                                    DiscourseExternalAuthorization(
                                                        requestId = presentationId,
                                                        url = pending.url.toString(),
                                                        expiresAtEpochMillis = pending.expiresAtEpochMillis,
                                                    ),
                                                )
                                            }
                                        }
                                    }

                                    DiscourseAuthenticationAction.BeginFallbackLogin -> {
                                        if (backend.sessionState.value is DiscourseSessionState.Guest) {
                                            val presentationId = nextPresentation()
                                            launchOperation {
                                                backend.cancelAuthorization()
                                                backend.clearBrowserCookies()
                                                AuthenticationSuccess.FallbackReady(
                                                    DiscourseRestrictedBrowserRequest(
                                                        requestId = presentationId,
                                                        mode = DiscourseRestrictedBrowserMode.FallbackLogin,
                                                        initialUrl = "$DISCOURSE_ORIGIN/login",
                                                    ),
                                                )
                                            }
                                        }
                                    }

                                    is DiscourseAuthenticationAction.AuthorizationOpened -> {
                                        update { current ->
                                            if (current.externalAuthorization?.requestId == action.requestId) {
                                                current.copy(externalAuthorization = null, failure = null)
                                            } else {
                                                current
                                            }
                                        }
                                    }

                                    is DiscourseAuthenticationAction.AuthorizationLaunchFailed -> {
                                        val current = state().externalAuthorization
                                        if (current?.requestId == action.requestId) {
                                            launchOperation {
                                                backend.cancelAuthorization()
                                                AuthenticationSuccess.AuthorizationCancelled
                                            }
                                            update {
                                                it.copy(
                                                    externalAuthorization = null,
                                                    failure = DiscourseAuthenticationFailureKind.BrowserUnavailable,
                                                )
                                            }
                                        }
                                    }

                                    is DiscourseAuthenticationAction.CompleteRestrictedBrowser -> {
                                        val actorJob = currentCoroutineContext()[Job]
                                        try {
                                            action.receipt.resolve {
                                                val current = state()
                                                val request = current.restrictedBrowser
                                                if (
                                                    actorJob?.isActive != true ||
                                                    request?.matches(action.requestId, action.mode) != true ||
                                                    current.restrictedBrowserHandoffInProgress
                                                ) {
                                                    false
                                                } else {
                                                    when (action.mode) {
                                                        DiscourseRestrictedBrowserMode.FallbackLogin -> {
                                                            val launched =
                                                                launchOperation(
                                                                    kind = AuthenticationOperationKind.FallbackLogin,
                                                                    restrictedBrowserRequest = request,
                                                                ) {
                                                                    backend.completeWebSession()
                                                                    AuthenticationSuccess.Authenticated
                                                                }
                                                            if (launched) {
                                                                update {
                                                                    it.copy(restrictedBrowserHandoffInProgress = true)
                                                                }
                                                            }
                                                            launched
                                                        }

                                                        DiscourseRestrictedBrowserMode.ManualChallenge -> {
                                                            if (challengeJob?.isActive == true) {
                                                                false
                                                            } else {
                                                                update {
                                                                    it.copy(
                                                                        isBusy = true,
                                                                        restrictedBrowserHandoffInProgress = true,
                                                                        failure = null,
                                                                    )
                                                                }
                                                                challengeJob =
                                                                    launch {
                                                                        try {
                                                                            val completed =
                                                                                backend
                                                                                    .completeManualChallengeAfterCookieConsumption(
                                                                                        action.requestId,
                                                                                    )
                                                                            events.send(
                                                                                AuthenticationEvent.ChallengeResolved(
                                                                                    requestId = action.requestId,
                                                                                    completed = completed,
                                                                                    failure = null,
                                                                                ),
                                                                            )
                                                                        } catch (cancellation: CancellationException) {
                                                                            events.trySend(
                                                                                AuthenticationEvent.ChallengeCancelled(
                                                                                    action.requestId,
                                                                                ),
                                                                            )
                                                                            throw cancellation
                                                                        } catch (failure: Throwable) {
                                                                            events.send(
                                                                                AuthenticationEvent.ChallengeResolved(
                                                                                    requestId = action.requestId,
                                                                                    completed = false,
                                                                                    failure =
                                                                                        failure.toAuthenticationFailure(),
                                                                                ),
                                                                            )
                                                                        }
                                                                    }
                                                                true
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        } finally {
                                            // Actor cancellation before resolution must not leave a
                                            // platform waiting until its defensive timeout.
                                            action.receipt.reject()
                                        }
                                    }

                                    is DiscourseAuthenticationAction.CancelRestrictedBrowser -> {
                                        val request = state().restrictedBrowser
                                        val challengeCommitInProgress =
                                            request?.mode == DiscourseRestrictedBrowserMode.ManualChallenge &&
                                                challengeJob?.isActive == true
                                        val actorJob = currentCoroutineContext()[Job]
                                        try {
                                            val accepted =
                                                action.receipt.resolve {
                                                    if (
                                                        actorJob?.isActive != true ||
                                                        request?.matches(action.requestId, action.mode) != true ||
                                                        challengeCommitInProgress
                                                    ) {
                                                        false
                                                    } else {
                                                        update {
                                                            it.copy(
                                                                restrictedBrowser = null,
                                                                restrictedBrowserHandoffInProgress = false,
                                                                isBusy = operationJob?.isActive == true,
                                                            )
                                                        }
                                                        true
                                                    }
                                                }
                                            if (accepted && request != null) {
                                                cancelRestrictedBrowser(request)
                                                update { it.copy(isBusy = operationJob?.isActive == true) }
                                            }
                                        } finally {
                                            action.receipt.reject()
                                        }
                                    }

                                    is DiscourseAuthenticationAction.RestrictedBrowserFailed -> {
                                        val request = state().restrictedBrowser
                                        val challengeCommitInProgress =
                                            request?.mode == DiscourseRestrictedBrowserMode.ManualChallenge &&
                                                challengeJob?.isActive == true
                                        val actorJob = currentCoroutineContext()[Job]
                                        try {
                                            val accepted =
                                                action.receipt.resolve {
                                                    if (
                                                        actorJob?.isActive != true ||
                                                        request?.matches(action.requestId, action.mode) != true ||
                                                        challengeCommitInProgress
                                                    ) {
                                                        false
                                                    } else {
                                                        update {
                                                            it.copy(
                                                                restrictedBrowser = null,
                                                                restrictedBrowserHandoffInProgress = false,
                                                                isBusy = operationJob?.isActive == true,
                                                                failure =
                                                                    DiscourseAuthenticationFailureKind.BrowserUnavailable,
                                                            )
                                                        }
                                                        true
                                                    }
                                                }
                                            if (accepted && request != null) {
                                                cancelRestrictedBrowser(request)
                                                update { it.copy(isBusy = operationJob?.isActive == true) }
                                            }
                                        } finally {
                                            action.receipt.reject()
                                        }
                                    }

                                    DiscourseAuthenticationAction.Logout -> {
                                        val owner = queued.owner
                                        if (owner != null) {
                                            // Logout is never dropped behind an accepted login command. Joining
                                            // first also prevents a cancelled operation from publishing a late
                                            // success event after the pinned owner CAS begins.
                                            operationJob?.cancelAndJoin()
                                            challengeJob?.cancelAndJoin()
                                            operationJob = null
                                            challengeJob = null
                                            activeOperationId = null
                                            activeOperationKind = null
                                            activeRestrictedBrowserOperation = null
                                            launchOperation(AuthenticationOperationKind.Logout) {
                                                backend.logout(owner.generation, owner.accountId)
                                                AuthenticationSuccess.LoggedOut
                                            }
                                        }
                                    }

                                    DiscourseAuthenticationAction.DismissFailure -> {
                                        update { it.copy(failure = null) }
                                    }
                                }
                            }
                        }
                    }

                    events.onReceive { event ->
                        when (event) {
                            is AuthenticationEvent.OperationSucceeded -> {
                                if (event.operationId != activeOperationId) return@onReceive
                                activeOperationId = null
                                activeOperationKind = null
                                activeRestrictedBrowserOperation = null
                                operationJob = null
                                val generationIsCurrent =
                                    event.startGeneration == backend.sessionState.value.generation
                                if (!generationIsCurrent && event.result.requiresOriginalGeneration) {
                                    update {
                                        DiscourseAuthenticationState(
                                            isBusy = challengeJob?.isActive == true,
                                        )
                                    }
                                    return@onReceive
                                }
                                when (val result = event.result) {
                                    is AuthenticationSuccess.AuthorizationReady -> {
                                        update {
                                            it.copy(
                                                isBusy = challengeJob?.isActive == true,
                                                externalAuthorization = result.authorization,
                                                failure = null,
                                            )
                                        }
                                    }

                                    is AuthenticationSuccess.FallbackReady -> {
                                        update {
                                            it.copy(
                                                isBusy = challengeJob?.isActive == true,
                                                restrictedBrowser = result.request,
                                                restrictedBrowserHandoffInProgress = false,
                                                failure = null,
                                            )
                                        }
                                    }

                                    AuthenticationSuccess.Authenticated,
                                    AuthenticationSuccess.LoggedOut,
                                    -> {
                                        update {
                                            DiscourseAuthenticationState(
                                                isBusy = challengeJob?.isActive == true,
                                            )
                                        }
                                    }

                                    AuthenticationSuccess.AuthorizationCancelled -> {
                                        update { current ->
                                            current.copy(isBusy = challengeJob?.isActive == true)
                                        }
                                    }

                                    AuthenticationSuccess.RedirectRejected -> {
                                        update {
                                            it.copy(
                                                isBusy = challengeJob?.isActive == true,
                                                failure = DiscourseAuthenticationFailureKind.Authentication,
                                            )
                                        }
                                    }
                                }
                            }

                            is AuthenticationEvent.OperationFailed -> {
                                if (event.operationId != activeOperationId) return@onReceive
                                activeOperationId = null
                                activeOperationKind = null
                                activeRestrictedBrowserOperation = null
                                operationJob = null
                                if (event.startGeneration != backend.sessionState.value.generation) {
                                    update {
                                        DiscourseAuthenticationState(
                                            isBusy = challengeJob?.isActive == true,
                                        )
                                    }
                                    return@onReceive
                                }
                                update {
                                    it.copy(
                                        isBusy = challengeJob?.isActive == true,
                                        externalAuthorization = null,
                                        restrictedBrowser = null,
                                        restrictedBrowserHandoffInProgress = false,
                                        failure = event.failure,
                                    )
                                }
                            }

                            is AuthenticationEvent.OperationCancelled -> {
                                if (event.operationId != activeOperationId) return@onReceive
                                activeOperationId = null
                                activeOperationKind = null
                                activeRestrictedBrowserOperation = null
                                operationJob = null
                                update {
                                    DiscourseAuthenticationState(
                                        isBusy = challengeJob?.isActive == true,
                                    )
                                }
                            }

                            is AuthenticationEvent.SessionChanged -> {
                                val generationChanged = event.state.generation != observedGeneration
                                observedGeneration = event.state.generation
                                val operationOwnsSessionTransition =
                                    activeOperationKind == AuthenticationOperationKind.SessionTransition ||
                                        activeOperationKind == AuthenticationOperationKind.FallbackLogin
                                val authenticatedOutsideLogout =
                                    event.state is DiscourseSessionState.Authenticated &&
                                        activeOperationKind != AuthenticationOperationKind.Logout
                                if (
                                    !operationOwnsSessionTransition &&
                                    (authenticatedOutsideLogout || generationChanged)
                                ) {
                                    operationJob?.cancelAndJoin()
                                    challengeJob?.cancelAndJoin()
                                    operationJob = null
                                    challengeJob = null
                                    activeOperationId = null
                                    activeOperationKind = null
                                    activeRestrictedBrowserOperation = null
                                    update { DiscourseAuthenticationState() }
                                }
                            }

                            is AuthenticationEvent.ManualChallengeChanged -> {
                                val challenge = event.request
                                if (challenge == null) {
                                    update { current ->
                                        if (
                                            current.restrictedBrowser?.mode ==
                                            DiscourseRestrictedBrowserMode.ManualChallenge
                                        ) {
                                            current.copy(
                                                restrictedBrowser = null,
                                                restrictedBrowserHandoffInProgress = false,
                                                isBusy = operationJob?.isActive == true,
                                            )
                                        } else {
                                            current
                                        }
                                    }
                                } else {
                                    update {
                                        val browser =
                                            DiscourseRestrictedBrowserRequest(
                                                requestId = challenge.requestId,
                                                mode = DiscourseRestrictedBrowserMode.ManualChallenge,
                                                initialUrl = challenge.origin,
                                            )
                                        it.copy(
                                            restrictedBrowser = browser,
                                            restrictedBrowserHandoffInProgress =
                                                it.restrictedBrowserHandoffInProgress &&
                                                    it.restrictedBrowser?.matches(
                                                        browser.requestId,
                                                        browser.mode,
                                                    ) == true,
                                            failure = null,
                                        )
                                    }
                                }
                            }

                            is AuthenticationEvent.ChallengeResolved -> {
                                challengeJob = null
                                update { current ->
                                    if (
                                        current.restrictedBrowser?.matches(
                                            event.requestId,
                                            DiscourseRestrictedBrowserMode.ManualChallenge,
                                        ) == true
                                    ) {
                                        current.copy(
                                            restrictedBrowser = null,
                                            restrictedBrowserHandoffInProgress = false,
                                            isBusy = operationJob?.isActive == true,
                                            failure =
                                                event.failure
                                                    ?: if (event.completed) {
                                                        null
                                                    } else {
                                                        DiscourseAuthenticationFailureKind.ChallengeRequired
                                                    },
                                        )
                                    } else {
                                        current.copy(isBusy = operationJob?.isActive == true)
                                    }
                                }
                            }

                            is AuthenticationEvent.ChallengeCancelled -> {
                                challengeJob = null
                                update { current ->
                                    if (
                                        current.restrictedBrowser?.matches(
                                            event.requestId,
                                            DiscourseRestrictedBrowserMode.ManualChallenge,
                                        ) == true
                                    ) {
                                        current.copy(
                                            restrictedBrowser = null,
                                            restrictedBrowserHandoffInProgress = false,
                                            isBusy = operationJob?.isActive == true,
                                        )
                                    } else {
                                        current.copy(isBusy = operationJob?.isActive == true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
}

internal interface DiscourseAuthenticationBackend {
    val sessionState: StateFlow<DiscourseSessionState>

    val manualChallenge: StateFlow<DiscourseManualChallengeRequest?>

    suspend fun beginAuthorization(): DiscoursePendingAuthorization

    suspend fun cancelAuthorization(): Boolean

    suspend fun clearBrowserCookies()

    suspend fun completeWebSession(): DiscourseLoginResult.Authenticated

    suspend fun completeRedirect(rawUri: String): DiscourseLoginResult

    suspend fun logout(
        expectedGeneration: Long,
        expectedAccountId: String,
    ): Boolean

    suspend fun completeManualChallengeAfterCookieConsumption(requestId: Long): Boolean

    suspend fun cancelManualChallenge(requestId: Long): Boolean
}

private class DefaultDiscourseAuthenticationBackend(
    private val loginService: DiscourseLoginService,
    private val webSessionLogin: DiscourseWebSessionLogin,
    private val cookieBridge: DiscourseWebSessionCookieBridge,
    private val sessionManager: DiscourseSessionManager,
    private val challengeCoordinator: DiscourseManualChallengeCoordinator,
) : DiscourseAuthenticationBackend {
    override val sessionState: StateFlow<DiscourseSessionState> = sessionManager.state
    override val manualChallenge: StateFlow<DiscourseManualChallengeRequest?> = challengeCoordinator.request

    override suspend fun beginAuthorization(): DiscoursePendingAuthorization = loginService.beginAuthorization()

    override suspend fun cancelAuthorization(): Boolean = loginService.cancelAuthorization()

    override suspend fun clearBrowserCookies() {
        cookieBridge.clearLinuxDoCookies()
    }

    override suspend fun completeWebSession(): DiscourseLoginResult.Authenticated = webSessionLogin.complete()

    override suspend fun completeRedirect(rawUri: String): DiscourseLoginResult = loginService.completeRedirect(rawUri)

    override suspend fun logout(
        expectedGeneration: Long,
        expectedAccountId: String,
    ): Boolean = loginService.logout(expectedGeneration, expectedAccountId)

    override suspend fun completeManualChallengeAfterCookieConsumption(requestId: Long): Boolean =
        challengeCoordinator.completeAfterCookieConsumption(requestId)

    override suspend fun cancelManualChallenge(requestId: Long): Boolean = challengeCoordinator.cancel(requestId)
}

private data class AuthenticationOwner(
    val generation: Long,
    val accountId: String,
)

private data class RestrictedBrowserOperation(
    val operationId: Long,
    val requestId: Long,
    val mode: DiscourseRestrictedBrowserMode,
) {
    fun matches(request: DiscourseRestrictedBrowserRequest): Boolean = requestId == request.requestId && mode == request.mode
}

private enum class AuthenticationOperationKind {
    Authentication,
    SessionTransition,
    FallbackLogin,
    Logout,
}

private enum class AuthenticationActorLifecycle {
    NotStarted,
    Running,
    Closed,
    Finished,
}

private enum class RedirectClaimAdmission {
    Open,
    Claiming,
    Closed,
}

private enum class TerminalReceiptState {
    Pending,
    Claiming,
    Owned,
    Rejected,
}

/** Public commands and the private raw redirect carrier share one bounded actor queue. */
private sealed interface QueuedAuthenticationCommand {
    data class Ui(
        val action: DiscourseAuthenticationAction,
        val owner: AuthenticationOwner? = null,
    ) : QueuedAuthenticationCommand

    class RawRedirect(
        val rawUri: String,
        val receipt: DiscourseRedirectReceipt,
    ) : QueuedAuthenticationCommand
}

private fun QueuedAuthenticationCommand.rejectReceipt() {
    when (this) {
        is QueuedAuthenticationCommand.RawRedirect -> receipt.reject()
        is QueuedAuthenticationCommand.Ui -> action.rejectTerminalReceipt()
    }
}

private fun DiscourseAuthenticationAction.rejectTerminalReceipt() {
    (this as? DiscourseRestrictedBrowserTerminalAction)?.receipt?.reject()
}

private sealed interface AuthenticationEvent {
    data class OperationSucceeded(
        val operationId: Long,
        val startGeneration: Long,
        val result: AuthenticationSuccess,
    ) : AuthenticationEvent

    data class OperationFailed(
        val operationId: Long,
        val startGeneration: Long,
        val failure: DiscourseAuthenticationFailureKind,
    ) : AuthenticationEvent

    data class OperationCancelled(
        val operationId: Long,
        val startGeneration: Long,
    ) : AuthenticationEvent

    data class SessionChanged(
        val state: DiscourseSessionState,
    ) : AuthenticationEvent

    data class ManualChallengeChanged(
        val request: DiscourseManualChallengeRequest?,
    ) : AuthenticationEvent

    data class ChallengeResolved(
        val requestId: Long,
        val completed: Boolean,
        val failure: DiscourseAuthenticationFailureKind?,
    ) : AuthenticationEvent

    data class ChallengeCancelled(
        val requestId: Long,
    ) : AuthenticationEvent
}

private sealed interface AuthenticationSuccess {
    data class AuthorizationReady(
        val authorization: DiscourseExternalAuthorization,
    ) : AuthenticationSuccess

    data class FallbackReady(
        val request: DiscourseRestrictedBrowserRequest,
    ) : AuthenticationSuccess

    data object Authenticated : AuthenticationSuccess

    data object LoggedOut : AuthenticationSuccess

    data object AuthorizationCancelled : AuthenticationSuccess

    data object RedirectRejected : AuthenticationSuccess
}

/** Results that expose a browser surface or failure are valid only for their original session. */
private val AuthenticationSuccess.requiresOriginalGeneration: Boolean
    get() =
        when (this) {
            AuthenticationSuccess.Authenticated,
            AuthenticationSuccess.LoggedOut,
            -> false

            is AuthenticationSuccess.AuthorizationReady,
            is AuthenticationSuccess.FallbackReady,
            AuthenticationSuccess.AuthorizationCancelled,
            AuthenticationSuccess.RedirectRejected,
            -> true
        }

private fun DiscourseRestrictedBrowserRequest.matches(
    requestId: Long,
    mode: DiscourseRestrictedBrowserMode,
): Boolean = this.requestId == requestId && this.mode == mode

private fun Throwable.toAuthenticationFailure(): DiscourseAuthenticationFailureKind =
    when (this) {
        is DiscourseAuthenticationException -> {
            DiscourseAuthenticationFailureKind.Authentication
        }

        is DiscoursePermissionException -> {
            DiscourseAuthenticationFailureKind.Permission
        }

        is DiscourseRateLimitException -> {
            DiscourseAuthenticationFailureKind.RateLimited
        }

        is DiscourseCloudflareChallengeException -> {
            DiscourseAuthenticationFailureKind.ChallengeRequired
        }

        is DiscourseNetworkException -> {
            DiscourseAuthenticationFailureKind.Network
        }

        is DiscourseServerException -> {
            DiscourseAuthenticationFailureKind.Server
        }

        is DiscourseAuthExchangeException -> {
            when (reason) {
                DiscourseAuthExchangeFailure.ActiveSession,
                DiscourseAuthExchangeFailure.InvalidSecret,
                DiscourseAuthExchangeFailure.SessionCookie,
                DiscourseAuthExchangeFailure.Identity,
                -> {
                    DiscourseAuthenticationFailureKind.Authentication
                }

                DiscourseAuthExchangeFailure.ChallengeHandler -> {
                    DiscourseAuthenticationFailureKind.ChallengeRequired
                }

                DiscourseAuthExchangeFailure.Csrf,
                DiscourseAuthExchangeFailure.OtpResponse,
                DiscourseAuthExchangeFailure.RevokeResponse,
                -> {
                    DiscourseAuthenticationFailureKind.InvalidResponse
                }
            }
        }

        is DiscourseException -> {
            DiscourseAuthenticationFailureKind.InvalidResponse
        }

        else -> {
            DiscourseAuthenticationFailureKind.InvalidResponse
        }
    }

private const val AUTH_ACTION_CAPACITY: Int = 24
private const val AUTH_EVENT_CAPACITY: Int = 24
private const val LINUX_DO_HOST: String = "linux.do"
private const val USER_API_KEY_PATH: String = "/user-api-key/new"
private const val MAX_BROWSER_URL_LENGTH: Int = 16 * 1024
private const val MAX_AUTH_REDIRECT_LENGTH: Int = 16 * 1024
