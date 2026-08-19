package dev.dimension.flare

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumPresenter

/** Retains the shared presenter across Activity configuration changes and closes it exactly once. */
internal class ForumPresenterViewModel(
    application: Application,
) : AndroidViewModel(application) {
    val presenter: DiscourseForumPresenter = (application as App).createForumPresenter()

    override fun onCleared() {
        presenter.close()
        super.onCleared()
    }
}
