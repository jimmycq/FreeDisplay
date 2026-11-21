package com.n1tools.box.miracast.player

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

/**
 * Low-latency audio player and AAC-LC / ADTS decoder for Miracast (Wi-Fi Display).
 */
class MiracastAudioPlayer : Thread("MiracastAudioPlayerThread") {

    companion object {
        private const val TAG = "MiracastAudioPlayer"
        private const val DEFAULT_SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val QUEUE_CAPACITY = 40
        private const val MAX_LATENCY_QUEUE_SIZE = 8 // Drop packets when queue builds up to maintain lip-sync
    }

    private val audioQueue: BlockingQueue<ByteArray> = LinkedBlockingQueue(QUEUE_CAPACITY)

    @Volatile
    private var isRunning = false
    @Volatile
    var isAudioEnabled = true
        set(value) {
            field = value
            if (!value) {
                audioQueue.clear()
                try {
                    audioTrack?.flush()
                } catch (ignored: Exception) {}
            }
            Log.i(TAG, "Miracast audio enabled state updated in real-time: $value")
        }
    private var audioTrack: AudioTrack? = null
    private var mediaDecoder: MediaCodec? = null

    private var currentSampleRate = DEFAULT_SAMPLE_RATE
    private var currentChannels = 2

    init {
        initAudioTrack(currentSampleRate, currentChannels)
        initDecoder(currentSampleRate, currentChannels)
    }

    private fun initAudioTrack(sampleRate: Int, channels: Int) {
        try {
            val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, AUDIO_FORMAT)
            val bufferSize = (minBufSize * 2).coerceAtLeast(4096)

            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelMask,
                AUDIO_FORMAT,
                bufferSize,
                AudioTrack.MODE_STREAM
            ).apply {
                if (state == AudioTrack.STATE_INITIALIZED) {
                    play()
                    Log.i(TAG, "AudioTrack initialized: ${sampleRate}Hz, $channels channels, bufferSize: $bufferSize")
                } else {
                    Log.e(TAG, "AudioTrack initialization failed state: $state")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioTrack", e)
        }
    }

    private fun initDecoder(sampleRate: Int, channels: Int) {
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                // CSD-0 for AAC-LC: 48kHz Stereo is 0x11, 0x90; 44.1kHz Stereo is 0x12, 0x10
                val csd0 = if (sampleRate == 44100) {
                    byteArrayOf(0x12.toByte(), 0x10.toByte())
                } else {
                    byteArrayOf(0x11.toByte(), 0x90.toByte())
                }
                setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            }
            mediaDecoder = try {
                MediaCodec.createByCodecName("OMX.google.aac.decoder").apply {
                    configure(format, null, null, 0)
                    start()
                    Log.i(TAG, "Initialized Google AAC decoder for Miracast audio: ${sampleRate}Hz, $channels ch")
                }
            } catch (e: Exception) {
                Log.w(TAG, "OMX.google.aac.decoder unavailable, fallback to default", e)
                MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(format, null, null, 0)
                    start()
                    Log.i(TAG, "Initialized fallback AAC decoder: ${sampleRate}Hz, $channels ch")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AAC decoder", e)
            mediaDecoder = null
        }
    }

    /**
     * Feed audio payload extracted from MPEG-TS PES.
     * Supports both LPCM direct playback and AAC-LC / ADTS hardware/software decoding.
     */
    fun addAudioPesPayload(data: ByteArray, offset: Int, length: Int, isLpcm: Boolean = false) {
        if (!isRunning || !isAudioEnabled || length <= 0) return

        if (isLpcm) {
            // Direct LPCM playback: Big-Endian 16-bit to Little-Endian conversion
            var pcmStart = offset
            var pcmLen = length
            val subStreamId = data[offset].toInt() and 0xFF
            // DVD/WFD LPCM sub-header (0x80..0x87 or 0xA0) is 4 bytes
            if (subStreamId == 0xA0 || (subStreamId in 0x80..0x87)) {
                pcmStart += 4
                pcmLen -= 4
            }
            if (pcmLen <= 1) return
            val pcm = ByteArray(pcmLen)
            val pairs = pcmLen / 2
            for (i in 0 until pairs) {
                val idx = pcmStart + (i * 2)
                pcm[i * 2] = data[idx + 1]     // lo
                pcm[i * 2 + 1] = data[idx]     // hi
            }
            audioTrack?.write(pcm, 0, pcm.size)
            return
        }

        var cur = offset
        val end = offset + length
        var foundAdts = false

        while (cur + 7 <= end) {
            val b0 = data[cur].toInt() and 0xFF
            val b1 = data[cur + 1].toInt() and 0xFF

            // Check ADTS syncword (0xFFF)
            if (b0 == 0xFF && (b1 and 0xF0) == 0xF0) {
                val b3 = data[cur + 3].toInt() and 0xFF
                val b4 = data[cur + 4].toInt() and 0xFF
                val b5 = data[cur + 5].toInt() and 0xFF

                // ADTS frame length is 13 bits
                val frameLen = ((b3 and 0x03) shl 11) or (b4 shl 3) or ((b5 and 0xE0) ushr 5)
                val headerLen = if ((b1 and 0x01) != 0) 7 else 9

                if (frameLen in (headerLen + 1)..4096 && cur + frameLen <= end) {
                    val rawLen = frameLen - headerLen
                    val frame = ByteArray(rawLen)
                    System.arraycopy(data, cur + headerLen, frame, 0, rawLen)
                    queueAudioFrame(frame)
                    cur += frameLen
                    continue
                }
            }
            cur++
        }

        // If no ADTS header found, do NOT feed corrupted or unknown payload into MediaCodec
        // Discard silently to avoid crashing the hardware/software AAC state machine
    }

    private fun queueAudioFrame(frame: ByteArray) {
        if (audioQueue.size > MAX_LATENCY_QUEUE_SIZE) {
            audioQueue.poll() // Discard stale audio frame for low latency
        }
        if (!audioQueue.offer(frame)) {
            audioQueue.poll()
            audioQueue.offer(frame)
        }
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        isRunning = true
        Log.i(TAG, "MiracastAudioPlayer thread started")

        val bufferInfo = MediaCodec.BufferInfo()
        var inputBuffers: Array<ByteBuffer>? = null
        var outputBuffers: Array<ByteBuffer>? = null

        try {
            inputBuffers = mediaDecoder?.inputBuffers
            outputBuffers = mediaDecoder?.outputBuffers
        } catch (ignored: Exception) {}

        while (isRunning) {
            try {
                val frameData = audioQueue.take()
                if (!isRunning) break

                val decoder = mediaDecoder
                if (decoder != null) {
                    if (inputBuffers == null || outputBuffers == null) {
                        inputBuffers = decoder.inputBuffers
                        outputBuffers = decoder.outputBuffers
                    }

                    // 1. Queue input buffer into decoder using legacy pre-allocated direct buffers
                    // This avoids the known Android 5.1 x86_64 JNI SIGSEGV in JMediaCodec::getBuffer
                    val inIndex = decoder.dequeueInputBuffer(5000)
                    if (inIndex >= 0 && inputBuffers != null && inIndex < inputBuffers.size) {
                        val inputBuffer = inputBuffers[inIndex]
                        inputBuffer.clear()
                        val writeLen = frameData.size.coerceAtMost(inputBuffer.remaining())
                        inputBuffer.put(frameData, 0, writeLen)
                        decoder.queueInputBuffer(inIndex, 0, writeLen, 0, 0)
                    }

                    // 2. Drain decoded PCM output buffers
                    var outIndex = decoder.dequeueOutputBuffer(bufferInfo, 5000)
                    while (outIndex >= 0 && isRunning) {
                        if (outputBuffers != null && outIndex < outputBuffers.size) {
                            val outputBuffer = outputBuffers[outIndex]
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                val pcmChunk = ByteArray(bufferInfo.size)
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.get(pcmChunk, 0, bufferInfo.size)
                                audioTrack?.write(pcmChunk, 0, pcmChunk.size)
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        outIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                    }

                    if (outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        outputBuffers = decoder.outputBuffers
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = decoder.outputFormat
                        val newSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val newChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        Log.i(TAG, "Audio output format changed: ${newSampleRate}Hz, $newChannels channels")
                        if (newSampleRate != currentSampleRate || newChannels != currentChannels) {
                            currentSampleRate = newSampleRate
                            currentChannels = newChannels
                            recreateAudioTrack(newSampleRate, newChannels)
                        }
                    }
                }
            } catch (e: InterruptedException) {
                if (!isRunning) break
            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "Audio decode error, backing off: ${e.message}")
                    try {
                        Thread.sleep(100)
                    } catch (ignored: Exception) {}
                    reinitDecoder()
                    inputBuffers = null
                    outputBuffers = null
                }
            }
        }

        cleanup()
        Log.i(TAG, "MiracastAudioPlayer thread terminated")
    }

    @Synchronized
    private fun reinitDecoder() {
        try {
            audioQueue.clear()
            mediaDecoder?.let {
                it.stop()
                it.release()
            }
            mediaDecoder = null
        } catch (ignored: Exception) {}
        initDecoder(currentSampleRate, currentChannels)
    }

    @Synchronized
    private fun recreateAudioTrack(sampleRate: Int, channels: Int) {
        try {
            audioTrack?.let {
                it.stop()
                it.release()
            }
            initAudioTrack(sampleRate, channels)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to recreate AudioTrack", e)
        }
    }

    fun stopPlayer() {
        isRunning = false
        interrupt()
    }

    private fun cleanup() {
        try {
            audioQueue.clear()
            mediaDecoder?.let {
                it.stop()
                it.release()
            }
            mediaDecoder = null

            audioTrack?.let {
                it.stop()
                it.release()
            }
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Error during MiracastAudioPlayer cleanup", e)
        }
    }
}
