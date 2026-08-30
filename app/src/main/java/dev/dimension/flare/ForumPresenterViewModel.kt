package dev.dimension.flare

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.dimension.flare.data.network.discourse.auth.DiscourseAuthenticationPresenter
import dev.dimension.flare.data.network.discourse.auth.DiscourseQrLoginService
import dev.dimension.flare.data.network.discourse.auth.DiscourseSavedLoginStore
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerPresenter
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumPresenter

/** Retains the shared presenter across Activity configuration changes and closes it exactly once. */
internal class ForumPresenterViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as App
    val presenter: DiscourseForumPresenter = app.createForumPresenter()
    val composerPresenter: DiscourseComposerPresenter = app.createComposerPresenter()
    val authenticationPresenter: DiscourseAuthenticationPresenter =
        app.createAuthenticationPresenter()
    val qrLoginService: DiscourseQrLoginService = app.qrLoginService()
    val savedLoginStore: DiscourseSavedLoginStore = app.savedLoginStore()

    /** Keeps long polling bound to the visible Activity lifecycle across configuration changes. */
    fun setForeground(isForeground: Boolean) {
        presenter.setForeground(isForeground)
    }

    /**
     * Called only after MainActivity is resumed, ensuring challenge state is rendered by the visible
     * host rather than by the short-lived exported redirect Activity.
     */
    fun deliverPendingAuthenticationRedirect() {
        app.deliverPendingAuthenticationRedirect(authenticationPresenter::completeRedirect)
    }

    override fun onCleared() {
        authenticationPresenter.close()
        composerPresenter.close()
        presenter.close()
        super.onCleared()
    }
}
