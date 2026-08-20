package dev.dimension.flare.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerPresenter
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumPresenter

/** Desktop host for the shared presenter and width-driven list/detail/extra workspace. */
@Composable
public fun DesktopForumShell(
    presenter: DiscourseForumPresenter,
    composerPresenter: DiscourseComposerPresenter,
    modifier: Modifier = Modifier,
) {
    val state by presenter.models.collectAsState()
    val composerState by composerPresenter.models.collectAsState()
    val attachmentPicker = rememberForumAttachmentPicker()
    ForumWorkspaceWithComposer(
        state = state,
        onAction = { presenter.dispatch(it) },
        composerState = composerState,
        onComposerAction = { composerPresenter.dispatchForumAction(it) },
        attachmentPicker = attachmentPicker,
        modifier = modifier,
    )
}
