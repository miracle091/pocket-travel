package com.pockettravel.feature.vault

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces

/** Bitmap senza pixel: nei test JVM contano solo le dimensioni. */
internal class FakeImageBitmap(override val width: Int, override val height: Int) : ImageBitmap {
    override val colorSpace: ColorSpace = ColorSpaces.Srgb
    override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888
    override val hasAlpha: Boolean = true

    override fun prepareToDraw() = Unit

    override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) = Unit
}
