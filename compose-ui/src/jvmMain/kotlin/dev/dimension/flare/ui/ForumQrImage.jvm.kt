package dev.dimension.flare.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.image.BufferedImage

internal actual fun createForumQrImage(
    value: String,
    size: Int,
): ImageBitmap? =
    try {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until size) {
            for (x in 0 until size) {
                image.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xffffff)
            }
        }
        image.toComposeImageBitmap()
    } catch (_: IllegalArgumentException) {
        null
    }
