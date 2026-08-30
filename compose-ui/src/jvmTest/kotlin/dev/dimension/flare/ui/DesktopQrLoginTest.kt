package dev.dimension.flare.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class DesktopQrLoginTest {
    @Test
    fun decodesQrImageAndRejectsArbitraryBytes() {
        val expected = "flaredo://qr-login?fixture=1"
        val matrix = QRCodeWriter().encode(expected, BarcodeFormat.QR_CODE, 320, 320)
        val bytes =
            ByteArrayOutputStream().use { output ->
                ImageIO.write(MatrixToImageWriter.toBufferedImage(matrix), "png", output)
                output.toByteArray()
            }

        assertEquals(expected, decodeDesktopQr(bytes))
        assertNull(decodeDesktopQr("not-an-image".encodeToByteArray()))
    }
}
