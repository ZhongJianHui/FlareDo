package dev.dimension.flare

import dev.dimension.flare.data.network.discourse.auth.DiscourseCloudflareChallengeHandler
import dev.dimension.flare.data.network.discourse.auth.DiscourseLoginService
import dev.dimension.flare.data.network.discourse.auth.DiscourseManualChallengeCookieHandler
import dev.dimension.flare.data.network.discourse.auth.DiscourseWebSessionCookieBridge
import dev.dimension.flare.data.network.discourse.auth.DiscourseWebSessionLogin
import dev.dimension.flare.data.network.discourse.auth.JvmDiscourseWebSessionCookieBridge
import dev.dimension.flare.data.network.discourse.discourseAuthenticationModule
import dev.dimension.flare.data.network.discourse.discourseModule
import dev.dimension.flare.data.network.discourse.realtime.DiscourseMessageBusCursorStore
import dev.dimension.flare.data.network.discourse.realtime.RoomDiscourseMessageBusCursorStore
import dev.dimension.flare.data.network.discourse.session.SessionOnlySecureCredentialStore
import dev.dimension.flare.di.sharedModule
import org.koin.dsl.koinApplication
import java.net.CookieManager
import java.net.CookiePolicy
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

class DesktopDiscourseHostModuleTest {
    @Test
    fun productionHostGraphResolvesBothLoginPathsAndSharesItsBrowserCookieManager() {
        val directory = Files.createTempDirectory("flaredo-desktop-graph-")
        val browserCookieManager =
            CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        val application =
            koinApplication {
                allowOverride(true)
                modules(
                    sharedModule,
                    discourseModule,
                    discourseAuthenticationModule,
                    createDesktopDiscourseHostModule(
                        credentialStore = SessionOnlySecureCredentialStore(),
                        databasePath = directory.resolve("graph.db"),
                        browserCookieManager = browserCookieManager,
                    ),
                )
            }

        try {
            assertIs<DiscourseLoginService>(application.koin.get<DiscourseLoginService>())
            assertIs<DiscourseWebSessionLogin>(application.koin.get<DiscourseWebSessionLogin>())
            assertIs<JvmDiscourseWebSessionCookieBridge>(
                application.koin.get<DiscourseWebSessionCookieBridge>(),
            )
            assertIs<DiscourseManualChallengeCookieHandler>(
                application.koin.get<DiscourseCloudflareChallengeHandler>(),
            )
            assertIs<RoomDiscourseMessageBusCursorStore>(
                application.koin.get<DiscourseMessageBusCursorStore>(),
            )
            assertSame(browserCookieManager, application.koin.get<CookieManager>())
        } finally {
            application.close()
            directory.deleteRecursivelyForTest()
        }
    }
}

private fun Path.deleteRecursivelyForTest() {
    if (!Files.exists(this)) return
    Files.walk(this).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
    }
}
