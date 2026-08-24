package dev.dimension.flare.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerMode
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerPresenter
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerState
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerSubmitStatus
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumAction
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumDestination
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFeed
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumPresenter
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumState

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

internal enum class ForumDesktopShortcutKey {
    Escape,
    Find,
    NewTopic,
    Submit,
    Other,
}

internal sealed interface ForumDesktopShortcutCommand {
    data class Forum(
        val action: DiscourseForumAction,
    ) : ForumDesktopShortcutCommand

    data class Composer(
        val action: ForumComposerAction,
    ) : ForumDesktopShortcutCommand

    data object Consume : ForumDesktopShortcutCommand
}

/** Resolves shortcuts without a Compose focus owner so Window-level behavior remains deterministic. */
internal fun forumDesktopShortcutFor(
    key: ForumDesktopShortcutKey,
    isKeyDown: Boolean,
    isShortcutModifierPressed: Boolean,
    forumState: DiscourseForumState,
    composerState: DiscourseComposerState,
): ForumDesktopShortcutCommand? {
    if (!isKeyDown) return null

    if (composerState.mode != DiscourseComposerMode.Closed) {
        if (key == ForumDesktopShortcutKey.Escape) {
            return if (forumCanDismissComposer(composerState)) {
                ForumDesktopShortcutCommand.Composer(ForumComposerAction.Close)
            } else {
                ForumDesktopShortcutCommand.Consume
            }
        }
        if (isShortcutModifierPressed && key == ForumDesktopShortcutKey.Submit) {
            val canSubmit =
                composerState.canEdit &&
                    composerState.canSubmit &&
                    composerState.submitStatus !in
                    setOf(
                        DiscourseComposerSubmitStatus.Published,
                        DiscourseComposerSubmitStatus.PendingModeration,
                    )
            return if (canSubmit) {
                ForumDesktopShortcutCommand.Composer(ForumComposerAction.Submit)
            } else {
                ForumDesktopShortcutCommand.Consume
            }
        }
        if (
            isShortcutModifierPressed &&
            key in setOf(ForumDesktopShortcutKey.Find, ForumDesktopShortcutKey.NewTopic)
        ) {
            return ForumDesktopShortcutCommand.Consume
        }
        return null
    }

    if (key == ForumDesktopShortcutKey.Escape && forumState.selectedTopicId != null) {
        return ForumDesktopShortcutCommand.Forum(DiscourseForumAction.CloseTopic)
    }
    if (!isShortcutModifierPressed) return null

    return when (key) {
        ForumDesktopShortcutKey.Find -> {
            ForumDesktopShortcutCommand.Forum(
                DiscourseForumAction.SelectDestination(DiscourseForumDestination.Search),
            )
        }

        ForumDesktopShortcutKey.NewTopic -> {
            if (forumCanCreateTopic(forumState) && forumCanOpenComposer(composerState)) {
                ForumDesktopShortcutCommand.Composer(
                    ForumComposerAction.OpenNewTopic(
                        categoryId = (forumState.selection as? DiscourseForumFeed.Category)?.id,
                    ),
                )
            } else {
                null
            }
        }

        ForumDesktopShortcutKey.Escape,
        ForumDesktopShortcutKey.Submit,
        ForumDesktopShortcutKey.Other,
        -> {
            null
        }
    }
}

/** Handles Ctrl on Windows/Linux and Command on macOS before the focused editor sees the event. */
public fun handleDesktopForumShortcut(
    event: KeyEvent,
    presenter: DiscourseForumPresenter,
    composerPresenter: DiscourseComposerPresenter,
): Boolean {
    val key =
        when (event.key) {
            Key.Escape -> ForumDesktopShortcutKey.Escape

            Key.F -> ForumDesktopShortcutKey.Find

            Key.N -> ForumDesktopShortcutKey.NewTopic

            Key.Enter,
            Key.NumPadEnter,
            -> ForumDesktopShortcutKey.Submit

            else -> ForumDesktopShortcutKey.Other
        }
    val command =
        forumDesktopShortcutFor(
            key = key,
            isKeyDown = event.type == KeyEventType.KeyDown,
            isShortcutModifierPressed = event.isCtrlPressed || event.isMetaPressed,
            forumState = presenter.models.value,
            composerState = composerPresenter.models.value,
        ) ?: return false
    when (command) {
        is ForumDesktopShortcutCommand.Forum -> {
            presenter.dispatch(command.action)
        }

        is ForumDesktopShortcutCommand.Composer -> {
            composerPresenter.dispatchForumAction(command.action)
        }

        ForumDesktopShortcutCommand.Consume -> {}
    }
    return true
}
