package dev.dimension.flare

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class TaoWebViewSmokeTest {
    @Test
    fun expectedBackgroundAndContentPixelsPassRenderingValidation() {
        val screenshot =
            renderedPng { image ->
                image.createGraphics().use { graphics ->
                    graphics.color = Color(0x0f, 0x76, 0x6e)
                    graphics.fillRect(0, 0, image.width, image.height)
                    graphics.color = Color.WHITE
                    graphics.fillRect(16, 16, 48, 24)
                }
            }

        assertTrue(screenshot.isRenderedSmokePage())
    }

    @Test
    fun transparentScreenshotFailsRenderingValidation() {
        assertFalse(renderedPng {}.isRenderedSmokePage())
    }

    @Test
    fun singleColorScreenshotFailsRenderingValidation() {
        val screenshot =
            renderedPng { image ->
                image.createGraphics().use { graphics ->
                    graphics.color = Color.WHITE
                    graphics.fillRect(0, 0, image.width, image.height)
                }
            }

        assertFalse(screenshot.isRenderedSmokePage())
    }

    @Test
    fun internalTimeoutTriggersDeterministicFailureBoundary() =
        runTest {
            var unavailableCalls = 0

            runTaoSmokeWithFailureBoundary(
                operation = {
                    withTimeout(1_000L) { awaitCancellation() }
                },
                onUnavailable = { unavailableCalls += 1 },
            )

            assertEquals(1, unavailableCalls)
        }

    @Test
    fun externalCancellationStillPropagatesUnchanged() =
        runTest {
            val cancellation = CancellationException("host stopped")
            var unavailableCalls = 0

            val thrown =
                assertFailsWith<CancellationException> {
                    runTaoSmokeWithFailureBoundary(
                        operation = { throw cancellation },
                        onUnavailable = { unavailableCalls += 1 },
                    )
                }

            assertSame(cancellation, thrown)
            assertEquals(0, unavailableCalls)
        }

    @Test
    fun enclosingTimeoutRemainsStructuredCancellation() =
        runTest {
            var unavailableCalls = 0

            assertFailsWith<TimeoutCancellationException> {
                withTimeout(1_000L) {
                    runTaoSmokeWithFailureBoundary(
                        operation = { awaitCancellation() },
                        onUnavailable = { unavailableCalls += 1 },
                    )
                }
            }

            assertEquals(0, unavailableCalls)
        }
}

private fun renderedPng(paint: (BufferedImage) -> Unit): ByteArray {
    val image = BufferedImage(TEST_IMAGE_WIDTH, TEST_IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB)
    paint(image)
    return ByteArrayOutputStream().use { output ->
        check(ImageIO.write(image, "png", output))
        output.toByteArray()
    }
}

private inline fun <T : java.awt.Graphics> T.use(block: (T) -> Unit) {
    try {
        block(this)
    } finally {
        dispose()
    }
}

private const val TEST_IMAGE_WIDTH: Int = 200
private const val TEST_IMAGE_HEIGHT: Int = 120
