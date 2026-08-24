package dev.dimension.flare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberDialogState
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
import dev.nucleusframework.application.DecoratedDialog
import dev.nucleusframework.webview.cookie.Cookie
import dev.nucleusframework.webview.request.RequestInterceptor
import dev.nucleusframework.webview.request.WebRequest
import dev.nucleusframework.webview.request.WebRequestInterceptResult
import dev.nucleusframework.webview.web.NativeWebView
import dev.nucleusframework.webview.web.WebContent
import dev.nucleusframework.webview.web.WebView
import dev.nucleusframework.webview.web.WebViewNavigator
import dev.nucleusframework.webview.web.WebViewState
import dev.nucleusframework.webview.web.rememberWebViewNavigator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource
import java.net.CookieManager
import java.net.HttpCookie
import java.net.URI
import kotlin.time.Clock

/** Desktop system-browser and native WebView effects kept outside the shared presenter. */
@Composable
public fun DesktopAuthenticationBrowserEffects(
    state: DiscourseAuthenticationState,
    browserCookieManager: CookieManager,
    openExternalUri: suspend (String) -> Boolean,
    onAction: (DiscourseAuthenticationAction) -> Boolean,
) {
    val externalAuthorization = state.externalAuthorization
    LaunchedEffect(externalAuthorization?.requestId) {
        val request = externalAuthorization ?: return@LaunchedEffect
        onAction(
            if (openDesktopSystemAuthorization(request, openExternalUri)) {
                DiscourseAuthenticationAction.AuthorizationOpened(request.requestId)
            } else {
                DiscourseAuthenticationAction.AuthorizationLaunchFailed(request.requestId)
            },
        )
    }

    state.restrictedBrowser?.let { request ->
        key(request.requestId, request.mode) {
            DesktopRestrictedBrowserDialog(
                request = request,
                browserCookieManager = browserCookieManager,
                onAction = onAction,
            )
        }
    }
}

/**
 * Dispatches only the portless Linux.do User API Key endpoint to the platform URI provider.
 *
 * A `true` result means the provider accepted the request, not that an external browser process
 * definitely reached the page. The Tao Linux provider launches `xdg-open` asynchronously because
 * initializing AWT's Desktop implementation would deadlock the native GLX event loop.
 */
internal suspend fun openDesktopSystemAuthorization(
    request: DiscourseExternalAuthorization,
    openUri: suspend (String) -> Boolean,
): Boolean {
    if (!DiscourseBrowserUrlPolicy.isExternalAuthorizationUrl(request.url)) return false
    val uri = runCatching { URI(request.url) }.getOrNull() ?: return false
    if (
        uri.scheme != "https" ||
        uri.host != LINUX_DO_HOST ||
        uri.port != -1 ||
        uri.rawUserInfo != null ||
        uri.rawPath != USER_API_KEY_PATH ||
        uri.rawFragment != null
    ) {
        return false
    }
    return try {
        openUri(request.url)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    }
}

@Composable
private fun DesktopRestrictedBrowserDialog(
    request: DiscourseRestrictedBrowserRequest,
    browserCookieManager: CookieManager,
    onAction: (DiscourseAuthenticationAction) -> Boolean,
) {
    val webViewState =
        remember(request.requestId) {
            WebViewState(WebContent.NavigatorOnly).apply {
                webSettings.isJavaScriptEnabled = true
                webSettings.supportZoom = false
                webSettings.allowFileAccessFromFileURLs = false
                webSettings.allowUniversalAccessFromFileURLs = false
                webSettings.desktopWebSettings.apply {
                    incognito = true
                    enableClipboard = false
                    enableDevtools = false
                    enableNavigationGestures = false
                    autoplayWithoutUserInteraction = false
                    initScript = ""
                }
            }
        }
    val requestInterceptor =
        remember(request.requestId) {
            object : RequestInterceptor {
                override fun onInterceptUrlRequest(
                    request: WebRequest,
                    navigator: WebViewNavigator,
                ): WebRequestInterceptResult =
                    if (
                        !request.isForMainFrame ||
                        DiscourseBrowserUrlPolicy.isAllowedTopLevelUrl(request.url)
                    ) {
                        WebRequestInterceptResult.Allow
                    } else {
                        WebRequestInterceptResult.Reject
                    }
            }
        }
    val navigator = rememberWebViewNavigator(requestInterceptor = requestInterceptor)
    val coroutineScope = rememberCoroutineScope()
    var nativeWebView by remember(request.requestId) { mutableStateOf<NativeWebView?>(null) }
    var isPrepared by remember(request.requestId) { mutableStateOf(false) }
    var handoffRejected by remember(request.requestId) { mutableStateOf(false) }
    var browserFailed by remember(request.requestId) { mutableStateOf(false) }
    var handoffInProgress by remember(request.requestId) { mutableStateOf(false) }
    val terminalActionState =
        remember(request.requestId) {
            mutableStateOf<DiscourseRestrictedBrowserTerminalAction?>(null)
        }

    LaunchedEffect(nativeWebView, request.requestId) {
        val mountedWebView = nativeWebView ?: return@LaunchedEffect
        try {
            if (
                !DiscourseBrowserUrlPolicy.isAllowedTopLevelUrl(request.initialUrl) ||
                !awaitDesktopWebViewReady(isReady = mountedWebView::isReady)
            ) {
                browserFailed = true
                return@LaunchedEffect
            }
            // Every dialog receives a fresh incognito profile. Explicit clearing also covers a
            // backend that reuses a native context while this process remains alive.
            webViewState.cookieManager.removeAllCookies()
            navigator.loadUrl(request.initialUrl)
            isPrepared = true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            browserFailed = true
        }
    }

    DisposableEffect(request.requestId) {
        onDispose {
            nativeWebView?.let { mounted ->
                runCatching { mounted.stopLoading() }
                runCatching { mounted.closeDevTools() }
            }
            val terminalAction = terminalActionState.value
            val actorHandoffOwned =
                terminalAction is DiscourseAuthenticationAction.CompleteRestrictedBrowser &&
                    terminalAction.receipt.ownershipTransferred
            if (shouldClearDesktopRestrictedBrowserCookiesOnDispose(actorHandoffOwned)) {
                clearDesktopLinuxDoCookies(browserCookieManager)
            }
        }
    }

    suspend fun clearNativeCookiesAndVerify(): Boolean =
        try {
            webViewState.cookieManager.removeAllCookies()
            webViewState.cookieManager.getCookies(DISCOURSE_ORIGIN).isEmpty()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }

    suspend fun clearAfterCancellation() {
        withContext(NonCancellable) {
            var cleanupCancellation: CancellationException? = null
            try {
                webViewState.cookieManager.removeAllCookies()
            } catch (failure: CancellationException) {
                cleanupCancellation = failure
            } catch (_: Exception) {
                // The incognito native view is destroyed with the dialog. The application Cookie
                // handoff below is the security boundary that must always be cleared synchronously.
            }
            clearDesktopLinuxDoCookies(browserCookieManager)
            cleanupCancellation?.let { throw it }
        }
    }

    fun cancel() {
        if (handoffInProgress) return
        handoffInProgress = true
        val terminalAction =
            DiscourseAuthenticationAction.CancelRestrictedBrowser(
                requestId = request.requestId,
                mode = request.mode,
            )
        terminalActionState.value = terminalAction
        coroutineScope.launch {
            try {
                clearNativeCookiesAndVerify()
                clearDesktopLinuxDoCookies(browserCookieManager)
                dispatchDesktopRestrictedBrowserAction(
                    action = terminalAction,
                    onAction = onAction,
                    clearBrowserState = ::clearAfterCancellation,
                    onRejected = {
                        if (terminalActionState.value === terminalAction) {
                            terminalActionState.value = null
                        }
                        handoffRejected = true
                        handoffInProgress = false
                    },
                )
            } catch (cancellation: CancellationException) {
                cleanupDesktopRestrictedBrowserAfterCallerCancellation(
                    action = terminalAction,
                    cancellation = cancellation,
                    clearBrowserState = ::clearAfterCancellation,
                )
                throw cancellation
            } catch (_: Exception) {
                if (terminalAction.receipt.ownershipTransferred) return@launch
                clearAfterCancellation()
                val retryAction =
                    DiscourseAuthenticationAction.CancelRestrictedBrowser(
                        requestId = request.requestId,
                        mode = request.mode,
                    )
                terminalActionState.value = retryAction
                dispatchDesktopRestrictedBrowserAction(
                    action = retryAction,
                    onAction = onAction,
                    clearBrowserState = ::clearAfterCancellation,
                    onRejected = {
                        if (terminalActionState.value === retryAction) {
                            terminalActionState.value = null
                        }
                        handoffRejected = true
                        handoffInProgress = false
                    },
                )
            }
        }
    }

    val dialogTitle =
        stringResource(
            if (request.mode == DiscourseRestrictedBrowserMode.ManualChallenge) {
                Res.string.forum_auth_challenge_title
            } else {
                Res.string.forum_auth_browser_title
            },
        )
    DecoratedDialog(
        onCloseRequest = {
            if (!handoffInProgress) cancel()
        },
        title = dialogTitle,
        state = rememberDialogState(size = DpSize(920.dp, 720.dp)),
        resizable = true,
    ) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = dialogTitle,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = ::cancel, enabled = !handoffInProgress) {
                        Text(stringResource(Res.string.forum_auth_browser_cancel))
                    }
                    Button(
                        enabled = isPrepared && !browserFailed && !handoffInProgress,
                        onClick = {
                            handoffInProgress = true
                            val terminalAction =
                                DiscourseAuthenticationAction.CompleteRestrictedBrowser(
                                    requestId = request.requestId,
                                    mode = request.mode,
                                )
                            terminalActionState.value = terminalAction
                            coroutineScope.launch {
                                try {
                                    val copied =
                                        copyRestrictedDesktopCookies(
                                            mode = request.mode,
                                            cookies = webViewState.cookieManager.getCookies(DISCOURSE_ORIGIN),
                                            destination = browserCookieManager,
                                        )
                                    val nativeCookiesCleared = clearNativeCookiesAndVerify()
                                    if (!copied || !nativeCookiesCleared) {
                                        clearDesktopLinuxDoCookies(browserCookieManager)
                                        handoffRejected = true
                                        handoffInProgress = false
                                    } else {
                                        dispatchDesktopRestrictedBrowserAction(
                                            action = terminalAction,
                                            onAction = onAction,
                                            clearBrowserState = ::clearAfterCancellation,
                                            onRejected = {
                                                if (terminalActionState.value === terminalAction) {
                                                    terminalActionState.value = null
                                                }
                                                handoffRejected = true
                                                handoffInProgress = false
                                            },
                                        )
                                    }
                                } catch (cancellation: CancellationException) {
                                    cleanupDesktopRestrictedBrowserAfterCallerCancellation(
                                        action = terminalAction,
                                        cancellation = cancellation,
                                        clearBrowserState = ::clearAfterCancellation,
                                    )
                                    throw cancellation
                                } catch (_: Exception) {
                                    if (!terminalAction.receipt.ownershipTransferred) {
                                        clearAfterCancellation()
                                        terminalActionState.value = null
                                        handoffRejected = true
                                        handoffInProgress = false
                                    }
                                }
                            }
                        },
                    ) {
                        Text(stringResource(Res.string.forum_auth_browser_complete))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (browserFailed || handoffRejected) {
                    Text(
                        stringResource(Res.string.forum_auth_browser_unavailable),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (browserFailed) {
                    Box(Modifier.fillMaxSize())
                } else {
                    WebView(
                        state = webViewState,
                        navigator = navigator,
                        modifier = Modifier.fillMaxSize(),
                        onCreated = { created -> nativeWebView = created },
                        onDispose = { disposed ->
                            runCatching { disposed.stopLoading() }
                            runCatching { disposed.closeDevTools() }
                            if (nativeWebView === disposed) nativeWebView = null
                        },
                    ) {
                        if (!isPrepared || handoffInProgress) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }
}

internal suspend fun awaitDesktopWebViewReady(
    timeoutMillis: Long = WEBVIEW_READY_TIMEOUT_MILLIS,
    pollMillis: Long = WEBVIEW_READY_POLL_MILLIS,
    isReady: () -> Boolean,
): Boolean {
    require(timeoutMillis > 0L) { "WebView readiness timeout must be positive" }
    require(pollMillis > 0L) { "WebView readiness poll interval must be positive" }
    return withTimeoutOrNull(timeoutMillis) {
        while (!isReady()) delay(pollMillis)
        true
    } == true
}

/**
 * Performs the composable's outer cancellation cleanup only while Cookie ownership is still local.
 *
 * The inner dispatch helper may rethrow prompt cancellation after a positive actor receipt. Its
 * caller must not erase that presenter-owned handoff a second time. Cleanup failures are attached
 * to, but never replace, the original cancellation propagated by the caller.
 */
internal suspend fun cleanupDesktopRestrictedBrowserAfterCallerCancellation(
    action: DiscourseRestrictedBrowserTerminalAction,
    cancellation: CancellationException,
    clearBrowserState: suspend () -> Unit,
    ownershipTransferred: (DiscourseRestrictedBrowserTerminalAction) -> Boolean =
        { terminalAction -> terminalAction.receipt.ownershipTransferred },
) {
    if (ownershipTransferred(action)) return
    try {
        withContext(NonCancellable) { clearBrowserState() }
    } catch (cleanupFailure: Throwable) {
        if (cleanupFailure !== cancellation) cancellation.addSuppressed(cleanupFailure)
    }
}

/** A destination Cookie snapshot survives disposal only after a Complete receipt is actor-owned. */
internal fun shouldClearDesktopRestrictedBrowserCookiesOnDispose(actorHandoffOwned: Boolean): Boolean = !actorHandoffOwned

/**
 * Hands a terminal browser action to the presenter and fails closed when its actor rejects it.
 *
 * A false return means the presenter never became the Cookie owner. Cleanup and the local unlock are
 * therefore completed in [NonCancellable], even if the dialog is disposed during the rejection path.
 * Prompt cancellation after a positive receipt propagates without clearing presenter-owned Cookies.
 */
internal suspend fun dispatchDesktopRestrictedBrowserAction(
    action: DiscourseRestrictedBrowserTerminalAction,
    onAction: (DiscourseAuthenticationAction) -> Boolean,
    clearBrowserState: suspend () -> Unit,
    onRejected: () -> Unit,
    receiptTimeoutMillis: Long = DESKTOP_TERMINAL_RECEIPT_TIMEOUT_MILLIS,
    awaitOwnership: suspend (DiscourseRestrictedBrowserTerminalAction, Long) -> Boolean =
        { terminalAction, timeoutMillis -> terminalAction.receipt.awaitOwnership(timeoutMillis) },
    ownershipTransferred: (DiscourseRestrictedBrowserTerminalAction) -> Boolean =
        { terminalAction -> terminalAction.receipt.ownershipTransferred },
): Boolean {
    require(receiptTimeoutMillis > 0L) { "A terminal receipt timeout must be positive" }

    suspend fun cleanupRejectedHandoff(): Throwable? {
        var cleanupFailure: Throwable? = null
        withContext(NonCancellable) {
            try {
                clearBrowserState()
            } catch (failure: CancellationException) {
                cleanupFailure = failure
            } catch (_: Throwable) {
                // The local unlock must survive a native WebView cleanup failure.
            }
            try {
                onRejected()
            } catch (failure: Throwable) {
                val previous = cleanupFailure
                if (previous == null) {
                    cleanupFailure = failure
                } else if (previous !== failure) {
                    previous.addSuppressed(failure)
                }
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

/**
 * Copies a validated fixed-origin native WebView snapshot into the private JVM CookieStore.
 * The previous one-use handoff is cleared first; validation happens before any new value is written,
 * and a partial write is cleared on error.
 */
internal fun copyRestrictedDesktopCookies(
    mode: DiscourseRestrictedBrowserMode,
    cookies: List<Cookie>,
    destination: CookieManager,
    nowEpochSeconds: Long = Clock.System.now().epochSeconds,
): Boolean {
    clearDesktopLinuxDoCookies(destination)
    if (nowEpochSeconds < 0L || cookies.size > MAX_COOKIE_COUNT) return false
    val bounded = mutableListOf<ValidatedDesktopCookie>()
    var aggregateLength = 0
    for (cookie in cookies) {
        val validated = cookie.toValidatedCookie(nowEpochSeconds) ?: continue
        aggregateLength += validated.name.length + validated.value.length
        if (aggregateLength > MAX_COOKIE_HEADER_LENGTH) return false
        bounded += validated
    }
    if (bounded.distinctBy { Triple(it.name, it.domain, it.path) }.size != bounded.size) return false
    if (
        mode == DiscourseRestrictedBrowserMode.ManualChallenge &&
        bounded.any { it.name == AUTH_COOKIE_NAME }
    ) {
        return false
    }
    val requiredName =
        if (mode == DiscourseRestrictedBrowserMode.FallbackLogin) {
            AUTH_COOKIE_NAME
        } else {
            CHALLENGE_COOKIE_NAME
        }
    if (bounded.none { it.name == requiredName && it.value.isNotEmpty() }) return false
    val selected =
        if (mode == DiscourseRestrictedBrowserMode.ManualChallenge) {
            bounded.filterNot { it.name == AUTH_COOKIE_NAME }
        } else {
            bounded
        }

    return try {
        selected.forEach { cookie -> destination.cookieStore.add(LINUX_DO_URI, cookie.toHttpCookie(nowEpochSeconds)) }
        true
    } catch (_: IllegalArgumentException) {
        clearDesktopLinuxDoCookies(destination)
        false
    } catch (_: SecurityException) {
        clearDesktopLinuxDoCookies(destination)
        false
    }
}

private fun Cookie.toValidatedCookie(nowEpochSeconds: Long): ValidatedDesktopCookie? {
    // Native engines use null for host-only cookies selected by getCookies(fixedOrigin).
    val normalizedDomain = domain?.removePrefix(".")?.lowercase() ?: LINUX_DO_HOST
    if (normalizedDomain != LINUX_DO_HOST) return null
    val normalizedPath = path?.takeIf(String::isNotBlank) ?: "/"
    if (
        name.isBlank() ||
        name.length > MAX_COOKIE_NAME_LENGTH ||
        value.length > MAX_COOKIE_VALUE_LENGTH ||
        normalizedPath.length > MAX_COOKIE_PATH_LENGTH ||
        !normalizedPath.startsWith('/') ||
        name.any(Char::isISOControl) ||
        value.any(Char::isISOControl) ||
        normalizedPath.any(Char::isISOControl)
    ) {
        return null
    }
    // ComposeWebView exposes Unix epoch seconds here, matching HttpCookie.maxAge's second unit.
    val expiry = expiresDate
    if (expiry != null && expiry <= nowEpochSeconds) return null
    return ValidatedDesktopCookie(
        name = name,
        value = value,
        domain = normalizedDomain,
        path = normalizedPath,
        expiresAtEpochSeconds = expiry,
        httpOnly = isHttpOnly == true || name == AUTH_COOKIE_NAME,
    )
}

private data class ValidatedDesktopCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAtEpochSeconds: Long?,
    val httpOnly: Boolean,
) {
    fun toHttpCookie(nowEpochSeconds: Long): HttpCookie =
        HttpCookie(name, value).also { cookie ->
            cookie.domain = domain
            cookie.path = path
            cookie.secure = true
            cookie.isHttpOnly = httpOnly
            cookie.version = 0
            cookie.maxAge =
                expiresAtEpochSeconds
                    ?.minus(nowEpochSeconds)
                    ?.coerceAtLeast(0L)
                    ?: -1L
        }
}

private fun clearDesktopLinuxDoCookies(destination: CookieManager) {
    destination.cookieStore
        .get(LINUX_DO_URI)
        .filter { cookie -> cookie.domain?.removePrefix(".")?.lowercase() in setOf(null, LINUX_DO_HOST) }
        .forEach { cookie ->
            if (!destination.cookieStore.remove(LINUX_DO_URI, cookie)) {
                destination.cookieStore.remove(null, cookie)
            }
        }
}

private const val LINUX_DO_HOST: String = "linux.do"
private const val USER_API_KEY_PATH: String = "/user-api-key/new"
private const val AUTH_COOKIE_NAME: String = "_t"
private const val CHALLENGE_COOKIE_NAME: String = "cf_clearance"
private const val MAX_COOKIE_COUNT: Int = 128
private const val MAX_COOKIE_HEADER_LENGTH: Int = 64 * 1024
private const val MAX_COOKIE_NAME_LENGTH: Int = 256
private const val MAX_COOKIE_VALUE_LENGTH: Int = 16 * 1024
private const val MAX_COOKIE_PATH_LENGTH: Int = 1_024
private const val WEBVIEW_READY_POLL_MILLIS: Long = 25L
private const val WEBVIEW_READY_TIMEOUT_MILLIS: Long = 15_000L
private const val DESKTOP_TERMINAL_RECEIPT_TIMEOUT_MILLIS: Long = 5_000L
private val LINUX_DO_URI: URI = URI.create("$DISCOURSE_ORIGIN/")
