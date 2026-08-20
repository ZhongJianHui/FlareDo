package dev.dimension.flare

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.dimension.flare.data.network.discourse.auth.DiscourseLoginService
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerPresenter
import dev.dimension.flare.data.network.discourse.discourseAuthenticationModule
import dev.dimension.flare.data.network.discourse.discourseModule
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumPresenter
import dev.dimension.flare.data.network.discourse.session.DesktopCredentialStoreAvailability
import dev.dimension.flare.data.network.discourse.session.SessionOnlySecureCredentialStore
import dev.dimension.flare.data.network.discourse.session.createDesktopSecureCredentialStore
import dev.dimension.flare.di.sharedModule
import dev.dimension.flare.ui.DesktopForumShell
import dev.dimension.flare.ui.theme.FlareDoTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.painterResource
import org.koin.dsl.koinApplication
import java.nio.file.Path
import java.util.Locale

public fun main() {
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

    try {
        application {
            Window(
                onCloseRequest = ::exitApplication,
                title = "FlareDo",
                icon = painterResource(Res.drawable.flaredo_logo),
                state =
                    rememberWindowState(
                        position = WindowPosition(Alignment.Center),
                        size = DpSize(width = 1180.dp, height = 760.dp),
                    ),
            ) {
                FlareDoTheme {
                    DesktopForumShell(presenter, composerPresenter)
                }
            }
        }
    } finally {
        // A JVM entry point is the one intentional blocking bridge: Room/Koin must outlive the
        // presenter's final non-cancellable draft flush and any accepted post mutation.
        runBlocking {
            closeDesktopApplication(
                closeComposer = composerPresenter::closeAndFlush,
                closeForum = presenter::close,
                closeDependencies = dependencies::close,
            )
        }
    }
}

/** Preserves teardown order even when an earlier close reports a failure. */
internal suspend fun closeDesktopApplication(
    closeComposer: suspend () -> Unit,
    closeForum: () -> Unit,
    closeDependencies: () -> Unit,
) {
    try {
        closeComposer()
    } finally {
        try {
            closeForum()
        } finally {
            closeDependencies()
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

/** Treats environment and system properties as untrusted process input. */
private fun String?.toAbsolutePathOrNull(): Path? =
    this
        ?.takeIf { value -> value.isNotBlank() && value.none(Char::isISOControl) }
        ?.let { value -> runCatching { Path.of(value) }.getOrNull() }
        ?.takeIf(Path::isAbsolute)
