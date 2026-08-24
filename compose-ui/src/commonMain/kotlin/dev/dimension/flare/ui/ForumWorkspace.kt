package dev.dimension.flare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.ArrowLeft
import compose.icons.fontawesomeicons.solid.ArrowsRotate
import compose.icons.fontawesomeicons.solid.Bell
import compose.icons.fontawesomeicons.solid.Comment
import compose.icons.fontawesomeicons.solid.Eye
import compose.icons.fontawesomeicons.solid.Fire
import compose.icons.fontawesomeicons.solid.House
import compose.icons.fontawesomeicons.solid.MagnifyingGlass
import compose.icons.fontawesomeicons.solid.Plus
import compose.icons.fontawesomeicons.solid.RightFromBracket
import compose.icons.fontawesomeicons.solid.Tag
import compose.icons.fontawesomeicons.solid.TriangleExclamation
import compose.icons.fontawesomeicons.solid.User
import dev.dimension.flare.compose.ui.Res
import dev.dimension.flare.compose.ui.forum_back
import dev.dimension.flare.compose.ui.forum_browse
import dev.dimension.flare.compose.ui.forum_categories
import dev.dimension.flare.compose.ui.forum_latest
import dev.dimension.flare.compose.ui.forum_load_more
import dev.dimension.flare.compose.ui.forum_new_topic
import dev.dimension.flare.compose.ui.forum_notifications
import dev.dimension.flare.compose.ui.forum_open_topic
import dev.dimension.flare.compose.ui.forum_original_post
import dev.dimension.flare.compose.ui.forum_popular
import dev.dimension.flare.compose.ui.forum_post_number
import dev.dimension.flare.compose.ui.forum_profile
import dev.dimension.flare.compose.ui.forum_refresh
import dev.dimension.flare.compose.ui.forum_replies
import dev.dimension.flare.compose.ui.forum_search
import dev.dimension.flare.compose.ui.forum_selected_topic
import dev.dimension.flare.compose.ui.forum_session_recovery_authentication
import dev.dimension.flare.compose.ui.forum_session_recovery_challenge
import dev.dimension.flare.compose.ui.forum_session_recovery_permission
import dev.dimension.flare.compose.ui.forum_session_recovery_retry_sign_out
import dev.dimension.flare.compose.ui.forum_session_recovery_title
import dev.dimension.flare.compose.ui.forum_tag_count
import dev.dimension.flare.compose.ui.forum_tags
import dev.dimension.flare.compose.ui.forum_topic
import dev.dimension.flare.compose.ui.forum_topic_count
import dev.dimension.flare.compose.ui.forum_topic_posts
import dev.dimension.flare.compose.ui.forum_unread
import dev.dimension.flare.compose.ui.forum_views
import dev.dimension.flare.compose.ui.product_name
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthenticationAction
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerState
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumAction
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumCategoryOption
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumContentSource
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumDestination
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFeed
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumState
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumTagOption
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumTopic
import dev.dimension.flare.data.network.discourse.realtime.DiscourseSessionRecoveryReason
import dev.dimension.flare.ui.model.UiArticle
import dev.dimension.flare.ui.model.UiTimelineV2
import org.jetbrains.compose.resources.stringResource

internal val ForumNavigationWidth = 76.dp
internal val ForumMediumListPaneWidth = 260.dp
internal val ForumExpandedListPaneWidth = 280.dp
internal val ForumSupportingPaneWidth = 200.dp
internal val ForumPaneDividerWidth = 1.dp
internal val ForumActiveSpineWidth = 3.dp

private val ForumMinimumMediumDetailPaneWidth = 280.dp
private val ForumMinimumExpandedListPaneWidth = 220.dp
private val ForumMinimumExpandedDetailPaneWidth = 360.dp
private val ForumMinimumSupportingPaneWidth = 168.dp

/** Exact manual pane budget after the navigation rail and dividers have been reserved. */
internal data class ForumManualMultiPaneLayout(
    val listPaneWidth: Dp,
    val detailPaneWidth: Dp,
    val supportingPaneWidth: Dp?,
)

/**
 * Keeps the article pane readable at the 840/900 dp expanded boundaries.
 *
 * Desktop cannot use Android's Navigation 3 scene strategy, so its panes share an explicit budget.
 * Supporting content is the first pane to collapse when the available width cannot preserve a
 * useful list and a 360 dp article. Preferred widths are reached gradually instead of consuming
 * all newly available space before the article can grow.
 */
internal fun forumManualMultiPaneLayoutFor(
    workspaceWidth: Dp,
    layoutClass: ForumLayoutClass,
): ForumManualMultiPaneLayout {
    require(layoutClass != ForumLayoutClass.Compact) {
        "Compact workspaces do not have a multi-pane budget"
    }
    val contentWidth =
        maxOf(
            0.dp,
            workspaceWidth - ForumNavigationWidth - ForumPaneDividerWidth,
        )

    if (layoutClass == ForumLayoutClass.Medium) {
        val paneBudget = maxOf(0.dp, contentWidth - ForumPaneDividerWidth)
        val listWidth =
            minOf(
                ForumMediumListPaneWidth,
                maxOf(0.dp, paneBudget - ForumMinimumMediumDetailPaneWidth),
            )
        return ForumManualMultiPaneLayout(
            listPaneWidth = listWidth,
            detailPaneWidth = maxOf(0.dp, paneBudget - listWidth),
            supportingPaneWidth = null,
        )
    }

    val threePaneBudget = maxOf(0.dp, contentWidth - (ForumPaneDividerWidth * 2))
    val minimumThreePaneBudget =
        ForumMinimumExpandedListPaneWidth +
            ForumMinimumExpandedDetailPaneWidth +
            ForumMinimumSupportingPaneWidth
    if (threePaneBudget >= minimumThreePaneBudget) {
        val extraWidth = threePaneBudget - minimumThreePaneBudget
        val listExtra =
            minOf(
                ForumExpandedListPaneWidth - ForumMinimumExpandedListPaneWidth,
                extraWidth * 0.6f,
            )
        val supportingExtra =
            minOf(
                ForumSupportingPaneWidth - ForumMinimumSupportingPaneWidth,
                extraWidth - listExtra,
            )
        val listWidth = ForumMinimumExpandedListPaneWidth + listExtra
        val supportingWidth = ForumMinimumSupportingPaneWidth + supportingExtra
        return ForumManualMultiPaneLayout(
            listPaneWidth = listWidth,
            detailPaneWidth = threePaneBudget - listWidth - supportingWidth,
            supportingPaneWidth = supportingWidth,
        )
    }

    val twoPaneBudget = maxOf(0.dp, contentWidth - ForumPaneDividerWidth)
    val listWidth =
        minOf(
            ForumExpandedListPaneWidth,
            maxOf(0.dp, twoPaneBudget - ForumMinimumExpandedDetailPaneWidth),
        )
    return ForumManualMultiPaneLayout(
        listPaneWidth = listWidth,
        detailPaneWidth = maxOf(0.dp, twoPaneBudget - listWidth),
        supportingPaneWidth = null,
    )
}

private data class ForumRootDestinationItem(
    val destination: DiscourseForumDestination,
    val label: String,
    val icon: ImageVector,
)

/**
 * State-driven forum workspace used by desktop and network-free Android screenshot tests.
 *
 * Android production places these same panes inside Navigation 3 scenes. This manual composition
 * remains the desktop implementation because Navigation 3 UI does not publish a JVM artifact.
 */
@Composable
public fun ForumWorkspace(
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    ForumWorkspaceWithComposer(
        state = state,
        onAction = onAction,
        composerState = DiscourseComposerState(),
        onComposerAction = {},
        attachmentPicker = ForumAttachmentPicker.Unavailable,
        modifier = modifier,
    )
}

/** Production workspace variant that overlays the authenticated composer and optimistic actions. */
@Composable
internal fun ForumWorkspaceWithComposer(
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
    composerState: DiscourseComposerState,
    onComposerAction: (ForumComposerAction) -> Unit,
    attachmentPicker: ForumAttachmentPicker,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .testTag(ForumTestTags.WORKSPACE),
        ) {
            val layoutClass = forumLayoutClassFor(maxWidth)
            ForumNavigationFrame(
                layoutClass = layoutClass,
                state = state,
                onAction = onAction,
            ) {
                ForumManualPanes(
                    layoutClass = layoutClass,
                    workspaceWidth = maxWidth,
                    state = state,
                    onAction = onAction,
                    composerState = composerState,
                    onComposerAction = onComposerAction,
                )
            }
            ForumComposerLayer(
                layoutClass = layoutClass,
                state = composerState,
                onAction = onComposerAction,
                attachmentPicker = attachmentPicker,
            )
        }
    }
}

@Composable
private fun ForumManualPanes(
    layoutClass: ForumLayoutClass,
    workspaceWidth: Dp,
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
    composerState: DiscourseComposerState,
    onComposerAction: (ForumComposerAction) -> Unit,
) {
    when (layoutClass) {
        ForumLayoutClass.Compact -> {
            if (state.selectedTopicId == null) {
                ForumPrimaryPane(
                    state,
                    onAction,
                    composerState,
                    onComposerAction,
                    Modifier.fillMaxSize(),
                )
            } else {
                ForumTopicDetailPane(
                    state = state,
                    showBackButton = true,
                    onBack = { onAction(DiscourseForumAction.CloseTopic) },
                    onRetry = { onAction(DiscourseForumAction.RetryTopic) },
                    composerState = composerState,
                    onComposerAction = onComposerAction,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        ForumLayoutClass.Medium -> {
            val paneLayout = forumManualMultiPaneLayoutFor(workspaceWidth, layoutClass)
            Row(modifier = Modifier.fillMaxSize()) {
                ForumPrimaryPane(
                    state,
                    onAction,
                    composerState,
                    onComposerAction,
                    Modifier.width(paneLayout.listPaneWidth),
                )
                ForumPaneDivider()
                ForumTopicDetailPane(
                    state = state,
                    showBackButton = false,
                    onBack = { onAction(DiscourseForumAction.CloseTopic) },
                    onRetry = { onAction(DiscourseForumAction.RetryTopic) },
                    composerState = composerState,
                    onComposerAction = onComposerAction,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ForumLayoutClass.Expanded -> {
            val paneLayout = forumManualMultiPaneLayoutFor(workspaceWidth, layoutClass)
            Row(modifier = Modifier.fillMaxSize()) {
                ForumPrimaryPane(
                    state,
                    onAction,
                    composerState,
                    onComposerAction,
                    Modifier.width(paneLayout.listPaneWidth),
                )
                ForumPaneDivider()
                ForumTopicDetailPane(
                    state = state,
                    showBackButton = false,
                    onBack = { onAction(DiscourseForumAction.CloseTopic) },
                    onRetry = { onAction(DiscourseForumAction.RetryTopic) },
                    composerState = composerState,
                    onComposerAction = onComposerAction,
                    modifier = Modifier.weight(1f),
                )
                paneLayout.supportingPaneWidth?.let { supportingPaneWidth ->
                    ForumPaneDivider()
                    ForumSupportingPane(
                        state,
                        onAction,
                        Modifier.width(supportingPaneWidth),
                    )
                }
            }
        }
    }
}

/** Navigation chrome stays outside NavDisplay so it is never a back-stack destination. */
@Composable
internal fun ForumNavigationFrame(
    layoutClass: ForumLayoutClass,
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
    content: @Composable () -> Unit,
) {
    val destinations = forumDestinations()
    val selectedDestination = state.destination
    val selectDestination: (DiscourseForumDestination) -> Unit = { destination ->
        destination.navigationAction(state)?.let(onAction)
    }
    val unreadCount = state.notifications.snapshot?.unreadCount ?: 0

    Column(modifier = Modifier.fillMaxSize()) {
        state.realtimeRecoveryReason?.let { reason ->
            ForumSessionRecoveryBand(
                reason = reason,
                layoutClass = layoutClass,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            when (layoutClass) {
                ForumLayoutClass.Compact -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) { content() }
                        ForumBottomBar(destinations, selectedDestination, unreadCount, selectDestination)
                    }
                }

                ForumLayoutClass.Medium,
                ForumLayoutClass.Expanded,
                -> {
                    Row(modifier = Modifier.fillMaxSize()) {
                        ForumNavigationRail(destinations, selectedDestination, unreadCount, selectDestination)
                        ForumPaneDivider()
                        Box(modifier = Modifier.weight(1f)) { content() }
                    }
                }
            }
        }
    }
}

/**
 * Generation-scoped terminal realtime failures stay visible above every pane until logout succeeds.
 *
 * The compact layout gives translated text the full width before placing the action at the end of a
 * second row. Wider workspaces keep the same information in one scan line without changing pane
 * widths. The action deliberately reuses normal logout so persistence and vault cleanup remain owned
 * by the session lifecycle instead of presentation code.
 */
@Composable
private fun ForumSessionRecoveryBand(
    reason: DiscourseSessionRecoveryReason,
    layoutClass: ForumLayoutClass,
    modifier: Modifier = Modifier,
) {
    val authentication = LocalForumAuthentication.current
    val title = stringResource(Res.string.forum_session_recovery_title)
    val body = forumSessionRecoveryMessage(reason)
    val actionLabel = stringResource(Res.string.forum_session_recovery_retry_sign_out)
    val contentColor = MaterialTheme.colorScheme.onErrorContainer

    Surface(
        modifier =
            modifier
                .testTag(ForumTestTags.SESSION_RECOVERY_BAND)
                .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = contentColor,
    ) {
        if (layoutClass == ForumLayoutClass.Compact) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ForumSessionRecoveryMessage(
                    title = title,
                    body = body,
                    modifier = Modifier.fillMaxWidth(),
                )
                ForumSessionRecoveryAction(
                    label = actionLabel,
                    enabled = !authentication.state.isBusy,
                    onClick = { authentication.onAction(DiscourseAuthenticationAction.Logout) },
                    modifier = Modifier.align(Alignment.End),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ForumSessionRecoveryMessage(
                    title = title,
                    body = body,
                    modifier = Modifier.weight(1f),
                )
                ForumSessionRecoveryAction(
                    label = actionLabel,
                    enabled = !authentication.state.isBusy,
                    onClick = { authentication.onAction(DiscourseAuthenticationAction.Logout) },
                )
            }
        }
    }
}

@Composable
private fun ForumSessionRecoveryMessage(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.testTag(ForumTestTags.SESSION_RECOVERY_MESSAGE),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = FontAwesomeIcons.Solid.TriangleExclamation,
            contentDescription = null,
            modifier = Modifier.padding(top = 2.dp).size(18.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ForumSessionRecoveryAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = MaterialTheme.colorScheme.onErrorContainer
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.testTag(ForumTestTags.SESSION_RECOVERY_ACTION),
        colors =
            ButtonDefaults.textButtonColors(
                contentColor = contentColor,
                disabledContentColor = contentColor.copy(alpha = 0.38f),
            ),
    ) {
        Icon(
            imageVector = FontAwesomeIcons.Solid.RightFromBracket,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(label)
    }
}

/** Presentation-safe explanation for each terminal recovery reason. */
@Composable
internal fun forumSessionRecoveryMessage(reason: DiscourseSessionRecoveryReason): String =
    when (reason) {
        DiscourseSessionRecoveryReason.AuthenticationRequired -> {
            stringResource(Res.string.forum_session_recovery_authentication)
        }

        DiscourseSessionRecoveryReason.PermissionDenied -> {
            stringResource(Res.string.forum_session_recovery_permission)
        }

        DiscourseSessionRecoveryReason.ManualChallengeRequired -> {
            stringResource(Res.string.forum_session_recovery_challenge)
        }
    }

@Composable
private fun forumDestinations(): List<ForumRootDestinationItem> =
    listOf(
        ForumRootDestinationItem(
            DiscourseForumDestination.Latest,
            stringResource(Res.string.forum_latest),
            FontAwesomeIcons.Solid.House,
        ),
        ForumRootDestinationItem(
            DiscourseForumDestination.Hot,
            stringResource(Res.string.forum_popular),
            FontAwesomeIcons.Solid.Fire,
        ),
        ForumRootDestinationItem(
            DiscourseForumDestination.Search,
            stringResource(Res.string.forum_search),
            FontAwesomeIcons.Solid.MagnifyingGlass,
        ),
        ForumRootDestinationItem(
            DiscourseForumDestination.Notifications,
            stringResource(Res.string.forum_notifications),
            FontAwesomeIcons.Solid.Bell,
        ),
        ForumRootDestinationItem(
            DiscourseForumDestination.Profile,
            stringResource(Res.string.forum_profile),
            FontAwesomeIcons.Solid.User,
        ),
    )

@Composable
private fun ForumBottomBar(
    destinations: List<ForumRootDestinationItem>,
    selectedDestination: DiscourseForumDestination,
    unreadCount: Int,
    onDestinationSelected: (DiscourseForumDestination) -> Unit,
) {
    NavigationBar(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        destinations.forEach { item ->
            NavigationBarItem(
                selected = selectedDestination == item.destination,
                onClick = { onDestinationSelected(item.destination) },
                icon = {
                    ForumNavigationIcon(
                        icon = item.icon,
                        label = item.label,
                        unreadCount =
                            unreadCount.takeIf {
                                item.destination == DiscourseForumDestination.Notifications
                            } ?: 0,
                    )
                },
                label = {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun ForumNavigationRail(
    destinations: List<ForumRootDestinationItem>,
    selectedDestination: DiscourseForumDestination,
    unreadCount: Int,
    onDestinationSelected: (DiscourseForumDestination) -> Unit,
) {
    NavigationRail(
        modifier = Modifier.width(ForumNavigationWidth).fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surface,
        header = { ForumBrandMark() },
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        destinations.forEach { item ->
            Box(modifier = Modifier.fillMaxWidth()) {
                if (selectedDestination == item.destination) {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .width(ForumActiveSpineWidth)
                            .height(40.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                NavigationRailItem(
                    modifier = Modifier.align(Alignment.Center),
                    selected = selectedDestination == item.destination,
                    onClick = { onDestinationSelected(item.destination) },
                    icon = {
                        ForumNavigationIcon(
                            icon = item.icon,
                            label = item.label,
                            unreadCount =
                                unreadCount.takeIf {
                                    item.destination == DiscourseForumDestination.Notifications
                                } ?: 0,
                        )
                    },
                    label = {
                        Text(
                            item.label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ForumNavigationIcon(
    icon: ImageVector,
    label: String,
    unreadCount: Int,
) {
    Box(modifier = Modifier.size(width = 30.dp, height = 24.dp), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(20.dp))
        if (unreadCount > 0) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    unreadCount.coerceAtMost(99).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onError,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Routes the stable workspace destination to its independently paged primary pane. */
@Composable
internal fun ForumPrimaryPane(
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
    composerState: DiscourseComposerState,
    onComposerAction: (ForumComposerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state.destination) {
        DiscourseForumDestination.Latest,
        DiscourseForumDestination.Hot,
        -> ForumTopicListPane(state, onAction, composerState, onComposerAction, modifier)

        DiscourseForumDestination.Search -> ForumSearchPane(state, onAction, modifier)

        DiscourseForumDestination.Notifications -> ForumNotificationsPane(state, onAction, modifier)

        DiscourseForumDestination.Profile -> ForumProfilePane(state, onAction, modifier)
    }
}

@Composable
private fun ForumBrandMark() {
    Surface(
        modifier = Modifier.size(38.dp),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("F", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/** Flat, paging-aware feed pane shared by manual and Navigation 3 layouts. */
@Composable
internal fun ForumTopicListPane(
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
    composerState: DiscourseComposerState,
    onComposerAction: (ForumComposerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .testTag(ForumTestTags.TOPIC_LIST),
    ) {
        val feedFailure = state.feedFailure
        ForumFeedHeader(
            state = state,
            composerState = composerState,
            onRefresh = { onAction(DiscourseForumAction.Refresh) },
            onNewTopic = {
                val categoryId = (state.selection as? DiscourseForumFeed.Category)?.id
                onComposerAction(ForumComposerAction.OpenNewTopic(categoryId))
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (state.feedSource == DiscourseForumContentSource.StaleCache) {
            ForumCachedContentNotice()
        }
        ForumTaxonomyStrip(state, onAction)
        when {
            state.isFeedLoading && state.topics.isEmpty() -> {
                ForumTopicLoadingState()
            }

            feedFailure != null && state.topics.isEmpty() -> {
                ForumFailureState(
                    failure = feedFailure,
                    onRetry = { onAction(DiscourseForumAction.Refresh) },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            state.topics.isEmpty() -> {
                ForumEmptyState(
                    onRefresh = { onAction(DiscourseForumAction.Refresh) },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                ForumTopicList(state, onAction)
            }
        }
    }
}

@Composable
private fun ForumFeedHeader(
    state: DiscourseForumState,
    composerState: DiscourseComposerState,
    onRefresh: () -> Unit,
    onNewTopic: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.product_name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                "linux.do  /  ${state.selection.displayLabel()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (forumCanCreateTopic(state)) {
            val newTopicLabel = stringResource(Res.string.forum_new_topic)
            IconButton(
                onClick = onNewTopic,
                enabled = forumCanOpenComposer(composerState),
                modifier = Modifier.testTag(ForumTestTags.NEW_TOPIC),
            ) {
                Icon(
                    FontAwesomeIcons.Solid.Plus,
                    newTopicLabel,
                    Modifier.size(18.dp),
                )
            }
        }
        val refreshLabel = stringResource(Res.string.forum_refresh)
        IconButton(onClick = onRefresh, enabled = !state.isFeedLoading) {
            if (state.isFeedLoading && state.topics.isNotEmpty()) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(FontAwesomeIcons.Solid.ArrowsRotate, refreshLabel, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ForumTaxonomyStrip(
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
) {
    if (state.categories.isEmpty() && state.tags.isEmpty()) return
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.categories, key = { "category:${it.id}" }) { category ->
            FilterChip(
                selected = (state.selection as? DiscourseForumFeed.Category)?.id == category.id,
                onClick = { onAction(DiscourseForumAction.SelectFeed(category.asForumFeed())) },
                label = { Text(category.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { ForumCategorySwatch(category.colorHex) },
                shape = RoundedCornerShape(6.dp),
            )
        }
        items(state.tags, key = { "tag:${it.id}" }) { tag ->
            FilterChip(
                selected = (state.selection as? DiscourseForumFeed.Tag)?.slug == tag.slug,
                onClick = { onAction(DiscourseForumAction.SelectFeed(tag.asForumFeed())) },
                label = { Text("#${tag.name}", maxLines = 1) },
                leadingIcon = {
                    Icon(FontAwesomeIcons.Solid.Tag, contentDescription = null, Modifier.size(13.dp))
                },
                shape = RoundedCornerShape(6.dp),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ForumTopicList(
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
) {
    val listState = rememberLazyListState()
    val appendFailure = state.appendFailure
    val shouldLoadMore by
        remember(
            state.topics.size,
            state.nextPage,
            state.isAppending,
            appendFailure,
        ) {
            derivedStateOf {
                val lastVisible =
                    listState.layoutInfo.visibleItemsInfo
                        .lastOrNull()
                        ?.index ?: -1
                shouldAutomaticallyLoadNextPage(
                    lastVisibleIndex = lastVisible,
                    topicCount = state.topics.size,
                    nextPage = state.nextPage,
                    isAppending = state.isAppending,
                    failure = appendFailure,
                )
            }
        }
    LaunchedEffect(shouldLoadMore, state.nextPage) {
        if (shouldLoadMore) onAction(DiscourseForumAction.LoadNextPage)
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(state.topics, key = UiTimelineV2.Topic::itemKey) { topic ->
            val topicId = topic.discourse?.ref?.topicId
            ForumTopicRow(
                topic = topic,
                isSelected = topicId != null && topicId == state.selectedTopicId,
                onClick = { topicId?.let { onAction(DiscourseForumAction.OpenTopic(it)) } },
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 19.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        if (state.isAppending) {
            item(key = "append-progress") {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag(ForumTestTags.LOAD_MORE_PROGRESS),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(Res.string.forum_load_more),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (appendFailure != null) {
            item(key = "append-failure") {
                ForumAppendFailureState(
                    failure = appendFailure,
                    onRetry = { onAction(DiscourseForumAction.LoadNextPage) },
                )
            }
        }
    }
}

/** Prevents an append failure at the list tail from turning into a request retry loop. */
internal fun shouldAutomaticallyLoadNextPage(
    lastVisibleIndex: Int,
    topicCount: Int,
    nextPage: Int?,
    isAppending: Boolean,
    failure: dev.dimension.flare.data.network.discourse.forum.DiscourseForumFailureKind?,
): Boolean =
    nextPage != null &&
        !isAppending &&
        failure == null &&
        topicCount > 0 &&
        lastVisibleIndex >= topicCount - 3

@Composable
private fun ForumTopicRow(
    topic: UiTimelineV2.Topic,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val topicId = topic.discourse?.ref?.topicId
    val openDescription = stringResource(Res.string.forum_open_topic, topic.title)
    val selectedDescription = stringResource(Res.string.forum_selected_topic)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 116.dp)
                .clickable(
                    enabled = topicId != null,
                    onClickLabel = openDescription,
                    role = Role.Button,
                    onClick = onClick,
                ).semantics {
                    selected = isSelected
                    role = Role.Button
                    if (isSelected) contentDescription = "$selectedDescription. $openDescription"
                }.then(
                    if (topicId == null) Modifier else Modifier.testTag(ForumTestTags.topic(topicId)),
                ),
    ) {
        Box(
            Modifier
                .width(ForumActiveSpineWidth)
                .fillMaxHeight()
                .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    topic.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (topic.unread) {
                    Spacer(Modifier.width(8.dp))
                    val unread = stringResource(Res.string.forum_unread)
                    Box(
                        Modifier
                            .padding(top = 5.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondary)
                            .semantics { contentDescription = unread },
                    )
                }
            }
            Text(
                topic.excerpt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ForumAuthorAvatar(topic.author.displayName, 24.dp)
                Spacer(Modifier.width(7.dp))
                Text(
                    topic.author.displayName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ForumTopicMetric(
                    FontAwesomeIcons.Solid.Comment,
                    topic.replyCount,
                    stringResource(Res.string.forum_replies, topic.replyCount),
                )
                Spacer(Modifier.width(10.dp))
                ForumTopicMetric(
                    FontAwesomeIcons.Solid.Eye,
                    topic.viewCount,
                    stringResource(Res.string.forum_views, topic.viewCount),
                )
            }
        }
    }
}

@Composable
private fun ForumTopicMetric(
    icon: ImageVector,
    count: Int,
    description: String,
) {
    Row(
        modifier = Modifier.semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(
            count.compactCount(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Topic pane renders only sanitized UiArticleBlock values; raw cooked HTML cannot reach it. */
@Composable
internal fun ForumTopicDetailPane(
    state: DiscourseForumState,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    composerState: DiscourseComposerState,
    onComposerAction: (ForumComposerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.background)
                .testTag(ForumTestTags.TOPIC_DETAIL),
    ) {
        val topicFailure = state.topicFailure
        val selectedTopic = state.selectedTopic
        ForumTopicPaneHeader(showBackButton, onBack)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (state.topicSource == DiscourseForumContentSource.StaleCache) {
            ForumCachedContentNotice()
        }
        when {
            state.selectedTopicId == null -> {
                ForumNoTopicState(Modifier.fillMaxSize())
            }

            state.isTopicLoading && selectedTopic == null -> {
                ForumTopicDetailLoadingState()
            }

            topicFailure != null && selectedTopic == null -> {
                ForumFailureState(topicFailure, onRetry, Modifier.fillMaxSize())
            }

            selectedTopic != null -> {
                ForumTopicDocument(
                    topic = selectedTopic,
                    selectedPostNumber = state.selectedPostNumber,
                    composerState = composerState,
                    onComposerAction = onComposerAction,
                )
            }

            else -> {
                ForumTopicDetailLoadingState()
            }
        }
    }
}

@Composable
private fun ForumTopicPaneHeader(
    showBackButton: Boolean,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBackButton) {
            IconButton(onClick = onBack) {
                Icon(
                    FontAwesomeIcons.Solid.ArrowLeft,
                    stringResource(Res.string.forum_back),
                    Modifier.size(18.dp),
                )
            }
        } else {
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.forum_topic),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(Res.string.forum_topic_posts),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ForumTopicDocument(
    topic: DiscourseForumTopic,
    selectedPostNumber: Int?,
    composerState: DiscourseComposerState,
    onComposerAction: (ForumComposerAction) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(topic.topicId, topic.discourse, composerState.sessionGeneration) {
        if (topic.discourse != null) {
            onComposerAction(ForumComposerAction.SynchronizeTopic(topic))
        }
    }
    LaunchedEffect(topic.topicId, selectedPostNumber) {
        val articleIndex =
            selectedPostNumber?.let { target ->
                topic.articles.indexOfFirst { it.discourse?.postNumber == target }
            } ?: -1
        if (articleIndex >= 0) listState.scrollToItem(articleIndex + 1)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
    ) {
        item(key = "topic-heading-${topic.topicId}") {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 780.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    topic.title,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (topic.tags.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(topic.tags, key = { it }) { ForumTagLabel(it) }
                    }
                }
                ForumTopicActionBar(topic, composerState, onComposerAction)
                Spacer(Modifier.height(12.dp))
            }
        }
        items(topic.articles, key = UiArticle::itemKey) { article ->
            ForumArticle(
                article = article,
                composerState = composerState,
                onComposerAction = onComposerAction,
            )
        }
    }
}

@Composable
private fun ForumArticle(
    article: UiArticle,
    composerState: DiscourseComposerState,
    onComposerAction: (ForumComposerAction) -> Unit,
) {
    val postNumber = article.discourse?.postNumber
    LaunchedEffect(article.itemKey, article.discourse, composerState.sessionGeneration) {
        if (article.discourse != null) {
            onComposerAction(ForumComposerAction.SynchronizePost(article))
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 780.dp).padding(bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ForumAuthorAvatar(article.author.displayName, 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    article.author.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (postNumber == null || postNumber == 1) {
                        stringResource(Res.string.forum_original_post)
                    } else {
                        stringResource(Res.string.forum_post_number, postNumber)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ForumRichText(article.blocks)
        ForumPostActionBar(article, composerState, onComposerAction)
        HorizontalDivider(
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** Third-pane taxonomy browser for expanded windows and Navigation 3 extra-pane metadata. */
@Composable
internal fun ForumSupportingPane(
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .testTag(ForumTestTags.SUPPORTING_PANE),
    ) {
        ForumPaneHeader(stringResource(Res.string.forum_browse))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when {
            state.isTaxonomyLoading && state.categories.isEmpty() && state.tags.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }

            state.taxonomyFailure != null && state.categories.isEmpty() && state.tags.isEmpty() -> {
                ForumTaxonomyFailure { onAction(DiscourseForumAction.RetryTaxonomy) }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    item(key = "categories-header") {
                        ForumTaxonomySectionHeader(stringResource(Res.string.forum_categories))
                    }
                    items(state.categories, key = DiscourseForumCategoryOption::id) { category ->
                        ForumTaxonomyRow(
                            label = category.name,
                            supporting = stringResource(Res.string.forum_topic_count, category.topicCount),
                            selected =
                                (state.selection as? DiscourseForumFeed.Category)?.id == category.id,
                            swatchHex = category.colorHex,
                            onClick = {
                                onAction(DiscourseForumAction.SelectFeed(category.asForumFeed()))
                            },
                        )
                    }
                    item(key = "tags-header") {
                        ForumTaxonomySectionHeader(stringResource(Res.string.forum_tags))
                    }
                    items(state.tags, key = DiscourseForumTagOption::id) { tag ->
                        ForumTaxonomyRow(
                            label = "#${tag.name}",
                            supporting = stringResource(Res.string.forum_tag_count, tag.count),
                            selected = (state.selection as? DiscourseForumFeed.Tag)?.slug == tag.slug,
                            onClick = {
                                onAction(DiscourseForumAction.SelectFeed(tag.asForumFeed()))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ForumPaneHeader(title: String) {
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ForumTaxonomySectionHeader(title: String) {
    Text(
        title,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ForumTaxonomyRow(
    label: String,
    supporting: String,
    selected: Boolean,
    onClick: () -> Unit,
    swatchHex: String? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { this.selected = selected },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(ForumActiveSpineWidth)
                .fillMaxHeight()
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        Row(
            modifier = Modifier.weight(1f).padding(horizontal = 13.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (swatchHex != null) {
                ForumCategorySwatch(swatchHex)
                Spacer(Modifier.width(9.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    supporting,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun ForumCategorySwatch(colorHex: String?) {
    Box(
        Modifier
            .size(10.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(colorHex.toForumCategoryColor() ?: MaterialTheme.colorScheme.tertiary),
    )
}

@Composable
internal fun ForumAuthorAvatar(
    displayName: String,
    size: androidx.compose.ui.unit.Dp,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            displayName.firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ForumTagLabel(tag: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            "#$tag",
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

@Composable
private fun DiscourseForumFeed.displayLabel(): String =
    when (this) {
        DiscourseForumFeed.Latest -> stringResource(Res.string.forum_latest)
        DiscourseForumFeed.Hot -> stringResource(Res.string.forum_popular)
        is DiscourseForumFeed.Category -> name
        is DiscourseForumFeed.Tag -> "#$name"
    }

/** Current destinations are inert; switching sends one semantic presenter action. */
internal fun DiscourseForumDestination.navigationAction(state: DiscourseForumState): DiscourseForumAction? =
    takeUnless { it == state.destination }?.let(DiscourseForumAction::SelectDestination)

internal fun DiscourseForumCategoryOption.asForumFeed(): DiscourseForumFeed.Category =
    DiscourseForumFeed.Category(id, slug, parentSlug, name)

internal fun DiscourseForumTagOption.asForumFeed(): DiscourseForumFeed.Tag = DiscourseForumFeed.Tag(name, slug)

private fun String?.toForumCategoryColor(): Color? {
    val normalized = this?.trim()?.removePrefix("#") ?: return null
    if (normalized.length != 6) return null
    val rgb = normalized.toLongOrNull(radix = 16) ?: return null
    return Color(0xFF000000L or rgb)
}

private fun Int.compactCount(): String =
    when {
        this >= 1_000_000 -> "${this / 100_000 / 10.0}m"
        this >= 1_000 -> "${this / 100 / 10.0}k"
        else -> toString()
    }

@Composable
internal fun ForumPaneDivider() {
    Box(
        Modifier
            .width(ForumPaneDividerWidth)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
