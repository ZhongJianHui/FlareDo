package dev.dimension.flare

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumState
import dev.dimension.flare.ui.ForumPreviewFixtures
import dev.dimension.flare.ui.ForumWorkspace
import dev.dimension.flare.ui.theme.FlareDoTheme

/** Network-free Stage 4 and Stage 6 baselines; the exhaustive matrix belongs to Stage 9. */
@PreviewTest
@Preview(name = "compact-list-light", widthDp = 400, heightDp = 800, showBackground = true)
@Composable
public fun compactListLightScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.loaded(withSelectedTopic = false))
}

@PreviewTest
@Preview(name = "compact-detail-light", widthDp = 400, heightDp = 800, showBackground = true)
@Composable
public fun compactDetailLightScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.loaded(withSelectedTopic = true))
}

@PreviewTest
@Preview(name = "compact-cached-dark", widthDp = 400, heightDp = 800, showBackground = true)
@Composable
public fun compactCachedDarkScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.cached(), darkTheme = true)
}

@PreviewTest
@Preview(name = "medium-list-detail", widthDp = 610, heightDp = 800, showBackground = true)
@Composable
public fun mediumListDetailScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.loaded())
}

@PreviewTest
@Preview(name = "expanded-workspace", widthDp = 900, heightDp = 800, showBackground = true)
@Composable
public fun expandedWorkspaceScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.loaded())
}

@PreviewTest
@Preview(name = "compact-error", widthDp = 400, heightDp = 800, showBackground = true)
@Composable
public fun compactErrorScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.error())
}

@PreviewTest
@Preview(name = "compact-search-results", widthDp = 400, heightDp = 800, showBackground = true)
@Composable
public fun compactSearchResultsScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.search())
}

@PreviewTest
@Preview(name = "medium-notifications", widthDp = 610, heightDp = 800, showBackground = true)
@Composable
public fun mediumNotificationsScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.notifications())
}

@PreviewTest
@Preview(name = "expanded-profile", widthDp = 900, heightDp = 800, showBackground = true)
@Composable
public fun expandedProfileScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.profile())
}

@Composable
private fun ForumScreenshot(
    state: DiscourseForumState,
    darkTheme: Boolean = false,
) {
    FlareDoTheme(darkTheme = darkTheme) {
        ForumWorkspace(state = state, onAction = {})
    }
}
