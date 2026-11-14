package com.hazbu.xcam.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.FileOutputStream

object MediaConverter {
    fun extractFrameFromVideo(context: Context, path: String, timestampMs: Long): ByteArray? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            val frame = retriever.getFrameAtTime(
                (timestampMs * 1000).toInt(),
                MediaMetadataRetriever.OPTION_CLOSEST
            )
            if (frame != null) {
                val bmp = Bitmap.createBitmap(
                    frame.width, frame.height, Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bmp)
                canvas.drawBitmap(frame, 0f, 0f, null)
                frame.recycle()

                val buffer = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, buffer)
                bmp.recycle()
                buffer.toByteArray()
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    fun copyToInternal(context: Context, sourcePath: String, name: String): String? {
        return try {
            val dest = File(context.filesDir, name)
            File(sourcePath).inputStream().use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            dest.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    private class ByteArrayOutputStream : java.io.ByteArrayOutputStream() {
        fun toByteArray(): ByteArray {
            return this.buf
        }
    }
}
