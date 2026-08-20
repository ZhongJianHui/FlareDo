package dev.dimension.flare.data.network.discourse

import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthAttemptStore
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthRedirectProcessor
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthTokenGenerator
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthorizationCoordinator
import dev.dimension.flare.data.network.discourse.auth.DiscourseCloudflareChallengeHandler
import dev.dimension.flare.data.network.discourse.auth.DiscourseLoginService
import dev.dimension.flare.data.network.discourse.auth.DiscourseManualChallengeCoordinator
import dev.dimension.flare.data.network.discourse.auth.DiscourseManualChallengePresenter
import dev.dimension.flare.data.network.discourse.auth.DiscourseOtpSessionExchangeTransport
import dev.dimension.flare.data.network.discourse.auth.DiscourseWebSessionLogin
import dev.dimension.flare.data.network.discourse.auth.MemoryDiscourseAuthAttemptStore
import dev.dimension.flare.data.network.discourse.auth.createPlatformDiscourseAuthTokenGenerator
import dev.dimension.flare.data.network.discourse.composer.DefaultDiscourseComposerRepository
import dev.dimension.flare.data.network.discourse.composer.DefaultDiscoursePostActionRepository
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerPresenter
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerRepository
import dev.dimension.flare.data.network.discourse.composer.DiscourseDraftStore
import dev.dimension.flare.data.network.discourse.composer.DiscoursePostActionRepository
import dev.dimension.flare.data.network.discourse.composer.MemoryDiscourseDraftStore
import dev.dimension.flare.data.network.discourse.content.DiscourseCookedHtmlParser
import dev.dimension.flare.data.network.discourse.forum.DefaultDiscourseForumAccountRepository
import dev.dimension.flare.data.network.discourse.forum.DefaultDiscourseForumRepository
import dev.dimension.flare.data.network.discourse.forum.DefaultDiscourseForumSearchRepository
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumAccountMapper
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumAccountRepository
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumCache
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumMapper
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumPresenter
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumRepository
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumSearchMapper
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumSearchRepository
import dev.dimension.flare.data.network.discourse.forum.MemoryDiscourseForumCache
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieStorage
import dev.dimension.flare.data.network.discourse.session.DiscourseCsrfTokenStore
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionLifecycle
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.SecureCredentialStore
import dev.dimension.flare.data.network.discourse.session.SessionOnlySecureCredentialStore
import dev.dimension.flare.model.PlatformSpec
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.onClose

/**
 * Linux.do transport and session definitions shared by every host.
 *
 * Stage 5 replaces [SecureCredentialStore] with each platform's vault implementation. The current
 * fallback is deliberately process-only and therefore cannot persist secrets as plaintext.
 */
public val discourseModule: Module =
    module {
        single<PlatformSpec> { DiscoursePlatformSpec }
        single { DiscourseCookieStorage() } onClose { storage ->
            storage?.close()
        }
        single { DiscourseCsrfTokenStore() }
        single {
            DiscourseSessionManager(
                cookieStorage = get(),
                csrfTokenStore = get(),
            )
        }
        single<SecureCredentialStore> { SessionOnlySecureCredentialStore() } onClose { store ->
            (store as? SessionOnlySecureCredentialStore)?.close()
        }
        single { createDiscourseHttpClient(cookieStorage = get()) } onClose { client ->
            client?.close()
        }
        single { createDiscourseWireTransport(client = get()) }
        single<DiscourseApi> {
            DefaultDiscourseApi(
                wire = get(),
                sessionManager = get(),
                client = get(),
            )
        }
        single { DiscourseDataSource(api = get()) }
        // Platform hosts override this with Room so unfinished text survives process restarts.
        single<DiscourseDraftStore> { MemoryDiscourseDraftStore() }
        single<DiscourseComposerRepository> {
            DefaultDiscourseComposerRepository(
                dataSource = get(),
                draftStore = get(),
                sessionManager = get(),
            )
        }
        single<DiscoursePostActionRepository> {
            DefaultDiscoursePostActionRepository(
                dataSource = get(),
                sessionManager = get(),
            )
        }
        single { DiscourseCookedHtmlParser() }
        single { DiscourseForumMapper(cookedHtmlParser = get()) }
        single { DiscourseForumSearchMapper(cookedHtmlParser = get()) }
        single<DiscourseForumSearchRepository> {
            DefaultDiscourseForumSearchRepository(
                dataSource = get(),
                mapper = get(),
                sessionManager = get(),
            )
        }
        single { DiscourseForumAccountMapper(cookedHtmlParser = get()) }
        single<DiscourseForumAccountRepository> {
            DefaultDiscourseForumAccountRepository(
                dataSource = get(),
                mapper = get(),
                sessionManager = get(),
            )
        }
        // Platform hosts replace this with roomDiscourseForumCache(...) after opening their DB.
        single<DiscourseForumCache> { MemoryDiscourseForumCache() }
        single<DiscourseForumRepository> {
            DefaultDiscourseForumRepository(
                dataSource = get(),
                mapper = get(),
                cache = get(),
                sessionManager = get(),
            )
        }
        // A presenter has a screen lifecycle and must never be reused after close().
        factory {
            DiscourseForumPresenter(
                repository = get(),
                searchRepository = get(),
                accountRepository = get(),
                sessionManager = get(),
            )
        }
        // Composer actors own bounded channels and generation-bound child jobs, so each screen
        // receives a fresh lifecycle instance just like the forum presenter above.
        factory {
            DiscourseComposerPresenter(
                repository = get(),
                draftStore = get(),
                postActionRepository = get(),
                sessionManager = get(),
            )
        }
    }

/**
 * Login state-machine definitions layered over [discourseModule].
 *
 * Platform hosts must bind a persistent [SecureCredentialStore], RSA generator/decryptor, and
 * `DiscourseSessionStore` before resolving [DiscourseLoginService]. Android/Desktop additionally
 * replace the memory attempt store with `RoomDiscourseAuthAttemptStore`. Keeping these definitions
 * separate lets anonymous forum tests load the transport graph without initializing a native vault.
 */
public val discourseAuthenticationModule: Module =
    module {
        single<DiscourseAuthAttemptStore> { MemoryDiscourseAuthAttemptStore() }
        single<DiscourseAuthTokenGenerator> { createPlatformDiscourseAuthTokenGenerator() }
        single { DiscourseManualChallengeCoordinator() }
        single<DiscourseManualChallengePresenter> { get<DiscourseManualChallengeCoordinator>() }
        single<DiscourseCloudflareChallengeHandler> {
            DiscourseCloudflareChallengeHandler { false }
        }
        single {
            DiscourseAuthorizationCoordinator(
                keyPairGenerator = get(),
                tokenGenerator = get(),
                credentialStore = get(),
                attemptStore = get(),
            )
        }
        single {
            DiscourseAuthRedirectProcessor(
                attemptStore = get(),
                credentialStore = get(),
                decryptor = get(),
                nowEpochMillis = {
                    kotlin.time.Clock.System
                        .now()
                        .toEpochMilliseconds()
                },
            )
        }
        single {
            DiscourseOtpSessionExchangeTransport(
                client = get(),
                sessionManager = get(),
                challengeHandler = get(),
            )
        }
        single {
            DiscourseSessionLifecycle(
                sessionManager = get(),
                sessionStore = get(),
            )
        }
        single {
            DiscourseLoginService(
                authorizationCoordinator = get(),
                redirectProcessor = get(),
                exchangeTransport = get(),
                sessionLifecycle = get(),
                sessionManager = get(),
                cookieBridge = get(),
                api = get(),
            )
        }
        single {
            DiscourseWebSessionLogin(
                cookieBridge = get(),
                sessionManager = get(),
                sessionLifecycle = get(),
                api = get(),
            )
        }
    }
