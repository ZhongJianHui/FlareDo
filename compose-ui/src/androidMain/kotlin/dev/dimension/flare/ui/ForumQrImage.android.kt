package dev.dimension.flare.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

internal actual fun createForumQrImage(
    value: String,
    size: Int,
): ImageBitmap? =
    try {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) 0xff000000.toInt() else 0xffffffff.toInt()
            }
        }
        Bitmap
            .createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
            .asImageBitmap()
    } catch (_: IllegalArgumentException) {
        null
    }
