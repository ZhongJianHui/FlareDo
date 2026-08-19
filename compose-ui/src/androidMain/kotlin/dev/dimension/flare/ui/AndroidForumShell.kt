@file:OptIn(androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi::class)

package dev.dimension.flare.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumAction
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFeed
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumPresenter
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumState
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface ForumNavKey : NavKey

@Serializable
internal data class ForumFeedRoute(
    val feed: DiscourseForumFeed,
) : ForumNavKey

@Serializable
internal data class ForumTopicRoute(
    val topicId: Long,
) : ForumNavKey

@Serializable
internal data object ForumEmptyDetailRoute : ForumNavKey

@Serializable
internal data object ForumSupportingRoute : ForumNavKey

/** Collects the shared presenter with the Android lifecycle and dispatches non-blocking actions. */
@Composable
public fun AndroidForumShell(
    presenter: DiscourseForumPresenter,
    modifier: Modifier = Modifier,
) {
    val state by presenter.models.collectAsStateWithLifecycle()
    AndroidForumShell(
        state = state,
        onAction = { presenter.dispatch(it) },
        modifier = modifier,
    )
}

/**
 * Android Navigation 3 host for the adaptive list/detail/extra scene.
 *
 * [rememberNavBackStack] serializes these closed route keys across activity and process recreation.
 * The presenter remains the authority for the selected feed/topic; the reconciliation effect only
 * mirrors that state into navigation after a restore or window-size transition.
 */
@Composable
internal fun AndroidForumShell(
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
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
            val activity = LocalContext.current.findActivity()
            val backStack = rememberNavBackStack(ForumFeedRoute(state.selection))
            val sceneStrategy = rememberListDetailSceneStrategy<NavKey>()
            val restoredFeed = remember { backStack.filterIsInstance<ForumFeedRoute>().first().feed }
            val restoredTopicId =
                remember { backStack.filterIsInstance<ForumTopicRoute>().lastOrNull()?.topicId }
            var restorationDispatched by remember { mutableStateOf(false) }
            var restorationComplete by remember { mutableStateOf(false) }

            fun desiredRoutes(): List<ForumNavKey> =
                forumRoutesFor(
                    feed = state.selection,
                    selectedTopicId = state.selectedTopicId,
                    layoutClass = layoutClass,
                )

            fun replaceRoutes(routes: List<ForumNavKey>) {
                backStack.clear()
                backStack.addAll(routes)
            }

            fun dispatchAndNavigate(action: DiscourseForumAction) {
                when (action) {
                    is DiscourseForumAction.SelectFeed -> {
                        replaceRoutes(
                            forumRoutesFor(
                                feed = action.feed,
                                selectedTopicId = null,
                                layoutClass = layoutClass,
                            ),
                        )
                    }

                    is DiscourseForumAction.OpenTopic -> {
                        replaceRoutes(
                            forumRoutesFor(
                                feed = state.selection,
                                selectedTopicId = action.topicId,
                                layoutClass = layoutClass,
                            ),
                        )
                    }

                    DiscourseForumAction.CloseTopic -> {
                        replaceRoutes(
                            forumRoutesFor(
                                feed = state.selection,
                                selectedTopicId = null,
                                layoutClass = layoutClass,
                            ),
                        )
                    }

                    DiscourseForumAction.Refresh,
                    DiscourseForumAction.LoadNextPage,
                    DiscourseForumAction.RetryTaxonomy,
                    DiscourseForumAction.RetryTopic,
                    -> {}
                }
                onAction(action)
            }

            fun popRoute() {
                if (backStack.any { it is ForumTopicRoute }) {
                    dispatchAndNavigate(DiscourseForumAction.CloseTopic)
                }
            }

            LaunchedEffect(Unit) {
                restoredForumActions(
                    state = state,
                    restoredFeed = restoredFeed,
                    restoredTopicId = restoredTopicId,
                ).forEach(onAction)
                restorationDispatched = true
            }

            LaunchedEffect(
                restorationDispatched,
                state.selection.stableKey,
                state.selectedTopicId,
            ) {
                if (
                    restorationDispatched &&
                    state.selection.stableKey == restoredFeed.stableKey &&
                    state.selectedTopicId == restoredTopicId
                ) {
                    restorationComplete = true
                }
            }
            LaunchedEffect(
                state.selection.stableKey,
                state.selectedTopicId,
                layoutClass,
                restorationComplete,
            ) {
                if (restorationComplete) {
                    val desired = desiredRoutes()
                    if (backStack.toList() != desired) replaceRoutes(desired)
                }
            }

            ForumNavigationFrame(
                layoutClass = layoutClass,
                state = state,
                onAction = ::dispatchAndNavigate,
            ) {
                NavDisplay(
                    backStack = backStack,
                    modifier = Modifier.fillMaxSize(),
                    onBack = ::popRoute,
                    sceneStrategies = listOf(sceneStrategy),
                    entryProvider =
                        entryProvider {
                            entry<ForumFeedRoute>(
                                metadata =
                                    ListDetailSceneStrategy.listPane() +
                                        ListDetailSceneStrategy.preferredPaneSize(
                                            width = ForumExpandedListPaneWidth,
                                        ),
                            ) {
                                ForumTopicListPane(
                                    state = state,
                                    onAction = ::dispatchAndNavigate,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            entry<ForumTopicRoute>(
                                metadata = ListDetailSceneStrategy.detailPane(),
                            ) {
                                ForumTopicDetailPane(
                                    state = state,
                                    showBackButton = layoutClass == ForumLayoutClass.Compact,
                                    onBack = {
                                        dispatchAndNavigate(DiscourseForumAction.CloseTopic)
                                    },
                                    onRetry = {
                                        dispatchAndNavigate(DiscourseForumAction.RetryTopic)
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            entry<ForumEmptyDetailRoute>(
                                metadata = ListDetailSceneStrategy.detailPane(),
                            ) {
                                ForumTopicDetailPane(
                                    state =
                                        state.copy(
                                            selectedTopicId = null,
                                            selectedTopic = null,
                                        ),
                                    showBackButton = false,
                                    onBack = {},
                                    onRetry = {},
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            entry<ForumSupportingRoute>(
                                metadata =
                                    ListDetailSceneStrategy.extraPane() +
                                        ListDetailSceneStrategy.preferredPaneSize(
                                            width = ForumSupportingPaneWidth,
                                        ),
                            ) {
                                ForumSupportingPane(
                                    state = state,
                                    onAction = ::dispatchAndNavigate,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        },
                )
            }
            BackHandler(enabled = backStack.none { it is ForumTopicRoute }) {
                activity?.finish()
            }
        }
    }
}

/** Builds pane routes from current semantic state; static panes never become user history. */
internal fun forumRoutesFor(
    feed: DiscourseForumFeed,
    selectedTopicId: Long?,
    layoutClass: ForumLayoutClass,
): List<ForumNavKey> =
    buildList {
        add(ForumFeedRoute(feed))
        if (layoutClass == ForumLayoutClass.Expanded) {
            add(ForumSupportingRoute)
        }
        if (selectedTopicId != null) {
            add(ForumTopicRoute(selectedTopicId))
        } else if (layoutClass != ForumLayoutClass.Compact) {
            add(ForumEmptyDetailRoute)
        }
    }

/** Rehydrates presenter semantics before its initial state can replace a restored Nav3 stack. */
internal fun restoredForumActions(
    state: DiscourseForumState,
    restoredFeed: DiscourseForumFeed,
    restoredTopicId: Long?,
): List<DiscourseForumAction> =
    buildList {
        if (state.selection.stableKey != restoredFeed.stableKey) {
            add(DiscourseForumAction.SelectFeed(restoredFeed))
        }
        if (restoredTopicId != null && state.selectedTopicId != restoredTopicId) {
            add(DiscourseForumAction.OpenTopic(restoredTopicId))
        } else if (restoredTopicId == null && state.selectedTopicId != null) {
            add(DiscourseForumAction.CloseTopic)
        }
    }

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
