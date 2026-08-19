package dev.dimension.flare.data.network.discourse.session

import io.ktor.http.Cookie
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class DiscourseCookieStorageTest {
    @Test
    fun neverReadsOrWritesCookiesAcrossTheFixedOrigin() =
        runTest {
            val storage = DiscourseCookieStorage()
            storage.addCookie(
                LINUX_DO_ROOT,
                Cookie(name = "_t", value = "self-authored-session", httpOnly = true),
            )

            assertEquals(listOf("_t"), storage.get(LINUX_DO_ROOT).map(Cookie::name))
            assertTrue(storage.get(Url("http://linux.do/")).isEmpty())
            assertTrue(storage.get(Url("https://assets.linux.do/")).isEmpty())
            assertTrue(storage.get(Url("https://example.test/")).isEmpty())
            assertTrue(storage.get(Url("https://linux.do:444/")).isEmpty())

            assertFailsWith<RejectedDiscourseCookieException> {
                storage.addCookie(
                    Url("https://example.test/"),
                    Cookie(name = "foreign", value = "blocked"),
                )
            }
            assertFailsWith<RejectedDiscourseCookieException> {
                storage.addCookie(
                    LINUX_DO_ROOT,
                    Cookie(name = "foreign", value = "blocked", domain = ".example.test"),
                )
            }
        }

    @Test
    fun appliesPathExpiryDeletionAndClearSemantics() =
        runTest {
            var now = 1_000L
            val storage = DiscourseCookieStorage(nowEpochMillis = { now })
            storage.addCookie(
                Url("https://linux.do/t/42"),
                Cookie(name = "topic-cookie", value = "value", path = "/t", maxAge = 10),
            )

            assertEquals(1, storage.get(Url("https://linux.do/t/42")).size)
            assertTrue(storage.get(Url("https://linux.do/latest")).isEmpty())

            now = 11_000L
            assertTrue(storage.get(Url("https://linux.do/t/42")).isEmpty())
            assertTrue(storage.snapshot().isEmpty())

            storage.addCookie(LINUX_DO_ROOT, Cookie(name = "replace", value = "first"))
            storage.addCookie(
                LINUX_DO_ROOT,
                Cookie(name = "replace", value = "expired", maxAge = 0),
            )
            assertTrue(storage.get(LINUX_DO_ROOT).isEmpty())

            storage.addCookie(LINUX_DO_ROOT, Cookie(name = "clear-me", value = "value"))
            storage.clear()
            assertTrue(storage.snapshot().isEmpty())
        }

    @Test
    fun snapshotUsesAbsoluteExpiryAndImportIsAtomic() =
        runTest {
            var now = 1_000L
            val source = DiscourseCookieStorage(nowEpochMillis = { now })
            source.addCookie(
                LINUX_DO_ROOT,
                Cookie(name = "_t", value = "session", maxAge = 10, httpOnly = true),
            )
            val snapshot = source.snapshot()
            assertEquals(11_000L, snapshot.single().expiresAtEpochMillis)

            now = 8_000L
            val restored = DiscourseCookieStorage(nowEpochMillis = { now })
            restored.importSnapshot(snapshot)
            assertEquals("session", restored.get(LINUX_DO_ROOT).single().value)

            assertFailsWith<RejectedDiscourseCookieException> {
                restored.importSnapshot(
                    listOf(
                        DiscourseCookieSnapshot(
                            name = "foreign",
                            value = "must-not-replace-current-state",
                            domain = "example.test",
                        ),
                    ),
                )
            }
            assertEquals("session", restored.get(LINUX_DO_ROOT).single().value)

            now = 11_000L
            assertTrue(restored.get(LINUX_DO_ROOT).isEmpty())
        }

    @Test
    fun rejectsCountAndValueBoundsWithoutEvictingValidCookies() =
        runTest {
            val storage =
                DiscourseCookieStorage(
                    maxCookies = 1,
                    maxCookieNameBytes = 16,
                    maxCookieValueBytes = 16,
                    maxTotalBytes = 256,
                )
            storage.addCookie(LINUX_DO_ROOT, Cookie(name = "kept", value = "value"))

            assertFailsWith<RejectedDiscourseCookieException> {
                storage.addCookie(LINUX_DO_ROOT, Cookie(name = "second", value = "value"))
            }
            assertFailsWith<RejectedDiscourseCookieException> {
                storage.addCookie(LINUX_DO_ROOT, Cookie(name = "large", value = "x".repeat(17)))
            }
            assertEquals(listOf("kept"), storage.snapshot().map(DiscourseCookieSnapshot::name))

            storage.close()
            assertTrue(storage.snapshot().isEmpty())
            assertFailsWith<IllegalStateException> {
                storage.addCookie(LINUX_DO_ROOT, Cookie(name = "late", value = "blocked"))
            }
        }

    @Test
    fun clearRejectsAWriteThatEnteredUnderThePreviousRevision() =
        runTest {
            lateinit var storage: DiscourseCookieStorage
            var clearDuringClockRead = false
            storage =
                DiscourseCookieStorage(
                    nowEpochMillis = {
                        if (clearDuringClockRead) storage.clear()
                        1_000L
                    },
                )
            storage.addCookie(LINUX_DO_ROOT, Cookie(name = "old", value = "value"))

            clearDuringClockRead = true
            storage.addCookie(LINUX_DO_ROOT, Cookie(name = "late-old", value = "must-be-dropped"))

            clearDuringClockRead = false
            assertTrue(storage.snapshot().isEmpty())
        }

    private companion object {
        val LINUX_DO_ROOT: Url = Url("https://linux.do/")
    }
}
