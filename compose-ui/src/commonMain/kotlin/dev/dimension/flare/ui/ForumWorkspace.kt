package dev.dimension.flare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.ArrowLeft
import compose.icons.fontawesomeicons.solid.ArrowsRotate
import compose.icons.fontawesomeicons.solid.Comment
import compose.icons.fontawesomeicons.solid.Eye
import compose.icons.fontawesomeicons.solid.Fire
import compose.icons.fontawesomeicons.solid.Folder
import compose.icons.fontawesomeicons.solid.House
import compose.icons.fontawesomeicons.solid.Tag
import dev.dimension.flare.compose.ui.Res
import dev.dimension.flare.compose.ui.forum_back
import dev.dimension.flare.compose.ui.forum_browse
import dev.dimension.flare.compose.ui.forum_categories
import dev.dimension.flare.compose.ui.forum_latest
import dev.dimension.flare.compose.ui.forum_load_more
import dev.dimension.flare.compose.ui.forum_open_topic
import dev.dimension.flare.compose.ui.forum_original_post
import dev.dimension.flare.compose.ui.forum_popular
import dev.dimension.flare.compose.ui.forum_post_number
import dev.dimension.flare.compose.ui.forum_refresh
import dev.dimension.flare.compose.ui.forum_replies
import dev.dimension.flare.compose.ui.forum_selected_topic
import dev.dimension.flare.compose.ui.forum_tag_count
import dev.dimension.flare.compose.ui.forum_tags
import dev.dimension.flare.compose.ui.forum_topic
import dev.dimension.flare.compose.ui.forum_topic_count
import dev.dimension.flare.compose.ui.forum_topic_posts
import dev.dimension.flare.compose.ui.forum_unread
import dev.dimension.flare.compose.ui.forum_views
import dev.dimension.flare.compose.ui.product_name
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumAction
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumCategoryOption
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumContentSource
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFeed
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumState
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumTagOption
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumTopic
import dev.dimension.flare.ui.model.UiArticle
import dev.dimension.flare.ui.model.UiTimelineV2
import org.jetbrains.compose.resources.stringResource

internal val ForumNavigationWidth = 76.dp
internal val ForumMediumListPaneWidth = 260.dp
internal val ForumExpandedListPaneWidth = 320.dp
internal val ForumSupportingPaneWidth = 244.dp
internal val ForumPaneDividerWidth = 1.dp
internal val ForumActiveSpineWidth = 3.dp

/** Anonymous browsing roots available before account features are introduced. */
internal enum class ForumRootDestination {
    Latest,
    Hot,
    Categories,
    Tags,
}

private data class ForumRootDestinationItem(
    val destination: ForumRootDestination,
    val label: String,
    val icon: ImageVector,
)

/**
 * State-driven forum workspace used by desktop and network-free Android screenshot tests.
 *
 * Android production places these same panes inside Navigation 3 scenes. This manual composition
 * remains the desktop implementation because Navigation 3 UI does not publish a JVM artifact.
 */
@Composable
public fun ForumWorkspace(
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .testTag(ForumTestTags.WORKSPACE),
        ) {
            val layoutClass = forumLayoutClassFor(maxWidth)
            ForumNavigationFrame(
                layoutClass = layoutClass,
                state = state,
                onAction = onAction,
            ) {
                ForumManualPanes(
                    layoutClass = layoutClass,
                    state = state,
                    onAction = onAction,
                )
            }
        }
    }
}

@Composable
private fun ForumManualPanes(
    layoutClass: ForumLayoutClass,
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
) {
    when (layoutClass) {
        ForumLayoutClass.Compact -> {
            if (state.selectedTopicId == null) {
                ForumTopicListPane(state, onAction, Modifier.fillMaxSize())
            } else {
                ForumTopicDetailPane(
                    state = state,
                    showBackButton = true,
                    onBack = { onAction(DiscourseForumAction.CloseTopic) },
                    onRetry = { onAction(DiscourseForumAction.RetryTopic) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        ForumLayoutClass.Medium -> {
            Row(modifier = Modifier.fillMaxSize()) {
                ForumTopicListPane(
                    state,
                    onAction,
                    Modifier.width(ForumMediumListPaneWidth),
                )
                ForumPaneDivider()
                ForumTopicDetailPane(
                    state = state,
                    showBackButton = false,
                    onBack = { onAction(DiscourseForumAction.CloseTopic) },
                    onRetry = { onAction(DiscourseForumAction.RetryTopic) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ForumLayoutClass.Expanded -> {
            Row(modifier = Modifier.fillMaxSize()) {
                ForumTopicListPane(
                    state,
                    onAction,
                    Modifier.width(ForumExpandedListPaneWidth),
                )
                ForumPaneDivider()
                ForumTopicDetailPane(
                    state = state,
                    showBackButton = false,
                    onBack = { onAction(DiscourseForumAction.CloseTopic) },
                    onRetry = { onAction(DiscourseForumAction.RetryTopic) },
                    modifier = Modifier.weight(1f),
                )
                ForumPaneDivider()
                ForumSupportingPane(
                    state,
                    onAction,
                    Modifier.width(ForumSupportingPaneWidth),
                )
            }
        }
    }
}

/** Navigation chrome stays outside NavDisplay so it is never a back-stack destination. */
@Composable
internal fun ForumNavigationFrame(
    layoutClass: ForumLayoutClass,
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
    content: @Composable () -> Unit,
) {
    val destinations = forumDestinations()
    val selectedDestination = state.selection.destination
    val selectDestination: (ForumRootDestination) -> Unit = { destination ->
        destination.navigationAction(state)?.let(onAction)
    }

    when (layoutClass) {
        ForumLayoutClass.Compact -> {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) { content() }
                ForumBottomBar(destinations, selectedDestination, selectDestination)
            }
        }

        ForumLayoutClass.Medium,
        ForumLayoutClass.Expanded,
        -> {
            Row(modifier = Modifier.fillMaxSize()) {
                ForumNavigationRail(destinations, selectedDestination, selectDestination)
                ForumPaneDivider()
                Box(modifier = Modifier.weight(1f)) { content() }
            }
        }
    }
}

@Composable
private fun forumDestinations(): List<ForumRootDestinationItem> =
    listOf(
        ForumRootDestinationItem(
            ForumRootDestination.Latest,
            stringResource(Res.string.forum_latest),
            FontAwesomeIcons.Solid.House,
        ),
        ForumRootDestinationItem(
            ForumRootDestination.Hot,
            stringResource(Res.string.forum_popular),
            FontAwesomeIcons.Solid.Fire,
        ),
        ForumRootDestinationItem(
            ForumRootDestination.Categories,
            stringResource(Res.string.forum_categories),
            FontAwesomeIcons.Solid.Folder,
        ),
        ForumRootDestinationItem(
            ForumRootDestination.Tags,
            stringResource(Res.string.forum_tags),
            FontAwesomeIcons.Solid.Tag,
        ),
    )

@Composable
private fun ForumBottomBar(
    destinations: List<ForumRootDestinationItem>,
    selectedDestination: ForumRootDestination,
    onDestinationSelected: (ForumRootDestination) -> Unit,
) {
    NavigationBar(
        modifier = Modifier.fillMaxWidth().height(72.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        destinations.forEach { item ->
            NavigationBarItem(
                selected = selectedDestination == item.destination,
                onClick = { onDestinationSelected(item.destination) },
                icon = {
                    Icon(item.icon, item.label, Modifier.size(20.dp))
                },
                label = {
                    Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
            )
        }
    }
}

@Composable
private fun ForumNavigationRail(
    destinations: List<ForumRootDestinationItem>,
    selectedDestination: ForumRootDestination,
    onDestinationSelected: (ForumRootDestination) -> Unit,
) {
    NavigationRail(
        modifier = Modifier.width(ForumNavigationWidth).fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surface,
        header = { ForumBrandMark() },
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        destinations.forEach { item ->
            Box(modifier = Modifier.fillMaxWidth()) {
                if (selectedDestination == item.destination) {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .width(ForumActiveSpineWidth)
                            .height(40.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                NavigationRailItem(
                    modifier = Modifier.align(Alignment.Center),
                    selected = selectedDestination == item.destination,
                    onClick = { onDestinationSelected(item.destination) },
                    icon = { Icon(item.icon, item.label, Modifier.size(20.dp)) },
                    label = {
                        Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
        }
    }
}

@Composable
private fun ForumBrandMark() {
    Surface(
        modifier = Modifier.size(38.dp),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("F", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/** Flat, paging-aware feed pane shared by manual and Navigation 3 layouts. */
@Composable
internal fun ForumTopicListPane(
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .testTag(ForumTestTags.TOPIC_LIST),
    ) {
        val feedFailure = state.feedFailure
        ForumFeedHeader(state) { onAction(DiscourseForumAction.Refresh) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (state.feedSource == DiscourseForumContentSource.StaleCache) {
            ForumCachedContentNotice()
        }
        ForumTaxonomyStrip(state, onAction)
        when {
            state.isFeedLoading && state.topics.isEmpty() -> {
                ForumTopicLoadingState()
            }

            feedFailure != null && state.topics.isEmpty() -> {
                ForumFailureState(
                    failure = feedFailure,
                    onRetry = { onAction(DiscourseForumAction.Refresh) },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            state.topics.isEmpty() -> {
                ForumEmptyState(
                    onRefresh = { onAction(DiscourseForumAction.Refresh) },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                ForumTopicList(state, onAction)
            }
        }
    }
}

@Composable
private fun ForumFeedHeader(
    state: DiscourseForumState,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(72.dp).padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.product_name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                "linux.do  /  ${state.selection.displayLabel()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val refreshLabel = stringResource(Res.string.forum_refresh)
        IconButton(onClick = onRefresh, enabled = !state.isFeedLoading) {
            if (state.isFeedLoading && state.topics.isNotEmpty()) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(FontAwesomeIcons.Solid.ArrowsRotate, refreshLabel, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ForumTaxonomyStrip(
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
) {
    when (state.selection.destination) {
        ForumRootDestination.Categories -> {
            if (state.categories.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.categories, key = DiscourseForumCategoryOption::id) { category ->
                        FilterChip(
                            selected =
                                (state.selection as? DiscourseForumFeed.Category)?.id == category.id,
                            onClick = {
                                onAction(DiscourseForumAction.SelectFeed(category.asForumFeed()))
                            },
                            label = {
                                Text(category.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            leadingIcon = { ForumCategorySwatch(category.colorHex) },
                            shape = RoundedCornerShape(6.dp),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        ForumRootDestination.Tags -> {
            if (state.tags.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.tags, key = DiscourseForumTagOption::id) { tag ->
                        FilterChip(
                            selected = (state.selection as? DiscourseForumFeed.Tag)?.slug == tag.slug,
                            onClick = { onAction(DiscourseForumAction.SelectFeed(tag.asForumFeed())) },
                            label = { Text("#${tag.name}", maxLines = 1) },
                            shape = RoundedCornerShape(6.dp),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        ForumRootDestination.Latest,
        ForumRootDestination.Hot,
        -> {}
    }
}

@Composable
private fun ForumTopicList(
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
) {
    val listState = rememberLazyListState()
    val appendFailure = state.appendFailure
    val shouldLoadMore by
        remember(
            state.topics.size,
            state.nextPage,
            state.isAppending,
            appendFailure,
        ) {
            derivedStateOf {
                val lastVisible =
                    listState.layoutInfo.visibleItemsInfo
                        .lastOrNull()
                        ?.index ?: -1
                shouldAutomaticallyLoadNextPage(
                    lastVisibleIndex = lastVisible,
                    topicCount = state.topics.size,
                    nextPage = state.nextPage,
                    isAppending = state.isAppending,
                    failure = appendFailure,
                )
            }
        }
    LaunchedEffect(shouldLoadMore, state.nextPage) {
        if (shouldLoadMore) onAction(DiscourseForumAction.LoadNextPage)
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(state.topics, key = UiTimelineV2.Topic::itemKey) { topic ->
            val topicId = topic.discourse?.ref?.topicId
            ForumTopicRow(
                topic = topic,
                isSelected = topicId != null && topicId == state.selectedTopicId,
                onClick = { topicId?.let { onAction(DiscourseForumAction.OpenTopic(it)) } },
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 19.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        if (state.isAppending) {
            item(key = "append-progress") {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag(ForumTestTags.LOAD_MORE_PROGRESS),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(Res.string.forum_load_more),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (appendFailure != null) {
            item(key = "append-failure") {
                ForumAppendFailureState(
                    failure = appendFailure,
                    onRetry = { onAction(DiscourseForumAction.LoadNextPage) },
                )
            }
        }
    }
}

/** Prevents an append failure at the list tail from turning into a request retry loop. */
internal fun shouldAutomaticallyLoadNextPage(
    lastVisibleIndex: Int,
    topicCount: Int,
    nextPage: Int?,
    isAppending: Boolean,
    failure: dev.dimension.flare.data.network.discourse.forum.DiscourseForumFailureKind?,
): Boolean =
    nextPage != null &&
        !isAppending &&
        failure == null &&
        topicCount > 0 &&
        lastVisibleIndex >= topicCount - 3

@Composable
private fun ForumTopicRow(
    topic: UiTimelineV2.Topic,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val topicId = topic.discourse?.ref?.topicId
    val openDescription = stringResource(Res.string.forum_open_topic, topic.title)
    val selectedDescription = stringResource(Res.string.forum_selected_topic)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 116.dp)
                .clickable(
                    enabled = topicId != null,
                    onClickLabel = openDescription,
                    role = Role.Button,
                    onClick = onClick,
                ).semantics {
                    selected = isSelected
                    role = Role.Button
                    if (isSelected) contentDescription = "$selectedDescription. $openDescription"
                }.then(
                    if (topicId == null) Modifier else Modifier.testTag(ForumTestTags.topic(topicId)),
                ),
    ) {
        Box(
            Modifier
                .width(ForumActiveSpineWidth)
                .fillMaxHeight()
                .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    topic.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (topic.unread) {
                    Spacer(Modifier.width(8.dp))
                    val unread = stringResource(Res.string.forum_unread)
                    Box(
                        Modifier
                            .padding(top = 5.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondary)
                            .semantics { contentDescription = unread },
                    )
                }
            }
            Text(
                topic.excerpt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ForumAuthorAvatar(topic.author.displayName, 24.dp)
                Spacer(Modifier.width(7.dp))
                Text(
                    topic.author.displayName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ForumTopicMetric(
                    FontAwesomeIcons.Solid.Comment,
                    topic.replyCount,
                    stringResource(Res.string.forum_replies, topic.replyCount),
                )
                Spacer(Modifier.width(10.dp))
                ForumTopicMetric(
                    FontAwesomeIcons.Solid.Eye,
                    topic.viewCount,
                    stringResource(Res.string.forum_views, topic.viewCount),
                )
            }
        }
    }
}

@Composable
private fun ForumTopicMetric(
    icon: ImageVector,
    count: Int,
    description: String,
) {
    Row(
        modifier = Modifier.semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(
            count.compactCount(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Topic pane renders only sanitized UiArticleBlock values; raw cooked HTML cannot reach it. */
@Composable
internal fun ForumTopicDetailPane(
    state: DiscourseForumState,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.background)
                .testTag(ForumTestTags.TOPIC_DETAIL),
    ) {
        val topicFailure = state.topicFailure
        val selectedTopic = state.selectedTopic
        ForumTopicPaneHeader(showBackButton, onBack)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (state.topicSource == DiscourseForumContentSource.StaleCache) {
            ForumCachedContentNotice()
        }
        when {
            state.selectedTopicId == null -> {
                ForumNoTopicState(Modifier.fillMaxSize())
            }

            state.isTopicLoading && selectedTopic == null -> {
                ForumTopicDetailLoadingState()
            }

            topicFailure != null && selectedTopic == null -> {
                ForumFailureState(topicFailure, onRetry, Modifier.fillMaxSize())
            }

            selectedTopic != null -> {
                ForumTopicDocument(selectedTopic)
            }

            else -> {
                ForumTopicDetailLoadingState()
            }
        }
    }
}

@Composable
private fun ForumTopicPaneHeader(
    showBackButton: Boolean,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBackButton) {
            IconButton(onClick = onBack) {
                Icon(
                    FontAwesomeIcons.Solid.ArrowLeft,
                    stringResource(Res.string.forum_back),
                    Modifier.size(18.dp),
                )
            }
        } else {
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.forum_topic),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(Res.string.forum_topic_posts),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ForumTopicDocument(topic: DiscourseForumTopic) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
    ) {
        item(key = "topic-heading-${topic.topicId}") {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 780.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    topic.title,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (topic.tags.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(topic.tags, key = { it }) { ForumTagLabel(it) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        items(topic.articles, key = UiArticle::itemKey) { ForumArticle(it) }
    }
}

@Composable
private fun ForumArticle(article: UiArticle) {
    val postNumber = article.discourse?.postNumber
    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 780.dp).padding(bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ForumAuthorAvatar(article.author.displayName, 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    article.author.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (postNumber == null || postNumber == 1) {
                        stringResource(Res.string.forum_original_post)
                    } else {
                        stringResource(Res.string.forum_post_number, postNumber)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ForumRichText(article.blocks)
        HorizontalDivider(
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** Third-pane taxonomy browser for expanded windows and Navigation 3 extra-pane metadata. */
@Composable
internal fun ForumSupportingPane(
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .testTag(ForumTestTags.SUPPORTING_PANE),
    ) {
        ForumPaneHeader(stringResource(Res.string.forum_browse))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when {
            state.isTaxonomyLoading && state.categories.isEmpty() && state.tags.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }

            state.taxonomyFailure != null && state.categories.isEmpty() && state.tags.isEmpty() -> {
                ForumTaxonomyFailure { onAction(DiscourseForumAction.RetryTaxonomy) }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    item(key = "categories-header") {
                        ForumTaxonomySectionHeader(stringResource(Res.string.forum_categories))
                    }
                    items(state.categories, key = DiscourseForumCategoryOption::id) { category ->
                        ForumTaxonomyRow(
                            label = category.name,
                            supporting = stringResource(Res.string.forum_topic_count, category.topicCount),
                            selected =
                                (state.selection as? DiscourseForumFeed.Category)?.id == category.id,
                            swatchHex = category.colorHex,
                            onClick = {
                                onAction(DiscourseForumAction.SelectFeed(category.asForumFeed()))
                            },
                        )
                    }
                    item(key = "tags-header") {
                        ForumTaxonomySectionHeader(stringResource(Res.string.forum_tags))
                    }
                    items(state.tags, key = DiscourseForumTagOption::id) { tag ->
                        ForumTaxonomyRow(
                            label = "#${tag.name}",
                            supporting = stringResource(Res.string.forum_tag_count, tag.count),
                            selected = (state.selection as? DiscourseForumFeed.Tag)?.slug == tag.slug,
                            onClick = {
                                onAction(DiscourseForumAction.SelectFeed(tag.asForumFeed()))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ForumPaneHeader(title: String) {
    Box(
        modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ForumTaxonomySectionHeader(title: String) {
    Text(
        title,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ForumTaxonomyRow(
    label: String,
    supporting: String,
    selected: Boolean,
    onClick: () -> Unit,
    swatchHex: String? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { this.selected = selected },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(ForumActiveSpineWidth)
                .fillMaxHeight()
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        Row(
            modifier = Modifier.weight(1f).padding(horizontal = 13.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (swatchHex != null) {
                ForumCategorySwatch(swatchHex)
                Spacer(Modifier.width(9.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    supporting,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun ForumCategorySwatch(colorHex: String?) {
    Box(
        Modifier
            .size(10.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(colorHex.toForumCategoryColor() ?: MaterialTheme.colorScheme.tertiary),
    )
}

@Composable
internal fun ForumAuthorAvatar(
    displayName: String,
    size: androidx.compose.ui.unit.Dp,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            displayName.firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ForumTagLabel(tag: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            "#$tag",
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

@Composable
private fun DiscourseForumFeed.displayLabel(): String =
    when (this) {
        DiscourseForumFeed.Latest -> stringResource(Res.string.forum_latest)
        DiscourseForumFeed.Hot -> stringResource(Res.string.forum_popular)
        is DiscourseForumFeed.Category -> name
        is DiscourseForumFeed.Tag -> "#$name"
    }

internal val DiscourseForumFeed.destination: ForumRootDestination
    get() =
        when (this) {
            DiscourseForumFeed.Latest -> ForumRootDestination.Latest
            DiscourseForumFeed.Hot -> ForumRootDestination.Hot
            is DiscourseForumFeed.Category -> ForumRootDestination.Categories
            is DiscourseForumFeed.Tag -> ForumRootDestination.Tags
        }

/**
 * Resolves a root-navigation click without silently trapping a failed taxonomy request.
 *
 * Categories and tags select their first available item because the feed model deliberately has no
 * synthetic "all categories" route. When discovery failed before producing any items, the same
 * familiar navigation control becomes the retry affordance on compact and medium windows, where
 * the expanded supporting pane is not present. A valid empty response remains inert so an empty
 * taxonomy cannot create an accidental retry loop.
 */
internal fun ForumRootDestination.navigationAction(state: DiscourseForumState): DiscourseForumAction? {
    val feed = feedOrNull(state)
    if (feed != null) {
        return if (feed.stableKey == state.selection.stableKey) {
            null
        } else {
            DiscourseForumAction.SelectFeed(feed)
        }
    }
    val isMissingTaxonomy =
        when (this) {
            ForumRootDestination.Categories -> state.categories.isEmpty()

            ForumRootDestination.Tags -> state.tags.isEmpty()

            ForumRootDestination.Latest,
            ForumRootDestination.Hot,
            -> false
        }
    return if (
        isMissingTaxonomy &&
        state.taxonomyFailure != null &&
        !state.isTaxonomyLoading
    ) {
        DiscourseForumAction.RetryTaxonomy
    } else {
        null
    }
}

private fun ForumRootDestination.feedOrNull(state: DiscourseForumState): DiscourseForumFeed? =
    when (this) {
        ForumRootDestination.Latest -> {
            DiscourseForumFeed.Latest
        }

        ForumRootDestination.Hot -> {
            DiscourseForumFeed.Hot
        }

        ForumRootDestination.Categories -> {
            (state.selection as? DiscourseForumFeed.Category)
                ?: state.categories.firstOrNull()?.asForumFeed()
        }

        ForumRootDestination.Tags -> {
            (state.selection as? DiscourseForumFeed.Tag) ?: state.tags.firstOrNull()?.asForumFeed()
        }
    }

internal fun DiscourseForumCategoryOption.asForumFeed(): DiscourseForumFeed.Category =
    DiscourseForumFeed.Category(id, slug, parentSlug, name)

internal fun DiscourseForumTagOption.asForumFeed(): DiscourseForumFeed.Tag = DiscourseForumFeed.Tag(name, slug)

private fun String?.toForumCategoryColor(): Color? {
    val normalized = this?.trim()?.removePrefix("#") ?: return null
    if (normalized.length != 6) return null
    val rgb = normalized.toLongOrNull(radix = 16) ?: return null
    return Color(0xFF000000L or rgb)
}

private fun Int.compactCount(): String =
    when {
        this >= 1_000_000 -> "${this / 100_000 / 10.0}m"
        this >= 1_000 -> "${this / 100 / 10.0}k"
        else -> toString()
    }

@Composable
internal fun ForumPaneDivider() {
    Box(
        Modifier
            .width(ForumPaneDividerWidth)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
