package dev.dimension.flare

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.webview.web.WebContent
import dev.nucleusframework.webview.web.WebView
import dev.nucleusframework.webview.web.WebViewState
import dev.nucleusframework.webview.web.linux.LinuxWebKitNativeWebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * CI entry point that distinguishes a live WebKitGTK backend from ComposeWebView's no-op fallback.
 *
 * It intentionally loads only an in-memory page and emits no browser state. Linux CI runs it under
 * Xvfb; missing WebKitGTK/JSC/GTK/libsoup libraries, a no-op backend, failed navigation, or a broken
 * renderer all make the process fail.
 */
public fun main() {
    nucleusApplication(
        args = emptyArray(),
        backend = NucleusBackend.Tao,
        enableSingleInstance = false,
    ) {
        val applicationScope = this
        DecoratedWindow(
            onCloseRequest = { exitProcess(SMOKE_CLOSED_EXIT_CODE) },
            title = "FlareDo Tao WebView smoke",
            state = rememberWindowState(size = DpSize(480.dp, 320.dp)),
        ) {
            val webViewState =
                remember {
                    WebViewState(WebContent.NavigatorOnly).apply {
                        webSettings.isJavaScriptEnabled = false
                        webSettings.desktopWebSettings.apply {
                            incognito = true
                            enableClipboard = false
                            enableDevtools = false
                        }
                    }
                }
            WebView(
                state = webViewState,
                modifier = Modifier.fillMaxSize(),
            )
            LaunchedEffect(webViewState) {
                runTaoSmokeWithFailureBoundary(
                    operation = {
                        val nativeWebView: LinuxWebKitNativeWebView =
                            withTimeout<LinuxWebKitNativeWebView>(SMOKE_TIMEOUT_MILLIS) {
                                var readyWebView: LinuxWebKitNativeWebView? = null
                                while (readyWebView == null) {
                                    val candidate = webViewState.webView?.nativeWebView
                                    if (candidate is LinuxWebKitNativeWebView && candidate.isReady()) {
                                        readyWebView = candidate
                                    } else {
                                        delay(SMOKE_POLL_MILLIS)
                                    }
                                }
                                readyWebView
                            }
                        nativeWebView.loadHtml(SMOKE_HTML, SMOKE_BASE_URL)
                        withTimeout(SMOKE_TIMEOUT_MILLIS) {
                            while (nativeWebView.isLoading() || nativeWebView.getTitle() != SMOKE_TITLE) {
                                delay(SMOKE_POLL_MILLIS)
                            }
                        }
                        // The title can be available before GTK allocation and the first WebKit
                        // frame. Retry screenshots inside one total timeout so a slow Xvfb frame
                        // cannot make the smoke test flaky or reset its time budget indefinitely.
                        withTimeout(SMOKE_TIMEOUT_MILLIS) {
                            while (!nativeWebView.captureScreenshotAsync().isRenderedSmokePage()) {
                                delay(SMOKE_POLL_MILLIS)
                            }
                        }
                        println("FLAREDO_TAO_WEBVIEW_RENDERED")
                        applicationScope.exitApplication()
                    },
                    onUnavailable = {
                        System.err.println("FlareDo Tao WebView navigation/render smoke failed.")
                        exitProcess(SMOKE_UNAVAILABLE_EXIT_CODE)
                    },
                )
            }
        }
    }
}

/**
 * Converts this smoke test's own bounded timeout into a deterministic process failure.
 *
 * [TimeoutCancellationException] is also a [CancellationException], so catch order matters: an
 * internal readiness, navigation, or screenshot timeout must invoke [onUnavailable] instead of
 * ending only the Compose effect and leaving the Tao event loop alive. The context activity check
 * distinguishes those child timeouts from a timeout that cancelled the host scope itself, keeping
 * external cancellation structured and unchanged.
 */
internal suspend fun runTaoSmokeWithFailureBoundary(
    operation: suspend () -> Unit,
    onUnavailable: () -> Unit,
) {
    try {
        operation()
    } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        onUnavailable()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        onUnavailable()
    }
}

/**
 * Decodes the native PNG and verifies pixels from the actual smoke document.
 *
 * A PNG signature alone can also describe a transparent, blank, or truncated renderer result. The
 * in-memory page has a large `#0f766e` background and white heading, so requiring both color regions
 * proves that WebKitGTK submitted page content without depending on font metrics or a golden image.
 */
internal fun ByteArray?.isRenderedSmokePage(): Boolean {
    val png = this ?: return false
    if (
        png.size <= PNG_SIGNATURE.size ||
        PNG_SIGNATURE.indices.any { index -> png[index] != PNG_SIGNATURE[index] }
    ) {
        return false
    }
    val image =
        try {
            ImageIO.read(ByteArrayInputStream(png))
        } catch (_: RuntimeException) {
            null
        } catch (_: java.io.IOException) {
            null
        } ?: return false
    if (
        image.width !in MIN_SCREENSHOT_WIDTH..MAX_SCREENSHOT_DIMENSION ||
        image.height !in MIN_SCREENSHOT_HEIGHT..MAX_SCREENSHOT_DIMENSION
    ) {
        return false
    }

    val sampleStride = maxOf(1, maxOf(image.width, image.height) / MAX_SAMPLES_PER_AXIS)
    var opaquePixels = 0
    var backgroundPixels = 0
    var contentPixels = 0
    var firstOpaqueColor: Int? = null
    var hasDistinctOpaqueColor = false
    for (y in 0 until image.height step sampleStride) {
        for (x in 0 until image.width step sampleStride) {
            val argb = image.getRGB(x, y)
            val alpha = argb ushr 24 and 0xff
            if (alpha < MIN_OPAQUE_ALPHA) continue
            opaquePixels += 1
            val rgb = argb and 0x00ffffff
            val initialColor = firstOpaqueColor
            if (initialColor == null) {
                firstOpaqueColor = rgb
            } else if (rgb != initialColor) {
                hasDistinctOpaqueColor = true
            }

            val red = rgb ushr 16 and 0xff
            val green = rgb ushr 8 and 0xff
            val blue = rgb and 0xff
            if (
                red in BACKGROUND_RED_RANGE &&
                green in BACKGROUND_GREEN_RANGE &&
                blue in BACKGROUND_BLUE_RANGE
            ) {
                backgroundPixels += 1
            }
            if (red >= MIN_CONTENT_CHANNEL && green >= MIN_CONTENT_CHANNEL && blue >= MIN_CONTENT_CHANNEL) {
                contentPixels += 1
            }
        }
    }
    return opaquePixels > 0 &&
        hasDistinctOpaqueColor &&
        backgroundPixels > 0 &&
        contentPixels > 0
}

private val PNG_SIGNATURE: ByteArray =
    byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )

private const val SMOKE_BASE_URL: String = "https://flaredo.invalid/smoke/"
private const val SMOKE_TITLE: String = "FlareDo WebView smoke ready"
private const val SMOKE_HTML: String =
    """<!doctype html><html><head><meta charset="utf-8"><title>$SMOKE_TITLE</title></head>""" +
        """<body style="margin:0"><main style="position:fixed;inset:0;background:#0f766e;color:white">""" +
        """<div style="width:96px;height:64px;background:#fff"></div><h1>$SMOKE_TITLE</h1>""" +
        """</main></body></html>"""
private const val SMOKE_TIMEOUT_MILLIS: Long = 15_000L
private const val SMOKE_POLL_MILLIS: Long = 50L
private const val SMOKE_CLOSED_EXIT_CODE: Int = 2
private const val SMOKE_UNAVAILABLE_EXIT_CODE: Int = 3
private const val MIN_SCREENSHOT_WIDTH: Int = 160
private const val MIN_SCREENSHOT_HEIGHT: Int = 100
private const val MAX_SCREENSHOT_DIMENSION: Int = 4_096
private const val MAX_SAMPLES_PER_AXIS: Int = 1_024
private const val MIN_OPAQUE_ALPHA: Int = 0xf0
private const val MIN_CONTENT_CHANNEL: Int = 0xdc
private val BACKGROUND_RED_RANGE: IntRange = 0x00..0x28
private val BACKGROUND_GREEN_RANGE: IntRange = 0x5e..0x96
private val BACKGROUND_BLUE_RANGE: IntRange = 0x56..0x8e
