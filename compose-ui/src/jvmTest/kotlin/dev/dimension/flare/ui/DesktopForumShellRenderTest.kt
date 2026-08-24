package dev.dimension.flare.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumAction
import dev.dimension.flare.ui.theme.FlareDoTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
}

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
