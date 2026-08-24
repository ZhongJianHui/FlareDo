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

/**
 * Stage 9 geometry matrix. Every preview renders the same selected-topic fixture so width and
 * height are the only variables across the nine references.
 */
@PreviewTest
@Preview(name = "geometry-400x400-light", widthDp = 400, heightDp = 400, showBackground = true)
@Composable
public fun geometryCompact400x400LightScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.loaded())
}

@PreviewTest
@Preview(name = "geometry-400x500-light", widthDp = 400, heightDp = 500, showBackground = true)
@Composable
public fun geometryCompact400x500LightScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.loaded())
}

@PreviewTest
@Preview(name = "geometry-400x1000-light", widthDp = 400, heightDp = 1000, showBackground = true)
@Composable
public fun geometryCompact400x1000LightScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.loaded())
}

@PreviewTest
@Preview(name = "geometry-610x400-light", widthDp = 610, heightDp = 400, showBackground = true)
@Composable
public fun geometryMedium610x400LightScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.loaded())
}

@PreviewTest
@Preview(name = "geometry-610x500-light", widthDp = 610, heightDp = 500, showBackground = true)
@Composable
public fun geometryMedium610x500LightScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.loaded())
}

@PreviewTest
@Preview(name = "geometry-610x1000-light", widthDp = 610, heightDp = 1000, showBackground = true)
@Composable
public fun geometryMedium610x1000LightScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.loaded())
}

@PreviewTest
@Preview(name = "geometry-900x400-light", widthDp = 900, heightDp = 400, showBackground = true)
@Composable
public fun geometryExpanded900x400LightScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.loaded())
}

@PreviewTest
@Preview(name = "geometry-900x500-light", widthDp = 900, heightDp = 500, showBackground = true)
@Composable
public fun geometryExpanded900x500LightScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.loaded())
}

@PreviewTest
@Preview(name = "geometry-900x1000-light", widthDp = 900, heightDp = 1000, showBackground = true)
@Composable
public fun geometryExpanded900x1000LightScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.loaded())
}

/** Dark representatives cover every width-driven layout class without duplicating the full matrix. */
@PreviewTest
@Preview(name = "compact-dark", widthDp = 400, heightDp = 500, showBackground = true)
@Composable
public fun compactDarkScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.loaded(), darkTheme = true)
}

@PreviewTest
@Preview(name = "medium-dark", widthDp = 610, heightDp = 500, showBackground = true)
@Composable
public fun mediumDarkScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.loaded(), darkTheme = true)
}

@PreviewTest
@Preview(name = "expanded-dark", widthDp = 900, heightDp = 500, showBackground = true)
@Composable
public fun expandedDarkScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.loaded(), darkTheme = true)
}

/** Enlarged-font representatives catch clipping independently in compact, medium, and expanded. */
@PreviewTest
@Preview(
    name = "compact-font-150-percent",
    widthDp = 400,
    heightDp = 500,
    fontScale = 1.5f,
    showBackground = true,
)
@Composable
public fun compactFontScaleScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.loaded())
}

@PreviewTest
@Preview(
    name = "medium-font-150-percent",
    widthDp = 610,
    heightDp = 500,
    fontScale = 1.5f,
    showBackground = true,
)
@Composable
public fun mediumFontScaleScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.loaded())
}

@PreviewTest
@Preview(
    name = "expanded-font-150-percent",
    widthDp = 900,
    heightDp = 500,
    fontScale = 1.5f,
    showBackground = true,
)
@Composable
public fun expandedFontScaleScreenshot() {
    ForumScreenshot(state = ForumPreviewFixtures.loaded())
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
