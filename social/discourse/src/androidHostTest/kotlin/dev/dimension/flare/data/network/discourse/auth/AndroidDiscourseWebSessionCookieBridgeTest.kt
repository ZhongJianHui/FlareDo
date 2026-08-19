package dev.dimension.flare.data.network.discourse.auth

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidDiscourseWebSessionCookieBridgeTest {
    @Test
    fun bridgeReadsOnlyTheFixedOriginAndExpiresObservedNames() =
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
            assertEquals(2, backend.expired.size)
            assertTrue(backend.expired.all { (origin, _) -> origin == "https://linux.do" })
            assertTrue(backend.didFlush)
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
    val expired = mutableListOf<Pair<String, String>>()
    var didFlush = false

    override fun getCookie(origin: String): String? {
        readOrigins += origin
        return header
    }

    override suspend fun expireCookie(
        origin: String,
        cookie: String,
    ) {
        expired += origin to cookie
    }

    override fun flush() {
        didFlush = true
    }
}
