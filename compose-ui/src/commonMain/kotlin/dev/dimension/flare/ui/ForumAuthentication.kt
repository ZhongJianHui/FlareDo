package dev.dimension.flare.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthenticationAction
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthenticationState

/** Authentication capability injected by a native host without coupling preview UI to Koin. */
@Immutable
internal data class ForumAuthenticationUi(
    val state: DiscourseAuthenticationState = DiscourseAuthenticationState(),
    val onAction: (DiscourseAuthenticationAction) -> Unit = {},
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
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalForumAuthentication provides ForumAuthenticationUi(state, onAction),
        content = content,
    )
}
