package dev.dimension.flare.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthenticationAction
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthenticationFailureKind
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthenticationState
import dev.dimension.flare.data.network.discourse.auth.DiscourseQrShare
import dev.dimension.flare.data.network.discourse.auth.DiscourseQrShareAction
import dev.dimension.flare.data.network.discourse.auth.DiscourseQrShareState
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumAction
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumDestination
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFailureKind
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumNotificationsState
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumState
import dev.dimension.flare.data.network.discourse.realtime.DiscourseSessionRecoveryReason
import dev.dimension.flare.ui.theme.FlareDoTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Headless semantic and raster checks for the Compose Desktop forum workspace. */
@OptIn(ExperimentalTestApi::class)
internal class DesktopForumShellRenderTest {
    @Test
    fun expandedWorkspaceExposesThreeOrderedPanesAndRoutesTopicClicks() =
        runDesktopComposeUiTest(width = 1_280, height = 800) {
            val state = ForumPreviewFixtures.loaded()
            val firstTopicId = requireNotNull(state.topics.first().discourse).ref.topicId
            var observedAction: DiscourseForumAction? = null

            setContent {
                FlareDoTheme(darkTheme = false) {
                    ForumWorkspace(
                        state = state,
                        onAction = { observedAction = it },
                    )
                }
            }

            val workspace = onNodeWithTag(ForumTestTags.WORKSPACE).assertIsDisplayed()
            workspace.assertWidthIsEqualTo(1_280.dp).assertHeightIsEqualTo(800.dp)
            val list = onNodeWithTag(ForumTestTags.TOPIC_LIST).assertIsDisplayed()
            val detail = onNodeWithTag(ForumTestTags.TOPIC_DETAIL).assertIsDisplayed()
            val supporting = onNodeWithTag(ForumTestTags.SUPPORTING_PANE).assertIsDisplayed()
            val listBounds = list.getUnclippedBoundsInRoot()
            val detailBounds = detail.getUnclippedBoundsInRoot()
            val supportingBounds = supporting.getUnclippedBoundsInRoot()

            assertTrue(listBounds.right <= detailBounds.left, "Topic list must end before detail starts")
            assertTrue(detailBounds.right <= supportingBounds.left, "Detail must end before supporting pane starts")

            onNodeWithTag(ForumTestTags.topic(firstTopicId))
                .assertIsDisplayed()
                .assertHasClickAction()
                .performClick()
            runOnIdle {
                assertEquals(DiscourseForumAction.OpenTopic(firstTopicId), observedAction)
            }

            captureToImage().also { image ->
                assertEquals(1_280, image.width)
                assertEquals(800, image.height)
                image.assertVisibleVariation("topic list", listBounds)
                image.assertVisibleVariation("topic detail", detailBounds)
                image.assertVisibleVariation("supporting pane", supportingBounds)
                image.writeReport("expanded-workspace-light.png")
            }
        }

    @Test
    fun compactDarkWorkspaceRendersOnlyTheSelectedTopicPane() =
        runDesktopComposeUiTest(width = 500, height = 720) {
            setContent {
                FlareDoTheme(darkTheme = true) {
                    ForumWorkspace(
                        state = ForumPreviewFixtures.loaded(),
                        onAction = {},
                    )
                }
            }

            onNodeWithTag(ForumTestTags.WORKSPACE)
                .assertIsDisplayed()
                .assertWidthIsEqualTo(500.dp)
                .assertHeightIsEqualTo(720.dp)
            onNodeWithTag(ForumTestTags.TOPIC_LIST).assertDoesNotExist()
            onNodeWithTag(ForumTestTags.SUPPORTING_PANE).assertDoesNotExist()
            val detailBounds =
                onNodeWithTag(ForumTestTags.TOPIC_DETAIL)
                    .assertIsDisplayed()
                    .getUnclippedBoundsInRoot()

            captureToImage().also { image ->
                assertEquals(500, image.width)
                assertEquals(720, image.height)
                image.assertVisibleVariation("compact topic detail", detailBounds)
                image.writeReport("compact-topic-dark.png")
            }
        }

    @Test
    fun guestAuthenticationControlsDispatchPrimaryAndFallbackLogin() =
        runDesktopComposeUiTest(width = 500, height = 720) {
            val observedActions = mutableListOf<DiscourseAuthenticationAction>()
            var qrLaunches = 0
            val guestProfile =
                ForumPreviewFixtures.loaded(withSelectedTopic = false).copy(
                    destination = DiscourseForumDestination.Profile,
                )
            setContent {
                FlareDoTheme(darkTheme = false) {
                    ForumAuthenticationProvider(
                        state = DiscourseAuthenticationState(),
                        onAction = observedActions::add,
                        qrLoginAvailable = true,
                        onQrLogin = { qrLaunches += 1 },
                    ) {
                        ForumProfilePane(
                            state = guestProfile,
                            onAction = {},
                        )
                    }
                }
            }

            onNodeWithTag(ForumTestTags.AUTH_SIGN_OUT).assertDoesNotExist()
            captureToImage().writeReport("compact-auth-center-light.png")
            onNodeWithTag(ForumTestTags.AUTH_SIGN_IN)
                .assertIsDisplayed()
                .assertHasClickAction()
                .performClick()
            onNodeWithTag(ForumTestTags.AUTH_FALLBACK)
                .assertIsDisplayed()
                .assertHasClickAction()
                .performClick()
            onNodeWithTag(ForumTestTags.AUTH_QR)
                .assertIsDisplayed()
                .assertHasClickAction()
                .performClick()

            runOnIdle {
                assertEquals(
                    listOf(
                        DiscourseAuthenticationAction.BeginAuthorization,
                        DiscourseAuthenticationAction.BeginFallbackLogin,
                    ),
                    observedActions,
                )
                assertEquals(1, qrLaunches)
            }
        }

    @Test
    fun authenticatedProfileConfirmsDisplaysAndRevokesQrShare() =
        runDesktopComposeUiTest(width = 700, height = 720) {
            val profile = ForumPreviewFixtures.profile()
            val actions = mutableListOf<DiscourseQrShareAction>()
            var qrState by mutableStateOf(DiscourseQrShareState())
            val expiresAtEpochMillis = Clock.System.now().toEpochMilliseconds() + 600_000L
            setContent {
                FlareDoTheme(darkTheme = false) {
                    ForumAuthenticationProvider(
                        state = DiscourseAuthenticationState(),
                        onAction = {},
                        qrShareState = qrState,
                        onQrShareAction = { action ->
                            actions += action
                            if (action == DiscourseQrShareAction.Generate) {
                                qrState =
                                    DiscourseQrShareState(
                                        share =
                                            DiscourseQrShare(
                                                id = 1L,
                                                encodedValue =
                                                    "flaredo://qr-login?version=1&credential=a2V5&ticket=YWJjZGVm&account=member&expires=$expiresAtEpochMillis",
                                                username = "member",
                                                expiresAtEpochMillis = expiresAtEpochMillis,
                                            ),
                                    )
                            } else {
                                qrState = DiscourseQrShareState()
                            }
                        },
                    ) {
                        ForumProfilePane(state = profile, onAction = {})
                    }
                }
            }

            onNodeWithTag(ForumTestTags.AUTH_QR_SHARE).assertIsDisplayed().performClick()
            onNodeWithTag(ForumTestTags.AUTH_QR_SHARE_CONFIRM).assertIsDisplayed().performClick()
            captureToImage().writeReport("qr-share-dialog-light.png")
            onNodeWithTag(ForumTestTags.AUTH_QR_SHARE_IMAGE).assertIsDisplayed()
            onNodeWithTag(ForumTestTags.AUTH_QR_SHARE_DONE).assertIsDisplayed().performClick()
            runOnIdle {
                assertEquals(
                    listOf(DiscourseQrShareAction.Generate, DiscourseQrShareAction.Revoke),
                    actions,
                )
            }
        }

    @Test
    fun authenticatedProfileExposesOnlyLogoutCommand() =
        runDesktopComposeUiTest(width = 500, height = 720) {
            var observedAction: DiscourseAuthenticationAction? = null
            setContent {
                FlareDoTheme(darkTheme = false) {
                    ForumAuthenticationProvider(
                        state = DiscourseAuthenticationState(),
                        onAction = { observedAction = it },
                    ) {
                        ForumProfilePane(
                            state = ForumPreviewFixtures.profile(),
                            onAction = {},
                        )
                    }
                }
            }

            onNodeWithTag(ForumTestTags.AUTH_SIGN_IN).assertDoesNotExist()
            onNodeWithTag(ForumTestTags.AUTH_FALLBACK).assertDoesNotExist()
            onNodeWithTag(ForumTestTags.AUTH_SIGN_OUT)
                .assertIsDisplayed()
                .assertHasClickAction()
                .performClick()

            runOnIdle {
                assertEquals(DiscourseAuthenticationAction.Logout, observedAction)
            }
        }

    @Test
    fun compactGuestProfileKeepsFailedLoginActionsReachableAtLargeFontScale() =
        runDesktopComposeUiTest(width = 400, height = 400) {
            val observedActions = mutableListOf<DiscourseAuthenticationAction>()
            val guestProfile =
                ForumPreviewFixtures.loaded(withSelectedTopic = false).copy(
                    destination = DiscourseForumDestination.Profile,
                )
            setContent {
                val hostDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(hostDensity.density, fontScale = 1.5f),
                ) {
                    FlareDoTheme(darkTheme = false) {
                        ForumAuthenticationProvider(
                            state =
                                DiscourseAuthenticationState(
                                    failure = DiscourseAuthenticationFailureKind.BrowserUnavailable,
                                ),
                            onAction = observedActions::add,
                        ) {
                            ForumProfilePane(state = guestProfile, onAction = {})
                        }
                    }
                }
            }

            onNodeWithTag(ForumTestTags.PROFILE).assertIsDisplayed()
            onNodeWithTag(ForumTestTags.AUTH_FAILURE).performScrollTo().assertIsDisplayed()
            onNodeWithTag(ForumTestTags.AUTH_SIGN_IN)
                .performScrollTo()
                .assertIsDisplayed()
                .assertHasClickAction()
                .performClick()
            onNodeWithTag(ForumTestTags.AUTH_FALLBACK)
                .performScrollTo()
                .assertIsDisplayed()
                .assertHasClickAction()
                .performClick()

            runOnIdle {
                assertEquals(
                    listOf(
                        DiscourseAuthenticationAction.BeginAuthorization,
                        DiscourseAuthenticationAction.BeginFallbackLogin,
                    ),
                    observedActions,
                )
            }
        }

    @Test
    fun compactGuestNotificationsKeepsFailedLoginActionsReachableAtLargeFontScale() =
        runDesktopComposeUiTest(width = 400, height = 400) {
            val guestNotifications =
                ForumPreviewFixtures.loaded(withSelectedTopic = false).copy(
                    destination = DiscourseForumDestination.Notifications,
                )
            setContent {
                val hostDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(hostDensity.density, fontScale = 1.5f),
                ) {
                    FlareDoTheme(darkTheme = false) {
                        ForumAuthenticationProvider(
                            state =
                                DiscourseAuthenticationState(
                                    failure = DiscourseAuthenticationFailureKind.Network,
                                ),
                            onAction = {},
                        ) {
                            ForumNotificationsPane(state = guestNotifications, onAction = {})
                        }
                    }
                }
            }

            onNodeWithTag(ForumTestTags.NOTIFICATIONS).assertIsDisplayed()
            onNodeWithTag(ForumTestTags.AUTH_FAILURE).performScrollTo().assertIsDisplayed()
            onNodeWithTag(ForumTestTags.AUTH_SIGN_IN)
                .performScrollTo()
                .assertIsDisplayed()
                .assertHasClickAction()
            onNodeWithTag(ForumTestTags.AUTH_FALLBACK)
                .performScrollTo()
                .assertIsDisplayed()
                .assertHasClickAction()
        }

    @Test
    fun compactRecoveryKeepsLogoutReachableAndRemovesLoginActionsAtLargeFontScale() =
        runDesktopComposeUiTest(width = 400, height = 400) {
            val observedActions = mutableListOf<DiscourseAuthenticationAction>()
            setContent {
                val hostDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(hostDensity.density, fontScale = 1.5f),
                ) {
                    FlareDoTheme(darkTheme = false) {
                        ForumAuthenticationProvider(
                            state = DiscourseAuthenticationState(isBusy = true),
                            onAction = observedActions::add,
                        ) {
                            ForumWorkspace(
                                state = recoveryState(DiscourseSessionRecoveryReason.AuthenticationRequired),
                                onAction = {},
                            )
                        }
                    }
                }
            }

            val bandBounds =
                onNodeWithTag(ForumTestTags.SESSION_RECOVERY_BAND)
                    .assertIsDisplayed()
                    .assertWidthIsEqualTo(400.dp)
                    .getUnclippedBoundsInRoot()
            val messageBounds =
                onNodeWithTag(ForumTestTags.SESSION_RECOVERY_MESSAGE)
                    .assertIsDisplayed()
                    .getUnclippedBoundsInRoot()
            val action =
                onNodeWithTag(ForumTestTags.SESSION_RECOVERY_ACTION)
                    .assertIsDisplayed()
                    .assertIsEnabled()
                    .assertHasClickAction()
            val actionBounds = action.getUnclippedBoundsInRoot()
            val notificationsTop =
                onNodeWithTag(ForumTestTags.NOTIFICATIONS)
                    .assertIsDisplayed()
                    .getUnclippedBoundsInRoot()
                    .top

            assertTrue(messageBounds.bottom <= actionBounds.top, "Compact recovery text must precede its action")
            assertTrue(actionBounds.bottom <= bandBounds.bottom, "Compact recovery action must stay inside the band")
            assertTrue(bandBounds.bottom <= notificationsTop, "Recovery band must not cover compact content")
            onNodeWithTag(ForumTestTags.AUTH_REQUIRED).assertIsDisplayed()
            onNodeWithTag(ForumTestTags.AUTH_SIGN_IN).assertDoesNotExist()
            onNodeWithTag(ForumTestTags.AUTH_FALLBACK).assertDoesNotExist()

            action.performClick()
            runOnIdle {
                assertEquals(
                    listOf<DiscourseAuthenticationAction>(DiscourseAuthenticationAction.ClearSession),
                    observedActions,
                )
            }
        }

    @Test
    fun mediumRecoveryBandKeepsMessageActionAndPanesDisjoint() =
        runDesktopComposeUiTest(width = 610, height = 500) {
            setContent {
                FlareDoTheme(darkTheme = false) {
                    ForumAuthenticationProvider(
                        state = DiscourseAuthenticationState(),
                        onAction = {},
                    ) {
                        ForumWorkspace(
                            state = recoveryState(DiscourseSessionRecoveryReason.PermissionDenied),
                            onAction = {},
                        )
                    }
                }
            }

            assertWideRecoveryGeometry(expectedWidth = 610.dp)
            onNodeWithTag(ForumTestTags.TOPIC_DETAIL).assertIsDisplayed()
            onNodeWithTag(ForumTestTags.SUPPORTING_PANE).assertDoesNotExist()
        }

    @Test
    fun expandedRecoveryBandKeepsMessageActionAndThreePanesDisjoint() =
        runDesktopComposeUiTest(width = 900, height = 500) {
            setContent {
                FlareDoTheme(darkTheme = true) {
                    ForumAuthenticationProvider(
                        state = DiscourseAuthenticationState(),
                        onAction = {},
                    ) {
                        ForumWorkspace(
                            state = recoveryState(DiscourseSessionRecoveryReason.ManualChallengeRequired),
                            onAction = {},
                        )
                    }
                }
            }

            assertWideRecoveryGeometry(expectedWidth = 900.dp)
            onNodeWithTag(ForumTestTags.TOPIC_DETAIL).assertIsDisplayed()
            onNodeWithTag(ForumTestTags.SUPPORTING_PANE).assertIsDisplayed()
        }

    private fun ComposeUiTest.assertWideRecoveryGeometry(expectedWidth: Dp) {
        val bandBounds =
            onNodeWithTag(ForumTestTags.SESSION_RECOVERY_BAND)
                .assertIsDisplayed()
                .assertWidthIsEqualTo(expectedWidth)
                .getUnclippedBoundsInRoot()
        val messageBounds =
            onNodeWithTag(ForumTestTags.SESSION_RECOVERY_MESSAGE)
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
        val actionBounds =
            onNodeWithTag(ForumTestTags.SESSION_RECOVERY_ACTION)
                .assertIsDisplayed()
                .assertHasClickAction()
                .getUnclippedBoundsInRoot()
        val notificationsTop =
            onNodeWithTag(ForumTestTags.NOTIFICATIONS)
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
                .top

        assertTrue(messageBounds.right <= actionBounds.left, "Wide recovery text must end before its action")
        assertTrue(actionBounds.right <= bandBounds.right, "Wide recovery action must stay inside the band")
        assertTrue(bandBounds.bottom <= notificationsTop, "Recovery band must not cover account content")
        onNodeWithTag(ForumTestTags.AUTH_SIGN_IN).assertDoesNotExist()
        onNodeWithTag(ForumTestTags.AUTH_FALLBACK).assertDoesNotExist()
    }
}

private fun recoveryState(reason: DiscourseSessionRecoveryReason): DiscourseForumState =
    ForumPreviewFixtures.loaded(withSelectedTopic = false).copy(
        destination = DiscourseForumDestination.Notifications,
        sessionGeneration = 6L,
        accountId = "preview-account",
        isAuthenticated = true,
        accountUsername = "preview_member",
        notifications =
            DiscourseForumNotificationsState(
                failure = DiscourseForumFailureKind.Authentication,
            ),
        realtimeRecoveryReason = reason,
    )

/**
 * Checks a structural pixel contract instead of a cross-platform golden.
 *
 * Skiko provides a deterministic offscreen surface, but host font rasterization is deliberately
 * platform-specific. Requiring opaque samples, several color buckets, and useful luma range catches
 * blank or single-color panes while remaining stable on Linux, Windows, and macOS CI runners.
 */
private fun ImageBitmap.assertVisibleVariation(
    label: String,
    bounds: DpRect,
) {
    // runDesktopComposeUiTest uses Density(1f), so its dp bounds map directly onto raster pixels.
    val left = floor(bounds.left.value).toInt().coerceIn(0, width - 1)
    val top = floor(bounds.top.value).toInt().coerceIn(0, height - 1)
    val right =
        bounds.right.value
            .roundToInt()
            .coerceIn(left + 1, width)
    val bottom =
        bounds.bottom.value
            .roundToInt()
            .coerceIn(top + 1, height)
    val stepX = maxOf(1, (right - left) / 40)
    val stepY = maxOf(1, (bottom - top) / 40)
    val pixels = toPixelMap()
    val colorBuckets = mutableSetOf<Int>()
    var visibleSamples = 0
    var minimumLuma = Float.POSITIVE_INFINITY
    var maximumLuma = Float.NEGATIVE_INFINITY

    for (y in top until bottom step stepY) {
        for (x in left until right step stepX) {
            val color = pixels[x, y]
            if (color.alpha < 0.1f) continue
            val luma = color.red * 0.2126f + color.green * 0.7152f + color.blue * 0.0722f
            minimumLuma = minOf(minimumLuma, luma)
            maximumLuma = maxOf(maximumLuma, luma)
            colorBuckets +=
                (color.red * 15).roundToInt().coerceIn(0, 15) shl 8 or
                ((color.green * 15).roundToInt().coerceIn(0, 15) shl 4) or
                (color.blue * 15).roundToInt().coerceIn(0, 15)
            visibleSamples += 1
        }
    }

    assertTrue(visibleSamples >= 16, "$label rendered too few visible pixels: $visibleSamples")
    assertTrue(colorBuckets.size >= 4, "$label rendered as an effectively single-color region")
    assertTrue(maximumLuma - minimumLuma >= 0.08f, "$label does not contain readable contrast")
}

private fun ImageBitmap.writeReport(name: String) {
    val directory = File("build/reports/desktop-screenshots")
    check(directory.isDirectory || directory.mkdirs()) {
        "Unable to create desktop screenshot report directory: ${directory.absolutePath}"
    }
    val output = directory.resolve(name)
    check(ImageIO.write(toAwtImage(), "png", output)) {
        "No PNG writer is available for desktop screenshot reports"
    }
}
