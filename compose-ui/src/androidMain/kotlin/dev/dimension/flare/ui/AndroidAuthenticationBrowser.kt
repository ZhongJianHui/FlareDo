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
import android.webkit.JavascriptInterface
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import dev.dimension.flare.compose.ui.forum_auth_login_account_unavailable
import dev.dimension.flare.compose.ui.forum_auth_login_alternate_factor
import dev.dimension.flare.compose.ui.forum_auth_login_cloudflare_body
import dev.dimension.flare.compose.ui.forum_auth_login_cookie_missing
import dev.dimension.flare.compose.ui.forum_auth_login_identifier
import dev.dimension.flare.compose.ui.forum_auth_login_input_required
import dev.dimension.flare.compose.ui.forum_auth_login_invalid_credentials
import dev.dimension.flare.compose.ui.forum_auth_login_mini_body
import dev.dimension.flare.compose.ui.forum_auth_login_mini_title
import dev.dimension.flare.compose.ui.forum_auth_login_network_error
import dev.dimension.flare.compose.ui.forum_auth_login_password
import dev.dimension.flare.compose.ui.forum_auth_login_password_expired
import dev.dimension.flare.compose.ui.forum_auth_login_processing
import dev.dimension.flare.compose.ui.forum_auth_login_script_error
import dev.dimension.flare.compose.ui.forum_auth_login_second_factor
import dev.dimension.flare.compose.ui.forum_auth_login_second_factor_hint
import dev.dimension.flare.compose.ui.forum_auth_login_second_factor_submit
import dev.dimension.flare.compose.ui.forum_auth_login_submit
import dev.dimension.flare.compose.ui.forum_auth_login_totp_required
import dev.dimension.flare.compose.ui.forum_auth_login_unexpected_response
import dev.dimension.flare.compose.ui.forum_auth_login_verification_complete
import dev.dimension.flare.compose.ui.forum_auth_login_verification_expired
import dev.dimension.flare.compose.ui.forum_auth_login_verification_hint
import dev.dimension.flare.compose.ui.forum_auth_login_verification_rejected
import dev.dimension.flare.compose.ui.forum_auth_login_verification_required
import dev.dimension.flare.compose.ui.forum_auth_login_verification_retry
import dev.dimension.flare.compose.ui.forum_auth_login_verification_unavailable
import dev.dimension.flare.data.network.discourse.DISCOURSE_ORIGIN
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthenticationAction
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthenticationState
import dev.dimension.flare.data.network.discourse.auth.DiscourseBrowserUrlPolicy
import dev.dimension.flare.data.network.discourse.auth.DiscourseExternalAuthorization
import dev.dimension.flare.data.network.discourse.auth.DiscoursePasswordLoginFailureKind
import dev.dimension.flare.data.network.discourse.auth.DiscoursePasswordLoginResponse
import dev.dimension.flare.data.network.discourse.auth.DiscoursePasswordLoginResponseParser
import dev.dimension.flare.data.network.discourse.auth.DiscourseRestrictedBrowserMode
import dev.dimension.flare.data.network.discourse.auth.DiscourseRestrictedBrowserRequest
import dev.dimension.flare.data.network.discourse.auth.DiscourseRestrictedBrowserTerminalAction
import dev.dimension.flare.data.network.discourse.sanitizeAndroidDiscourseBrowserUserAgent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.io.ByteArrayInputStream
import java.util.UUID
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
    val miniLoginState =
        remember(request.requestId, request.mode) {
            AndroidMiniLoginUiState()
        }
    val miniLoginVerificationHint =
        stringResource(Res.string.forum_auth_login_verification_hint)
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
            miniLoginState.clearSensitive()
            miniLoginState.activeWebView = null
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

    fun resetMiniCaptcha() {
        miniLoginState.captchaToken = null
        miniLoginState.captchaTokenForRetry = null
        miniLoginState.captchaPassed = false
        miniLoginState.loginRequested = false
        miniLoginState.csrfRetryUsed = false
        miniLoginState.secondFactorTokenForRetry = null
        miniLoginState.awaitingCaptcha = false
        miniLoginState.resumeAfterBootstrap = false
        webView?.let { view ->
            if (!view.hasLostRenderer) {
                runCatching {
                    view.evaluateJavascript("window.__flareDoResetCaptcha && window.__flareDoResetCaptcha();", null)
                }
            }
        }
    }

    fun runMiniLogin(
        hcaptchaToken: String?,
        secondFactorToken: String?,
    ) {
        val view = webView ?: return
        if (view.hasLostRenderer || miniLoginState.completionStarted) return
        if (!miniLoginState.pageReady || miniLoginState.loginInFlight) {
            if (!miniLoginState.pageReady) {
                // The user may submit while the bootstrap document is still settling. Keep the
                // verified token in memory and let PageReady consume the pending request instead
                // of evaluating a login function that has not been installed yet.
                hcaptchaToken?.let { token ->
                    miniLoginState.captchaToken = token
                    miniLoginState.captchaPassed = true
                }
                secondFactorToken?.let { token ->
                    miniLoginState.secondFactorTokenForRetry = token
                }
                if (hcaptchaToken != null || secondFactorToken != null) {
                    miniLoginState.loginRequested = true
                    miniLoginState.awaitingCaptcha = hcaptchaToken != null
                }
            }
            return
        }
        if (secondFactorToken == null && hcaptchaToken.isNullOrBlank()) {
            miniLoginState.error = AndroidMiniLoginError.VerificationRequired
            return
        }
        miniLoginState.loginInFlight = true
        miniLoginState.processing = true
        miniLoginState.error = null
        miniLoginState.captchaTokenForRetry = hcaptchaToken?.takeIf(String::isNotEmpty)
        miniLoginState.secondFactorTokenForRetry = secondFactorToken?.takeIf(String::isNotEmpty)
        if (hcaptchaToken != null) {
            miniLoginState.captchaToken = null
            miniLoginState.captchaPassed = false
        }
        val identifier = miniLoginState.identifier.trim()
        val password = miniLoginState.password
        val invocation =
            buildMiniLoginInvocation(
                identifier = identifier,
                password = password,
                hcaptchaToken = hcaptchaToken,
                secondFactorToken = secondFactorToken,
            )
        try {
            view.evaluateJavascript(invocation, null)
        } catch (_: Throwable) {
            miniLoginState.loginInFlight = false
            miniLoginState.processing = false
            miniLoginState.error = AndroidMiniLoginError.Script
        }
    }

    fun submitMiniLogin() {
        if (handoffInProgress || preparationFailed || miniLoginState.processing) return
        val identifier = miniLoginState.identifier.trim()
        if (!isValidMiniLoginIdentifier(identifier)) {
            miniLoginState.error = AndroidMiniLoginError.InputRequired
            return
        }
        if (miniLoginState.secondFactorRequired) {
            if (!miniLoginState.totpEnabled) {
                miniLoginState.error = AndroidMiniLoginError.AlternateFactor
                return
            }
            val code = miniLoginState.secondFactorCode.trim()
            if (!isValidMiniLoginTotp(code)) {
                miniLoginState.error = AndroidMiniLoginError.TotpRequired
                return
            }
            runMiniLogin(hcaptchaToken = null, secondFactorToken = code)
            return
        }
        if (miniLoginState.password.isEmpty() || miniLoginState.password.length > MAX_MINI_LOGIN_PASSWORD_LENGTH) {
            miniLoginState.error = AndroidMiniLoginError.InputRequired
            return
        }
        miniLoginState.loginRequested = true
        miniLoginState.error = null
        miniLoginState.captchaToken?.let { token ->
            runMiniLogin(hcaptchaToken = token, secondFactorToken = null)
        } ?: run {
            miniLoginState.awaitingCaptcha = true
        }
    }

    suspend fun awaitMiniLoginCookie(): Boolean {
        repeat(MINI_LOGIN_COOKIE_WAIT_ATTEMPTS) {
            val cookieHeader =
                try {
                    CookieManager.getInstance().getCookie(DISCOURSE_ORIGIN)
                } catch (_: RuntimeException) {
                    null
                }
            if (isValidRestrictedCookieHandoff(DiscourseRestrictedBrowserMode.FallbackLogin, cookieHeader)) {
                return true
            }
            delay(MINI_LOGIN_COOKIE_WAIT_DELAY_MILLIS)
        }
        return false
    }

    fun handleMiniLoginEvent(event: AndroidMiniLoginEvent) {
        when (event) {
            AndroidMiniLoginEvent.PageReady -> {
                miniLoginState.pageReady = true
                miniLoginState.challengeVisible = false
                if (miniLoginState.resumeAfterBootstrap) {
                    miniLoginState.resumeAfterBootstrap = false
                    val hcaptchaToken = miniLoginState.captchaTokenForRetry
                    val secondFactorToken = miniLoginState.secondFactorTokenForRetry
                    if (hcaptchaToken != null || secondFactorToken != null) {
                        runMiniLogin(
                            hcaptchaToken = hcaptchaToken,
                            secondFactorToken = secondFactorToken,
                        )
                    }
                } else if (miniLoginState.secondFactorTokenForRetry != null) {
                    val token = miniLoginState.secondFactorTokenForRetry
                    miniLoginState.secondFactorTokenForRetry = null
                    runMiniLogin(hcaptchaToken = null, secondFactorToken = token)
                } else if (miniLoginState.loginRequested && !miniLoginState.loginInFlight) {
                    miniLoginState.captchaToken?.let { token ->
                        runMiniLogin(hcaptchaToken = token, secondFactorToken = null)
                    }
                }
            }

            is AndroidMiniLoginEvent.CaptchaPassed -> {
                if (!isValidMiniLoginCaptchaToken(event.token)) return
                miniLoginState.captchaToken = event.token
                miniLoginState.captchaPassed = true
                miniLoginState.awaitingCaptcha = false
                miniLoginState.error = null
                if (
                    !miniLoginState.secondFactorRequired &&
                    miniLoginState.loginRequested &&
                    !miniLoginState.loginInFlight
                ) {
                    runMiniLogin(hcaptchaToken = event.token, secondFactorToken = null)
                }
            }

            AndroidMiniLoginEvent.CaptchaExpired -> {
                miniLoginState.captchaToken = null
                miniLoginState.captchaPassed = false
                miniLoginState.error = AndroidMiniLoginError.VerificationExpired
            }

            AndroidMiniLoginEvent.CaptchaError -> {
                miniLoginState.captchaToken = null
                miniLoginState.captchaPassed = false
                miniLoginState.awaitingCaptcha = false
                miniLoginState.resumeAfterBootstrap = false
                miniLoginState.error = AndroidMiniLoginError.VerificationUnavailable
            }

            AndroidMiniLoginEvent.CaptchaUnavailable -> {
                miniLoginState.captchaToken = null
                miniLoginState.captchaPassed = false
                miniLoginState.awaitingCaptcha = false
                miniLoginState.resumeAfterBootstrap = false
                miniLoginState.error = AndroidMiniLoginError.VerificationUnavailable
            }

            is AndroidMiniLoginEvent.LoginResponse -> {
                miniLoginState.loginInFlight = false
                miniLoginState.processing = false
                when (event.phase) {
                    "csrf" -> {
                        if (event.statusCode == 403 && !miniLoginState.csrfRetryUsed) {
                            miniLoginState.csrfRetryUsed = true
                            miniLoginState.resumeAfterBootstrap =
                                miniLoginState.captchaTokenForRetry != null ||
                                miniLoginState.secondFactorTokenForRetry != null
                            miniLoginState.pageReady = false
                            miniLoginState.challengeVisible = true
                            webView?.let { view ->
                                if (!view.hasLostRenderer) {
                                    startMiniLoginBootstrap(view, miniLoginState)
                                }
                            }
                        } else {
                            miniLoginState.error = AndroidMiniLoginError.VerificationRequired
                            resetMiniCaptcha()
                        }
                    }

                    "hcaptcha" -> {
                        miniLoginState.error = AndroidMiniLoginError.VerificationRejected
                        resetMiniCaptcha()
                    }

                    "session" -> {
                        when (val parsed = DiscoursePasswordLoginResponseParser.parse(event.statusCode, event.body)) {
                            DiscoursePasswordLoginResponse.Success -> {
                                miniLoginState.completionStarted = true
                                miniLoginState.processing = true
                                miniLoginState.error = null
                                scope.launch {
                                    if (awaitMiniLoginCookie()) {
                                        miniLoginState.clearSensitive()
                                        submitTerminalAction(
                                            DiscourseAuthenticationAction.CompleteRestrictedBrowser(
                                                requestId = request.requestId,
                                                mode = request.mode,
                                            ),
                                        )
                                    } else {
                                        miniLoginState.completionStarted = false
                                        miniLoginState.processing = false
                                        miniLoginState.error = AndroidMiniLoginError.CookieMissing
                                        resetMiniCaptcha()
                                    }
                                }
                            }

                            is DiscoursePasswordLoginResponse.Failure -> {
                                miniLoginState.captchaTokenForRetry = null
                                when (parsed.kind) {
                                    DiscoursePasswordLoginFailureKind.SecondFactorRequired -> {
                                        miniLoginState.secondFactorRequired = true
                                        miniLoginState.totpEnabled = parsed.secondFactor?.totpEnabled == true
                                        miniLoginState.backupCodeEnabled =
                                            parsed.secondFactor?.backupCodeEnabled == true
                                        miniLoginState.securityKeyEnabled =
                                            parsed.secondFactor?.securityKeyEnabled == true
                                        miniLoginState.secondFactorCode = ""
                                        miniLoginState.loginRequested = false
                                        miniLoginState.awaitingCaptcha = false
                                        miniLoginState.secondFactorTokenForRetry = null
                                        miniLoginState.error = null
                                    }

                                    DiscoursePasswordLoginFailureKind.InvalidCredentials -> {
                                        miniLoginState.error = AndroidMiniLoginError.InvalidCredentials
                                        resetMiniCaptcha()
                                    }

                                    DiscoursePasswordLoginFailureKind.NotActivated -> {
                                        miniLoginState.error = AndroidMiniLoginError.AccountUnavailable
                                        resetMiniCaptcha()
                                    }

                                    DiscoursePasswordLoginFailureKind.NotApproved -> {
                                        miniLoginState.error = AndroidMiniLoginError.AccountUnavailable
                                        resetMiniCaptcha()
                                    }

                                    DiscoursePasswordLoginFailureKind.PasswordExpired -> {
                                        miniLoginState.error = AndroidMiniLoginError.PasswordExpired
                                        resetMiniCaptcha()
                                    }

                                    DiscoursePasswordLoginFailureKind.Unknown -> {
                                        miniLoginState.error = AndroidMiniLoginError.Unexpected
                                        resetMiniCaptcha()
                                    }
                                }
                            }

                            is DiscoursePasswordLoginResponse.Unexpected -> {
                                miniLoginState.error = AndroidMiniLoginError.Unexpected
                                resetMiniCaptcha()
                            }
                        }
                    }

                    "exception" -> {
                        miniLoginState.error = AndroidMiniLoginError.Network
                        resetMiniCaptcha()
                    }

                    else -> {
                        miniLoginState.error = AndroidMiniLoginError.Unexpected
                        resetMiniCaptcha()
                    }
                }
            }
        }
    }

    val miniEventHandler =
        rememberUpdatedState<(AndroidMiniLoginEvent) -> Unit> { event ->
            handleMiniLoginEvent(event)
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
                    if (request.mode == DiscourseRestrictedBrowserMode.ManualChallenge) {
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
                    if (request.mode == DiscourseRestrictedBrowserMode.FallbackLogin) {
                        AndroidMiniLoginSurface(
                            state = miniLoginState,
                            modifier = Modifier.fillMaxSize(),
                            onSubmit = { submitMiniLogin() },
                            onRetryCaptcha = {
                                if (!miniLoginState.processing && !handoffInProgress) {
                                    resetMiniCaptcha()
                                    webView?.let { view -> startMiniLoginBootstrap(view, miniLoginState) }
                                }
                            },
                            onCreateWebView = { hostContext ->
                                createMiniRestrictedAndroidWebView(
                                    context = hostContext,
                                    request = request,
                                    state = miniLoginState,
                                    instruction = miniLoginVerificationHint,
                                    onEvent = { event ->
                                        hostContext.findRestrictedBrowserHostActivity()?.runOnUiThread {
                                            miniEventHandler.value(event)
                                        } ?: miniEventHandler.value(event)
                                    },
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
                            onWebViewReleased = { released ->
                                if (webView === released) webView = null
                                if (miniLoginState.activeWebView === released) {
                                    miniLoginState.activeWebView = null
                                }
                                released.disposeRestrictedBrowser()
                            },
                        )
                    } else {
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
}

@Suppress("DEPRECATION", "SetJavaScriptEnabled")
private fun createRestrictedAndroidWebView(
    context: Context,
    request: DiscourseRestrictedBrowserRequest,
    onBrowserFailure: () -> Unit,
    initialUrl: String = request.initialUrl,
    onMainFramePageFinished: ((WebView, String) -> Unit)? = null,
    beforeInitialLoad: ((WebView) -> Unit)? = null,
): WebView =
    RestrictedAndroidWebView(context).apply webView@{
        val failureGate = AndroidBrowserFailureGate(onBrowserFailure)
        WebView.setWebContentsDebuggingEnabled(false)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        settings.apply {
            // Discourse and Cloudflare require JavaScript. Fallback login adds only the bounded,
            // nonce-protected bridge installed immediately before its fixed-origin bootstrap.
            userAgentString =
                sanitizeAndroidDiscourseBrowserUserAgent(
                    WebSettings.getDefaultUserAgent(context.applicationContext),
                )
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
            // hCaptcha renders in a cross-origin iframe. This WebView is a one-request profile and
            // is cleared on every terminal path, so third-party cookies do not become app storage.
            setAcceptThirdPartyCookies(this@webView, true)
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

                override fun onPageFinished(
                    view: WebView,
                    url: String,
                ) {
                    if (!view.hasLostRenderer) onMainFramePageFinished?.invoke(view, url)
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
        beforeInitialLoad?.invoke(this@webView)
        clearCache(true)
        clearHistory()
        loadUrl(initialUrl)
    }

/** Native credential surface around the short-lived hCaptcha WebView. */
@Composable
private fun AndroidMiniLoginSurface(
    state: AndroidMiniLoginUiState,
    modifier: Modifier,
    onSubmit: () -> Unit,
    onRetryCaptcha: () -> Unit,
    onCreateWebView: (Context) -> WebView,
    onWebViewReleased: (WebView) -> Unit,
) {
    val scrollState = rememberScrollState()
    val errorMessage =
        state.error?.let { error ->
            stringResource(
                when (error) {
                    AndroidMiniLoginError.InputRequired -> Res.string.forum_auth_login_input_required
                    AndroidMiniLoginError.VerificationRequired -> Res.string.forum_auth_login_verification_required
                    AndroidMiniLoginError.VerificationExpired -> Res.string.forum_auth_login_verification_expired
                    AndroidMiniLoginError.VerificationUnavailable -> Res.string.forum_auth_login_verification_unavailable
                    AndroidMiniLoginError.VerificationRejected -> Res.string.forum_auth_login_verification_rejected
                    AndroidMiniLoginError.InvalidCredentials -> Res.string.forum_auth_login_invalid_credentials
                    AndroidMiniLoginError.AccountUnavailable -> Res.string.forum_auth_login_account_unavailable
                    AndroidMiniLoginError.PasswordExpired -> Res.string.forum_auth_login_password_expired
                    AndroidMiniLoginError.Network -> Res.string.forum_auth_login_network_error
                    AndroidMiniLoginError.Unexpected -> Res.string.forum_auth_login_unexpected_response
                    AndroidMiniLoginError.CookieMissing -> Res.string.forum_auth_login_cookie_missing
                    AndroidMiniLoginError.AlternateFactor -> Res.string.forum_auth_login_alternate_factor
                    AndroidMiniLoginError.TotpRequired -> Res.string.forum_auth_login_totp_required
                    AndroidMiniLoginError.Script -> Res.string.forum_auth_login_script_error
                },
            )
        }
    Column(
        modifier = modifier.verticalScroll(scrollState).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(Res.string.forum_auth_login_mini_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(Res.string.forum_auth_login_mini_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.identifier,
            onValueChange = { value ->
                if (value.length <= MAX_MINI_LOGIN_IDENTIFIER_LENGTH) {
                    state.identifier = value
                    state.error = null
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.processing,
            singleLine = true,
            label = { Text(stringResource(Res.string.forum_auth_login_identifier)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = { value ->
                if (value.length <= MAX_MINI_LOGIN_PASSWORD_LENGTH) {
                    state.password = value
                    state.error = null
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.processing,
            singleLine = true,
            label = { Text(stringResource(Res.string.forum_auth_login_password)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        if (state.secondFactorRequired) {
            Text(
                text = stringResource(Res.string.forum_auth_login_second_factor),
                style = MaterialTheme.typography.titleSmall,
            )
            OutlinedTextField(
                value = state.secondFactorCode,
                onValueChange = { value ->
                    if (value.length <= MAX_MINI_LOGIN_TOTP_LENGTH && value.all(Char::isDigit)) {
                        state.secondFactorCode = value
                        state.error = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.processing,
                singleLine = true,
                label = { Text(stringResource(Res.string.forum_auth_login_second_factor_hint)) },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
            )
        }
        Text(
            text =
                when {
                    state.challengeVisible -> stringResource(Res.string.forum_auth_login_cloudflare_body)
                    state.processing -> stringResource(Res.string.forum_auth_login_processing)
                    state.captchaPassed -> stringResource(Res.string.forum_auth_login_verification_complete)
                    else -> stringResource(Res.string.forum_auth_login_verification_hint)
                },
            style = MaterialTheme.typography.bodySmall,
            color =
                if (state.error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        Box(
            // Interactive hCaptcha challenges can open a roughly 600px verification panel. Keep
            // enough stable viewport for that panel; the surrounding dialog remains scrollable on
            // compact screens.
            modifier = Modifier.fillMaxWidth().height(520.dp),
        ) {
            AndroidView(
                factory = onCreateWebView,
                modifier = Modifier.fillMaxSize(),
                onRelease = onWebViewReleased,
            )
        }
        errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (
            state.error == AndroidMiniLoginError.VerificationUnavailable ||
            state.error == AndroidMiniLoginError.VerificationRejected ||
            state.error == AndroidMiniLoginError.VerificationExpired
        ) {
            TextButton(
                onClick = onRetryCaptcha,
                enabled = !state.processing && !state.completionStarted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.forum_auth_login_verification_retry))
            }
        }
        Button(
            onClick = onSubmit,
            enabled =
                !state.processing &&
                    !state.completionStarted &&
                    isValidMiniLoginIdentifier(state.identifier.trim()) &&
                    (
                        if (state.secondFactorRequired) {
                            isValidMiniLoginTotp(state.secondFactorCode.trim())
                        } else {
                            state.password.isNotEmpty()
                        }
                    ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (state.secondFactorRequired) {
                        Res.string.forum_auth_login_second_factor_submit
                    } else {
                        Res.string.forum_auth_login_submit
                    },
                ),
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

private fun createMiniRestrictedAndroidWebView(
    context: Context,
    request: DiscourseRestrictedBrowserRequest,
    state: AndroidMiniLoginUiState,
    instruction: String,
    onEvent: (AndroidMiniLoginEvent) -> Unit,
    onBrowserFailure: () -> Unit,
): WebView {
    state.bootstrapGeneration += 1
    state.htmlInjected = false
    state.pageReady = false
    state.challengeVisible = true
    state.challengeProbeAttempts = 0
    return createRestrictedAndroidWebView(
        context = context,
        request = request,
        onBrowserFailure = onBrowserFailure,
        initialUrl = MINI_LOGIN_BOOTSTRAP_URL,
        beforeInitialLoad = { view ->
            state.activeWebView = view
            val bridge =
                AndroidMiniLoginJavascriptBridge(
                    nonce = state.bridgeNonce,
                    onEvent = { event ->
                        // JavascriptInterface callbacks run off the main thread. Posting through the
                        // exact WebView also drops callbacks from a released renderer.
                        view.post { if (state.activeWebView === view) onEvent(event) }
                    },
                )
            view.addJavascriptInterface(bridge, MINI_LOGIN_BRIDGE_NAME)
        },
        onMainFramePageFinished = { view, _ ->
            handleMiniLoginPageFinished(view, state, instruction, onEvent)
        },
    )
}

private fun handleMiniLoginPageFinished(
    view: WebView,
    state: AndroidMiniLoginUiState,
    instruction: String,
    onEvent: (AndroidMiniLoginEvent) -> Unit,
) {
    if (view.hasLostRenderer || state.activeWebView !== view || state.htmlInjected) return
    val generation = state.bootstrapGeneration
    if (state.challengeProbeAttempts >= MINI_LOGIN_MAX_CHALLENGE_PROBE_ATTEMPTS) {
        onEvent(AndroidMiniLoginEvent.CaptchaUnavailable)
        return
    }
    state.challengeProbeAttempts += 1
    runCatching {
        view.evaluateJavascript(MINI_LOGIN_CHALLENGE_PROBE) { rawResult ->
            if (
                view.hasLostRenderer ||
                state.activeWebView !== view ||
                state.htmlInjected ||
                generation != state.bootstrapGeneration
            ) {
                return@evaluateJavascript
            }
            val challenge = parseJavascriptBoolean(rawResult)
            if (challenge == null || challenge) {
                state.challengeVisible = true
                view.postDelayed(
                    { handleMiniLoginPageFinished(view, state, instruction, onEvent) },
                    MINI_LOGIN_CHALLENGE_POLL_DELAY_MILLIS,
                )
                return@evaluateJavascript
            }
            state.challengeVisible = false
            state.htmlInjected = true
            runCatching {
                view.loadDataWithBaseURL(
                    "$DISCOURSE_ORIGIN/",
                    buildMiniLoginHtml(state.bridgeNonce, instruction),
                    "text/html",
                    "UTF-8",
                    MINI_LOGIN_BOOTSTRAP_URL,
                )
            }.onFailure {
                state.htmlInjected = false
                onEvent(AndroidMiniLoginEvent.CaptchaError)
            }
        }
    }.onFailure {
        onEvent(AndroidMiniLoginEvent.CaptchaError)
    }
}

private fun startMiniLoginBootstrap(
    view: WebView,
    state: AndroidMiniLoginUiState,
) {
    if (view.hasLostRenderer || state.activeWebView !== view) return
    state.bootstrapGeneration += 1
    state.htmlInjected = false
    state.pageReady = false
    state.challengeVisible = true
    state.challengeProbeAttempts = 0
    view.loadUrl(MINI_LOGIN_BOOTSTRAP_URL)
}

internal class AndroidMiniLoginJavascriptBridge(
    private val nonce: String,
    private val onEvent: (AndroidMiniLoginEvent) -> Unit,
) {
    @JavascriptInterface
    fun ready(receivedNonce: String?) {
        if (receivedNonce == nonce) onEvent(AndroidMiniLoginEvent.PageReady)
    }

    @JavascriptInterface
    fun captcha(
        receivedNonce: String?,
        token: String?,
    ) {
        if (receivedNonce != nonce || !isValidMiniLoginCaptchaToken(token)) return
        onEvent(AndroidMiniLoginEvent.CaptchaPassed(requireNotNull(token)))
    }

    @JavascriptInterface
    fun captchaExpired(receivedNonce: String?) {
        if (receivedNonce == nonce) onEvent(AndroidMiniLoginEvent.CaptchaExpired)
    }

    @JavascriptInterface
    fun captchaError(receivedNonce: String?) {
        if (receivedNonce == nonce) onEvent(AndroidMiniLoginEvent.CaptchaError)
    }

    @JavascriptInterface
    fun captchaUnavailable(receivedNonce: String?) {
        if (receivedNonce == nonce) onEvent(AndroidMiniLoginEvent.CaptchaUnavailable)
    }

    @JavascriptInterface
    fun result(
        receivedNonce: String?,
        phase: String?,
        statusCode: Int,
        body: String?,
    ) {
        val safePhase = phase ?: return
        val safeBody = body ?: return
        if (
            receivedNonce != nonce ||
            safePhase !in MINI_LOGIN_RESPONSE_PHASES ||
            statusCode !in 0..599 ||
            safeBody.length > DiscoursePasswordLoginResponseParser.MAX_RESPONSE_CHARS ||
            safeBody.any { it == '\u0000' }
        ) {
            return
        }
        onEvent(AndroidMiniLoginEvent.LoginResponse(safePhase, statusCode, safeBody))
    }
}

internal fun buildMiniLoginHtml(
    nonce: String,
    instruction: String = "Complete the verification to continue signing in.",
): String {
    val quotedNonce = quoteMiniLoginJson(nonce)
    val quotedSiteKey = quoteMiniLoginJson(LINUX_DO_HCAPTCHA_SITE_KEY)
    val quotedInstruction = quoteMiniLoginJson(instruction)
    return """
<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
  <style>
    html,body { margin:0; min-height:100%; background:transparent; }
    body { font-family: sans-serif; color:#263238; }
    .wrap { min-height:160px; display:flex; flex-direction:column; align-items:center;
      justify-content:center; gap:12px; padding:12px; box-sizing:border-box; text-align:center; }
    .tip { margin:0; font-size:14px; line-height:1.45; }
    .h-captcha { min-height:78px; }
  </style>
</head>
<body>
  <main class="wrap">
    <p id="instruction" class="tip"></p>
    <div id="flaredo-captcha" class="h-captcha"></div>
  </main>
  <script>
    (function() {
      var nonce = $quotedNonce;
      var captchaId = null;
      var captchaLoadDeadline = 20000;
      document.getElementById('instruction').textContent = $quotedInstruction;
      function callReady() { try { window.FlareDoMini.ready(nonce); } catch (_) {} }
      function sendResult(phase, status, body) {
        try { window.FlareDoMini.result(nonce, phase, status, body || ''); } catch (_) {}
      }
      function bounded(text) {
        text = String(text || '');
        return text.length > 65536 ? text.slice(0, 65536) : text;
      }
      function onPass(token) {
        try { window.FlareDoMini.captcha(nonce, String(token || '')); } catch (_) {}
      }
      function onError() { try { window.FlareDoMini.captchaError(nonce); } catch (_) {} }
      function onExpired() { try { window.FlareDoMini.captchaExpired(nonce); } catch (_) {} }
      function onUnavailable() { try { window.FlareDoMini.captchaUnavailable(nonce); } catch (_) {} }
      window.onPass = onPass;
      window.onError = onError;
      window.onExpired = onExpired;
      window.flareDoCaptchaLoaded = function() {
        try {
          if (!window.hcaptcha || captchaId !== null) return;
          captchaId = window.hcaptcha.render('flaredo-captcha', {
            sitekey: $quotedSiteKey,
            callback: onPass,
            'error-callback': onError,
            'expired-callback': onExpired,
            size: 'normal'
          });
        } catch (_) { onError(); }
      };
      window.__flareDoResetCaptcha = function() {
        try {
          if (window.hcaptcha && captchaId !== null) {
            window.hcaptcha.reset(captchaId);
          } else {
            captchaId = null;
            window.flareDoCaptchaLoaded();
          }
        } catch (_) { onError(); }
      };
      window.__flareDoLogin = async function(identifier, password, hcaptchaToken, secondFactorToken) {
        try {
          var csrfResponse = await fetch('/session/csrf', {
            method:'GET', credentials:'include', cache:'no-store',
            headers:{'Accept':'application/json','X-Requested-With':'XMLHttpRequest'}
          });
          if (csrfResponse.status !== 200) {
            return sendResult('csrf', csrfResponse.status, '');
          }
          var csrfPayload = await csrfResponse.json();
          var csrf = csrfPayload && typeof csrfPayload.csrf === 'string' ? csrfPayload.csrf : '';
          if (!csrf) return sendResult('csrf', 200, '');
          if (hcaptchaToken) {
            var endpoints = ['/captcha/hcaptcha/create.json', '/hcaptcha/create.json'];
            var verified = false;
            var captchaStatus = 0;
            for (var i = 0; i < endpoints.length; i++) {
              try {
                var captchaResponse = await fetch(endpoints[i], {
                  method:'POST', credentials:'include',
                  headers:{'Content-Type':'application/x-www-form-urlencoded',
                    'X-CSRF-Token':csrf,'X-Requested-With':'XMLHttpRequest'},
                  body:'token=' + encodeURIComponent(hcaptchaToken)
                });
                captchaStatus = captchaResponse.status;
                if (captchaStatus >= 200 && captchaStatus < 300) {
                  verified = true; break;
                }
                if (captchaStatus !== 404) break;
              } catch (_) {
                captchaStatus = 0;
              }
            }
            if (!verified) return sendResult('hcaptcha', captchaStatus, '');
          }
          var form = 'login=' + encodeURIComponent(identifier) + '&password=' + encodeURIComponent(password);
          if (secondFactorToken) {
            form += '&second_factor_token=' + encodeURIComponent(secondFactorToken) + '&second_factor_method=1';
          }
          var sessionResponse = await fetch('/session.json', {
            method:'POST', credentials:'include',
            headers:{'Content-Type':'application/x-www-form-urlencoded',
              'X-CSRF-Token':csrf,'X-Requested-With':'XMLHttpRequest','Accept':'application/json'},
            body:form
          });
          sendResult('session', sessionResponse.status, bounded(await sessionResponse.text()));
        } catch (_) {
          sendResult('exception', 0, '');
        }
      };
      if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', callReady, {once:true});
      } else { callReady(); }
      setTimeout(function() {
        if (!window.hcaptcha || captchaId === null) onUnavailable();
      }, captchaLoadDeadline);
    })();
  </script>
  <script src="https://js.hcaptcha.com/1/api.js?onload=flareDoCaptchaLoaded&render=explicit" async defer></script>
</body>
</html>
        """.trimIndent()
}

internal fun buildMiniLoginInvocation(
    identifier: String,
    password: String,
    hcaptchaToken: String?,
    secondFactorToken: String?,
): String =
    "window.__flareDoLogin(" +
        quoteMiniLoginJson(identifier) + "," +
        quoteMiniLoginJson(password) + "," +
        (hcaptchaToken?.let(::quoteMiniLoginJson) ?: "null") + "," +
        (secondFactorToken?.let(::quoteMiniLoginJson) ?: "null") +
        ");"

/** Quotes a bounded Kotlin string for insertion as a JavaScript JSON string literal. */
internal fun quoteMiniLoginJson(value: String): String =
    buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> {
                    append("\\\"")
                }

                '\\' -> {
                    append("\\\\")
                }

                '\b' -> {
                    append("\\b")
                }

                '\u000C' -> {
                    append("\\f")
                }

                '\n' -> {
                    append("\\n")
                }

                '\r' -> {
                    append("\\r")
                }

                '\t' -> {
                    append("\\t")
                }

                '\u2028', '\u2029' -> {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                }

                in '\u0000'..'\u001F', in '\uD800'..'\uDFFF' -> {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                }

                else -> {
                    append(character)
                }
            }
        }
        append('"')
    }

internal fun parseJavascriptBoolean(rawResult: String?): Boolean? =
    rawResult?.trim()?.let { raw ->
        when (raw) {
            "true", "\"true\"" -> true
            "false", "\"false\"" -> false
            else -> null
        }
    }

private fun isValidMiniLoginIdentifier(value: String): Boolean =
    value.isNotBlank() &&
        value.length <= MAX_MINI_LOGIN_IDENTIFIER_LENGTH &&
        value.none(Char::isISOControl)

private fun isValidMiniLoginTotp(value: String): Boolean = value.length == 6 && value.all(Char::isDigit)

private fun isValidMiniLoginCaptchaToken(value: String?): Boolean =
    value != null &&
        value.isNotEmpty() &&
        value.length <= MAX_MINI_LOGIN_CAPTCHA_LENGTH &&
        value.none(Char::isISOControl)

private enum class AndroidMiniLoginError {
    InputRequired,
    VerificationRequired,
    VerificationExpired,
    VerificationUnavailable,
    VerificationRejected,
    InvalidCredentials,
    AccountUnavailable,
    PasswordExpired,
    Network,
    Unexpected,
    CookieMissing,
    AlternateFactor,
    TotpRequired,
    Script,
}

internal sealed interface AndroidMiniLoginEvent {
    data object PageReady : AndroidMiniLoginEvent

    data class CaptchaPassed(
        val token: String,
    ) : AndroidMiniLoginEvent

    data object CaptchaExpired : AndroidMiniLoginEvent

    data object CaptchaError : AndroidMiniLoginEvent

    data object CaptchaUnavailable : AndroidMiniLoginEvent

    data class LoginResponse(
        val phase: String,
        val statusCode: Int,
        val body: String,
    ) : AndroidMiniLoginEvent
}

private class AndroidMiniLoginUiState {
    val bridgeNonce: String = UUID.randomUUID().toString().replace("-", "")
    var identifier by mutableStateOf("")
    var password by mutableStateOf("")
    var secondFactorCode by mutableStateOf("")
    var captchaPassed by mutableStateOf(false)
    var awaitingCaptcha by mutableStateOf(false)
    var processing by mutableStateOf(false)
    var secondFactorRequired by mutableStateOf(false)
    var totpEnabled by mutableStateOf(false)
    var backupCodeEnabled by mutableStateOf(false)
    var securityKeyEnabled by mutableStateOf(false)
    var pageReady by mutableStateOf(false)
    var challengeVisible by mutableStateOf(false)
    var error by mutableStateOf<AndroidMiniLoginError?>(null)

    // These fields never enter saved state or shared presenter state.
    var captchaToken: String? = null
    var captchaTokenForRetry: String? = null
    var secondFactorTokenForRetry: String? = null
    var loginRequested: Boolean = false
    var loginInFlight: Boolean = false
    var completionStarted: Boolean = false
    var csrfRetryUsed: Boolean = false
    var resumeAfterBootstrap: Boolean = false
    var htmlInjected: Boolean = false
    var bootstrapGeneration: Long = 0L
    var challengeProbeAttempts: Int = 0
    var activeWebView: WebView? = null

    fun clearSensitive() {
        identifier = ""
        password = ""
        secondFactorCode = ""
        captchaToken = null
        captchaTokenForRetry = null
        secondFactorTokenForRetry = null
        loginRequested = false
        loginInFlight = false
    }
}

private const val LINUX_DO_HCAPTCHA_SITE_KEY = "a776b4ac-8c4c-441e-986a-c6ee9ed8cf08"
private const val MINI_LOGIN_BRIDGE_NAME = "FlareDoMini"
internal const val MINI_LOGIN_BOOTSTRAP_URL: String = "$DISCOURSE_ORIGIN/session/csrf"
private const val MINI_LOGIN_CHALLENGE_PROBE =
    "(function(){var t=((document.title||'')+' '+(document.body&&document.body.innerText||'')).toLowerCase().slice(0,8192);return /just a moment|checking your browser|challenge-platform|cf-chl-|verify you are human|cloudflare security/.test(t);})()"
private val MINI_LOGIN_RESPONSE_PHASES = setOf("csrf", "hcaptcha", "session", "exception")
private const val MAX_MINI_LOGIN_IDENTIFIER_LENGTH = 254
private const val MAX_MINI_LOGIN_PASSWORD_LENGTH = 512
private const val MAX_MINI_LOGIN_TOTP_LENGTH = 6
private const val MAX_MINI_LOGIN_CAPTCHA_LENGTH = 16 * 1024
private const val MINI_LOGIN_CHALLENGE_POLL_DELAY_MILLIS = 800L
private const val MINI_LOGIN_MAX_CHALLENGE_PROBE_ATTEMPTS = 225
private const val MINI_LOGIN_COOKIE_WAIT_ATTEMPTS = 12
private const val MINI_LOGIN_COOKIE_WAIT_DELAY_MILLIS = 250L

private fun WebView.disposeRestrictedBrowser() {
    if (hasLostRenderer) {
        destroy()
        return
    }
    removeJavascriptInterface(MINI_LOGIN_BRIDGE_NAME)
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
