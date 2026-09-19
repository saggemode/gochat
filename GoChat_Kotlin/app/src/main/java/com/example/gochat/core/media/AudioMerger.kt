package com.example.gochat.core.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Utility to concatenate multiple AAC/M4A audio files into a single M4A file
 * using Android's native MediaExtractor and MediaMuxer without re-encoding.
 */
object AudioMerger {

    private const val TAG = "AudioMerger"
    private const val BUFFER_SIZE = 256 * 1024

    fun mergeM4aFiles(sourceFiles: List<File>, outputFile: File): Boolean {
        val validFiles = sourceFiles.filter { it.exists() && it.length() > 0 }
        if (validFiles.isEmpty()) {
            Log.e(TAG, "No valid source files to merge")
            return false
        }

        if (validFiles.size == 1) {
            return try {
                validFiles[0].copyTo(outputFile, overwrite = true)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy single segment", e)
                false
            }
        }

        var muxer: MediaMuxer? = null
        val initialExtractor = MediaExtractor()

        try {
            initialExtractor.setDataSource(validFiles[0].absolutePath)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until initialExtractor.trackCount) {
                val format = initialExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                Log.e(TAG, "No audio track found in first file: ${validFiles[0].name}")
                return false
            }

            if (outputFile.exists()) {
                outputFile.delete()
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()

            var presentationTimeOffsetUs = 0L

            for (file in validFiles) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    var fileAudioTrack = -1
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                        if (mime.startsWith("audio/")) {
                            fileAudioTrack = i
                            break
                        }
                    }

                    if (fileAudioTrack == -1) {
                        Log.w(TAG, "Skipping file with no audio track: ${file.name}")
                        continue
                    }

                    extractor.selectTrack(fileAudioTrack)

                    var lastSampleTimeUs = 0L
                    var sampleCount = 0

                    while (true) {
                        bufferInfo.offset = 0
                        bufferInfo.size = extractor.readSampleData(buffer, 0)
                        if (bufferInfo.size < 0) {
                            break
                        }

                        val sampleTimeUs = extractor.sampleTime
                        bufferInfo.presentationTimeUs = sampleTimeUs + presentationTimeOffsetUs
                        bufferInfo.flags = extractor.sampleFlags

                        muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                        lastSampleTimeUs = sampleTimeUs
                        sampleCount++
                        extractor.advance()
                    }

                    // Add one frame duration (~23.2ms for AAC 1024 samples @ 44.1kHz)
                    val frameDurationUs = 23220L
                    presentationTimeOffsetUs += lastSampleTimeUs + frameDurationUs
                } catch (e: Exception) {
                    Log.e(TAG, "Error extracting track from ${file.name}", e)
                } finally {
                    extractor.release()
                }
            }

            muxer.stop()
            muxer.release()
            muxer = null

            return outputFile.exists() && outputFile.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to merge audio files", e)
            outputFile.delete()
            return false
        } finally {
            initialExtractor.release()
            try {
                muxer?.release()
            } catch (_: Exception) {}
        }
    }
}
