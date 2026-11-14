package com.hazbu.xcam.core.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Build
import com.hazbu.xcam.data.Constants.DEFAULT_CAPTURE_HEIGHT
import com.hazbu.xcam.data.Constants.DEFAULT_CAPTURE_WIDTH
import java.io.ByteArrayOutputStream

object XCamCapture {

    fun createJpeg(
        context: Context,
        path: String,
        width: Int,
        height: Int,
        rotation: Int,
        mirrored: Boolean,
        timestampMs: Int,
        maxSizeBytes: Int,
        logAction: (String) -> Unit,
        liveFrame: MediaCodecYuvDecoder.YuvFrame? = null
    ): ByteArray? {
        return try {
            if (liveFrame != null) {
                val bitmap = yuvToBitmap(liveFrame, rotation, mirrored)
                encodeJpeg(bitmap, maxSizeBytes)
            } else {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(path)
                val frame = retriever.getFrameAtTime(
                    (timestampMs * 1000L).toInt(),
                    MediaMetadataRetriever.OPTION_CLOSEST
                )

                if (frame == null) {
                    logAction("[CAPTURE] No frame at $timestampMs ms, using default")
                    null
                } else {
                    val rotated = rotateBitmap(frame, rotation)
                    val final = if (mirrored) mirrorBitmap(rotated) else rotated
                    encodeJpeg(final, maxSizeBytes)
                }
            }
        } catch (e: Throwable) {
            logAction("[CAPTURE] CreateJpeg failed: ${e.message}")
            null
        }
    }

    private fun yuvToBitmap(frame: MediaCodecYuvDecoder.YuvFrame, rotation: Int, mirrored: Boolean): Bitmap {
        // Simplified YUV420 to RGB conversion
        val yuv = frame.data
        val width = frame.width
        val height = frame.height
        val rgb = IntArray(width * height)

        var yIndex = 0
        val uvIndex = yuv.size - (width * height / 2)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val Y = yuv[yIndex] and 0xFF
                val U = yuv[uvIndex + ((y / 2) * (width / 2) + (x / 2)) * 2] and 0xFF
                val V = yuv[uvIndex + ((y / 2) * (width / 2) + (x / 2)) * 2 + 1] and 0xFF

                var r = (Y + (V - 128) * 1.402).toInt().coerceIn(0, 255)
                var g = (Y - (V - 128) * 0.34414 - (U - 128) * 0.71414).toInt().coerceIn(0, 255)
                var b = (Y + (U - 128) * 1.772).toInt().coerceIn(0, 255)

                rgb[y * width + x] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                yIndex++
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(rgb, 0, width, 0, 0, width, height)
        return if (rotation != 0 || mirrored) {
            var result = rotateBitmap(bitmap, rotation)
            if (mirrored) result = mirrorBitmap(result)
            result
        } else {
            bitmap
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun mirrorBitmap(bitmap: Bitmap): Bitmap {
        val matrix = Matrix()
        matrix.preScale(-1f, 1f)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun encodeJpeg(bitmap: Bitmap, maxSizeBytes: Int): ByteArray? {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val data = stream.toByteArray()
        if (data.size > maxSizeBytes) {
            stream.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
            return stream.toByteArray()
        }
        return data
    }
}
