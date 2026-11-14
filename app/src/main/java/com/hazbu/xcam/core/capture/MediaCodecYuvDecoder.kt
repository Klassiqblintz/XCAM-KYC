package com.hazbu.xcam.core.capture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.VideoFrameMetadata
import java.nio.ByteBuffer

/**
 * Decodes video frames to YUV420 for capture extraction.
 */
class MediaCodecYuvDecoder(
    private val context: Context,
    private val path: String,
    private val logAction: (String) -> Unit
) {
    private var decoder: MediaCodec? = null
    private var extractor: android.media.MediaExtractor? = null
    private var isRunning = false
    private var videoTrack = -1

    fun start() {
        try {
            extractor = android.media.MediaExtractor().apply {
                setDataSource(path)
                for (i in 0 until trackCount) {
                    val format = getTrackFormat(i)
                    if (format.getString(MediaFormat.KEY_MIME)!!.startsWith("video/")) {
                        videoTrack = i
                        break
                    }
                }
                if (videoTrack < 0) throw IllegalStateException("No video track found")
                selectTrack(videoTrack)
            }

            val format = extractor!!.getTrackFormat(videoTrack)
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also {
                it.configure(format, null, null, 0)
                it.start()
            }
            isRunning = true
        } catch (e: Throwable) {
            logAction("[YUV-DECODER] Start failed: ${e.message}")
        }
    }

    fun stop() {
        isRunning = false
        decoder?.stop()
        decoder?.release()
        decoder = null
        extractor?.release()
        extractor = null
    }

    fun getNextFrame(): YuvFrame? {
        if (!isRunning || decoder == null || extractor == null) return null

        val inputIndex = decoder!!.dequeueInputBuffer(1000)
        if (inputIndex >= 0) {
            val buffer = decoder!!.getInputBuffer(inputIndex)
            if (buffer != null) {
                val sampleSize = extractor!!.readSampleData(buffer, 0)
                if (sampleSize > 0) {
                    decoder!!.queueInputBuffer(inputIndex, 0, sampleSize, extractor!!.sampleTime, 0)
                    extractor!!.advance()
                } else {
                    decoder!!.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            }
        }

        val outputIndex = decoder!!.dequeueOutputBuffer(infoOut, 1000)
        if (outputIndex >= 0) {
            val outBuffer = decoder!!.getOutputBuffer(outputIndex)
            val time = infoOut.timeStampUs / 1000 // ms
            if (outBuffer != null) {
                val data = ByteArray(outBuffer.remaining())
                outBuffer.get(data)
                val frame = YuvFrame(data, infoOut.width, infoOut.height, time)
                decoder!!.releaseOutputBuffer(outputIndex, false)
                return frame
            }
            decoder!!.releaseOutputBuffer(outputIndex, false)
        }
        return null
    }

    val currentTimestampMs: Long
        get() = infoOut.timeStampUs / 1000

    class YuvFrame(val data: ByteArray, val width: Int, val height: Int, val timestampMs: Long)

    private val infoOut = MediaCodec.BufferInfo()
}
