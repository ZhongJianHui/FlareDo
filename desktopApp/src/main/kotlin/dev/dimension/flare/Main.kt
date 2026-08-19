package dev.dimension.flare

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.dimension.flare.ui.ForumShell
import dev.dimension.flare.ui.theme.FlareDoTheme
import org.jetbrains.compose.resources.painterResource

public fun main(): Unit =
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
                ForumShell()
            }
        }
    }
