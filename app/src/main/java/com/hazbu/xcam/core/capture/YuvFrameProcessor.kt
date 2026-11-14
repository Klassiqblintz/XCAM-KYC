package com.hazbu.xcam.core.capture

import android.graphics.Bitmap

/**
 * Processes YUV frames with rotation and mirroring transforms.
 */
class YuvFrameProcessor {

    fun process(
        frame: MediaCodecYuvDecoder.YuvFrame,
        rotation: Int,
        mirrored: Boolean
    ): Bitmap? {
        return try {
            val yuv = frame.data
            val width = frame.width
            val height = frame.height
            val rgb = IntArray(width * height)

            var yIndex = 0
            val uvIndex = yuv.size - (width * height / 2)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val Y = yuv[yIndex].toUInt() and 0xFFu
                    val U = yuv[uvIndex + ((y / 2) * (width / 2) + (x / 2)) * 2].toUInt() and 0xFFu
                    val V = yuv[uvIndex + ((y / 2) * (width / 2) + (x / 2)) * 2 + 1].toUInt() and 0xFFu

                    var r = (Y.toInt() + (V.toInt() - 128) * 1.402).toInt().coerceIn(0, 255)
                    var g = (Y.toInt() - (V.toInt() - 128) * 0.34414 - (U.toInt() - 128) * 0.71414).toInt().coerceIn(0, 255)
                    var b = (Y.toInt() + (U.toInt() - 128) * 1.772).toInt().coerceIn(0, 255)

                    rgb[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    yIndex++
                }
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(rgb, 0, width, 0, 0, width, height)

            var result = bitmap
            if (rotation == 90 || rotation == 180 || rotation == 270) {
                result = rotateBitmap(result, rotation)
            }
            if (mirrored) {
                result = mirrorBitmap(result)
            }

            if (result != bitmap) {
                bitmap.recycle()
            }
            result
        } catch (_: Throwable) {
            null
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = android.graphics.Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun mirrorBitmap(bitmap: Bitmap): Bitmap {
        val matrix = android.graphics.Matrix()
        matrix.preScale(-1f, 1f)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
