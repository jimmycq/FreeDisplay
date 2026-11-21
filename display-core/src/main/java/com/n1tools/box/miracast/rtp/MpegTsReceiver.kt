package com.n1tools.box.miracast.rtp

import android.os.Process
import android.util.Log
import com.n1tools.box.airplay.model.NALPacket
import com.n1tools.box.airplay.player.VideoPlayer
import com.n1tools.box.miracast.player.MiracastAudioPlayer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

class MpegTsReceiver(
    private val rtpPort: Int = 19000,
    private val videoPlayerProvider: () -> VideoPlayer?,
    private val audioPlayerProvider: () -> MiracastAudioPlayer? = { null },
    private val isAudioEnabled: () -> Boolean = { false },
    private val isVideoAllowed: () -> Boolean = { true },
    private val onFirstFrame: (() -> Unit)? = null
) : Thread("MpegTsReceiverThread") {

    companion object {
        private const val TAG = "MpegTsReceiver"
        private const val TS_PACKET_SIZE = 188
        private const val SYNC_BYTE: Byte = 0x47
        private const val BUFFER_SIZE = 65536
        private const val INITIAL_PES_CAPACITY = 256 * 1024 // 256KB initial buffer
    }

    /**
     * Reusable, non-allocating byte buffer for assembling PES video frames.
     * Completely eliminates ByteArrayOutputStream allocation and toByteArray() copies.
     */
    private class ReusablePesBuffer(initialCapacity: Int) {
        var buffer: ByteArray = ByteArray(initialCapacity)
            private set
        var size: Int = 0
            private set

        fun write(src: ByteArray, offset: Int, length: Int) {
            ensureCapacity(size + length)
            System.arraycopy(src, offset, buffer, size, length)
            size += length
        }

        fun reset() {
            size = 0
        }

        private fun ensureCapacity(minCapacity: Int) {
            if (minCapacity > buffer.size) {
                var newCapacity = buffer.size * 2
                if (newCapacity < minCapacity) {
                    newCapacity = minCapacity
                }
                val newBuffer = ByteArray(newCapacity)
                System.arraycopy(buffer, 0, newBuffer, 0, size)
                buffer = newBuffer
            }
        }
    }

    @Volatile
    private var isRunning = false
    @Volatile
    private var suspendUntilMs = 0L
    private var socket: DatagramSocket? = null

    // PES video assembly using zero-copy reusable buffer
    private val videoPesBuffer = ReusablePesBuffer(INITIAL_PES_CAPACITY)
    private var videoPid = -1
    private var isCollectingVideo = false

    // PES audio assembly
    private val audioPesBuffer = ReusablePesBuffer(64 * 1024)
    private var audioPid = -1
    private var isCollectingAudio = false

    override fun run() {
        // Boost thread priority for network packet receiver to prevent UDP buffer overrun
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        isRunning = true
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                trafficClass = 0x10 // IPTOS_LOWDELAY
                // 4MB UDP socket buffer for smooth 1080p60 reception without packet loss
                receiveBufferSize = 4 * 1024 * 1024
                bind(InetSocketAddress(rtpPort))
            }
            Log.i(TAG, "MPEG-TS RTP UDP Receiver listening on port $rtpPort (SO_RCVBUF=${socket?.receiveBufferSize})")

            val receiveData = ByteArray(BUFFER_SIZE)
            val packet = DatagramPacket(receiveData, receiveData.size)

            while (isRunning) {
                try {
                    socket?.receive(packet)
                    if (System.currentTimeMillis() < suspendUntilMs) continue
                    val len = packet.length
                    if (len <= 12) continue // Skip invalid / too small RTP

                    // RTP Header is 12 bytes
                    val rtpPayloadOffset = 12
                    val rtpPayloadLen = len - rtpPayloadOffset

                    parseRtpPayload(receiveData, rtpPayloadOffset, rtpPayloadLen)
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "Error receiving RTP packet", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind UDP socket on port $rtpPort", e)
        } finally {
            closeSocket()
        }
    }

    private fun parseRtpPayload(data: ByteArray, offset: Int, length: Int) {
        var currentOffset = offset
        val endOffset = offset + length

        while (currentOffset + TS_PACKET_SIZE <= endOffset) {
            if (data[currentOffset] == SYNC_BYTE) {
                processTsPacket(data, currentOffset)
                currentOffset += TS_PACKET_SIZE
            } else {
                // Resync
                currentOffset++
            }
        }
    }

    private fun processTsPacket(data: ByteArray, offset: Int) {
        val b1 = data[offset + 1].toInt() and 0xFF
        val b2 = data[offset + 2].toInt() and 0xFF
        val b3 = data[offset + 3].toInt() and 0xFF

        val pusi = (b1 and 0x40) != 0
        val pid = ((b1 and 0x1F) shl 8) or b2
        val adaptationFieldControl = (b3 and 0x30) ushr 4

        var payloadOffset = offset + 4
        if (adaptationFieldControl == 2 || adaptationFieldControl == 3) {
            val adaptationLen = data[payloadOffset].toInt() and 0xFF
            payloadOffset += 1 + adaptationLen
        }

        val payloadLen = (offset + TS_PACKET_SIZE) - payloadOffset
        if (payloadLen <= 0 || payloadOffset >= offset + TS_PACKET_SIZE) return

        // Auto-detect Video PID & Audio PID by inspecting PES stream_id
        if (pusi && payloadLen >= 4) {
            if (data[payloadOffset] == 0.toByte() && data[payloadOffset + 1] == 0.toByte() && data[payloadOffset + 2] == 1.toByte()) {
                val streamId = data[payloadOffset + 3].toInt() and 0xFF
                if (streamId in 0xE0..0xEF) {
                    if (videoPid != pid) {
                        videoPid = pid
                        Log.i(TAG, "Confirmed Video PID from PES stream_id (0x${Integer.toHexString(streamId)}): 0x${Integer.toHexString(videoPid)}")
                    }
                } else if ((streamId in 0xC0..0xDF) || streamId == 0xBD) {
                    if (audioPid != pid) {
                        audioPid = pid
                        Log.i(TAG, "Confirmed Audio PID from PES stream_id (0x${Integer.toHexString(streamId)}): 0x${Integer.toHexString(audioPid)}")
                    }
                }
            }
        }

        if (videoPid != -1 && pid == videoPid) {
            if (pusi) {
                // Previous PES frame complete
                if (isCollectingVideo && videoPesBuffer.size > 0) {
                    flushVideoPes(videoPesBuffer.buffer, videoPesBuffer.size)
                    videoPesBuffer.reset()
                }
                isCollectingVideo = true
            }

            if (isCollectingVideo) {
                videoPesBuffer.write(data, payloadOffset, payloadLen)
            }
        } else if (audioPid != -1 && pid == audioPid && isAudioEnabled()) {
            if (pusi) {
                // Previous audio PES frame complete
                if (isCollectingAudio && audioPesBuffer.size > 0) {
                    flushAudioPes(audioPesBuffer.buffer, audioPesBuffer.size)
                    audioPesBuffer.reset()
                }
                isCollectingAudio = true
            }

            if (isCollectingAudio) {
                audioPesBuffer.write(data, payloadOffset, payloadLen)
            }
        }
    }

    @Volatile
    private var hasReportedFirstFrame = false
    private var audioLogCount = 0

    fun suspend(durationMs: Long) {
        suspendUntilMs = if (durationMs > 0) System.currentTimeMillis() + durationMs else 0L
        resetFirstFrameState()
        Log.i(TAG, "RTP receiver suspended for ${durationMs}ms (old session packets dropped)")
    }

    private fun flushVideoPes(pesData: ByteArray, pesLength: Int) {
        // Strip PES Header (Start code 0x000001, Stream ID, Length, Flags, Header Length)
        if (pesLength < 9) return
        if (pesData[0] == 0.toByte() && pesData[1] == 0.toByte() && pesData[2] == 1.toByte()) {
            val streamId = pesData[3].toInt() and 0xFF
            // Video stream IDs are typically 0xE0 ~ 0xEF
            if (streamId in 0xE0..0xEF) {
                val pesHeaderLen = pesData[8].toInt() and 0xFF
                val nalOffset = 9 + pesHeaderLen
                if (nalOffset < pesLength) {
                    val nalLength = pesLength - nalOffset

                    if (!hasReportedFirstFrame) {
                        hasReportedFirstFrame = true
                        Log.i(TAG, "First video NAL frame detected (length: $nalLength bytes)")
                        onFirstFrame?.invoke()
                    }

                    if (!isVideoAllowed()) {
                        // Another source (e.g. Mac) is currently active; drop video packets at socket entry
                        return
                    }

                    // Allocate single byte array for the NAL slice without intermediate PES buffer clone
                    val nalBytes = ByteArray(nalLength)
                    System.arraycopy(pesData, nalOffset, nalBytes, 0, nalLength)

                    val nalPacket = NALPacket().apply {
                        nalData = nalBytes
                        offset = 0
                        length = nalLength
                        pts = System.nanoTime() / 1000
                    }
                    videoPlayerProvider()?.addPacker(nalPacket)
                }
            }
        }
    }

    fun resetFirstFrameState() {
        hasReportedFirstFrame = false
        videoPid = -1
        audioPid = -1
        audioLogCount = 0
        videoPesBuffer.reset()
        audioPesBuffer.reset()
        isCollectingVideo = false
        isCollectingAudio = false
        Log.i(TAG, "MpegTsReceiver state reset (ready for new stream / mode switch)")
    }

    private fun flushAudioPes(pesData: ByteArray, pesLength: Int) {
        if (!isVideoAllowed()) return // Ignore audio if Miracast is not the active display source
        if (pesLength < 9) return
        if (pesData[0] == 0.toByte() && pesData[1] == 0.toByte() && pesData[2] == 1.toByte()) {
            val streamId = pesData[3].toInt() and 0xFF
            if ((streamId in 0xC0..0xDF) || streamId == 0xBD) {
                val pesHeaderLen = pesData[8].toInt() and 0xFF
                val audioOffset = 9 + pesHeaderLen
                if (audioOffset < pesLength) {
                    val audioLength = pesLength - audioOffset
                    if (audioLogCount++ < 5) {
                        val preview = (0 until audioLength.coerceAtMost(8)).map {
                            String.format("%02X", pesData[audioOffset + it])
                        }.joinToString(" ")
                        Log.i(TAG, "MPEG-TS Audio PES parsed (streamId=0x${Integer.toHexString(streamId)}, len=$audioLength, head: $preview)")
                    }
                    val isLpcm = (streamId == 0xBD)
                    audioPlayerProvider()?.addAudioPesPayload(pesData, audioOffset, audioLength, isLpcm)
                }
            }
        }
    }

    fun stopReceiver() {
        isRunning = false
        closeSocket()
        interrupt()
    }

    private fun closeSocket() {
        try {
            socket?.close()
            socket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing UDP socket", e)
        }
    }
}
