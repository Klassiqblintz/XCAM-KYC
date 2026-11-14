package com.hazbu.xcam.core.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Decodes audio from video source for audio injection.
 */
@UnstableApi
class MediaCodecAudioDecoder(
    private val context: Context,
    private val path: String,
    private val logAction: (String) -> Unit
) {
    private var decoder: MediaCodec? = null
    private var extractor: android.media.MediaExtractor? = null
    private var isRunning = false
    private var audioTrack = -1

    fun start() {
        try {
            extractor = android.media.MediaExtractor().apply {
                setDataSource(path)
                for (i in 0 until trackCount) {
                    val format = getTrackFormat(i)
                    if (format.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/")) {
                        audioTrack = i
                        break
                    }
                }
                if (audioTrack < 0) throw IllegalStateException("No audio track found")
                selectTrack(audioTrack)
            }

            val format = extractor!!.getTrackFormat(audioTrack)
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also {
                it.configure(format, null, null, 0)
                it.start()
            }
            isRunning = true
        } catch (e: Throwable) {
            logAction("[AUDIO-DECODER] Start failed: ${e.message}")
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

    fun getNextAudioFrame(): ByteArray? {
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
            if (outBuffer != null) {
                val data = ByteArray(outBuffer.remaining())
                outBuffer.get(data)
                decoder!!.releaseOutputBuffer(outputIndex, false)
                return data
            }
            decoder!!.releaseOutputBuffer(outputIndex, false)
        }
        return null
    }

    private val infoOut = MediaCodec.BufferInfo()
}
