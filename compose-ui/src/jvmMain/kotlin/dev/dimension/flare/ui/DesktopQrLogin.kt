package dev.dimension.flare.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.dimension.flare.data.network.discourse.auth.DiscourseQrLoginException
import dev.dimension.flare.data.network.discourse.auth.DiscourseQrLoginFailure
import dev.dimension.flare.data.network.discourse.auth.DiscourseQrLoginService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** Desktop matches fluxdo's gallery-only fallback and never requests a camera capability. */
@Composable
internal fun rememberDesktopQrLoginCapability(
    service: DiscourseQrLoginService?,
    picker: ForumAttachmentPicker,
): ForumQrLoginCapability {
    if (service == null) return ForumQrLoginCapability()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<DiscourseQrLoginFailure?>(null) }

    return ForumQrLoginCapability(
        available = true,
        busy = busy,
        failure = failure,
        launch = {
            if (!busy) {
                busy = true
                failure = null
                picker.launch { result ->
                    when (result) {
                        is ForumAttachmentPickResult.Selected -> {
                            scope.launch {
                                try {
                                    val rawValue =
                                        withContext(Dispatchers.Default) {
                                            decodeDesktopQr(result.attachment.bytes)
                                        }
                                    if (rawValue == null) {
                                        failure = DiscourseQrLoginFailure.InvalidPayload
                                    } else {
                                        service.login(rawValue)
                                    }
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (error: DiscourseQrLoginException) {
                                    failure = error.failure
                                } catch (_: Throwable) {
                                    failure = DiscourseQrLoginFailure.ExchangeFailed
                                } finally {
                                    result.attachment.bytes.fill(0)
                                    busy = false
                                }
                            }
                        }

                        ForumAttachmentPickResult.Cancelled -> {
                            busy = false
                        }

                        ForumAttachmentPickResult.ReadFailed,
                        ForumAttachmentPickResult.TooLarge,
                        -> {
                            busy = false
                            failure = DiscourseQrLoginFailure.ScannerUnavailable
                        }
                    }
                }
            }
        },
    )
}

internal fun decodeDesktopQr(bytes: ByteArray): String? {
    val image = ByteArrayInputStream(bytes).use(ImageIO::read) ?: return null
    val bitmap = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(image)))
    return try {
        MultiFormatReader()
            .decode(
                bitmap,
                mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)),
            ).text
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    } catch (_: Exception) {
        null
    }
}
