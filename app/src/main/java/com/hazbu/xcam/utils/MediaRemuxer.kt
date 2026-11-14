package com.hazbu.xcam.utils

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.nio.ByteBuffer

object MediaRemuxer {
    fun loopVideo(context: android.content.Context, sourcePath: String, destPath: String): Boolean {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(sourcePath)

            val trackCount = extractor.trackCount
            if (trackCount == 0) return false

            // Find the best video track
            var videoTrack = -1
            var audioTrack = -1
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && videoTrack == -1) {
                    videoTrack = i
                } else if (mime.startsWith("audio/") && audioTrack == -1) {
                    audioTrack = i
                }
            }

            if (videoTrack == -1) return false

            // Create muxer
            val muxer: MediaMuxer
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                muxer = MediaMuxer(
                    destPath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
            } else {
                @Suppress("DEPRECATION")
                muxer = MediaMuxer(destPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP)
            }

            val videoOutTrack = muxer.addTrack(extractor.getTrackFormat(videoTrack))
            var audioOutTrack = -1
            if (audioTrack >= 0) {
                audioOutTrack = muxer.addTrack(extractor.getTrackFormat(audioTrack))
            }

            muxer.start()

            // Extract and write (single loop)
            val buffer = java.nio.ByteBuffer.allocate(1024 * 64)
            val bufferInfo = MediaCodec.BufferInfo()
            extractor.selectTrack(videoTrack)

            var isRead = false
            while (!isRead) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    isRead = true
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_NEXT_SYNC)
                    continue
                }
                buffer.limit(sampleSize)

                val ct = extractor.sampleTime
                val flags = extractor.sampleFlags

                // Set buffer info from extractor state
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.flags = flags
                bufferInfo.presentationTimeUs = ct

                if ((flags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    // Seek to start of this sync frame and copy the full sample
                    extractor.seekTo(ct, MediaExtractor.SEEK_TO_NEXT_SYNC)
                    while (!isRead) {
                        buffer.clear()
                        val s = extractor.readSampleData(buffer, 0)
                        if (s < 0) {
                            isRead = true
                            break
                        }
                        buffer.limit(s)
                        bufferInfo.size = s
                        bufferInfo.presentationTimeUs = extractor.sampleTime
                        bufferInfo.flags = extractor.sampleFlags
                        muxer.writeSampleData(videoOutTrack, buffer, bufferInfo)
                        extractor.advance()
                    }
                } else {
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    bufferInfo.flags = flags
                    muxer.writeSampleData(videoOutTrack, buffer, bufferInfo)
                    extractor.advance()
                }
            }

            // Copy audio if present
            if (audioTrack >= 0) {
                extractor.selectTrack(audioTrack)
                var aRead = false
                while (!aRead) {
                    buffer.clear()
                    val s = extractor.readSampleData(buffer, 0)
                    if (s < 0) {
                        aRead = true
                        break
                    }
                    buffer.limit(s)
                    bufferInfo.offset = 0
                    bufferInfo.size = s
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    bufferInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(audioOutTrack, buffer, bufferInfo)
                    extractor.advance()
                }
            }

            muxer.stop()
            muxer.release()
            true
        } catch (_: Throwable) {
            false
        }
    }
}
