package dev.dimension.flare.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.Qrcode
import compose.icons.fontawesomeicons.solid.TriangleExclamation
import dev.dimension.flare.compose.ui.Res
import dev.dimension.flare.compose.ui.forum_auth_browser_cancel
import dev.dimension.flare.compose.ui.forum_auth_qr_share_action
import dev.dimension.flare.compose.ui.forum_auth_qr_share_confirm_title
import dev.dimension.flare.compose.ui.forum_auth_qr_share_done
import dev.dimension.flare.compose.ui.forum_auth_qr_share_expired
import dev.dimension.flare.compose.ui.forum_auth_qr_share_failed
import dev.dimension.flare.compose.ui.forum_auth_qr_share_generate
import dev.dimension.flare.compose.ui.forum_auth_qr_share_regenerate
import dev.dimension.flare.compose.ui.forum_auth_qr_share_title
import dev.dimension.flare.compose.ui.forum_auth_qr_share_warning
import dev.dimension.flare.data.network.discourse.auth.DiscourseQrShareAction
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

@Composable
internal fun ForumQrShareControls(modifier: Modifier = Modifier) {
    val authentication = LocalForumAuthentication.current
    val state = authentication.qrShareState
    var confirming by remember { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { confirming = true },
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth().testTag(ForumTestTags.AUTH_QR_SHARE),
        ) {
            if (state.isBusy) {
                CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
            } else {
                Icon(FontAwesomeIcons.Solid.Qrcode, contentDescription = null, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.forum_auth_qr_share_action))
        }
        state.failure?.let {
            Text(
                stringResource(Res.string.forum_auth_qr_share_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            icon = {
                Icon(
                    FontAwesomeIcons.Solid.TriangleExclamation,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            },
            title = { Text(stringResource(Res.string.forum_auth_qr_share_confirm_title)) },
            text = { Text(stringResource(Res.string.forum_auth_qr_share_warning)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirming = false
                        authentication.onQrShareAction(DiscourseQrShareAction.Generate)
                    },
                    modifier = Modifier.testTag(ForumTestTags.AUTH_QR_SHARE_CONFIRM),
                ) {
                    Text(stringResource(Res.string.forum_auth_qr_share_generate))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(Res.string.forum_auth_browser_cancel))
                }
            },
        )
    }

    state.share?.let { share ->
        ForumQrShareDialog(
            share = share,
            isBusy = state.isBusy,
            onRegenerate = { authentication.onQrShareAction(DiscourseQrShareAction.Generate) },
            onClose = { authentication.onQrShareAction(DiscourseQrShareAction.Revoke) },
        )
    }
}

@Composable
private fun ForumQrShareDialog(
    share: dev.dimension.flare.data.network.discourse.auth.DiscourseQrShare,
    isBusy: Boolean,
    onRegenerate: () -> Unit,
    onClose: () -> Unit,
) {
    var nowEpochMillis by remember(share.id) {
        mutableLongStateOf(Clock.System.now().toEpochMilliseconds())
    }
    LaunchedEffect(share.id) {
        while (true) {
            delay(1_000L)
            nowEpochMillis = Clock.System.now().toEpochMilliseconds()
        }
    }
    val remainingSeconds = (share.expiresAtEpochMillis - nowEpochMillis).coerceAtLeast(0L) / 1_000L
    val image = remember(share.encodedValue) { createForumQrImage(share.encodedValue, 512) }

    AlertDialog(
        onDismissRequest = onClose,
        icon = {
            Icon(
                FontAwesomeIcons.Solid.Qrcode,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        },
        title = { Text(stringResource(Res.string.forum_auth_qr_share_title)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(Res.string.forum_auth_qr_share_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = stringResource(Res.string.forum_auth_qr_share_title),
                        modifier =
                            Modifier
                                .requiredSize(168.dp)
                                .background(Color.White, RoundedCornerShape(6.dp))
                                .padding(12.dp)
                                .testTag(ForumTestTags.AUTH_QR_SHARE_IMAGE),
                    )
                }
                if (share.username.isNotEmpty()) {
                    Text("@${share.username}", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    if (remainingSeconds > 0) {
                        remainingSeconds.toCountdown()
                    } else {
                        stringResource(Res.string.forum_auth_qr_share_expired)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        confirmButton = {
            Button(onClick = onRegenerate, enabled = !isBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isBusy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(Res.string.forum_auth_qr_share_regenerate))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onClose,
                enabled = !isBusy,
                modifier = Modifier.testTag(ForumTestTags.AUTH_QR_SHARE_DONE),
            ) {
                Text(stringResource(Res.string.forum_auth_qr_share_done))
            }
        },
    )
}

private fun Long.toCountdown(): String = "${(this / 60).toString().padStart(2, '0')}:${(this % 60).toString().padStart(2, '0')}"
