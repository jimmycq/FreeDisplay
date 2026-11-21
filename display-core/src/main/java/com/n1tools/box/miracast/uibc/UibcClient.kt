package com.n1tools.box.miracast.uibc

import android.util.Log
import android.view.MotionEvent
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * P0 experiment switches. Change one at a time and re-run the Huawei pairing.
 */
object UibcExperiment {
    const val DESCRIPTOR_TRAILING_BYTE: Byte = 0x00 // 0xFF was the previous value
    const val HID_TYPE: Byte = 0x01
    const val HID_USAGE_REPORT: Byte = 0x00
    const val HID_USAGE_DESCRIPTOR: Byte = 0x01
    const val KEEPALIVE_ENABLED = false
    const val CLICK_GAP_MS = 20L
    const val LOG_PACKET_HEX = true
    const val ABSOLUTE_MOUSE = false
    // Huawei advertises generic_cap_list=Keyboard, Mouse even though input_category_list=HIDC.
    // Test whether it accepts GENERIC absolute mouse/touch packets anyway.
    const val FORCE_GENERIC = true
    const val GENERIC_NORMALIZED = false
    const val GENERIC_MOUSE = true
}

/**
 * Wi-Fi Display (Miracast) User Input Back Channel (UIBC) client.
 * Transmits touch/mouse events from Nokia N1 Sink back to the Source (e.g. Huawei PC Desktop mode).
 * Fully complies with WFD v1.1.0 specification for HIDC USB Mouse (injecting HID Report Descriptor on connection).
 */
class UibcClient(
    val host: String,
    val port: Int,
    val isHidc: Boolean = true
) : Thread("UibcClientThread") {

    val isConnected: Boolean
        get() = isRunning && socket?.isConnected == true && socket?.isClosed == false

    /** Called when Huawei closes the UIBC socket, so the sink can re-enable UIBC over RTSP. */
    var onConnectionLost: (() -> Unit)? = null

    fun matches(targetHost: String, targetPort: Int, targetIsHidc: Boolean): Boolean {
        return host == targetHost && port == targetPort && isHidc == targetIsHidc
    }

    companion object {
        private const val TAG = "UibcClient"
        private const val TYPE_DOWN: Byte = 0x00
        private const val TYPE_UP: Byte = 0x01
        private const val TYPE_MOVE: Byte = 0x02
        private const val MOUSE_DOWN: Byte = 0x05
        private const val MOUSE_UP: Byte = 0x06
        private const val MOUSE_MOVE: Byte = 0x07

        // Known-working WFD HIDC USB mouse report descriptor (52 bytes).
        // Report = buttons + relative X + relative Y + wheel (4 bytes).
        private val MOUSE_HID_REPORT_DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x02.toByte(), // Usage (Mouse)
            0xA1.toByte(), 0x01.toByte(), // Collection (Application)
            0x09.toByte(), 0x01.toByte(), //   Usage (Pointer)
            0xA1.toByte(), 0x00.toByte(), //   Collection (Physical)
            0x05.toByte(), 0x09.toByte(), //     Usage Page (Button)
            0x19.toByte(), 0x01.toByte(), //     Usage Minimum (Button 1)
            0x29.toByte(), 0x03.toByte(), //     Usage Maximum (Button 3)
            0x15.toByte(), 0x00.toByte(), //     Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(), //     Logical Maximum (1)
            0x95.toByte(), 0x03.toByte(), //     Report Count (3)
            0x75.toByte(), 0x01.toByte(), //     Report Size (1)
            0x81.toByte(), 0x02.toByte(), //     Input (Data,Var,Abs)
            0x95.toByte(), 0x01.toByte(), //     Report Count (1)
            0x75.toByte(), 0x05.toByte(), //     Report Size (5)
            0x81.toByte(), 0x03.toByte(), //     Input (Const,Var,Abs)
            0x05.toByte(), 0x01.toByte(), //     Usage Page (Generic Desktop)
            0x09.toByte(), 0x30.toByte(), //     Usage (X)
            0x09.toByte(), 0x31.toByte(), //     Usage (Y)
            0x09.toByte(), 0x38.toByte(), //     Usage (Wheel)
            0x15.toByte(), 0x81.toByte(), //     Logical Minimum (-127)
            0x25.toByte(), 0x7F.toByte(), //     Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(), //     Report Size (8)
            0x95.toByte(), 0x03.toByte(), //     Report Count (3)
            0x81.toByte(), 0x06.toByte(), //     Input (Data,Var,Rel)
            0xC0.toByte(),                //   End Collection
            0xC0.toByte()                 // End Collection
        )

        // Absolute HIDC mouse: buttons(1) + X(2) + Y(2) + wheel(1) = 6-byte report.
        private val ABSOLUTE_MOUSE_HID_REPORT_DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x02.toByte(), // Usage (Mouse)
            0xA1.toByte(), 0x01.toByte(), // Collection (Application)
            0x09.toByte(), 0x01.toByte(), //   Usage (Pointer)
            0xA1.toByte(), 0x00.toByte(), //   Collection (Physical)
            0x05.toByte(), 0x09.toByte(), //     Usage Page (Button)
            0x19.toByte(), 0x01.toByte(), //     Usage Minimum (Button 1)
            0x29.toByte(), 0x03.toByte(), //     Usage Maximum (Button 3)
            0x15.toByte(), 0x00.toByte(), //     Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(), //     Logical Maximum (1)
            0x95.toByte(), 0x03.toByte(), //     Report Count (3)
            0x75.toByte(), 0x01.toByte(), //     Report Size (1)
            0x81.toByte(), 0x02.toByte(), //     Input (Data,Var,Abs)
            0x95.toByte(), 0x01.toByte(), //     Report Count (1)
            0x75.toByte(), 0x05.toByte(), //     Report Size (5)
            0x81.toByte(), 0x03.toByte(), //     Input (Const,Var,Abs)
            0x05.toByte(), 0x01.toByte(), //     Usage Page (Generic Desktop)
            0x09.toByte(), 0x30.toByte(), //     Usage (X)
            0x09.toByte(), 0x31.toByte(), //     Usage (Y)
            0x15.toByte(), 0x00.toByte(), //     Logical Minimum (0)
            0x26.toByte(), 0xFF.toByte(), 0x7F.toByte(), // Logical Maximum (32767)
            0x75.toByte(), 0x10.toByte(), //     Report Size (16)
            0x95.toByte(), 0x02.toByte(), //     Report Count (2)
            0x81.toByte(), 0x02.toByte(), //     Input (Data,Var,Abs)
            0x09.toByte(), 0x38.toByte(), //     Usage (Wheel)
            0x15.toByte(), 0x81.toByte(), //     Logical Minimum (-127)
            0x25.toByte(), 0x7F.toByte(), //     Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(), //     Report Size (8)
            0x95.toByte(), 0x01.toByte(), //     Report Count (1)
            0x81.toByte(), 0x06.toByte(), //     Input (Data,Var,Rel)
            0xC0.toByte(),                //   End Collection
            0xC0.toByte()                 // End Collection
        )
    }

    @Volatile
    private var isRunning = false
    private var socket: Socket? = null
    private var outStream: OutputStream? = null
    private val packetQueue = LinkedBlockingQueue<ByteArray>(200)

    // Touch / Trackpad gesture state
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = -1f
    private var lastY = -1f
    private var isDragging = false

    fun sendTouchEvent(
        action: Int,
        xPixel: Float,
        yPixel: Float,
        viewWidth: Int,
        viewHeight: Int,
        contentWidth: Int = -1,
        contentHeight: Int = -1
    ) {
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
            Log.i(TAG, "UIBC touch action=$action mode=${if (isHidc) "HIDC" else "GENERIC"} " +
                "pos=(${xPixel.toInt()},${yPixel.toInt()}) view=${viewWidth}x$viewHeight connected=$isConnected")
        }
        if (!isConnected) {
            // Do not queue stale taps across reconnect; they would fire at wrong cursor positions.
            return
        }
        if (!isHidc) {
            // Huawei resets the UIBC channel when a GENERIC MOUSE_MOVE arrives between
            // DOWN and UP. Keep tap-only mode for now; dragging can be added separately.
            if (action == MotionEvent.ACTION_MOVE) return
            val vW = viewWidth.coerceAtLeast(1)
            val vH = viewHeight.coerceAtLeast(1)
            val cW = if (contentWidth > 0) contentWidth else vW
            val cH = if (contentHeight > 0) contentHeight else vH
            // Some sources expect negotiated-resolution pixels, others expect 0..65535.
            val x = if (UibcExperiment.GENERIC_NORMALIZED) {
                ((xPixel / vW) * 65535).toInt().coerceIn(0, 65535)
            } else {
                ((xPixel / vW) * cW).toInt().coerceIn(0, 65535)
            }
            val y = if (UibcExperiment.GENERIC_NORMALIZED) {
                ((yPixel / vH) * 65535).toInt().coerceIn(0, 65535)
            } else {
                ((yPixel / vH) * cH).toInt().coerceIn(0, 65535)
            }
            val packet = buildGenericTouchPacket(action, x, y) ?: return
            enqueuePacket(packet)
            return
        }

        if (isHidc && UibcExperiment.ABSOLUTE_MOUSE) {
            sendAbsoluteHidcTouch(action, xPixel, yPixel, viewWidth, viewHeight)
            return
        }

        // HIDC Mode: Trackpad gestures (Tap = Click, Swipe = Move Cursor, Hold+Move = Drag)
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downTime = System.currentTimeMillis()
                downX = xPixel
                downY = yPixel
                lastX = xPixel
                lastY = yPixel
                isDragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (lastX < 0 || lastY < 0) {
                    lastX = xPixel
                    lastY = yPixel
                    return
                }

                val deltaX = xPixel - lastX
                val deltaY = yPixel - lastY
                lastX = xPixel
                lastY = yPixel

                // If held stationary for > 350ms, initiate drag
                val totalDist = Math.hypot((xPixel - downX).toDouble(), (yPixel - downY).toDouble())
                if (!isDragging && System.currentTimeMillis() - downTime > 350 && totalDist < 25) {
                    isDragging = true
                }

                val buttonByte: Byte = if (isDragging) 0x01 else 0x00
                // Cursor sensitivity scaling
                val dx = (deltaX * 1.2f).toInt().coerceIn(-127, 127).toByte()
                val dy = (deltaY * 1.2f).toInt().coerceIn(-127, 127).toByte()

                val packet = buildHidcMouseReportPacket(buttonByte, dx, dy)
                enqueuePacket(packet)
            }

            MotionEvent.ACTION_UP -> {
                val duration = System.currentTimeMillis() - downTime
                val totalDist = Math.hypot((xPixel - downX).toDouble(), (yPixel - downY).toDouble())

                if (duration < 350 && totalDist < 35) {
                    // Tap -> Left Click (Button Down followed by Button Up)
                    val clickDown = buildHidcMouseReportPacket(0x01, 0, 0)
                    val clickUp = buildHidcMouseReportPacket(0x00, 0, 0)
                    enqueuePacket(clickDown)
                    Thread {
                        try {
                            Thread.sleep(UibcExperiment.CLICK_GAP_MS)
                            enqueuePacket(clickUp)
                        } catch (ignored: InterruptedException) {}
                    }.start()
                    Log.d(TAG, "Sent HIDC Mouse Tap/Click (gap=${UibcExperiment.CLICK_GAP_MS}ms)")
                } else if (isDragging) {
                    // Release Drag
                    val dragRelease = buildHidcMouseReportPacket(0x00, 0, 0)
                    enqueuePacket(dragRelease)
                    Log.d(TAG, "Released HIDC Mouse Drag")
                }

                lastX = -1f
                lastY = -1f
                isDragging = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    val dragRelease = buildHidcMouseReportPacket(0x00, 0, 0)
                    enqueuePacket(dragRelease)
                }
                lastX = -1f
                lastY = -1f
                isDragging = false
            }
        }
    }

    private fun sendAbsoluteHidcTouch(
        action: Int,
        xPixel: Float,
        yPixel: Float,
        viewWidth: Int,
        viewHeight: Int
    ) {
        val vW = viewWidth.coerceAtLeast(1)
        val vH = viewHeight.coerceAtLeast(1)
        val xAbs = ((xPixel / vW) * 32767).toInt().coerceIn(0, 32767)
        val yAbs = ((yPixel / vH) * 32767).toInt().coerceIn(0, 32767)

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downTime = System.currentTimeMillis()
                downX = xPixel
                downY = yPixel
                lastX = xPixel
                lastY = yPixel
                isDragging = false
                // Position the absolute pointer at the touched location.
                enqueuePacket(buildAbsoluteHidcMouseReportPacket(0x00, xAbs, yAbs))
            }

            MotionEvent.ACTION_MOVE -> {
                val totalDist = Math.hypot((xPixel - downX).toDouble(), (yPixel - downY).toDouble())
                if (!isDragging && System.currentTimeMillis() - downTime > 350 && totalDist < 25) {
                    isDragging = true
                }
                lastX = xPixel
                lastY = yPixel
                val buttons: Byte = if (isDragging) 0x01 else 0x00
                enqueuePacket(buildAbsoluteHidcMouseReportPacket(buttons, xAbs, yAbs))
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val duration = System.currentTimeMillis() - downTime
                val totalDist = Math.hypot((xPixel - downX).toDouble(), (yPixel - downY).toDouble())
                if (action == MotionEvent.ACTION_UP && duration < 350 && totalDist < 35) {
                    val clickDown = buildAbsoluteHidcMouseReportPacket(0x01, xAbs, yAbs)
                    val clickUp = buildAbsoluteHidcMouseReportPacket(0x00, xAbs, yAbs)
                    enqueuePacket(clickDown)
                    Thread {
                        try {
                            Thread.sleep(UibcExperiment.CLICK_GAP_MS)
                            enqueuePacket(clickUp)
                        } catch (ignored: InterruptedException) {}
                    }.start()
                } else {
                    enqueuePacket(buildAbsoluteHidcMouseReportPacket(0x00, xAbs, yAbs))
                }
                lastX = -1f
                lastY = -1f
                isDragging = false
            }
        }
    }

    fun sendTouchEvent(action: Int, xNorm: Int, yNorm: Int) {
        sendTouchEvent(action, xNorm.toFloat(), yNorm.toFloat(), 1920, 1080)
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun enqueuePacket(packet: ByteArray) {
        if (packetQueue.size > 100) {
            packetQueue.poll() // drop oldest touch events to maintain low latency
        }
        packetQueue.offer(packet)
    }

    /**
     * Known-working WFD HIDC USB mouse input report.
     * 4-byte report: buttons + relative X + relative Y + wheel.
     * 14-byte packet = 9-byte header + 4-byte value + 1-byte 16-bit padding.
     */
    private fun buildHidcMouseReportPacket(buttonByte: Byte, dx: Byte, dy: Byte, wheel: Byte = 0): ByteArray {
        return ByteArray(14).apply {
            this[0] = 0x00 // Version 0, T=0, Reserved 0
            this[1] = 0x01 // Input Category: HIDC
            this[2] = 0x00 // Total Length MSB
            this[3] = 0x0E // Total Length LSB (14 bytes)
            this[4] = 0x01 // HID Input Path: 0x01 = USB
            this[5] = UibcExperiment.HID_TYPE
            this[6] = UibcExperiment.HID_USAGE_REPORT
            this[7] = 0x00 // Length of HIDC value MSB
            this[8] = 0x04 // Length of HIDC value LSB (4 bytes)
            this[9] = buttonByte // Button bitmask
            this[10] = dx
            this[11] = dy
            this[12] = wheel
            this[13] = 0x00 // Padding to 16-bit boundary
        }
    }

    private fun buildAbsoluteHidcMouseReportPacket(
        buttonByte: Byte,
        xAbs: Int,
        yAbs: Int,
        wheel: Byte = 0
    ): ByteArray {
        return ByteArray(16).apply {
            this[0] = 0x00
            this[1] = 0x01 // HIDC
            this[2] = 0x00
            this[3] = 0x10 // 16 bytes total
            this[4] = 0x01 // USB
            this[5] = UibcExperiment.HID_TYPE
            this[6] = UibcExperiment.HID_USAGE_REPORT
            this[7] = 0x00
            this[8] = 0x06 // 6-byte report: buttons + X(2) + Y(2) + wheel
            this[9] = buttonByte
            this[10] = ((xAbs shr 8) and 0xFF).toByte()
            this[11] = (xAbs and 0xFF).toByte()
            this[12] = ((yAbs shr 8) and 0xFF).toByte()
            this[13] = (yAbs and 0xFF).toByte()
            this[14] = wheel
            this[15] = 0x00 // padding
        }
    }

    private fun neutralHidcReport(): ByteArray = if (UibcExperiment.ABSOLUTE_MOUSE) {
        buildAbsoluteHidcMouseReportPacket(0x00, 16384, 16384)
    } else {
        buildHidcMouseReportPacket(0x00, 0, 0)
    }

    /**
     * WFD UIBC Specification - HIDC USB Mouse Report Descriptor Packet.
     * Mandatory to establish input context with Source (e.g. Huawei CastEngine / Linux uinput).
     */
    private fun sendHidcReportDescriptor(out: OutputStream) {
        val descriptor = if (UibcExperiment.ABSOLUTE_MOUSE) {
            ABSOLUTE_MOUSE_HID_REPORT_DESCRIPTOR
        } else {
            MOUSE_HID_REPORT_DESCRIPTOR
        }
        val descLen = descriptor.size
        val totalLen = 4 + 5 + descLen + 1
        val packet = ByteArray(totalLen).apply {
            // UIBC Common Header (4 bytes)
            this[0] = 0x00
            this[1] = 0x01 // Input Category: HIDC
            this[2] = ((totalLen shr 8) and 0xFF).toByte()
            this[3] = (totalLen and 0xFF).toByte()

            // HIDC Header (5 bytes)
            this[4] = 0x01 // HID Input Path: 0x01 = USB
            this[5] = UibcExperiment.HID_TYPE
            this[6] = UibcExperiment.HID_USAGE_DESCRIPTOR
            this[7] = ((descLen shr 8) and 0xFF).toByte()
            this[8] = (descLen and 0xFF).toByte()

            // HIDC Value (52-byte Report Descriptor)
            System.arraycopy(descriptor, 0, this, 9, descLen)
            this[totalLen - 1] = UibcExperiment.DESCRIPTOR_TRAILING_BYTE
        }
        out.write(packet)
        out.flush()
        if (UibcExperiment.LOG_PACKET_HEX) Log.i(TAG, "HIDC descriptor hex: ${hex(packet)}")
        Log.i(TAG, "Sent WFD HIDC Mouse Report Descriptor successfully ($totalLen bytes)")
    }

    private fun buildGenericTouchPacket(action: Int, xNorm: Int, yNorm: Int): ByteArray? {
        if (UibcExperiment.GENERIC_MOUSE) {
            // WFD Generic Mouse: no pointer count/id; body = buttons + X(2) + Y(2).
            val typeId: Byte = when (action) {
                MotionEvent.ACTION_DOWN -> MOUSE_DOWN
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> MOUSE_UP
                MotionEvent.ACTION_MOVE -> MOUSE_MOVE
                else -> return null
            }
            val buttons: Byte = if (action == MotionEvent.ACTION_DOWN) 0x01 else 0x00
            return ByteArray(12).apply {
                this[0] = 0x00
                this[1] = 0x00 // GENERIC
                this[2] = 0x00
                this[3] = 0x0C // 12 bytes total
                this[4] = typeId
                this[5] = 0x00
                this[6] = 0x05 // body length
                this[7] = buttons
                this[8] = ((xNorm shr 8) and 0xFF).toByte()
                this[9] = (xNorm and 0xFF).toByte()
                this[10] = ((yNorm shr 8) and 0xFF).toByte()
                this[11] = (yNorm and 0xFF).toByte()
            }
        }

        val typeId: Byte = when (action) {
            MotionEvent.ACTION_DOWN -> TYPE_DOWN
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> TYPE_UP
            MotionEvent.ACTION_MOVE -> TYPE_MOVE
            else -> return null
        }

        // Generic SingleTouch UIBC packet (14 bytes incl. 1-byte padding to 16-bit boundary)
        return ByteArray(14).apply {
            this[0] = 0x00
            this[1] = 0x00 // Category = GENERIC (0)
            this[2] = 0x00
            this[3] = 0x0E // 14 bytes total
            this[4] = typeId
            this[5] = 0x00
            this[6] = 0x06
            this[7] = 0x01 // 1 pointer
            this[8] = 0x00 // Pointer ID = 0
            this[9] = ((xNorm shr 8) and 0xFF).toByte()
            this[10] = (xNorm and 0xFF).toByte()
            this[11] = ((yNorm shr 8) and 0xFF).toByte()
            this[12] = (yNorm and 0xFF).toByte()
            this[13] = 0x00 // Padding
        }
    }

    override fun run() {
        isRunning = true
        var retryDelayMs = 1500L
        var lastHex = ""

        while (isRunning) {
            try {
                Log.i(TAG, "Connecting to UIBC server on $host:$port (isHidc=$isHidc)...")
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 3000)
                s.tcpNoDelay = true
                s.keepAlive = true
                s.soTimeout = 0

                socket = s
                val out = s.getOutputStream()
                outStream = out
                Log.i(TAG, "UIBC TCP connected successfully to $host:$port (mode=${if (isHidc) "HIDC" else "GENERIC"})")

                // Reset retry backoff on successful connection
                retryDelayMs = 1500L

                // WFD HIDC requires the report descriptor before any input report.
                // It must describe exactly the same 3-byte layout we send.
                if (isHidc) {
                    sendHidcReportDescriptor(out)
                    try {
                        Thread.sleep(100)
                    } catch (ignored: InterruptedException) {}
                }

                // Stream input reports. Keepalive is an experiment switch because a
                // zero-movement report may itself trigger Huawei UIBC reset.
                while (isRunning) {
                    val packet = if (UibcExperiment.KEEPALIVE_ENABLED) {
                        packetQueue.poll(1, TimeUnit.SECONDS)
                    } else {
                        packetQueue.take()
                    }
                    var outgoing = packet
                    if (outgoing == null && isHidc) {
                        outgoing = neutralHidcReport()
                    }
                    if (outgoing == null) continue
                    lastHex = hex(outgoing)
                    if (UibcExperiment.LOG_PACKET_HEX) Log.d(TAG, "UIBC TX: $lastHex")
                    out.write(outgoing)
                    out.flush()
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "UIBC connection failed or closed: ${e.message}, lastHex=$lastHex, retrying in ${retryDelayMs}ms...")
                    closeSocket()
                    try {
                        onConnectionLost?.invoke()
                    } catch (ex: Exception) {
                        Log.w(TAG, "UIBC re-enable callback failed: ${ex.message}")
                    }
                    try {
                        Thread.sleep(retryDelayMs)
                        retryDelayMs = (retryDelayMs + 1000L).coerceAtMost(5000L)
                    } catch (ignored: InterruptedException) {
                        break
                    }
                }
            }
        }
        closeSocket()
    }

    fun stopClient() {
        isRunning = false
        interrupt()
        closeSocket()
    }

    private fun closeSocket() {
        try {
            outStream?.close()
        } catch (ignored: Exception) {}
        try {
            socket?.close()
        } catch (ignored: Exception) {}
        outStream = null
        socket = null
        packetQueue.clear()
        lastX = -1f
        lastY = -1f
        isDragging = false
    }
}
