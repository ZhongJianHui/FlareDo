package dev.dimension.flare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.Bell
import compose.icons.fontawesomeicons.solid.Fire
import compose.icons.fontawesomeicons.solid.House
import compose.icons.fontawesomeicons.solid.MagnifyingGlass
import compose.icons.fontawesomeicons.solid.User
import dev.dimension.flare.compose.ui.Res
import dev.dimension.flare.compose.ui.forum_categories
import dev.dimension.flare.compose.ui.forum_latest
import dev.dimension.flare.compose.ui.forum_loading_topic
import dev.dimension.flare.compose.ui.forum_loading_topics
import dev.dimension.flare.compose.ui.forum_notifications
import dev.dimension.flare.compose.ui.forum_popular
import dev.dimension.flare.compose.ui.forum_profile
import dev.dimension.flare.compose.ui.forum_search
import dev.dimension.flare.compose.ui.forum_tags
import dev.dimension.flare.compose.ui.forum_topic
import dev.dimension.flare.compose.ui.product_name
import org.jetbrains.compose.resources.stringResource

private val NavigationWidth = 80.dp
private val ListPaneWidth = 264.dp
private val SupportingPaneWidth = 216.dp
private val PaneDividerWidth = 1.dp

internal enum class ForumLayoutClass {
    Compact,
    Medium,
    Expanded,
}

internal fun forumLayoutClassFor(width: Dp): ForumLayoutClass =
    when {
        width < 600.dp -> ForumLayoutClass.Compact
        width < 840.dp -> ForumLayoutClass.Medium
        else -> ForumLayoutClass.Expanded
    }

private data class ForumDestination(
    val label: String,
    val icon: ImageVector,
)

/**
 * Hosts the shared Android and desktop forum workspace.
 *
 * The 600 dp and 840 dp boundaries deliberately match the Stage 9 screenshot matrix: 400 dp
 * remains a single-pane touch layout, 610 dp gains a persistent rail and list-detail pair, and
 * 900 dp adds supporting forum metadata. Fixed side-pane widths prevent navigation labels,
 * loading states, or future topic counters from resizing the reading pane.
 */
@Composable
public fun ForumShell() {
    val destinations = forumDestinations()
    var selectedIndex by remember { mutableIntStateOf(0) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            when (forumLayoutClassFor(maxWidth)) {
                ForumLayoutClass.Compact -> {
                    CompactForumLayout(
                        destinations = destinations,
                        selectedIndex = selectedIndex,
                        onDestinationSelected = { selectedIndex = it },
                    )
                }

                ForumLayoutClass.Medium -> {
                    MediumForumLayout(
                        destinations = destinations,
                        selectedIndex = selectedIndex,
                        onDestinationSelected = { selectedIndex = it },
                    )
                }

                ForumLayoutClass.Expanded -> {
                    ExpandedForumLayout(
                        destinations = destinations,
                        selectedIndex = selectedIndex,
                        onDestinationSelected = { selectedIndex = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun forumDestinations(): List<ForumDestination> =
    listOf(
        ForumDestination(
            label = stringResource(Res.string.forum_latest),
            icon = FontAwesomeIcons.Solid.House,
        ),
        ForumDestination(
            label = stringResource(Res.string.forum_popular),
            icon = FontAwesomeIcons.Solid.Fire,
        ),
        ForumDestination(
            label = stringResource(Res.string.forum_search),
            icon = FontAwesomeIcons.Solid.MagnifyingGlass,
        ),
        ForumDestination(
            label = stringResource(Res.string.forum_notifications),
            icon = FontAwesomeIcons.Solid.Bell,
        ),
        ForumDestination(
            label = stringResource(Res.string.forum_profile),
            icon = FontAwesomeIcons.Solid.User,
        ),
    )

@Composable
private fun CompactForumLayout(
    destinations: List<ForumDestination>,
    selectedIndex: Int,
    onDestinationSelected: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopicListPane(
            title = destinations[selectedIndex].label,
            modifier = Modifier.weight(1f),
        )
        ForumBottomBar(
            destinations = destinations,
            selectedIndex = selectedIndex,
            onDestinationSelected = onDestinationSelected,
        )
    }
}

@Composable
private fun MediumForumLayout(
    destinations: List<ForumDestination>,
    selectedIndex: Int,
    onDestinationSelected: (Int) -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        ForumNavigationRail(
            destinations = destinations,
            selectedIndex = selectedIndex,
            onDestinationSelected = onDestinationSelected,
        )
        PaneDivider()
        TopicListPane(
            title = destinations[selectedIndex].label,
            modifier = Modifier.width(ListPaneWidth),
        )
        PaneDivider()
        TopicDetailPane(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ExpandedForumLayout(
    destinations: List<ForumDestination>,
    selectedIndex: Int,
    onDestinationSelected: (Int) -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        ForumNavigationRail(
            destinations = destinations,
            selectedIndex = selectedIndex,
            onDestinationSelected = onDestinationSelected,
        )
        PaneDivider()
        TopicListPane(
            title = destinations[selectedIndex].label,
            modifier = Modifier.width(ListPaneWidth),
        )
        PaneDivider()
        TopicDetailPane(modifier = Modifier.weight(1f))
        PaneDivider()
        SupportingPane(modifier = Modifier.width(SupportingPaneWidth))
    }
}

@Composable
private fun ForumBottomBar(
    destinations: List<ForumDestination>,
    selectedIndex: Int,
    onDestinationSelected: (Int) -> Unit,
) {
    NavigationBar(
        modifier = Modifier.fillMaxWidth().height(80.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        destinations.forEachIndexed { index, destination ->
            NavigationBarItem(
                selected = selectedIndex == index,
                onClick = { onDestinationSelected(index) },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = destination.label,
                        modifier = Modifier.size(20.dp),
                    )
                },
                label = { Text(destination.label) },
            )
        }
    }
}

@Composable
private fun ForumNavigationRail(
    destinations: List<ForumDestination>,
    selectedIndex: Int,
    onDestinationSelected: (Int) -> Unit,
) {
    NavigationRail(
        modifier = Modifier.width(NavigationWidth).fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surface,
        header = { BrandMark() },
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        destinations.forEachIndexed { index, destination ->
            NavigationRailItem(
                selected = selectedIndex == index,
                onClick = { onDestinationSelected(index) },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = destination.label,
                        modifier = Modifier.size(20.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun BrandMark() {
    Surface(
        modifier = Modifier.size(36.dp),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "F",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun TopicListPane(
    title: String,
    modifier: Modifier = Modifier,
) {
    val loadingDescription = stringResource(Res.string.forum_loading_topics)

    Column(modifier = modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
        BrandHeader(title)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = loadingDescription },
        ) {
            items(
                count = 10,
                key = { index -> index },
            ) { index ->
                TopicPlaceholder(index)
                HorizontalDivider(
                    modifier = Modifier.padding(start = 20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

@Composable
private fun BrandHeader(title: String) {
    Column(
        modifier = Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(Res.string.product_name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "linux.do  /  $title",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TopicPlaceholder(index: Int) {
    val accent =
        when (index % 3) {
            0 -> MaterialTheme.colorScheme.primary
            1 -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.tertiary
        }

    Row(modifier = Modifier.fillMaxWidth().height(88.dp)) {
        Box(
            modifier =
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(if (index == 0) accent else Color.Transparent),
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            PlaceholderLine(widthFraction = if (index % 2 == 0) 0.84f else 0.68f)
            PlaceholderLine(widthFraction = if (index % 3 == 0) 0.52f else 0.42f)
            Box(
                modifier =
                    Modifier
                        .width(48.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(accent.copy(alpha = 0.72f)),
            )
        }
    }
}

@Composable
private fun TopicDetailPane(modifier: Modifier = Modifier) {
    val loadingDescription = stringResource(Res.string.forum_loading_topic)

    Column(modifier = modifier.fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
        PaneHeader(stringResource(Res.string.forum_topic))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = loadingDescription },
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { PlaceholderLine(widthFraction = 0.78f, height = 20.dp) }
            item { PlaceholderLine(widthFraction = 0.36f, height = 8.dp) }
            item { Spacer(modifier = Modifier.height(8.dp)) }
            items(9) { index ->
                PlaceholderLine(
                    widthFraction =
                        when (index % 3) {
                            0 -> 1f
                            1 -> 0.9f
                            else -> 0.68f
                        },
                )
            }
        }
    }
}

@Composable
private fun SupportingPane(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
        PaneHeader(stringResource(Res.string.forum_categories))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(4) { index ->
                PlaceholderLine(widthFraction = if (index % 2 == 0) 0.8f else 0.62f)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PaneHeader(stringResource(Res.string.forum_tags))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(5) { index ->
                PlaceholderLine(widthFraction = if (index % 2 == 0) 0.56f else 0.72f)
            }
        }
    }
}

@Composable
private fun PaneHeader(title: String) {
    Box(
        modifier = Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PlaceholderLine(
    widthFraction: Float,
    height: Dp = 10.dp,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth(widthFraction)
                .height(height)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun PaneDivider() {
    Box(
        modifier =
            Modifier
                .width(PaneDividerWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
