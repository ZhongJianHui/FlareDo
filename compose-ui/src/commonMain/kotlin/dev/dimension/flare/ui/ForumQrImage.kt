package dev.dimension.flare.ui

import androidx.compose.ui.graphics.ImageBitmap

/** Creates a local QR bitmap without network, file, or browser access. */
internal expect fun createForumQrImage(
    value: String,
    size: Int,
): ImageBitmap?
