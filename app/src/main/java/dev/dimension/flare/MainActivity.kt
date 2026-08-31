package dev.dimension.flare

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.dimension.flare.ui.AndroidForumShell
import dev.dimension.flare.ui.theme.FlareDoTheme

/**
 * Single Android host for the shared Compose forum UI.
 *
 * This activity deliberately does not parse incoming intents. The launcher filter carries no data or
 * extras, keeping authentication callbacks and other externally supplied input out of the main UI host.
 */
class MainActivity : ComponentActivity() {
    private val forumViewModel: ForumPresenterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Let the Compose navigation surface draw through gesture and three-button navigation areas.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        val presenter = forumViewModel.presenter
        val composerPresenter = forumViewModel.composerPresenter
        val authenticationPresenter = forumViewModel.authenticationPresenter
        setContent {
            FlareDoTheme {
                AndroidForumShell(
                    presenter = presenter,
                    composerPresenter = composerPresenter,
                    authenticationPresenter = authenticationPresenter,
                    qrLoginService = forumViewModel.qrLoginService,
                    savedLoginStore = forumViewModel.savedLoginStore,
                    qrSharePresenter = forumViewModel.qrSharePresenter,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        forumViewModel.setForeground(true)
    }

    override fun onPostResume() {
        super.onPostResume()
        // Redirect callbacks are consumed only while this host and its challenge UI are visible.
        forumViewModel.deliverPendingAuthenticationRedirect()
    }

    override fun onStop() {
        forumViewModel.setForeground(false)
        super.onStop()
    }
}
