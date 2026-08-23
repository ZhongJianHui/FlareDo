package dev.dimension.flare

import android.app.Application
import dev.dimension.flare.data.database.FlareDoDatabase
import dev.dimension.flare.data.database.createAndroidFlareDoDatabase
import dev.dimension.flare.data.network.discourse.auth.AndroidDiscourseRsaPkcs1Crypto
import dev.dimension.flare.data.network.discourse.auth.AndroidDiscourseWebSessionCookieBridge
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthAttemptStore
import dev.dimension.flare.data.network.discourse.auth.DiscourseCloudflareChallengeHandler
import dev.dimension.flare.data.network.discourse.auth.DiscourseManualChallengeCookieHandler
import dev.dimension.flare.data.network.discourse.auth.DiscourseManualChallengePresenter
import dev.dimension.flare.data.network.discourse.auth.DiscourseRsaPkcs1Decryptor
import dev.dimension.flare.data.network.discourse.auth.DiscourseRsaPkcs1KeyPairGenerator
import dev.dimension.flare.data.network.discourse.auth.DiscourseWebSessionCookieBridge
import dev.dimension.flare.data.network.discourse.auth.RoomDiscourseAuthAttemptStore
import dev.dimension.flare.data.network.discourse.composer.DiscourseDraftStore
import dev.dimension.flare.data.network.discourse.composer.roomDiscourseDraftStore
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumCache
import dev.dimension.flare.data.network.discourse.forum.roomDiscourseForumCache
import dev.dimension.flare.data.network.discourse.realtime.DiscourseMessageBusCursorStore
import dev.dimension.flare.data.network.discourse.realtime.roomDiscourseMessageBusCursorStore
import dev.dimension.flare.data.network.discourse.session.AndroidKeystoreCredentialStore
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionStore
import dev.dimension.flare.data.network.discourse.session.RoomDiscourseSessionStore
import dev.dimension.flare.data.network.discourse.session.SecureCredentialStore
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.onClose

/**
 * Android-owned authentication and persistence definitions layered after the shared modules.
 *
 * Keeping this as a module factory makes the production override order explicit and lets host graph
 * tests replace only Android services that cannot execute against the local mock framework.
 */
internal fun createAndroidDiscourseHostModule(application: Application): Module =
    createAndroidDiscourseHostModule(
        databaseFactory = { createAndroidFlareDoDatabase(application) },
        credentialStoreFactory = { AndroidKeystoreCredentialStore(application) },
    )

/** Testable core of the Android module; production callers use the [Application] overload. */
internal fun createAndroidDiscourseHostModule(
    databaseFactory: () -> FlareDoDatabase,
    credentialStoreFactory: () -> SecureCredentialStore,
    webCookieBridgeFactory: () -> DiscourseWebSessionCookieBridge =
        { AndroidDiscourseWebSessionCookieBridge() },
): Module =
    module {
        single { databaseFactory() } onClose { database ->
            database?.close()
        }
        single<DiscourseForumCache> {
            roomDiscourseForumCache(
                dao = get<FlareDoDatabase>().forumCacheEntryDao(),
            )
        }
        single<DiscourseDraftStore> {
            roomDiscourseDraftStore(
                dao = get<FlareDoDatabase>().composerDraftDao(),
            )
        }
        single<DiscourseMessageBusCursorStore> {
            roomDiscourseMessageBusCursorStore(
                dao = get<FlareDoDatabase>().messageBusCursorDao(),
            )
        }
        single<SecureCredentialStore> {
            credentialStoreFactory()
        }
        single { AndroidDiscourseRsaPkcs1Crypto() }
        single<DiscourseRsaPkcs1KeyPairGenerator> { get<AndroidDiscourseRsaPkcs1Crypto>() }
        single<DiscourseRsaPkcs1Decryptor> { get<AndroidDiscourseRsaPkcs1Crypto>() }
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
        single<DiscourseWebSessionCookieBridge> {
            webCookieBridgeFactory()
        }
        single<DiscourseCloudflareChallengeHandler> {
            DiscourseManualChallengeCookieHandler(
                presenter = get<DiscourseManualChallengePresenter>(),
                cookieBridge = get(),
                sessionManager = get<DiscourseSessionManager>(),
            )
        }
    }
