package dev.dimension.flare.data.network.discourse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class DiscourseHttpClientUserAgentTest {
    @Test
    fun removesAndroidWebViewMarkersUsedByEmbeddedBrowsers() {
        val raw =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"

        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            sanitizeAndroidDiscourseBrowserUserAgent(raw),
        )
    }

    @Test
    fun rejectsMissingOversizedAndNonAsciiUserAgents() {
        assertNull(sanitizeAndroidDiscourseBrowserUserAgent(null))
        assertNull(sanitizeAndroidDiscourseBrowserUserAgent(" "))
        assertNull(sanitizeAndroidDiscourseBrowserUserAgent("x".repeat(1_025)))
        assertNull(sanitizeAndroidDiscourseBrowserUserAgent("Mozilla/5.0\u0000"))
    }
}
