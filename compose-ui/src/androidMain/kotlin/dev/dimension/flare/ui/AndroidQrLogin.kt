package dev.dimension.flare.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.dimension.flare.data.network.discourse.auth.DiscourseQrLoginException
import dev.dimension.flare.data.network.discourse.auth.DiscourseQrLoginFailure
import dev.dimension.flare.data.network.discourse.auth.DiscourseQrLoginService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** System-owned QR scanner keeps camera permissions and lifecycle outside FlareDo. */
@Composable
internal fun rememberAndroidQrLoginCapability(service: DiscourseQrLoginService?): ForumQrLoginCapability {
    if (service == null) return ForumQrLoginCapability()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scanner =
        remember(context) {
            GmsBarcodeScanning.getClient(
                context,
                GmsBarcodeScannerOptions
                    .Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .enableAutoZoom()
                    .build(),
            )
        }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<DiscourseQrLoginFailure?>(null) }

    fun finishWith(rawValue: String?) {
        if (rawValue.isNullOrBlank()) {
            busy = false
            failure = DiscourseQrLoginFailure.InvalidPayload
            return
        }
        scope.launch {
            try {
                service.login(rawValue)
                failure = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: DiscourseQrLoginException) {
                failure = error.failure
            } catch (_: Throwable) {
                failure = DiscourseQrLoginFailure.ExchangeFailed
            } finally {
                busy = false
            }
        }
    }

    return ForumQrLoginCapability(
        available = true,
        busy = busy,
        failure = failure,
        launch = {
            if (!busy) {
                busy = true
                failure = null
                scanner
                    .startScan()
                    .addOnSuccessListener { barcode -> finishWith(barcode.rawValue) }
                    .addOnCanceledListener { busy = false }
                    .addOnFailureListener {
                        busy = false
                        failure = DiscourseQrLoginFailure.ScannerUnavailable
                    }
            }
        },
    )
}
