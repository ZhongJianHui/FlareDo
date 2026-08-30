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
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthenticationAction
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthenticationPresenter
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthenticationState
import dev.dimension.flare.data.network.discourse.auth.DiscourseQrLoginService
import dev.dimension.flare.data.network.discourse.auth.DiscourseSavedLoginStore
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerMode
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerPresenter
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerState
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumAction
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumDestination
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFeed
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumPresenter
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumState
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface ForumNavKey : NavKey

@Serializable
internal data class ForumFeedRoute(
    val feed: DiscourseForumFeed,
    val destination: DiscourseForumDestination,
) : ForumNavKey

@Serializable
internal data class ForumTopicRoute(
    val topicId: Long,
    val postNumber: Int? = null,
) : ForumNavKey {
    init {
        require(topicId > 0L) { "Forum topic route id must be positive" }
        require(postNumber == null || postNumber > 0) {
            "Forum topic route post number must be positive"
        }
    }
}

@Serializable
internal data object ForumEmptyDetailRoute : ForumNavKey

@Serializable
internal data object ForumSupportingRoute : ForumNavKey

/** Collects the shared presenter with the Android lifecycle and dispatches non-blocking actions. */
@Composable
public fun AndroidForumShell(
    presenter: DiscourseForumPresenter,
    composerPresenter: DiscourseComposerPresenter,
    authenticationPresenter: DiscourseAuthenticationPresenter,
    qrLoginService: DiscourseQrLoginService? = null,
    savedLoginStore: DiscourseSavedLoginStore? = null,
    modifier: Modifier = Modifier,
) {
    val state by presenter.models.collectAsStateWithLifecycle()
    val composerState by composerPresenter.models.collectAsStateWithLifecycle()
    val authenticationState by authenticationPresenter.models.collectAsStateWithLifecycle()
    val attachmentPicker = rememberForumAttachmentPicker()
    val qrLogin = rememberAndroidQrLoginCapability(qrLoginService)
    AndroidForumShell(
        state = state,
        onAction = { presenter.dispatch(it) },
        composerState = composerState,
        onComposerAction = { composerPresenter.dispatchForumAction(it) },
        attachmentPicker = attachmentPicker,
        authenticationState = authenticationState,
        onAuthenticationAction = { authenticationPresenter.dispatch(it) },
        qrLogin = qrLogin,
        savedLoginStore = savedLoginStore,
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
    composerState: DiscourseComposerState,
    onComposerAction: (ForumComposerAction) -> Unit,
    attachmentPicker: ForumAttachmentPicker,
    authenticationState: DiscourseAuthenticationState = DiscourseAuthenticationState(),
    onAuthenticationAction: (DiscourseAuthenticationAction) -> Boolean = { false },
    qrLogin: ForumQrLoginCapability = ForumQrLoginCapability(),
    savedLoginStore: DiscourseSavedLoginStore? = null,
    modifier: Modifier = Modifier,
) {
    ForumAuthenticationProvider(
        state = authenticationState,
        onAction = { action -> onAuthenticationAction(action) },
        qrLoginAvailable = qrLogin.available,
        qrLoginBusy = qrLogin.busy,
        qrLoginFailure = qrLogin.failure,
        onQrLogin = qrLogin.launch,
    ) {
        AndroidAuthenticationBrowserEffects(
            state = authenticationState,
            onAction = onAuthenticationAction,
            savedLoginStore = savedLoginStore,
        )
        AndroidForumShellContent(
            state = state,
            onAction = onAction,
            composerState = composerState,
            onComposerAction = onComposerAction,
            attachmentPicker = attachmentPicker,
            modifier = modifier,
        )
    }
}

@Composable
private fun AndroidForumShellContent(
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
    composerState: DiscourseComposerState,
    onComposerAction: (ForumComposerAction) -> Unit,
    attachmentPicker: ForumAttachmentPicker,
    modifier: Modifier,
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
            val backStack =
                rememberNavBackStack(
                    ForumFeedRoute(
                        feed = state.selection,
                        destination = state.destination,
                    ),
                )
            val sceneStrategy = rememberListDetailSceneStrategy<NavKey>()
            val restoredRoot = remember { backStack.filterIsInstance<ForumFeedRoute>().first() }
            val restoredTopic = remember { backStack.filterIsInstance<ForumTopicRoute>().lastOrNull() }
            var restorationDispatched by remember { mutableStateOf(false) }
            var restorationComplete by remember { mutableStateOf(false) }

            fun desiredRoutes(): List<ForumNavKey> =
                forumRoutesFor(
                    destination = state.destination,
                    feed = state.selection,
                    selectedTopicId = state.selectedTopicId,
                    selectedPostNumber = state.selectedPostNumber,
                    layoutClass = layoutClass,
                )

            fun replaceRoutes(routes: List<ForumNavKey>) {
                backStack.clear()
                backStack.addAll(routes)
            }

            fun dispatchAndNavigate(action: DiscourseForumAction) {
                when (action) {
                    is DiscourseForumAction.SelectDestination -> {
                        replaceRoutes(
                            forumRoutesFor(
                                destination = action.destination,
                                feed = state.selection,
                                selectedTopicId = null,
                                selectedPostNumber = null,
                                layoutClass = layoutClass,
                            ),
                        )
                    }

                    is DiscourseForumAction.SelectFeed -> {
                        replaceRoutes(
                            forumRoutesFor(
                                destination =
                                    if (action.feed == DiscourseForumFeed.Hot) {
                                        DiscourseForumDestination.Hot
                                    } else {
                                        DiscourseForumDestination.Latest
                                    },
                                feed = action.feed,
                                selectedTopicId = null,
                                selectedPostNumber = null,
                                layoutClass = layoutClass,
                            ),
                        )
                    }

                    is DiscourseForumAction.OpenTopic -> {
                        replaceRoutes(
                            forumRoutesFor(
                                destination = state.destination,
                                feed = state.selection,
                                selectedTopicId = action.topicId,
                                selectedPostNumber = action.postNumber,
                                layoutClass = layoutClass,
                            ),
                        )
                    }

                    DiscourseForumAction.CloseTopic -> {
                        replaceRoutes(
                            forumRoutesFor(
                                destination = state.destination,
                                feed = state.selection,
                                selectedTopicId = null,
                                selectedPostNumber = null,
                                layoutClass = layoutClass,
                            ),
                        )
                    }

                    DiscourseForumAction.Refresh,
                    DiscourseForumAction.RefreshNotifications,
                    DiscourseForumAction.RetryNotifications,
                    DiscourseForumAction.RetryProfile,
                    DiscourseForumAction.RetrySearch,
                    DiscourseForumAction.LoadNextPage,
                    DiscourseForumAction.LoadNextActivityPage,
                    DiscourseForumAction.LoadNextNotificationsPage,
                    DiscourseForumAction.LoadNextSearchPage,
                    is DiscourseForumAction.MarkNotificationsRead,
                    is DiscourseForumAction.OpenProfile,
                    DiscourseForumAction.RetryTaxonomy,
                    DiscourseForumAction.RetryTopic,
                    DiscourseForumAction.SubmitSearch,
                    is DiscourseForumAction.UpdateSearchQuery,
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
                    restoredDestination = restoredRoot.destination,
                    restoredFeed = restoredRoot.feed,
                    restoredTopicId = restoredTopic?.topicId,
                    restoredPostNumber = restoredTopic?.postNumber,
                ).forEach(onAction)
                restorationDispatched = true
            }

            LaunchedEffect(
                restorationDispatched,
                state.destination,
                state.selection.stableKey,
                state.selectedTopicId,
                state.selectedPostNumber,
            ) {
                if (
                    restorationDispatched &&
                    state.destination == restoredRoot.destination &&
                    state.selection.stableKey == restoredRoot.feed.stableKey &&
                    state.selectedTopicId == restoredTopic?.topicId &&
                    state.selectedPostNumber == restoredTopic?.postNumber
                ) {
                    restorationComplete = true
                }
            }
            LaunchedEffect(
                state.destination,
                state.selection.stableKey,
                state.selectedTopicId,
                state.selectedPostNumber,
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
                                ForumPrimaryPane(
                                    state = state,
                                    onAction = ::dispatchAndNavigate,
                                    composerState = composerState,
                                    onComposerAction = onComposerAction,
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
                                    composerState = composerState,
                                    onComposerAction = onComposerAction,
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
                                    composerState = composerState,
                                    onComposerAction = onComposerAction,
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
            ForumComposerLayer(
                layoutClass = layoutClass,
                state = composerState,
                onAction = onComposerAction,
                attachmentPicker = attachmentPicker,
            )
            BackHandler(
                enabled =
                    composerState.mode == DiscourseComposerMode.Closed &&
                        backStack.none { it is ForumTopicRoute },
            ) {
                activity?.finish()
            }
            BackHandler(enabled = shouldConsumeComposerAndroidBack(composerState)) {
                if (shouldCloseComposerOnAndroidBack(composerState)) {
                    onComposerAction(ForumComposerAction.Close)
                }
            }
        }
    }
}

internal fun shouldConsumeComposerAndroidBack(state: DiscourseComposerState): Boolean = state.mode != DiscourseComposerMode.Closed

/** Back is consumed while submitting so Android cannot close the editor or finish the activity. */
internal fun shouldCloseComposerOnAndroidBack(state: DiscourseComposerState): Boolean =
    shouldConsumeComposerAndroidBack(state) &&
        forumCanDismissComposer(state)

/** Builds pane routes from current semantic state; static panes never become user history. */
internal fun forumRoutesFor(
    destination: DiscourseForumDestination,
    feed: DiscourseForumFeed,
    selectedTopicId: Long?,
    selectedPostNumber: Int?,
    layoutClass: ForumLayoutClass,
): List<ForumNavKey> =
    buildList {
        require(selectedTopicId != null || selectedPostNumber == null) {
            "A forum post route requires a topic id"
        }
        add(ForumFeedRoute(feed, destination))
        if (layoutClass == ForumLayoutClass.Expanded) {
            add(ForumSupportingRoute)
        }
        if (selectedTopicId != null) {
            add(ForumTopicRoute(selectedTopicId, selectedPostNumber))
        } else if (layoutClass != ForumLayoutClass.Compact) {
            add(ForumEmptyDetailRoute)
        }
    }

/** Rehydrates presenter semantics before its initial state can replace a restored Nav3 stack. */
internal fun restoredForumActions(
    state: DiscourseForumState,
    restoredDestination: DiscourseForumDestination,
    restoredFeed: DiscourseForumFeed,
    restoredTopicId: Long?,
    restoredPostNumber: Int?,
): List<DiscourseForumAction> =
    buildList {
        require(restoredTopicId != null || restoredPostNumber == null) {
            "A restored forum post requires a topic id"
        }
        val feedChanged = state.selection.stableKey != restoredFeed.stableKey
        if (feedChanged) {
            add(DiscourseForumAction.SelectFeed(restoredFeed))
        }
        val destinationAfterFeed =
            if (feedChanged) {
                if (restoredFeed == DiscourseForumFeed.Hot) {
                    DiscourseForumDestination.Hot
                } else {
                    DiscourseForumDestination.Latest
                }
            } else {
                state.destination
            }
        if (destinationAfterFeed != restoredDestination) {
            add(DiscourseForumAction.SelectDestination(restoredDestination))
        }
        if (
            restoredTopicId != null &&
            (state.selectedTopicId != restoredTopicId || state.selectedPostNumber != restoredPostNumber)
        ) {
            add(DiscourseForumAction.OpenTopic(restoredTopicId, restoredPostNumber))
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
