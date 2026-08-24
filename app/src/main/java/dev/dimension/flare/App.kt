package dev.dimension.flare

import android.app.Application
import dev.dimension.flare.auth.DiscourseAuthRedirectDeliveryResult
import dev.dimension.flare.auth.DiscourseAuthRedirectInbox
import dev.dimension.flare.auth.DiscourseAuthRedirectSink
import dev.dimension.flare.auth.DiscourseAuthRedirectSinkOwner
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthenticationPresenter
import dev.dimension.flare.data.network.discourse.auth.DiscourseLoginService
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerPresenter
import dev.dimension.flare.data.network.discourse.discourseAuthenticationModule
import dev.dimension.flare.data.network.discourse.discourseModule
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumPresenter
import dev.dimension.flare.di.sharedModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication

/**
 * Android process entry point for FlareDo.
 *
 * Keep initialization that genuinely requires an Android [Application] context here. Portable forum
 * services and their dependency graph belong to the shared modules so Android, desktop, and Apple hosts
 * observe the same behavior.
 */
class App :
    Application(),
    DiscourseAuthRedirectSinkOwner {
    private lateinit var dependencies: KoinApplication
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val discourseAuthRedirectInbox = DiscourseAuthRedirectInbox()

    internal val koin: Koin
        get() = dependencies.koin

    /** The exported Activity may only enqueue into bounded process memory; it never performs exchange. */
    override val discourseAuthRedirectSink: DiscourseAuthRedirectSink = discourseAuthRedirectInbox

    override fun onCreate() {
        super.onCreate()
        dependencies =
            koinApplication {
                allowOverride(true)
                modules(
                    sharedModule,
                    discourseModule,
                    discourseAuthenticationModule,
                    createAndroidDiscourseHostModule(this@App),
                )
            }

        // Anonymous requests may start immediately; a successful restore advances the generation
        // and cancels them before publishing authenticated cookies.
        applicationScope.launch {
            koin.get<DiscourseLoginService>().restoreSession()
        }
    }

    /** A presenter has an Activity lifecycle and is never retained after its `close()` call. */
    internal fun createForumPresenter(): DiscourseForumPresenter = koin.get()

    /** Composer has the same retained Activity lifecycle as the read-only forum presenter. */
    internal fun createComposerPresenter(): DiscourseComposerPresenter = koin.get()

    /** Authentication UI work is retained across configuration changes but never process-global. */
    internal fun createAuthenticationPresenter(): DiscourseAuthenticationPresenter = koin.get()

    /** Moves one callback from process memory into the visible Activity's retained presenter. */
    internal fun deliverPendingAuthenticationRedirect(consumer: (String) -> Boolean): DiscourseAuthRedirectDeliveryResult =
        discourseAuthRedirectInbox.deliverPending(consumer)

    override fun onTerminate() {
        applicationScope.cancel()
        if (::dependencies.isInitialized) dependencies.close()
        super.onTerminate()
    }
}
