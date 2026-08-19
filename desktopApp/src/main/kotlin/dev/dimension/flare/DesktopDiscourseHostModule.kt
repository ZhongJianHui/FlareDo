package dev.dimension.flare

import dev.dimension.flare.data.database.FlareDoDatabase
import dev.dimension.flare.data.database.createJvmFlareDoDatabase
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthAttemptStore
import dev.dimension.flare.data.network.discourse.auth.DiscourseCloudflareChallengeHandler
import dev.dimension.flare.data.network.discourse.auth.DiscourseManualChallengeCookieHandler
import dev.dimension.flare.data.network.discourse.auth.DiscourseManualChallengePresenter
import dev.dimension.flare.data.network.discourse.auth.DiscourseRsaPkcs1Decryptor
import dev.dimension.flare.data.network.discourse.auth.DiscourseRsaPkcs1KeyPairGenerator
import dev.dimension.flare.data.network.discourse.auth.DiscourseWebSessionCookieBridge
import dev.dimension.flare.data.network.discourse.auth.JvmDiscourseRsaPkcs1Crypto
import dev.dimension.flare.data.network.discourse.auth.JvmDiscourseWebSessionCookieBridge
import dev.dimension.flare.data.network.discourse.auth.RoomDiscourseAuthAttemptStore
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumCache
import dev.dimension.flare.data.network.discourse.forum.roomDiscourseForumCache
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionStore
import dev.dimension.flare.data.network.discourse.session.RoomDiscourseSessionStore
import dev.dimension.flare.data.network.discourse.session.SecureCredentialStore
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.net.CookieManager
import java.net.CookiePolicy
import java.nio.file.Path

/**
 * Windows/Linux-owned persistence, crypto, and restricted browser bridge definitions.
 *
 * [browserCookieManager] is private to this application graph and is never installed as the JVM
 * process default. A future desktop WebView host may synchronize only its fixed Linux.do profile
 * into this manager without exposing cookies from another application or origin.
 */
internal fun createDesktopDiscourseHostModule(
    credentialStore: SecureCredentialStore,
    databasePath: Path,
    browserCookieManager: CookieManager =
        CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER),
): Module =
    module {
        single { createJvmFlareDoDatabase(databasePath) } onClose { database ->
            database?.close()
        }
        single<DiscourseForumCache> {
            roomDiscourseForumCache(
                dao = get<FlareDoDatabase>().forumCacheEntryDao(),
            )
        }
        single<SecureCredentialStore> { credentialStore } onClose { store ->
            (store as? AutoCloseable)?.close()
        }
        single { JvmDiscourseRsaPkcs1Crypto() }
        single<DiscourseRsaPkcs1KeyPairGenerator> { get<JvmDiscourseRsaPkcs1Crypto>() }
        single<DiscourseRsaPkcs1Decryptor> { get<JvmDiscourseRsaPkcs1Crypto>() }
        single<DiscourseAuthAttemptStore> {
            RoomDiscourseAuthAttemptStore(
                dao = get<FlareDoDatabase>().secureVaultReferenceDao(),
                credentialStore = get(),
            )
        }
        single<DiscourseSessionStore> {
            RoomDiscourseSessionStore(
                dao = get<FlareDoDatabase>().secureVaultReferenceDao(),
                credentialStore = get(),
                cookieValidator = get(),
            )
        }
        single<CookieManager> { browserCookieManager }
        single<DiscourseWebSessionCookieBridge> {
            JvmDiscourseWebSessionCookieBridge(get<CookieManager>())
        }
        single<DiscourseCloudflareChallengeHandler> {
            DiscourseManualChallengeCookieHandler(
                presenter = get<DiscourseManualChallengePresenter>(),
                cookieBridge = get(),
                sessionManager = get<DiscourseSessionManager>(),
            )
        }
    }
