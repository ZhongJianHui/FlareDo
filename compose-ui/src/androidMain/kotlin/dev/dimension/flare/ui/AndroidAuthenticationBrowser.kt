package dev.dimension.flare.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.dimension.flare.compose.ui.Res
import dev.dimension.flare.compose.ui.forum_auth_browser_cancel
import dev.dimension.flare.compose.ui.forum_auth_browser_complete
import dev.dimension.flare.compose.ui.forum_auth_browser_title
import dev.dimension.flare.compose.ui.forum_auth_browser_unavailable
import dev.dimension.flare.compose.ui.forum_auth_challenge_title
import dev.dimension.flare.data.network.discourse.DISCOURSE_ORIGIN
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthenticationAction
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthenticationState
import dev.dimension.flare.data.network.discourse.auth.DiscourseBrowserUrlPolicy
import dev.dimension.flare.data.network.discourse.auth.DiscourseExternalAuthorization
import dev.dimension.flare.data.network.discourse.auth.DiscourseRestrictedBrowserMode
import dev.dimension.flare.data.network.discourse.auth.DiscourseRestrictedBrowserRequest
import dev.dimension.flare.data.network.discourse.auth.DiscourseRestrictedBrowserTerminalAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Owns Android-only browser effects while common UI receives only redacted authentication state. */
@Composable
internal fun AndroidAuthenticationBrowserEffects(
    state: DiscourseAuthenticationState,
    onAction: (DiscourseAuthenticationAction) -> Boolean,
) {
    val context = LocalContext.current
    val externalAuthorization = state.externalAuthorization
    LaunchedEffect(externalAuthorization?.requestId) {
        val request = externalAuthorization ?: return@LaunchedEffect
        val action =
            if (openAndroidSystemAuthorization(context, request)) {
                DiscourseAuthenticationAction.AuthorizationOpened(request.requestId)
            } else {
                DiscourseAuthenticationAction.AuthorizationLaunchFailed(request.requestId)
            }
        onAction(action)
    }

    state.restrictedBrowser?.let { request ->
        key(request.requestId, request.mode) {
            AndroidRestrictedBrowserDialog(
                request = request,
                sharedHandoffInProgress = state.restrictedBrowserHandoffInProgress,
                onAction = onAction,
            )
        }
    }
}

/**
 * Launches only the exact User API Key URL and never forwards extras, ClipData, grants, or a nested
 * Intent. The Android URI checks intentionally duplicate the common parser at the final boundary.
 */
internal fun openAndroidSystemAuthorization(
    context: Context,
    request: DiscourseExternalAuthorization,
): Boolean {
    if (!DiscourseBrowserUrlPolicy.isExternalAuthorizationUrl(request.url)) return false
    val uri = runCatching { Uri.parse(request.url) }.getOrNull() ?: return false
    if (
        uri.scheme != "https" ||
        uri.host != LINUX_DO_HOST ||
        uri.port != -1 ||
        !uri.userInfo.isNullOrEmpty() ||
        uri.encodedPath != USER_API_KEY_PATH ||
        uri.fragment != null
    ) {
        return false
    }
    val intent = Intent(Intent.ACTION_VIEW, uri)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}

@Composable
private fun AndroidRestrictedBrowserDialog(
    request: DiscourseRestrictedBrowserRequest,
    sharedHandoffInProgress: Boolean,
    onAction: (DiscourseAuthenticationAction) -> Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val processRequestState =
        remember(request.requestId, request.mode) {
            androidRestrictedBrowserProcessState.acquire(
                AndroidRestrictedBrowserProcessRequestKey(request.requestId, request.mode),
            )
        }
    var isPrepared by processRequestState.isPrepared
    var preparationFailed by remember(request.requestId) { mutableStateOf(false) }
    var handoffRejected by remember(request.requestId) { mutableStateOf(false) }
    var cookieRejectionInProgress by remember(request.requestId) { mutableStateOf(false) }
    val localHandoffStartedState = processRequestState.localHandoffStarted
    var localHandoffStarted by localHandoffStartedState
    var webView by remember(request.requestId) { mutableStateOf<WebView?>(null) }
    val handoffInProgress =
        isAndroidRestrictedBrowserHandoffLocked(
            sharedHandoffInProgress = sharedHandoffInProgress,
            localHandoffStarted = localHandoffStarted,
        )
    val latestSharedHandoffInProgress by rememberUpdatedState(sharedHandoffInProgress)

    LaunchedEffect(sharedHandoffInProgress) {
        if (sharedHandoffInProgress) {
            // The retained presenter now owns the commit. Dropping the local publication guard lets
            // terminal disposal clear WebStorage after the shared non-cancellable cleanup finishes.
            localHandoffStarted = false
        }
    }

    LaunchedEffect(request.requestId, request.mode) {
        // A recreated Activity must not erase the Cookie snapshot while the retained presenter is
        // committing it. The process-memory bit closes the short gap before the actor publishes its
        // request-bound shared state without surviving process death or saved-state restoration.
        if (!shouldPrepareAndroidRestrictedBrowser(isPrepared, handoffInProgress)) {
            return@LaunchedEffect
        }
        try {
            // This application has no other Android WebView. Starting every new process-memory
            // request from an empty Cookie and WebStorage profile prevents prior auth state from
            // crossing request boundaries. The marker survives configuration only, not process death.
            clearAllAndroidWebStorage()
            clearAllAndroidWebCookies()
            isPrepared = true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            preparationFailed = true
        }
    }

    DisposableEffect(request.requestId, request.mode, context) {
        val hostActivity = context.findRestrictedBrowserHostActivity()
        onDispose {
            val isChangingConfigurations = hostActivity?.isChangingConfigurations == true
            if (shouldClearAndroidRestrictedBrowserWebStorageOnDispose(isChangingConfigurations)) {
                // WebStorage never participates in the Cookie snapshot, so terminal disposal can
                // erase it even while the retained presenter owns an accepted Cookie handoff.
                clearAllAndroidWebStorageBestEffort()
            }
            if (
                shouldClearAndroidRestrictedBrowserCookiesOnDispose(
                    // The local bit proves only that trySend accepted the command. The receipt-backed
                    // marker closes the gap before the presenter's shared state reaches composition.
                    handoffInProgress = latestSharedHandoffInProgress,
                    actorHandoffOwned = processRequestState.actorHandoffOwned,
                    isChangingConfigurations = isChangingConfigurations,
                )
            ) {
                // Presenter cancellation also awaits bridge cleanup. This callback fallback covers
                // terminal host disposal only when it cannot race an accepted Cookie snapshot.
                clearAllAndroidWebCookiesAsync()
            }
            androidRestrictedBrowserProcessState.release(
                state = processRequestState,
                isChangingConfigurations = isChangingConfigurations,
            )
        }
    }

    DisposableEffect(lifecycleOwner, webView) {
        val observedWebView = webView
        if (observedWebView == null) {
            onDispose {}
        } else {
            fun pauseBrowser() {
                if (observedWebView.hasLostRenderer) return
                observedWebView.onPause()
                // pauseTimers is process-global, but this is FlareDo's only Android WebView.
                observedWebView.pauseTimers()
            }

            fun resumeBrowser() {
                if (observedWebView.hasLostRenderer) return
                observedWebView.onResume()
                observedWebView.resumeTimers()
            }

            val observer =
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> resumeBrowser()

                        Lifecycle.Event.ON_PAUSE,
                        Lifecycle.Event.ON_STOP,
                        Lifecycle.Event.ON_DESTROY,
                        -> pauseBrowser()

                        else -> Unit
                    }
                }
            lifecycleOwner.lifecycle.addObserver(observer)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                resumeBrowser()
            } else {
                pauseBrowser()
            }
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                pauseBrowser()
            }
        }
    }

    fun submitTerminalAction(action: DiscourseRestrictedBrowserTerminalAction) {
        if (
            isAndroidRestrictedBrowserHandoffLocked(
                sharedHandoffInProgress = sharedHandoffInProgress,
                localHandoffStarted = localHandoffStartedState.value,
            )
        ) {
            return
        }
        // Close the click-to-actor gap before the bounded presenter queue is consulted. A rejected
        // command releases this bit only after both browser stores have been cleared fail-closed.
        localHandoffStarted = true
        processRequestState.terminalAction = action
        // UNDISPATCHED keeps trySend in this click/callback stack. If configuration changes after
        // launch returns, either the presenter already owns the action or rejection cleanup has
        // already entered its NonCancellable section.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            dispatchAndroidRestrictedBrowserAction(
                action = action,
                onAction = onAction,
                clearWebStorage = ::clearAllAndroidWebStorage,
                clearCookies = ::clearAllAndroidWebCookies,
                onRejected = {
                    if (processRequestState.terminalAction === action) {
                        processRequestState.terminalAction = null
                    }
                    localHandoffStarted = false
                    preparationFailed = true
                    handoffRejected = true
                    cookieRejectionInProgress = false
                },
            )
        }
    }

    fun cancel() {
        submitTerminalAction(
            DiscourseAuthenticationAction.CancelRestrictedBrowser(
                requestId = request.requestId,
                mode = request.mode,
            ),
        )
    }

    Dialog(
        onDismissRequest = ::cancel,
        properties =
            DialogProperties(
                dismissOnBackPress = !handoffInProgress,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.small,
            tonalElevation = 4.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text =
                            stringResource(
                                if (request.mode == DiscourseRestrictedBrowserMode.ManualChallenge) {
                                    Res.string.forum_auth_challenge_title
                                } else {
                                    Res.string.forum_auth_browser_title
                                },
                            ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = ::cancel, enabled = !handoffInProgress) {
                        Text(stringResource(Res.string.forum_auth_browser_cancel))
                    }
                    Button(
                        enabled =
                            isPrepared &&
                                !preparationFailed &&
                                !handoffInProgress &&
                                !cookieRejectionInProgress &&
                                webView != null,
                        onClick = {
                            if (localHandoffStartedState.value || sharedHandoffInProgress) {
                                return@Button
                            }
                            val cookieHeader =
                                try {
                                    CookieManager.getInstance().getCookie(DISCOURSE_ORIGIN)
                                } catch (_: RuntimeException) {
                                    null
                                }
                            if (isValidRestrictedCookieHandoff(request.mode, cookieHeader)) {
                                submitTerminalAction(
                                    DiscourseAuthenticationAction.CompleteRestrictedBrowser(
                                        requestId = request.requestId,
                                        mode = request.mode,
                                    ),
                                )
                            } else {
                                cookieRejectionInProgress = true
                                scope.launch {
                                    clearAllAndroidWebStorage()
                                    clearAllAndroidWebCookies()
                                    handoffRejected = true
                                    cookieRejectionInProgress = false
                                }
                            }
                        },
                    ) {
                        Text(stringResource(Res.string.forum_auth_browser_complete))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (preparationFailed || handoffRejected) {
                    Text(
                        stringResource(Res.string.forum_auth_browser_unavailable),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (handoffInProgress || (!isPrepared && !preparationFailed)) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (!preparationFailed) {
                    Box(Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { hostContext ->
                                createRestrictedAndroidWebView(
                                    context = hostContext,
                                    request = request,
                                    onBrowserFailure = {
                                        preparationFailed = true
                                        submitTerminalAction(
                                            DiscourseAuthenticationAction.RestrictedBrowserFailed(
                                                requestId = request.requestId,
                                                mode = request.mode,
                                            ),
                                        )
                                    },
                                ).also { created -> webView = created }
                            },
                            modifier = Modifier.fillMaxSize(),
                            onRelease = { released ->
                                // Release the exact AndroidView-owned instance. Keying a DisposableEffect
                                // by the mutable `webView` state would destroy it immediately after the
                                // factory changes that state from null to the created view.
                                if (webView === released) webView = null
                                released.disposeRestrictedBrowser()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Suppress("DEPRECATION", "SetJavaScriptEnabled")
private fun createRestrictedAndroidWebView(
    context: Context,
    request: DiscourseRestrictedBrowserRequest,
    onBrowserFailure: () -> Unit,
): WebView =
    RestrictedAndroidWebView(context).apply webView@{
        val failureGate = AndroidBrowserFailureGate(onBrowserFailure)
        WebView.setWebContentsDebuggingEnabled(false)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        settings.apply {
            // Discourse and Cloudflare require JavaScript, but no native JavaScript bridge exists.
            userAgentString = WebSettings.getDefaultUserAgent(context.applicationContext)
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(this@webView, false)
        }
        setDownloadListener { _, _, _, _, _ -> }
        webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    navigation: WebResourceRequest,
                ): Boolean =
                    shouldBlockRestrictedMainFrameRequest(
                        isForMainFrame = navigation.isForMainFrame,
                        rawUrl = navigation.url.toString(),
                    )

                @Deprecated("Covers legacy WebView navigation before WebResourceRequest")
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    url: String,
                ): Boolean = !DiscourseBrowserUrlPolicy.isAllowedTopLevelUrl(url)

                override fun onPageStarted(
                    view: WebView,
                    url: String,
                    favicon: Bitmap?,
                ) {
                    if (!DiscourseBrowserUrlPolicy.isAllowedTopLevelUrl(url)) view.stopLoading()
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    if (
                        !shouldBlockRestrictedMainFrameRequest(
                            isForMainFrame = request.isForMainFrame,
                            rawUrl = request.url.toString(),
                        )
                    ) {
                        return null
                    }
                    // Unlike shouldOverrideUrlLoading, interception also covers POST. Returning a
                    // synthetic response prevents the cross-origin request and form body leaving
                    // the process; UI state is changed back on the main WebView thread.
                    view.post {
                        if (!view.hasLostRenderer) view.stopLoading()
                        failureGate.report()
                    }
                    return blockedAndroidWebResponse()
                }

                override fun onReceivedSslError(
                    view: WebView,
                    handler: SslErrorHandler,
                    error: SslError,
                ) {
                    handler.cancel()
                    failureGate.report()
                }

                override fun onSafeBrowsingHit(
                    view: WebView,
                    request: WebResourceRequest,
                    threatType: Int,
                    callback: SafeBrowsingResponse,
                ) {
                    callback.backToSafety(true)
                    view.stopLoading()
                    failureGate.report()
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    (view as? RestrictedAndroidWebView)?.rendererGone = true
                    failureGate.report()
                    // No WebView method is safe after renderer loss. AndroidView.onRelease removes
                    // the attached view and takes the direct destroy-only branch below.
                    return true
                }
            }
        clearCache(true)
        clearHistory()
        loadUrl(request.initialUrl)
    }

private fun WebView.disposeRestrictedBrowser() {
    if (hasLostRenderer) {
        destroy()
        return
    }
    webViewClient = WebViewClient()
    setDownloadListener(null)
    stopLoading()
    clearHistory()
    clearCache(true)
    clearFormData()
    removeAllViews()
    destroy()
}

private class RestrictedAndroidWebView(
    context: Context,
) : WebView(context) {
    var rendererGone: Boolean = false
}

internal class AndroidBrowserFailureGate(
    private val onFirstFailure: () -> Unit,
) {
    private val reported = AtomicBoolean(false)

    fun report() {
        if (reported.compareAndSet(false, true)) onFirstFailure()
    }
}

private val WebView.hasLostRenderer: Boolean
    get() = (this as? RestrictedAndroidWebView)?.rendererGone == true

internal fun shouldBlockRestrictedMainFrameRequest(
    isForMainFrame: Boolean,
    rawUrl: String,
): Boolean = isForMainFrame && !DiscourseBrowserUrlPolicy.isAllowedTopLevelUrl(rawUrl)

/** Combines the retained actor lock with the process-memory click-to-actor publication guard. */
internal fun isAndroidRestrictedBrowserHandoffLocked(
    sharedHandoffInProgress: Boolean,
    localHandoffStarted: Boolean,
): Boolean = sharedHandoffInProgress || localHandoffStarted

/**
 * Transfers one terminal browser action to the presenter or clears every one-use browser secret.
 *
 * Channel admission is not ownership. Only the action's actor receipt may leave cleanup to the
 * presenter. Rejection, receipt timeout, and cancellation before ownership clear local state and
 * unlock in [NonCancellable]. Prompt cancellation after actor ownership only propagates: clearing
 * then would corrupt a handoff already being consumed by the presenter.
 */
internal suspend fun dispatchAndroidRestrictedBrowserAction(
    action: DiscourseRestrictedBrowserTerminalAction,
    onAction: (DiscourseAuthenticationAction) -> Boolean,
    clearWebStorage: () -> Unit,
    clearCookies: suspend () -> Unit,
    onRejected: () -> Unit,
    receiptTimeoutMillis: Long = ANDROID_TERMINAL_RECEIPT_TIMEOUT_MILLIS,
    awaitOwnership: suspend (DiscourseRestrictedBrowserTerminalAction, Long) -> Boolean =
        { terminalAction, timeoutMillis -> terminalAction.receipt.awaitOwnership(timeoutMillis) },
    ownershipTransferred: (DiscourseRestrictedBrowserTerminalAction) -> Boolean =
        { terminalAction -> terminalAction.receipt.ownershipTransferred },
): Boolean {
    require(receiptTimeoutMillis > 0L) { "A terminal receipt timeout must be positive" }

    suspend fun cleanupRejectedHandoff(): Throwable? {
        var cleanupFailure: Throwable? = null

        fun record(failure: Throwable) {
            val previous = cleanupFailure
            if (previous == null) {
                cleanupFailure = failure
            } else if (previous !== failure) {
                previous.addSuppressed(failure)
            }
        }

        withContext(NonCancellable) {
            try {
                clearWebStorage()
            } catch (failure: CancellationException) {
                record(failure)
            } catch (_: Throwable) {
                // Cookie cleanup and the local unlock must still run after WebStorage failure.
            }
            try {
                clearCookies()
            } catch (failure: CancellationException) {
                record(failure)
            } catch (_: Throwable) {
                // Terminal disposal repeats best-effort cleanup after WebView detachment.
            }
            try {
                onRejected()
            } catch (failure: Throwable) {
                record(failure)
            }
        }
        return cleanupFailure
    }

    try {
        if (onAction(action) && awaitOwnership(action, receiptTimeoutMillis)) return true
    } catch (cancellation: CancellationException) {
        if (!ownershipTransferred(action)) {
            cleanupRejectedHandoff()?.let { cleanupFailure ->
                if (cleanupFailure !== cancellation) cancellation.addSuppressed(cleanupFailure)
            }
        }
        throw cancellation
    }

    cleanupRejectedHandoff()?.let { throw it }
    return false
}

/** WebStorage is request-private and may be erased on every terminal (non-configuration) disposal. */
internal fun shouldClearAndroidRestrictedBrowserWebStorageOnDispose(isChangingConfigurations: Boolean): Boolean = !isChangingConfigurations

/** Cookie disposal avoids configuration rebuilds and both receipt-owned and shared handoffs. */
internal fun shouldClearAndroidRestrictedBrowserCookiesOnDispose(
    handoffInProgress: Boolean,
    actorHandoffOwned: Boolean = false,
    isChangingConfigurations: Boolean = false,
): Boolean = !handoffInProgress && !actorHandoffOwned && !isChangingConfigurations

/** A restored request keeps its process Cookie profile; only a new request starts by clearing it. */
internal fun shouldPrepareAndroidRestrictedBrowser(
    isPrepared: Boolean,
    handoffInProgress: Boolean,
): Boolean = !isPrepared && !handoffInProgress

/**
 * Single-slot process memory for the only restricted Android WebView.
 *
 * A configuration rebuild reuses the same holder, while process death creates a new store and forces
 * profile preparation even if Android restores Compose saved state with a colliding request id.
 */
internal class AndroidRestrictedBrowserProcessStateStore {
    private var active: AndroidRestrictedBrowserProcessRequestState? = null

    @Synchronized
    fun acquire(key: AndroidRestrictedBrowserProcessRequestKey): AndroidRestrictedBrowserProcessRequestState {
        val current = active
        if (current?.key == key) return current
        return AndroidRestrictedBrowserProcessRequestState(key).also { active = it }
    }

    @Synchronized
    fun release(
        state: AndroidRestrictedBrowserProcessRequestState,
        isChangingConfigurations: Boolean,
    ) {
        if (!isChangingConfigurations && active === state) active = null
    }
}

internal data class AndroidRestrictedBrowserProcessRequestKey(
    val requestId: Long,
    val mode: DiscourseRestrictedBrowserMode,
)

internal class AndroidRestrictedBrowserProcessRequestState internal constructor(
    internal val key: AndroidRestrictedBrowserProcessRequestKey,
) {
    internal val isPrepared = mutableStateOf(false)
    internal val localHandoffStarted = mutableStateOf(false)
    internal var terminalAction: DiscourseRestrictedBrowserTerminalAction? = null

    /** Reads the receipt directly so disposal does not wait for shared state recomposition. */
    internal val actorHandoffOwned: Boolean
        get() =
            terminalAction is DiscourseAuthenticationAction.CompleteRestrictedBrowser &&
                terminalAction?.receipt?.ownershipTransferred == true
}

private val androidRestrictedBrowserProcessState = AndroidRestrictedBrowserProcessStateStore()

private tailrec fun Context.findRestrictedBrowserHostActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findRestrictedBrowserHostActivity()
        else -> null
    }

private fun blockedAndroidWebResponse(): WebResourceResponse =
    WebResourceResponse(
        "text/plain",
        "UTF-8",
        403,
        "Blocked by FlareDo",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )

private fun clearAllAndroidWebStorage() {
    WebStorage.getInstance().deleteAllData()
}

private fun clearAllAndroidWebStorageBestEffort() {
    try {
        clearAllAndroidWebStorage()
    } catch (_: RuntimeException) {
        // The WebView is already closed; the next request repeats fail-closed profile preparation.
    }
}

private suspend fun clearAllAndroidWebCookies() {
    suspendCancellableCoroutine { continuation ->
        clearAllAndroidWebCookiesAsync {
            if (continuation.isActive) continuation.resume(Unit)
        }
    }
}

private fun clearAllAndroidWebCookiesAsync(onCleared: (() -> Unit)? = null) {
    val cookieManager = CookieManager.getInstance()
    cookieManager.removeAllCookies {
        // CookieManager's callback runs after the in-memory removal; flush only then so Domain
        // cookies such as Domain=.linux.do cannot survive a process restart.
        cookieManager.flush()
        onCleared?.invoke()
    }
}

/** Validates names and non-empty values without retaining a parsed Cookie snapshot in UI state. */
internal fun isValidRestrictedCookieHandoff(
    mode: DiscourseRestrictedBrowserMode,
    cookieHeader: String?,
): Boolean {
    val header = cookieHeader ?: return false
    if (
        header.isBlank() ||
        header.length > MAX_COOKIE_HEADER_LENGTH ||
        header.any { it == '\u0000' || it == '\r' || it == '\n' }
    ) {
        return false
    }
    val segments = header.split(';')
    val cookies =
        segments
            .map(String::trim)
            .mapNotNull { segment ->
                val delimiter = segment.indexOf('=')
                if (delimiter <= 0) return@mapNotNull null
                segment.substring(0, delimiter) to segment.substring(delimiter + 1)
            }
    if (cookies.size != segments.size || cookies.map { it.first }.distinct().size != cookies.size) {
        return false
    }
    return when (mode) {
        DiscourseRestrictedBrowserMode.FallbackLogin -> {
            cookies.any { (name, value) -> name == AUTH_COOKIE_NAME && value.isNotEmpty() }
        }

        DiscourseRestrictedBrowserMode.ManualChallenge -> {
            cookies.none { (name, _) -> name == AUTH_COOKIE_NAME } &&
                cookies.any { (name, value) -> name == CHALLENGE_COOKIE_NAME && value.isNotEmpty() }
        }
    }
}

private const val LINUX_DO_HOST: String = "linux.do"
private const val USER_API_KEY_PATH: String = "/user-api-key/new"
private const val AUTH_COOKIE_NAME: String = "_t"
private const val CHALLENGE_COOKIE_NAME: String = "cf_clearance"
private const val MAX_COOKIE_HEADER_LENGTH: Int = 64 * 1024
private const val ANDROID_TERMINAL_RECEIPT_TIMEOUT_MILLIS: Long = 5_000L
