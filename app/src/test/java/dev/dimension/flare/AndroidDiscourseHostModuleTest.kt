package dev.dimension.flare

import dev.dimension.flare.data.network.discourse.auth.AndroidDiscourseWebSessionCookieBridge
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthAttemptStore
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthenticationPresenter
import dev.dimension.flare.data.network.discourse.auth.DiscourseCloudflareChallengeHandler
import dev.dimension.flare.data.network.discourse.auth.DiscourseLoginService
import dev.dimension.flare.data.network.discourse.auth.DiscourseManualChallengeCookieHandler
import dev.dimension.flare.data.network.discourse.auth.DiscourseWebSessionCookieBridge
import dev.dimension.flare.data.network.discourse.auth.MemoryDiscourseAuthAttemptStore
import dev.dimension.flare.data.network.discourse.discourseAuthenticationModule
import dev.dimension.flare.data.network.discourse.discourseModule
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumCache
import dev.dimension.flare.data.network.discourse.forum.MemoryDiscourseForumCache
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieSnapshot
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionStore
import dev.dimension.flare.data.network.discourse.session.PersistedDiscourseSession
import dev.dimension.flare.data.network.discourse.session.SecureCredentialRef
import dev.dimension.flare.data.network.discourse.session.SessionOnlySecureCredentialStore
import dev.dimension.flare.di.sharedModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidDiscourseHostModuleTest {
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun productionHostBindingsResolveBothLoginPathsAndManualChallengeHandler() {
        val application =
            koinApplication {
                allowOverride(true)
                modules(
                    sharedModule,
                    discourseModule,
                    discourseAuthenticationModule,
                    createAndroidDiscourseHostModule(
                        databaseFactory = { error("The local JVM must not open Android Room") },
                        credentialStoreFactory = { SessionOnlySecureCredentialStore() },
                    ),
                    localAndroidSystemOverrides,
                )
            }

        try {
            assertIs<DiscourseLoginService>(application.koin.get<DiscourseLoginService>())
            assertIs<DiscourseAuthenticationPresenter>(
                application.koin.get<DiscourseAuthenticationPresenter>(),
            ).close()
            assertIs<AndroidDiscourseWebSessionCookieBridge>(
                application.koin.get<DiscourseWebSessionCookieBridge>(),
            )
            assertIs<DiscourseManualChallengeCookieHandler>(
                application.koin.get<DiscourseCloudflareChallengeHandler>(),
            )
        } finally {
            application.close()
        }
    }
}

/** Replaces only services that require a real Android Context or file-system backed Room runtime. */
private val localAndroidSystemOverrides =
    module {
        single<DiscourseForumCache> { MemoryDiscourseForumCache() }
        single<DiscourseAuthAttemptStore> { MemoryDiscourseAuthAttemptStore() }
        single<DiscourseSessionStore> { EmptyDiscourseSessionStore }
    }

private object EmptyDiscourseSessionStore : DiscourseSessionStore {
    override suspend fun replace(
        accountId: String,
        username: String?,
        cookies: List<DiscourseCookieSnapshot>,
    ): SecureCredentialRef = SecureCredentialRef("android-host-test-session")

    override suspend fun restore(): PersistedDiscourseSession? = null

    override suspend fun clear(expectedCredentialRef: SecureCredentialRef?) = Unit
}
