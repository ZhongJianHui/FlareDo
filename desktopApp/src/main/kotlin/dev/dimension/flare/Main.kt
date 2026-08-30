package dev.dimension.flare

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthRedirectParser
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthenticationPresenter
import dev.dimension.flare.data.network.discourse.auth.DiscourseLoginService
import dev.dimension.flare.data.network.discourse.auth.DiscourseQrLoginService
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerPresenter
import dev.dimension.flare.data.network.discourse.discourseAuthenticationModule
import dev.dimension.flare.data.network.discourse.discourseModule
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumPresenter
import dev.dimension.flare.data.network.discourse.session.DesktopCredentialStoreAvailability
import dev.dimension.flare.data.network.discourse.session.SessionOnlySecureCredentialStore
import dev.dimension.flare.data.network.discourse.session.createDesktopSecureCredentialStore
import dev.dimension.flare.di.sharedModule
import dev.dimension.flare.ui.DesktopAuthenticationBrowserEffects
import dev.dimension.flare.ui.DesktopForumShell
import dev.dimension.flare.ui.handleDesktopForumShortcut
import dev.dimension.flare.ui.theme.FlareDoTheme
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.tao.TaoDeepLinkBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.koin.dsl.koinApplication
import java.io.IOException
import java.net.CookieManager
import java.net.URI
import java.nio.file.Path
import java.util.Locale

public fun main(args: Array<String>) {
    val startupCallback = desktopAuthCallbackFromArguments(args)
    val instanceClaim =
        claimDesktopInstance(
            lockFile = flareDoInstanceLockPath(),
            startupCallback = startupCallback,
        )
    val primary =
        when (instanceClaim) {
            DesktopInstanceClaim.Secondary -> {
                return
            }

            DesktopInstanceClaim.CallbackDeliveryFailed -> {
                // The message is intentionally generic: callback query values must never reach logs.
                System.err.println("FlareDo could not deliver the authorization callback to the running instance.")
                return
            }

            is DesktopInstanceClaim.Primary -> {
                instanceClaim
            }
        }

    primary.use {
        runPrimaryDesktopApplication(
            startupCallback = startupCallback,
            callbackBroker = it.broker,
        )
    }
}

private fun runPrimaryDesktopApplication(
    startupCallback: String?,
    callbackBroker: DesktopAuthCallbackBroker?,
) {
    val credentialStore =
        runBlocking {
            when (val availability = createDesktopSecureCredentialStore()) {
                is DesktopCredentialStoreAvailability.Available -> {
                    availability.store
                }

                is DesktopCredentialStoreAvailability.Unavailable -> {
                    // Linux without an unlocked Secret Service is explicitly session-only. The
                    // unavailability reason contains no secret and is intentionally not persisted.
                    SessionOnlySecureCredentialStore()
                }
            }
        }
    val dependencies =
        koinApplication {
            allowOverride(true)
            modules(
                sharedModule,
                discourseModule,
                discourseAuthenticationModule,
                createDesktopDiscourseHostModule(
                    credentialStore = credentialStore,
                    databasePath = flareDoDatabasePath(),
                ),
            )
        }
    runBlocking {
        dependencies.koin.get<DiscourseLoginService>().restoreSession()
    }
    val presenter = dependencies.koin.get<DiscourseForumPresenter>()
    val composerPresenter = dependencies.koin.get<DiscourseComposerPresenter>()
    val authenticationPresenter = dependencies.koin.get<DiscourseAuthenticationPresenter>()
    val qrLoginService = dependencies.koin.get<DiscourseQrLoginService>()
    val browserCookieManager = dependencies.koin.get<CookieManager>()
    // Start the lazy Molecule actor before installing any callback transport. A queue receipt is
    // useful only when a live consumer can dequeue it within the broker's bounded ACK window.
    authenticationPresenter.models.value
    val authenticationCallback: (String) -> Boolean = { rawUri ->
        completeDesktopAuthenticationRedirect(authenticationPresenter, rawUri)
    }
    callbackBroker?.registerCallbackHandler(authenticationCallback)
    startupCallback?.let(authenticationCallback)
    // Install the native sink before Tao starts. TaoDeepLinkBridge's pending/sink handoff uses two
    // independent volatile fields, so registering from the first composition would leave a small
    // startup race in which an Apple Event could be stranded in the pending slot.
    TaoDeepLinkBridge.setSink { uri ->
        routeDesktopTaoAuthRedirect(uri, authenticationCallback)
    }

    try {
        // Raw callback arguments are handled by FlareDo's bounded loopback broker before Nucleus
        // starts. Passing an empty array prevents the framework's generic single-instance layer
        // from persisting or forwarding the encrypted payload and one-time password.
        nucleusApplication(
            args = emptyArray(),
            backend = NucleusBackend.Tao,
            enableSingleInstance = false,
        ) {
            val windowState =
                rememberWindowState(
                    position = WindowPosition(Alignment.Center),
                    size = DpSize(width = 1180.dp, height = 760.dp),
                )
            DecoratedWindow(
                onCloseRequest = ::exitApplication,
                title = "FlareDo",
                icon = painterResource(Res.drawable.flaredo_logo),
                state = windowState,
                onPreviewKeyEvent = { event ->
                    handleDesktopForumShortcut(event, presenter, composerPresenter)
                },
            ) {
                DisposableEffect(presenter) {
                    onDispose { presenter.setForeground(false) }
                }
                LaunchedEffect(windowState.isMinimized) {
                    // Desktop has no process lifecycle equivalent. A visible non-minimized window
                    // is the bounded foreground owner; minimizing it cancels the in-flight poll.
                    presenter.setForeground(!windowState.isMinimized)
                }
                FlareDoTheme {
                    val authenticationState by authenticationPresenter.models.collectAsState()
                    DesktopAuthenticationBrowserEffects(
                        state = authenticationState,
                        browserCookieManager = browserCookieManager,
                        openExternalUri = ::openDesktopExternalUri,
                        onAction = { authenticationPresenter.dispatch(it) },
                    )
                    DesktopForumShell(
                        presenter = presenter,
                        composerPresenter = composerPresenter,
                        authenticationState = authenticationState,
                        onAuthenticationAction = { authenticationPresenter.dispatch(it) },
                        qrLoginService = qrLoginService,
                    )
                }
            }
        }
    } finally {
        // The public bridge has no clear operation; replace the sink so it cannot keep the
        // presenter (or a subsequently delivered callback value) alive during teardown.
        TaoDeepLinkBridge.setSink { }
        // Seal the loopback entry point and wait for callbacks that already crossed its gate while
        // the presenter and Koin graph are still alive. The broker retains its socket and instance
        // lock until the surrounding primary.use block closes after this function returns.
        callbackBroker?.stopAcceptingCallbacks()
        // A JVM entry point is the one intentional blocking bridge: Room/Koin must outlive the
        // presenter's final non-cancellable draft flush and any accepted post mutation.
        runBlocking {
            closeDesktopApplication(
                closeComposer = composerPresenter::closeAndFlush,
                closeAuthentication = authenticationPresenter::closeAndJoin,
                closeForum = presenter::closeAndJoin,
                closeDependencies = dependencies::close,
            )
        }
    }
}

/**
 * Accepts one protocol argument without logging or normalizing its encrypted query values.
 *
 * The JDK projection duplicates the scheme/authority/path/port boundary before the shared parser
 * validates bounded query fields. The original string is then placed directly into the presenter's
 * private actor queue and never copied into Compose state.
 */
internal fun desktopAuthCallbackFromArguments(arguments: Array<String>): String? {
    if (arguments.size != 1) return null
    val rawUri = arguments.single()
    if (rawUri.length !in 1..MAX_DESKTOP_CALLBACK_LENGTH || rawUri.any(Char::isISOControl)) return null
    val parsed = runCatching { URI(rawUri) }.getOrNull() ?: return null
    if (
        parsed.scheme != "discourse" ||
        parsed.rawAuthority != "auth_redirect" ||
        parsed.port != -1 ||
        parsed.rawUserInfo != null ||
        !parsed.rawPath.isNullOrEmpty() ||
        parsed.rawFragment != null
    ) {
        return null
    }
    if (DiscourseAuthRedirectParser.parse(rawUri) == null) return null
    return rawUri
}

/** Routes a native Tao URI through the same strict, non-retaining boundary as CLI callbacks. */
internal fun routeDesktopTaoAuthRedirect(
    uri: URI,
    onCallback: (String) -> Boolean,
): Boolean {
    val callback = desktopAuthCallbackFromArguments(arrayOf(uri.toString())) ?: return false
    return onCallback(callback)
}

/**
 * Blocks only a native callback thread until the shared actor has taken ownership of the URI.
 *
 * A queue insertion is not sufficient for the loopback protocol's positive ACK: presenter teardown
 * could otherwise discard the encrypted callback immediately afterward. Receipt timeout expires a
 * still-queued command, so a late actor cannot process it after the secondary process begins retrying.
 */
internal fun completeDesktopAuthenticationRedirect(
    presenter: DiscourseAuthenticationPresenter,
    rawUri: String,
): Boolean {
    val receipt = presenter.completeRedirectWithReceipt(rawUri) ?: return false
    return runBlocking { receipt.awaitAcceptance(DESKTOP_REDIRECT_RECEIPT_TIMEOUT_MILLIS.toLong()) }
}

/**
 * Launches a browser without touching AWT, whose event loop is incompatible with Tao windows.
 * The caller has already validated the fixed Linux.do authorization endpoint; this host boundary
 * repeats the structural URI check before exposing it to an operating-system process.
 */
internal suspend fun openDesktopExternalUri(rawUri: String): Boolean =
    withContext(Dispatchers.IO) {
        val uri = runCatching { URI(rawUri) }.getOrNull() ?: return@withContext false
        if (
            uri.scheme != "https" ||
            uri.host != "linux.do" ||
            uri.port != -1 ||
            uri.rawUserInfo != null ||
            uri.rawPath != "/user-api-key/new" ||
            uri.rawFragment != null ||
            rawUri.any(Char::isISOControl)
        ) {
            return@withContext false
        }
        val command =
            when {
                System.getProperty("os.name", "").contains("mac", ignoreCase = true) -> {
                    listOf("/usr/bin/open", rawUri)
                }

                System.getProperty("os.name", "").contains("windows", ignoreCase = true) -> {
                    listOf("rundll32.exe", "url.dll,FileProtocolHandler", rawUri)
                }

                else -> {
                    listOf("xdg-open", rawUri)
                }
            }
        try {
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

/** Preserves teardown order even when an earlier close reports a failure. */
internal suspend fun closeDesktopApplication(
    closeComposer: suspend () -> Unit,
    closeAuthentication: suspend () -> Unit,
    closeForum: suspend () -> Unit,
    closeDependencies: () -> Unit,
) {
    try {
        closeComposer()
    } finally {
        try {
            closeAuthentication()
        } finally {
            try {
                closeForum()
            } finally {
                closeDependencies()
            }
        }
    }
}

/** Resolves an app-owned, absolute cache path without writing relative to the launch directory. */
private fun flareDoDatabasePath(): Path {
    val osName = System.getProperty("os.name", "").lowercase(Locale.ROOT)
    val environmentRoot =
        if (osName.contains("windows")) {
            System.getenv("LOCALAPPDATA")
        } else {
            System.getenv("XDG_DATA_HOME")
        }
    val configuredRoot = environmentRoot.toAbsolutePathOrNull()
    val userHome = System.getProperty("user.home").toAbsolutePathOrNull()
    val safeRoot =
        configuredRoot
            ?: requireNotNull(userHome) {
                "Desktop user home must be an absolute path without control characters"
            }.let { home ->
                if (osName.contains("windows")) {
                    home.resolve(".flaredo")
                } else {
                    // Separate segments keep this path portable across JVM file-system providers.
                    home.resolve(".local").resolve("share")
                }
            }
    return safeRoot.resolve("FlareDo").resolve("flaredo.db").normalize()
}

/** Keeps the single-instance coordination file beside the app-owned database. */
private fun flareDoInstanceLockPath(): Path = requireNotNull(flareDoDatabasePath().parent).resolve("flaredo.instance.lock")

/** Treats environment and system properties as untrusted process input. */
private fun String?.toAbsolutePathOrNull(): Path? =
    this
        ?.takeIf { value -> value.isNotBlank() && value.none(Char::isISOControl) }
        ?.let { value -> runCatching { Path.of(value) }.getOrNull() }
        ?.takeIf(Path::isAbsolute)

private const val MAX_DESKTOP_CALLBACK_LENGTH: Int = 16 * 1024
