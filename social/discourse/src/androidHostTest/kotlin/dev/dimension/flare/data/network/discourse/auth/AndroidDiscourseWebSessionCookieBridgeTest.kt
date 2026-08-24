package dev.dimension.flare.data.network.discourse.auth

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidDiscourseWebSessionCookieBridgeTest {
    @Test
    fun bridgeReadsOnlyTheFixedOriginAndRemovesTheWholeWebViewCookieStore() =
        runTest {
            val backend = FakeAndroidWebCookieBackend("_t=session-value; cf_clearance=clearance-value")
            val bridge =
                AndroidDiscourseWebSessionCookieBridge(
                    mainDispatcher = StandardTestDispatcher(testScheduler),
                    backend = backend,
                )

            val cookies = bridge.snapshotLinuxDoCookies()

            assertEquals(listOf("_t", "cf_clearance"), cookies.map { it.name })
            assertTrue(cookies.single { it.name == "_t" }.httpOnly)
            assertTrue(cookies.all { it.domain == "linux.do" && it.path == "/" && it.secure })
            assertEquals(listOf("https://linux.do"), backend.readOrigins)

            bridge.clearLinuxDoCookies()
            assertEquals(1, backend.removeAllCalls)
            assertTrue(backend.didFlush)
            assertEquals(listOf("removeAll", "flush"), backend.cleanupEvents)
            // Cleanup does not infer cookie scope from getCookie(), which omits Domain metadata.
            assertEquals(listOf("https://linux.do"), backend.readOrigins)
        }

    @Test
    fun parserRejectsDuplicatesMalformedSegmentsAndOversizedInput() {
        assertFailsWith<IllegalArgumentException> {
            parseBoundedWebCookieHeader("_t=first; _t=second")
        }
        assertFailsWith<IllegalArgumentException> {
            parseBoundedWebCookieHeader("_t=value; malformed")
        }
        assertFailsWith<IllegalArgumentException> {
            parseBoundedWebCookieHeader("_t=${"a".repeat(64 * 1024)}")
        }
    }
}

private class FakeAndroidWebCookieBackend(
    private val header: String?,
) : AndroidWebCookieBackend {
    val readOrigins = mutableListOf<String>()
    val cleanupEvents = mutableListOf<String>()
    var removeAllCalls = 0
    var didFlush = false

    override fun getCookie(origin: String): String? {
        readOrigins += origin
        return header
    }

    override suspend fun removeAllCookies() {
        removeAllCalls += 1
        cleanupEvents += "removeAll"
    }

    override fun flush() {
        didFlush = true
        cleanupEvents += "flush"
    }
}
