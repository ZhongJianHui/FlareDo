package dev.dimension.flare.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthenticationAction
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthenticationState
import dev.dimension.flare.data.network.discourse.auth.DiscourseQrLoginFailure
import dev.dimension.flare.data.network.discourse.auth.DiscourseQrShareAction
import dev.dimension.flare.data.network.discourse.auth.DiscourseQrShareState

/** Authentication capability injected by a native host without coupling preview UI to Koin. */
@Immutable
internal data class ForumAuthenticationUi(
    val state: DiscourseAuthenticationState = DiscourseAuthenticationState(),
    val onAction: (DiscourseAuthenticationAction) -> Unit = {},
    val qrLoginAvailable: Boolean = false,
    val qrLoginBusy: Boolean = false,
    val qrLoginFailure: DiscourseQrLoginFailure? = null,
    val onQrLogin: () -> Unit = {},
    val qrShareState: DiscourseQrShareState = DiscourseQrShareState(),
    val onQrShareAction: (DiscourseQrShareAction) -> Unit = {},
)

@Immutable
internal data class ForumQrLoginCapability(
    val available: Boolean = false,
    val busy: Boolean = false,
    val failure: DiscourseQrLoginFailure? = null,
    val launch: () -> Unit = {},
)

/**
 * Network-free previews intentionally receive an inert guest capability. Production hosts replace
 * it at the workspace root, while platform browser effects remain outside common UI code.
 */
internal val LocalForumAuthentication = staticCompositionLocalOf { ForumAuthenticationUi() }

@Composable
internal fun ForumAuthenticationProvider(
    state: DiscourseAuthenticationState,
    onAction: (DiscourseAuthenticationAction) -> Unit,
    qrLoginAvailable: Boolean = false,
    qrLoginBusy: Boolean = false,
    qrLoginFailure: DiscourseQrLoginFailure? = null,
    onQrLogin: () -> Unit = {},
    qrShareState: DiscourseQrShareState = DiscourseQrShareState(),
    onQrShareAction: (DiscourseQrShareAction) -> Unit = {},
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalForumAuthentication provides
            ForumAuthenticationUi(
                state = state,
                onAction = onAction,
                qrLoginAvailable = qrLoginAvailable,
                qrLoginBusy = qrLoginBusy,
                qrLoginFailure = qrLoginFailure,
                onQrLogin = onQrLogin,
                qrShareState = qrShareState,
                onQrShareAction = onQrShareAction,
            ),
        content = content,
    )
}
