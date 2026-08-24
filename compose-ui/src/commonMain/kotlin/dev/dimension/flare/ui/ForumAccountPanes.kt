package dev.dimension.flare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.ArrowsRotate
import compose.icons.fontawesomeicons.solid.Bell
import compose.icons.fontawesomeicons.solid.CheckDouble
import compose.icons.fontawesomeicons.solid.Clock
import compose.icons.fontawesomeicons.solid.Heart
import compose.icons.fontawesomeicons.solid.Lock
import compose.icons.fontawesomeicons.solid.MagnifyingGlass
import compose.icons.fontawesomeicons.solid.Medal
import compose.icons.fontawesomeicons.solid.User
import dev.dimension.flare.compose.ui.Res
import dev.dimension.flare.compose.ui.forum_activity_hidden
import dev.dimension.flare.compose.ui.forum_auth_required_body
import dev.dimension.flare.compose.ui.forum_auth_required_title
import dev.dimension.flare.compose.ui.forum_likes
import dev.dimension.flare.compose.ui.forum_loading_more_activity
import dev.dimension.flare.compose.ui.forum_loading_more_notifications
import dev.dimension.flare.compose.ui.forum_loading_more_results
import dev.dimension.flare.compose.ui.forum_mark_all_read
import dev.dimension.flare.compose.ui.forum_mark_read
import dev.dimension.flare.compose.ui.forum_notification_generic
import dev.dimension.flare.compose.ui.forum_notifications
import dev.dimension.flare.compose.ui.forum_notifications_empty_body
import dev.dimension.flare.compose.ui.forum_notifications_empty_title
import dev.dimension.flare.compose.ui.forum_open_notification
import dev.dimension.flare.compose.ui.forum_open_topic
import dev.dimension.flare.compose.ui.forum_profile
import dev.dimension.flare.compose.ui.forum_profile_activity
import dev.dimension.flare.compose.ui.forum_profile_badges
import dev.dimension.flare.compose.ui.forum_profile_days_visited
import dev.dimension.flare.compose.ui.forum_profile_likes_received
import dev.dimension.flare.compose.ui.forum_profile_no_activity
import dev.dimension.flare.compose.ui.forum_profile_posts
import dev.dimension.flare.compose.ui.forum_profile_staff
import dev.dimension.flare.compose.ui.forum_profile_topics
import dev.dimension.flare.compose.ui.forum_profile_trust_level
import dev.dimension.flare.compose.ui.forum_refresh
import dev.dimension.flare.compose.ui.forum_retry
import dev.dimension.flare.compose.ui.forum_search
import dev.dimension.flare.compose.ui.forum_search_empty_body
import dev.dimension.flare.compose.ui.forum_search_empty_title
import dev.dimension.flare.compose.ui.forum_search_hint
import dev.dimension.flare.compose.ui.forum_search_no_results_body
import dev.dimension.flare.compose.ui.forum_search_no_results_title
import dev.dimension.flare.compose.ui.forum_search_results
import dev.dimension.flare.compose.ui.forum_search_submit
import dev.dimension.flare.compose.ui.forum_unread_count
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumAction
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumActivity
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFailureKind
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumNotification
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumProfile
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumSearchHit
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumState
import org.jetbrains.compose.resources.stringResource

/** Search results use their own one-based cursor and never share feed paging state. */
@Composable
internal fun ForumSearchPane(
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val search = state.search
    val searchFailure = search.failure
    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .testTag(ForumTestTags.SEARCH_RESULTS),
    ) {
        ForumSectionPaneHeader(stringResource(Res.string.forum_search))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = search.query,
                onValueChange = { value ->
                    runCatching { DiscourseForumAction.UpdateSearchQuery(value) }
                        .getOrNull()
                        ?.let(onAction)
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(Res.string.forum_search_hint)) },
                leadingIcon = {
                    Icon(
                        FontAwesomeIcons.Solid.MagnifyingGlass,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
            Spacer(Modifier.width(8.dp))
            val submitLabel = stringResource(Res.string.forum_search_submit)
            IconButton(
                onClick = { onAction(DiscourseForumAction.SubmitSearch) },
                enabled = search.query.isNotBlank() && !search.isLoading,
            ) {
                Icon(
                    FontAwesomeIcons.Solid.MagnifyingGlass,
                    submitLabel,
                    Modifier.size(19.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when {
            search.isLoading && search.items.isEmpty() -> {
                ForumPaneLoading()
            }

            searchFailure != null && search.items.isEmpty() -> {
                ForumFailureState(
                    failure = searchFailure,
                    onRetry = { onAction(DiscourseForumAction.RetrySearch) },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            search.submittedQuery.isBlank() -> {
                ForumAccountCenteredState(
                    icon = FontAwesomeIcons.Solid.MagnifyingGlass,
                    title = stringResource(Res.string.forum_search_empty_title),
                    body = stringResource(Res.string.forum_search_empty_body),
                )
            }

            search.items.isEmpty() -> {
                ForumAccountCenteredState(
                    icon = FontAwesomeIcons.Solid.MagnifyingGlass,
                    title = stringResource(Res.string.forum_search_no_results_title),
                    body = stringResource(Res.string.forum_search_no_results_body),
                )
            }

            else -> {
                ForumSearchResults(state, onAction)
            }
        }
    }
}

@Composable
private fun ForumSearchResults(
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
) {
    val search = state.search
    val listState = rememberLazyListState()
    val loadNext by
        remember(search.items.size, search.nextPage, search.isAppending, search.appendFailure) {
            derivedStateOf {
                val lastVisible =
                    listState.layoutInfo.visibleItemsInfo
                        .lastOrNull()
                        ?.index ?: -1
                search.nextPage != null &&
                    !search.isAppending &&
                    search.appendFailure == null &&
                    lastVisible >= search.items.size - PAGING_PREFETCH_DISTANCE
            }
        }
    LaunchedEffect(loadNext, search.nextPage) {
        if (loadNext) onAction(DiscourseForumAction.LoadNextSearchPage)
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item(key = "search-result-heading") {
            Text(
                stringResource(Res.string.forum_search_results, search.submittedQuery),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 11.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        items(search.items, key = DiscourseForumSearchHit::itemKey) { hit ->
            ForumSearchHitRow(hit) {
                onAction(
                    DiscourseForumAction.OpenTopic(
                        topicId = hit.topic.topicId,
                        postNumber = hit.topic.postNumber,
                    ),
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 18.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        item(key = "search-paging") {
            ForumPagingFooter(
                isLoading = search.isAppending,
                failure = search.appendFailure,
                loadingLabel = stringResource(Res.string.forum_loading_more_results),
                onRetry = { onAction(DiscourseForumAction.RetrySearch) },
            )
        }
    }
}

@Composable
private fun ForumSearchHitRow(
    hit: DiscourseForumSearchHit,
    onClick: () -> Unit,
) {
    val openLabel = stringResource(Res.string.forum_open_topic, hit.title)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 112.dp)
                .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            hit.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (hit.excerpt.isNotBlank()) {
            Text(
                hit.excerpt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ForumAuthorAvatar(hit.author.displayName, 22.dp)
            Spacer(Modifier.width(7.dp))
            Text(
                hit.author.displayName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                FontAwesomeIcons.Solid.Heart,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                stringResource(Res.string.forum_likes, hit.likeCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Authenticated notification list; guest state remains entirely local and performs no request. */
@Composable
internal fun ForumNotificationsPane(
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val notifications = state.notifications
    val snapshot = notifications.snapshot
    val notificationFailure = notifications.failure
    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .testTag(ForumTestTags.NOTIFICATIONS),
    ) {
        ForumSectionPaneHeader(
            title = stringResource(Res.string.forum_notifications),
            supporting =
                snapshot
                    ?.unreadCount
                    ?.takeIf { it > 0 }
                    ?.let { stringResource(Res.string.forum_unread_count, it) },
            actionIcon = FontAwesomeIcons.Solid.ArrowsRotate,
            actionLabel = stringResource(Res.string.forum_refresh),
            actionEnabled = !notifications.isLoading,
            onAction = { onAction(DiscourseForumAction.RefreshNotifications) },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (snapshot?.unreadCount?.let { it > 0 } == true) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { onAction(DiscourseForumAction.MarkNotificationsRead()) },
                    enabled = !notifications.isMarkingRead,
                ) {
                    Icon(
                        FontAwesomeIcons.Solid.CheckDouble,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(stringResource(Res.string.forum_mark_all_read))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        when {
            !state.isAuthenticated || notificationFailure == DiscourseForumFailureKind.Authentication -> {
                ForumAuthenticationRequiredState()
            }

            notifications.isLoading && snapshot == null -> {
                ForumPaneLoading()
            }

            notificationFailure != null && snapshot == null -> {
                ForumFailureState(
                    failure = notificationFailure,
                    onRetry = { onAction(DiscourseForumAction.RetryNotifications) },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            snapshot == null || snapshot.items.isEmpty() -> {
                ForumAccountCenteredState(
                    icon = FontAwesomeIcons.Solid.Bell,
                    title = stringResource(Res.string.forum_notifications_empty_title),
                    body = stringResource(Res.string.forum_notifications_empty_body),
                )
            }

            else -> {
                ForumNotificationList(state, onAction)
            }
        }
    }
}

@Composable
private fun ForumNotificationList(
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
) {
    val notifications = state.notifications
    val items = checkNotNull(notifications.snapshot).items
    val listState = rememberLazyListState()
    val loadNext by
        remember(items.size, notifications.nextOffset, notifications.isAppending, notifications.appendFailure) {
            derivedStateOf {
                val lastVisible =
                    listState.layoutInfo.visibleItemsInfo
                        .lastOrNull()
                        ?.index ?: -1
                notifications.nextOffset != null &&
                    !notifications.isAppending &&
                    notifications.appendFailure == null &&
                    lastVisible >= items.size - PAGING_PREFETCH_DISTANCE
            }
        }
    LaunchedEffect(loadNext, notifications.nextOffset) {
        if (loadNext) onAction(DiscourseForumAction.LoadNextNotificationsPage)
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(items, key = DiscourseForumNotification::id) { notification ->
            ForumNotificationRow(notification, notifications.isMarkingRead) {
                if (!notification.read) {
                    onAction(DiscourseForumAction.MarkNotificationsRead(notification.id))
                }
                notification.topic?.let { topic ->
                    onAction(DiscourseForumAction.OpenTopic(topic.topicId, topic.postNumber))
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 18.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        item(key = "notification-paging") {
            ForumPagingFooter(
                isLoading = notifications.isAppending,
                failure = notifications.appendFailure ?: notifications.markFailure,
                loadingLabel = stringResource(Res.string.forum_loading_more_notifications),
                onRetry = { onAction(DiscourseForumAction.RetryNotifications) },
            )
        }
    }
}

@Composable
private fun ForumNotificationRow(
    notification: DiscourseForumNotification,
    isMarkingRead: Boolean,
    onClick: () -> Unit,
) {
    val title = notification.title ?: notification.data.topicTitle
    val resolvedTitle = title ?: stringResource(Res.string.forum_notification_generic)
    val openLabel = stringResource(Res.string.forum_open_notification, resolvedTitle)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 82.dp)
                .background(
                    if (notification.read) Color.Transparent else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
                ).clickable(
                    enabled = !isMarkingRead,
                    onClickLabel = openLabel,
                    role = Role.Button,
                    onClick = onClick,
                ).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(
                        if (notification.read) {
                            MaterialTheme.colorScheme.outlineVariant
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                    ),
        )
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                resolvedTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (notification.read) FontWeight.Normal else FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val actor = notification.actingUser?.displayName ?: notification.data.displayUsername
            if (!actor.isNullOrBlank()) {
                Text(
                    actor,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!notification.read) {
            Icon(
                FontAwesomeIcons.Solid.CheckDouble,
                contentDescription = stringResource(Res.string.forum_mark_read),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Profile and raw-row-offset activity pager share one quiet, scan-friendly surface. */
@Composable
internal fun ForumProfilePane(
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val profileState = state.profile
    val profileFailure = profileState.failure
    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .testTag(ForumTestTags.PROFILE),
    ) {
        ForumSectionPaneHeader(
            title = stringResource(Res.string.forum_profile),
            supporting = profileState.username?.let { "@$it" },
            actionIcon = FontAwesomeIcons.Solid.ArrowsRotate,
            actionLabel = stringResource(Res.string.forum_refresh),
            actionEnabled = !profileState.isLoading && !profileState.isActivityLoading,
            onAction = { onAction(DiscourseForumAction.RetryProfile) },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when {
            profileState.value == null &&
                (!state.isAuthenticated || profileFailure == DiscourseForumFailureKind.Authentication) -> {
                ForumAuthenticationRequiredState()
            }

            profileState.isLoading && profileState.value == null -> {
                ForumPaneLoading()
            }

            profileFailure != null && profileState.value == null -> {
                ForumFailureState(
                    failure = profileFailure,
                    onRetry = { onAction(DiscourseForumAction.RetryProfile) },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            profileState.value != null -> {
                ForumProfileDocument(state, onAction)
            }

            else -> {
                ForumPaneLoading()
            }
        }
    }
}

@Composable
private fun ForumProfileDocument(
    state: DiscourseForumState,
    onAction: (DiscourseForumAction) -> Unit,
) {
    val profile = checkNotNull(state.profile.value)
    val activity = state.profile.activity
    val activityFailure = state.profile.activityFailure
    val listState = rememberLazyListState()
    val loadNext by
        remember(
            activity.size,
            state.profile.nextOffset,
            state.profile.isAppendingActivity,
            state.profile.activityAppendFailure,
        ) {
            derivedStateOf {
                val lastVisible =
                    listState.layoutInfo.visibleItemsInfo
                        .lastOrNull()
                        ?.index ?: -1
                state.profile.nextOffset != null &&
                    !state.profile.isAppendingActivity &&
                    state.profile.activityAppendFailure == null &&
                    lastVisible >= activity.size - PAGING_PREFETCH_DISTANCE
            }
        }
    LaunchedEffect(loadNext, state.profile.nextOffset) {
        if (loadNext) onAction(DiscourseForumAction.LoadNextActivityPage)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item(key = "profile-header") { ForumProfileHeader(profile) }
        if (profile.bio.isNotEmpty()) {
            item(key = "profile-bio") {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                    ForumRichText(profile.bio)
                }
            }
        }
        item(key = "profile-metrics") { ForumProfileMetrics(profile) }
        if (profile.badges.isNotEmpty()) {
            item(key = "profile-badges") { ForumProfileBadges(profile) }
        }
        item(key = "profile-activity-heading") {
            Text(
                stringResource(Res.string.forum_profile_activity),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                        .semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (state.profile.isActivityLoading && activity.isEmpty()) {
            item(key = "profile-activity-loading") { ForumPaneLoading(Modifier.height(128.dp)) }
        } else if (activityFailure != null && activity.isEmpty()) {
            item(key = "profile-activity-failure") {
                ForumFailureState(
                    failure = activityFailure,
                    onRetry = { onAction(DiscourseForumAction.RetryProfile) },
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                )
            }
        } else if (activity.isEmpty()) {
            item(key = "profile-activity-empty") {
                Text(
                    stringResource(Res.string.forum_profile_no_activity),
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(activity, key = DiscourseForumActivity::itemKey) { item ->
                ForumActivityRow(item) {
                    item.topic?.let { topic ->
                        onAction(DiscourseForumAction.OpenTopic(topic.topicId, topic.postNumber))
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(start = 18.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        item(key = "activity-paging") {
            ForumPagingFooter(
                isLoading = state.profile.isAppendingActivity,
                failure = state.profile.activityAppendFailure,
                loadingLabel = stringResource(Res.string.forum_loading_more_activity),
                onRetry = { onAction(DiscourseForumAction.LoadNextActivityPage) },
            )
        }
    }
}

@Composable
private fun ForumProfileHeader(profile: DiscourseForumProfile) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ForumAuthorAvatar(profile.displayName, 54.dp)
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                profile.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "@${profile.username}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(Res.string.forum_profile_trust_level, profile.trustLevel),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (profile.staff) {
                    Text(
                        stringResource(Res.string.forum_profile_staff),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ForumProfileMetrics(profile: DiscourseForumProfile) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            ForumProfileMetric(
                profile.summary.topicCount,
                stringResource(Res.string.forum_profile_topics),
                Modifier.weight(1f),
            )
            ForumProfileMetric(
                profile.summary.postCount,
                stringResource(Res.string.forum_profile_posts),
                Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            ForumProfileMetric(
                profile.summary.likesReceived,
                stringResource(Res.string.forum_profile_likes_received),
                Modifier.weight(1f),
            )
            ForumProfileMetric(
                profile.summary.daysVisited,
                stringResource(Res.string.forum_profile_days_visited),
                Modifier.weight(1f),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ForumProfileMetric(
    value: Int,
    label: String,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.heightIn(min = 72.dp).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ForumProfileBadges(profile: DiscourseForumProfile) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            stringResource(Res.string.forum_profile_badges),
            modifier = Modifier.padding(horizontal = 18.dp).semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(9.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(profile.badges, key = { it.id }) { badge ->
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(FontAwesomeIcons.Solid.Medal, contentDescription = null, Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(badge.name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ForumActivityRow(
    activity: DiscourseForumActivity,
    onClick: () -> Unit,
) {
    val title = activity.title?.takeIf(String::isNotBlank)
    val body = title ?: activity.excerpt.takeIf(String::isNotBlank)
    val resolved = body ?: stringResource(Res.string.forum_activity_hidden)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .clickable(enabled = activity.topic != null, role = Role.Button, onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            FontAwesomeIcons.Solid.Clock,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                resolved,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            activity.actingUser?.let { user ->
                Text(
                    user.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ForumSectionPaneHeader(
    title: String,
    supporting: String? = null,
    actionIcon: ImageVector? = null,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!supporting.isNullOrBlank()) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (actionIcon != null && actionLabel != null && onAction != null) {
            IconButton(onClick = onAction, enabled = actionEnabled) {
                Icon(actionIcon, actionLabel, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ForumAuthenticationRequiredState() {
    ForumAccountCenteredState(
        icon = FontAwesomeIcons.Solid.Lock,
        title = stringResource(Res.string.forum_auth_required_title),
        body = stringResource(Res.string.forum_auth_required_body),
    )
}

@Composable
private fun ForumAccountCenteredState(
    icon: ImageVector,
    title: String,
    body: String,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
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
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ForumPaneLoading(modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun ForumPagingFooter(
    isLoading: Boolean,
    failure: DiscourseForumFailureKind?,
    loadingLabel: String,
    onRetry: () -> Unit,
) {
    when {
        isLoading -> {
            Row(
                modifier = Modifier.fillMaxWidth().height(52.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(9.dp))
                Text(
                    loadingLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        failure != null -> {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = onRetry, shape = RoundedCornerShape(6.dp)) {
                    Text(stringResource(Res.string.forum_retry))
                }
            }
        }
    }
}

private const val PAGING_PREFETCH_DISTANCE: Int = 3
