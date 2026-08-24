package dev.dimension.flare.ui

import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerMode
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerState
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerSubmitStatus
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerTarget
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumAction
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumDestination
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFeed
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

internal class DesktopForumShortcutTest {
    @Test
    fun escapeClosesComposerBeforeSelectedTopic() {
        val command =
            forumDesktopShortcutFor(
                key = ForumDesktopShortcutKey.Escape,
                isKeyDown = true,
                isShortcutModifierPressed = false,
                forumState = forumState().copy(selectedTopicId = 42L),
                composerState = editableComposerState(),
            )

        assertEquals(
            ForumDesktopShortcutCommand.Composer(ForumComposerAction.Close),
            command,
        )
    }

    @Test
    fun submittingComposerConsumesEscapeAndGlobalShortcuts() {
        val submitting =
            editableComposerState().copy(
                submitStatus = DiscourseComposerSubmitStatus.Submitting,
            )

        assertIs<ForumDesktopShortcutCommand.Consume>(
            forumDesktopShortcutFor(
                key = ForumDesktopShortcutKey.Escape,
                isKeyDown = true,
                isShortcutModifierPressed = false,
                forumState = forumState().copy(selectedTopicId = 42L),
                composerState = submitting,
            ),
        )
        assertIs<ForumDesktopShortcutCommand.Consume>(
            forumDesktopShortcutFor(
                key = ForumDesktopShortcutKey.Find,
                isKeyDown = true,
                isShortcutModifierPressed = true,
                forumState = forumState(),
                composerState = submitting,
            ),
        )
    }

    @Test
    fun escapeClosesTopicWhenComposerIsClosed() {
        val command =
            forumDesktopShortcutFor(
                key = ForumDesktopShortcutKey.Escape,
                isKeyDown = true,
                isShortcutModifierPressed = false,
                forumState = forumState().copy(selectedTopicId = 42L),
                composerState = DiscourseComposerState(),
            )

        assertEquals(
            ForumDesktopShortcutCommand.Forum(DiscourseForumAction.CloseTopic),
            command,
        )
    }

    @Test
    fun modifiedFindAndNewTopicResolveToSemanticCommands() {
        assertEquals(
            ForumDesktopShortcutCommand.Forum(
                DiscourseForumAction.SelectDestination(DiscourseForumDestination.Search),
            ),
            forumDesktopShortcutFor(
                key = ForumDesktopShortcutKey.Find,
                isKeyDown = true,
                isShortcutModifierPressed = true,
                forumState = forumState(),
                composerState = DiscourseComposerState(),
            ),
        )
        assertEquals(
            ForumDesktopShortcutCommand.Composer(ForumComposerAction.OpenNewTopic(CATEGORY_ID)),
            forumDesktopShortcutFor(
                key = ForumDesktopShortcutKey.NewTopic,
                isKeyDown = true,
                isShortcutModifierPressed = true,
                forumState = forumState(),
                composerState = DiscourseComposerState(),
            ),
        )
    }

    @Test
    fun modifiedEnterSubmitsOnlyAnEditableComposer() {
        assertEquals(
            ForumDesktopShortcutCommand.Composer(ForumComposerAction.Submit),
            forumDesktopShortcutFor(
                key = ForumDesktopShortcutKey.Submit,
                isKeyDown = true,
                isShortcutModifierPressed = true,
                forumState = forumState(),
                composerState = editableComposerState(),
            ),
        )
        assertNull(
            forumDesktopShortcutFor(
                key = ForumDesktopShortcutKey.Submit,
                isKeyDown = true,
                isShortcutModifierPressed = true,
                forumState = forumState(),
                composerState = DiscourseComposerState(),
            ),
        )
    }

    @Test
    fun keyUpAndUnmodifiedLettersAreNotConsumed() {
        assertNull(
            forumDesktopShortcutFor(
                key = ForumDesktopShortcutKey.Find,
                isKeyDown = false,
                isShortcutModifierPressed = true,
                forumState = forumState(),
                composerState = DiscourseComposerState(),
            ),
        )
        assertNull(
            forumDesktopShortcutFor(
                key = ForumDesktopShortcutKey.NewTopic,
                isKeyDown = true,
                isShortcutModifierPressed = false,
                forumState = forumState(),
                composerState = DiscourseComposerState(),
            ),
        )
    }

    private fun forumState(): DiscourseForumState =
        DiscourseForumState(
            selection =
                DiscourseForumFeed.Category(
                    id = CATEGORY_ID,
                    slug = "development",
                    name = "Development",
                ),
            isAuthenticated = true,
            canCreateTopic = true,
        )

    private fun editableComposerState(): DiscourseComposerState =
        DiscourseComposerState(
            mode = DiscourseComposerMode.Reply,
            sessionGeneration = 3L,
            accountId = "fixture-account",
            target = DiscourseComposerTarget.Reply(topicId = 42L),
        )

    private companion object {
        const val CATEGORY_ID: Long = 7L
    }
}
