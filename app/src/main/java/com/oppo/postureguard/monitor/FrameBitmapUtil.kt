package com.oppo.postureguard.monitor

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

object FrameBitmapUtil {
    /**
     * Converts CameraX RGBA_8888 ImageProxy to a rotated/mirrored bitmap suitable for MediaPipe Tasks.
     * Closes [imageProxy] before returning.
     */
    fun toUprightBitmap(imageProxy: ImageProxy, isFrontCamera: Boolean): Bitmap {
        val rotation = imageProxy.imageInfo.rotationDegrees
        val width = imageProxy.width
        val height = imageProxy.height

        // For RGBA_8888 output, rowStride may include padding. Copy into a padded bitmap then crop.
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowPadding = (rowStride - pixelStride * width).coerceAtLeast(0)

        val paddedBitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        paddedBitmap.copyPixelsFromBuffer(buffer)
        imageProxy.close()

        val bitmapBuffer = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)

        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            if (isFrontCamera) {
                // Mirror horizontally to match what users expect in a front camera view.
                postScale(-1f, 1f, width.toFloat(), height.toFloat())
            }
        }
        return Bitmap.createBitmap(
            bitmapBuffer,
            0,
            0,
            bitmapBuffer.width,
            bitmapBuffer.height,
            matrix,
            true
        )
    }
}
