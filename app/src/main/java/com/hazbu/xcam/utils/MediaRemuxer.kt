package com.hazbu.xcam.utils

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File

object MediaRemuxer {
    fun loopVideo(context: Context, sourcePath: String, destPath: String): Boolean {
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
            val buffer = ByteArray(1024 * 64)
            extractor.selectTrack(videoTrack)

            var isRead = false
            while (!isRead) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    isRead = true
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_NEXT_SYNC)
                    continue
                }

                val ct = extractor.sampleTime
                val flags = extractor.sampleFlags

                if ((flags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    // Seek to start of this sync frame and copy the full sample
                    extractor.seekTo(ct, MediaExtractor.SEEK_TO_NEXT_SYNC)
                    while (!isRead) {
                        val s = extractor.readSampleData(buffer, 0)
                        if (s < 0) {
                            isRead = true
                            break
                        }
                        muxer.writeSampleData(
                            videoOutTrack,
                            buffer,
                              MediaMuxer.SampleInfo.Builder()
                                  .setSize(s)
                                  .setTimeUs(extractor.sampleTime)
                                  .setFlags(extractor.sampleFlags)
                                  .build()
                        )
                        extractor.advance()
                    }
                } else {
                    muxer.writeSampleData(
                        videoOutTrack,
                        buffer,
                        MediaMuxer.SampleInfo.Builder()
                            .setSize(sampleSize)
                            .setTimeUs(extractor.sampleTime)
                            .setFlags(extractor.sampleFlags)
                            .build()
                    )
                    extractor.advance()
                }
            }

            // Copy audio if present
            if (audioTrack >= 0) {
                extractor.selectTrack(audioTrack)
                var aRead = false
                while (!aRead) {
                    val s = extractor.readSampleData(buffer, 0)
                    if (s < 0) {
                        aRead = true
                        break
                    }
                    muxer.writeSampleData(
                        audioOutTrack.toLong(),
                        buffer,
                        MediaMuxer.SampleInfo.Builder()
                            .setSize(s)
                            .setTimeUs(extractor.sampleTime)
                            .setFlags(extractor.sampleFlags)
                            .build()
                    )
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
