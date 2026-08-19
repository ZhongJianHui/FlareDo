package dev.dimension.flare

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.dimension.flare.data.database.FlareDoDatabase
import dev.dimension.flare.data.database.createJvmFlareDoDatabase
import dev.dimension.flare.data.network.discourse.discourseModule
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumCache
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumPresenter
import dev.dimension.flare.data.network.discourse.forum.roomDiscourseForumCache
import dev.dimension.flare.di.sharedModule
import dev.dimension.flare.ui.DesktopForumShell
import dev.dimension.flare.ui.theme.FlareDoTheme
import org.jetbrains.compose.resources.painterResource
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.nio.file.Path
import java.util.Locale

public fun main() {
    val dependencies =
        koinApplication {
            allowOverride(true)
            modules(
                sharedModule,
                discourseModule,
                module {
                    single { createJvmFlareDoDatabase(flareDoDatabasePath()) } onClose { database ->
                        database?.close()
                    }
                    single<DiscourseForumCache> {
                        roomDiscourseForumCache(
                            dao = get<FlareDoDatabase>().forumCacheEntryDao(),
                        )
                    }
                },
            )
        }
    val presenter = dependencies.koin.get<DiscourseForumPresenter>()

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
                    DesktopForumShell(presenter)
                }
            }
        }
    } finally {
        presenter.close()
        dependencies.close()
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
