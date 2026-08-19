package dev.dimension.flare.data.network.discourse

import dev.dimension.flare.data.network.discourse.content.DiscourseCookedHtmlParser
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumCache
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumMapper
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumPresenter
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumRepository
import dev.dimension.flare.data.network.discourse.forum.MemoryDiscourseForumCache
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieStorage
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.SecureCredentialRef
import dev.dimension.flare.data.network.discourse.session.SecureCredentialStore
import dev.dimension.flare.data.network.discourse.session.SessionOnlySecureCredentialStore
import dev.dimension.flare.di.sharedModule
import dev.dimension.flare.model.PlatformRegistry
import io.ktor.client.HttpClient
import io.ktor.http.Cookie
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class DiscourseModuleTest {
    @Test
    fun sharedAndDiscourseModulesResolveTheForumDependencyGraphAndCloseSessionState() =
        runTest {
            val application =
                koinApplication {
                    modules(sharedModule, discourseModule)
                }
            val credentialStore = application.koin.get<SecureCredentialStore>()
            val cookieStorage = application.koin.get<DiscourseCookieStorage>()
            var credentialReference: SecureCredentialRef? = null
            var firstPresenter: DiscourseForumPresenter? = null
            var secondPresenter: DiscourseForumPresenter? = null

            try {
                val registry = application.koin.get<PlatformRegistry>()
                val api = application.koin.get<DiscourseApi>()
                val dataSource = application.koin.get<DiscourseDataSource>()
                val parser = application.koin.get<DiscourseCookedHtmlParser>()
                val mapper = application.koin.get<DiscourseForumMapper>()
                val cache = application.koin.get<DiscourseForumCache>()
                val repository = application.koin.get<DiscourseForumRepository>()
                firstPresenter = application.koin.get()
                secondPresenter = application.koin.get()

                assertEquals(listOf(DiscoursePlatformSpec), registry.all)
                assertSame(DiscoursePlatformSpec, registry.find("linux.do"))
                assertSame(api, dataSource.api)
                assertSame(
                    application.koin.get<DiscourseSessionManager>(),
                    application.koin.get<DiscourseSessionManager>(),
                )
                assertIs<SessionOnlySecureCredentialStore>(credentialStore)
                assertSame(parser, application.koin.get<DiscourseCookedHtmlParser>())
                assertSame(mapper, application.koin.get<DiscourseForumMapper>())
                assertIs<MemoryDiscourseForumCache>(cache)
                assertSame(repository, application.koin.get<DiscourseForumRepository>())
                assertNotSame(firstPresenter, secondPresenter)

                credentialReference =
                    credentialStore.save("account-42", byteArrayOf(1, 2, 3, 4))
                cookieStorage.addCookie(
                    Url("https://linux.do/"),
                    Cookie(name = "_t", value = "self-authored-session", httpOnly = true),
                )
                // Resolving the transport also installs the cookie-storage lifecycle hook.
                application.koin.get<HttpClient>()
            } finally {
                firstPresenter?.close()
                secondPresenter?.close()
                application.close()
            }

            assertNull(credentialStore.load(requireNotNull(credentialReference)))
            assertFailsWith<IllegalStateException> {
                credentialStore.save("late-account", byteArrayOf(5, 6, 7, 8))
            }
            assertTrue(cookieStorage.snapshot().isEmpty())
            assertFailsWith<IllegalStateException> {
                cookieStorage.addCookie(
                    Url("https://linux.do/"),
                    Cookie(name = "late", value = "blocked"),
                )
            }
        }
}
