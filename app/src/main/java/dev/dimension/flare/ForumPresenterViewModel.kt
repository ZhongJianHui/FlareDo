package dev.dimension.flare

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerPresenter
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumPresenter

/** Retains the shared presenter across Activity configuration changes and closes it exactly once. */
internal class ForumPresenterViewModel(
    application: Application,
) : AndroidViewModel(application) {
    val presenter: DiscourseForumPresenter = (application as App).createForumPresenter()
    val composerPresenter: DiscourseComposerPresenter = (application as App).createComposerPresenter()

    /** Keeps long polling bound to the visible Activity lifecycle across configuration changes. */
    fun setForeground(isForeground: Boolean) {
        presenter.setForeground(isForeground)
    }

    override fun onCleared() {
        composerPresenter.close()
        presenter.close()
        super.onCleared()
    }
}
