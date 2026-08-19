package dev.dimension.flare.data.network.discourse.auth

import kotlinx.coroutines.test.runTest
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmDiscourseWebSessionCookieBridgeTest {
    @Test
    fun snapshotKeepsOnlyBoundedLinuxDoCookies() =
        runTest {
            val manager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
            manager.cookieStore.add(
                LINUX_DO_URI,
                cookie(name = "_t", value = "session-value", httpOnly = true),
            )
            manager.cookieStore.add(
                LINUX_DO_URI,
                cookie(
                    name = "cf_clearance",
                    value = "challenge.AZaz09-_:+=/",
                    maxAge = 90L,
                ),
            )
            manager.cookieStore.add(
                FOREIGN_URI,
                cookie(name = "foreign", value = "must-not-cross", domain = "example.com"),
            )

            val bridge =
                JvmDiscourseWebSessionCookieBridge(
                    cookieStore = manager.cookieStore,
                    nowEpochMillis = { 1_000_000L },
                    testMarker = Unit,
                )

            val snapshot = bridge.snapshotLinuxDoCookies()

            assertEquals(listOf("_t", "cf_clearance"), snapshot.map { it.name })
            assertTrue(snapshot.all { it.domain == "linux.do" && it.secure })
            assertTrue(snapshot.single { it.name == "_t" }.httpOnly)
            assertEquals(
                1_090_000L,
                snapshot.single { it.name == "cf_clearance" }.expiresAtEpochMillis,
            )
        }

    @Test
    fun nonSessionCookieCannotInjectASessionCookieThroughItsRawValue() =
        runTest {
            val manager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
            manager.cookieStore.add(
                LINUX_DO_URI,
                cookie(name = "_t", value = "fresh-session", httpOnly = true),
            )
            manager.cookieStore.add(
                LINUX_DO_URI,
                cookie(name = "cf_clearance", value = "clearance; _t=attacker-session"),
            )
            val bridge = JvmDiscourseWebSessionCookieBridge(manager)

            assertFailsWith<IllegalArgumentException> {
                bridge.snapshotLinuxDoCookies()
            }
        }

    @Test
    fun browserControlCharactersFailClosed() =
        runTest {
            val manager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
            manager.cookieStore.add(
                LINUX_DO_URI,
                cookie(name = "cf_clearance", value = "clearance\u0001value"),
            )
            val bridge = JvmDiscourseWebSessionCookieBridge(manager)

            assertFailsWith<IllegalArgumentException> {
                bridge.snapshotLinuxDoCookies()
            }
        }

    @Test
    fun clearRemovesLinuxDoWithoutEnumeratingAnotherOrigin() =
        runTest {
            val manager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
            manager.cookieStore.add(LINUX_DO_URI, cookie(name = "_t", value = "session-value"))
            manager.cookieStore.add(
                FOREIGN_URI,
                cookie(name = "foreign", value = "must-remain", domain = "example.com"),
            )
            val bridge = JvmDiscourseWebSessionCookieBridge(manager)

            bridge.clearLinuxDoCookies()

            assertTrue(manager.cookieStore.get(LINUX_DO_URI).isEmpty())
            assertEquals(
                "must-remain",
                manager.cookieStore
                    .get(FOREIGN_URI)
                    .single()
                    .value,
            )
        }

    @Test
    fun oversizedBrowserCookieFailsClosed() =
        runTest {
            val manager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
            manager.cookieStore.add(
                LINUX_DO_URI,
                cookie(name = "cf_clearance", value = "x".repeat(8 * 1024 + 1)),
            )
            val bridge = JvmDiscourseWebSessionCookieBridge(manager)

            assertFailsWith<IllegalArgumentException> {
                bridge.snapshotLinuxDoCookies()
            }
        }

    private fun cookie(
        name: String,
        value: String,
        domain: String = "linux.do",
        maxAge: Long = -1L,
        httpOnly: Boolean = false,
    ): HttpCookie =
        HttpCookie(name, value).apply {
            this.domain = domain
            path = "/"
            secure = true
            this.maxAge = maxAge
            isHttpOnly = httpOnly
        }

    private companion object {
        val LINUX_DO_URI: URI = URI.create("https://linux.do/")
        val FOREIGN_URI: URI = URI.create("https://example.com/")
    }
}
