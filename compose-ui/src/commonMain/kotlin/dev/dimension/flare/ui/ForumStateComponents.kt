package dev.dimension.flare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.CircleExclamation
import compose.icons.fontawesomeicons.solid.ClockRotateLeft
import compose.icons.fontawesomeicons.solid.Comment
import compose.icons.fontawesomeicons.solid.RotateRight
import compose.icons.fontawesomeicons.solid.TriangleExclamation
import dev.dimension.flare.compose.ui.Res
import dev.dimension.flare.compose.ui.forum_cached_notice
import dev.dimension.flare.compose.ui.forum_empty_body
import dev.dimension.flare.compose.ui.forum_empty_title
import dev.dimension.flare.compose.ui.forum_error_title
import dev.dimension.flare.compose.ui.forum_failure_authentication
import dev.dimension.flare.compose.ui.forum_failure_challenge
import dev.dimension.flare.compose.ui.forum_failure_http
import dev.dimension.flare.compose.ui.forum_failure_invalid_response
import dev.dimension.flare.compose.ui.forum_failure_network
import dev.dimension.flare.compose.ui.forum_failure_permission
import dev.dimension.flare.compose.ui.forum_failure_rate_limited
import dev.dimension.flare.compose.ui.forum_failure_server
import dev.dimension.flare.compose.ui.forum_load_more_failed
import dev.dimension.flare.compose.ui.forum_loading_topic
import dev.dimension.flare.compose.ui.forum_loading_topics
import dev.dimension.flare.compose.ui.forum_no_topic_body
import dev.dimension.flare.compose.ui.forum_no_topic_title
import dev.dimension.flare.compose.ui.forum_refresh
import dev.dimension.flare.compose.ui.forum_retry
import dev.dimension.flare.compose.ui.forum_taxonomy_unavailable
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFailureKind
import org.jetbrains.compose.resources.stringResource

/** Cache state remains readable while clearly separated from authoritative network content. */
@Composable
internal fun ForumCachedContentNotice() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .padding(horizontal = 14.dp, vertical = 9.dp)
                .testTag(ForumTestTags.CACHED_NOTICE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            FontAwesomeIcons.Solid.ClockRotateLeft,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Spacer(Modifier.width(9.dp))
        Text(
            stringResource(Res.string.forum_cached_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
internal fun ForumTopicLoadingState() {
    val description = stringResource(Res.string.forum_loading_topics)
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .clearAndSetSemantics { contentDescription = description },
    ) {
        items(count = 7, key = { it }) { index ->
            ForumTopicSkeleton(index)
            HorizontalDivider(
                modifier = Modifier.padding(start = 19.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun ForumTopicSkeleton(index: Int) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(116.dp)
                .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ForumSkeletonLine(if (index % 2 == 0) 0.84f else 0.7f, 13.dp)
        ForumSkeletonLine(if (index % 3 == 0) 0.92f else 0.78f, 10.dp)
        ForumSkeletonLine(if (index % 2 == 0) 0.58f else 0.46f, 9.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            ForumSkeletonLine(0.32f, 8.dp)
        }
    }
}

@Composable
internal fun ForumTopicDetailLoadingState() {
    val description = stringResource(Res.string.forum_loading_topic)
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp)
                .clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        ForumSkeletonLine(0.82f, 24.dp)
        ForumSkeletonLine(0.36f, 10.dp)
        Spacer(Modifier.height(10.dp))
        repeat(10) { index ->
            ForumSkeletonLine(if (index % 3 == 2) 0.68f else 1f, 11.dp)
        }
    }
}

@Composable
private fun ForumSkeletonLine(
    widthFraction: Float,
    height: Dp,
) {
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
internal fun ForumEmptyState(
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ForumCenteredState(
        icon = FontAwesomeIcons.Solid.Comment,
        title = stringResource(Res.string.forum_empty_title),
        body = stringResource(Res.string.forum_empty_body),
        modifier = modifier.testTag(ForumTestTags.FEED_EMPTY),
        actionLabel = stringResource(Res.string.forum_refresh),
        onAction = onRefresh,
    )
}

@Composable
internal fun ForumNoTopicState(modifier: Modifier = Modifier) {
    ForumCenteredState(
        icon = FontAwesomeIcons.Solid.Comment,
        title = stringResource(Res.string.forum_no_topic_title),
        body = stringResource(Res.string.forum_no_topic_body),
        modifier = modifier,
    )
}

@Composable
internal fun ForumFailureState(
    failure: DiscourseForumFailureKind,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ForumCenteredState(
        icon = FontAwesomeIcons.Solid.TriangleExclamation,
        title = stringResource(Res.string.forum_error_title),
        body = failure.localizedDescription(),
        modifier = modifier.testTag(ForumTestTags.FEED_ERROR),
        actionLabel = stringResource(Res.string.forum_retry),
        onAction = onRetry,
    )
}

@Composable
private fun ForumCenteredState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(2.dp))
                Button(onClick = onAction, shape = RoundedCornerShape(6.dp)) {
                    Icon(
                        FontAwesomeIcons.Solid.RotateRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
internal fun ForumTaxonomyFailure(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            FontAwesomeIcons.Solid.CircleExclamation,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )
        Text(
            stringResource(Res.string.forum_taxonomy_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry, shape = RoundedCornerShape(6.dp)) {
            Icon(
                FontAwesomeIcons.Solid.RotateRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.forum_retry))
        }
    }
}

/** Append failures require a deliberate retry so a list-tail observer cannot loop requests. */
@Composable
internal fun ForumAppendFailureState(
    failure: DiscourseForumFailureKind,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            FontAwesomeIcons.Solid.CircleExclamation,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.forum_load_more_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                failure.localizedDescription(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onRetry, shape = RoundedCornerShape(6.dp)) {
            Text(stringResource(Res.string.forum_retry))
        }
    }
}

@Composable
private fun DiscourseForumFailureKind.localizedDescription(): String =
    stringResource(
        when (this) {
            DiscourseForumFailureKind.Network -> Res.string.forum_failure_network
            DiscourseForumFailureKind.Authentication -> Res.string.forum_failure_authentication
            DiscourseForumFailureKind.Permission -> Res.string.forum_failure_permission
            DiscourseForumFailureKind.RateLimited -> Res.string.forum_failure_rate_limited
            DiscourseForumFailureKind.ChallengeRequired -> Res.string.forum_failure_challenge
            DiscourseForumFailureKind.Server -> Res.string.forum_failure_server
            DiscourseForumFailureKind.InvalidResponse -> Res.string.forum_failure_invalid_response
            DiscourseForumFailureKind.Http -> Res.string.forum_failure_http
        },
    )
