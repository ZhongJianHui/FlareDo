package dev.dimension.flare.data.network.discourse

import dev.dimension.flare.data.network.discourse.content.DiscourseCookedHtmlParser
import dev.dimension.flare.data.network.discourse.forum.DefaultDiscourseForumRepository
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumCache
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumMapper
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumPresenter
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumRepository
import dev.dimension.flare.data.network.discourse.forum.MemoryDiscourseForumCache
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieStorage
import dev.dimension.flare.data.network.discourse.session.DiscourseCsrfTokenStore
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
            )
        }
        single { DiscourseDataSource(api = get()) }
        single { DiscourseCookedHtmlParser() }
        single { DiscourseForumMapper(cookedHtmlParser = get()) }
        // Platform hosts replace this with roomDiscourseForumCache(...) after opening their DB.
        single<DiscourseForumCache> { MemoryDiscourseForumCache() }
        single<DiscourseForumRepository> {
            DefaultDiscourseForumRepository(
                dataSource = get(),
                mapper = get(),
                cache = get(),
            )
        }
        // A presenter has a screen lifecycle and must never be reused after close().
        factory { DiscourseForumPresenter(repository = get()) }
    }
