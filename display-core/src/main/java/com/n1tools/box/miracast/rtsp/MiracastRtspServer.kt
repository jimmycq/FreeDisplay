package com.n1tools.box.miracast.rtsp

import android.util.Log
import com.n1tools.box.miracast.uibc.UibcExperiment
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class MiracastRtspServer(
    private val port: Int = 7236,
    private val rtpPort: Int = 19000,
    private val qualityProvider: () -> Int = { 0 },
    private val audioEnabledProvider: () -> Boolean = { false },
    private val onStreamStateChanged: (Boolean) -> Unit
) : Thread("MiracastRtspServerThread") {

    companion object {
        private const val TAG = "MiracastRtspServer"
        private const val DEFAULT_SESSION_ID = "28374619"
    }

    @Volatile
    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var clientSocket: Socket? = null
    // 华为/Mac 可能同时存在控制连接与镜像连接；只记录一个 socket 会导致旧连接无法关闭
    private val activeClients = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())

    // Miracast state tracking
    private val sinkCSeq = AtomicInteger(1)
    @Volatile
    private var currentPresentationUrl = "rtsp://192.168.49.1/wfd1.0/streamid=0"
    @Volatile
    private var sourceSessionId: String? = null
    @Volatile
    private var isStreamingActive = false

    @Volatile
    private var pendingSetupCSeq = -1
    @Volatile
    private var pendingPlayCSeq = -1
    @Volatile
    private var currentSourceHost: String = "192.168.49.1"
    @Volatile
    private var pendingUibcPort: Int = -1
    @Volatile
    private var lastUibcReenableMs = 0L
    @Volatile
    private var pendingUibcEnableCSeq = -1
    @Volatile
    private var uibcEnableConfirmed = false
    @Volatile
    private var uibcLaunchInProgress = false
    // 未明确协商时优先 GENERIC：Android/华为电脑模式走触摸注入；仅源端只支持 HIDC 时才用鼠标 HID
    @Volatile
    private var pendingUibcIsHidc: Boolean = false
    @Volatile
    var isWindowsSource: Boolean = false
        private set

    var onUibcRequested: ((sourceHost: String, uibcPort: Int, isHidc: Boolean) -> Unit)? = null

    override fun run() {
        isRunning = true
        try {
            serverSocket = ServerSocket(port).apply {
                reuseAddress = true
            }
            Log.i(TAG, "Miracast WFD RTSP Server listening on port $port")

            while (isRunning) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    activeClients.add(socket)
                    clientSocket = socket
                    val hostAddr = socket.inetAddress?.hostAddress ?: "192.168.49.1"
                    currentSourceHost = hostAddr
                    Log.i(TAG, "Miracast Source connected from: $hostAddr")
                    handleClient(socket, hostAddr)
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "Error accepting RTSP client", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind RTSP server socket on port $port", e)
        } finally {
            closeServer()
        }
    }

    fun connectToSource(hostIp: String, targetPort: Int = 7236) {
        if (clientSocket != null && clientSocket?.isConnected == true && !clientSocket!!.isClosed) return
        thread(name = "MiracastConnectToSourceThread") {
            try {
                // Short delay to let peer connect to us first
                Thread.sleep(800)
                if (clientSocket != null && clientSocket?.isConnected == true && !clientSocket!!.isClosed) return@thread

                currentPresentationUrl = "rtsp://$hostIp/wfd1.0/streamid=0"
                currentSourceHost = hostIp
                Log.i(TAG, "Attempting active RTSP connection to Source at $hostIp:$targetPort...")
                val s = Socket()
                s.connect(java.net.InetSocketAddress(hostIp, targetPort), 2000)
                synchronized(this) {
                    if (clientSocket != null && clientSocket?.isConnected == true && !clientSocket!!.isClosed) {
                        s.close()
                        return@thread
                    }
                    clientSocket = s
                    activeClients.add(s)
                }
                Log.i(TAG, "Connected to Source at $hostIp:$targetPort! Starting client handler...")
                handleClient(s, hostIp)
            } catch (e: Exception) {
                Log.d(TAG, "Active RTSP connection to $hostIp:$targetPort not needed: ${e.message}")
            }
        }
    }

    private fun handleClient(socket: Socket, peerIp: String) {
        thread(name = "MiracastClientHandler") {
            try {
                // Reset session state for each new client
                sourceSessionId = null
                isStreamingActive = false
                pendingSetupCSeq = -1
                pendingPlayCSeq = -1
                sinkCSeq.set(1)

                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val out = socket.getOutputStream()

                while (isRunning && socket.isConnected && !socket.isClosed) {
                    val requestLine = reader.readLine() ?: break
                    if (requestLine.isBlank()) continue

                    val headers = mutableMapOf<String, String>()
                    var line = reader.readLine()
                    var contentLength = 0
                    while (!line.isNullOrBlank()) {
                        val colonIndex = line.indexOf(':')
                        if (colonIndex > 0) {
                            val key = line.substring(0, colonIndex).trim()
                            val value = line.substring(colonIndex + 1).trim()
                            headers[key.lowercase()] = value
                            if (key.equals("Content-Length", ignoreCase = true)) {
                                contentLength = value.toIntOrNull() ?: 0
                            }
                        }
                        line = reader.readLine()
                    }

                    val body = if (contentLength > 0) {
                        val buffer = CharArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val count = reader.read(buffer, read, contentLength - read)
                            if (count <= 0) break
                            read += count
                        }
                        String(buffer, 0, read)
                    } else {
                        ""
                    }

                    val cseq = headers["cseq"] ?: "1"

                    if (requestLine.startsWith("RTSP/1.0", ignoreCase = true)) {
                        // Incoming Response from Source
                        Log.d(TAG, "RTSP Response from Source: $requestLine (CSeq: $cseq)")
                        handleSourceResponse(out, requestLine, cseq, headers, body)
                    } else {
                        // Incoming Request from Source
                        Log.d(TAG, "RTSP Request from Source: $requestLine (CSeq: $cseq)")
                        val tokens = requestLine.split(" ")
                        val method = if (tokens.isNotEmpty()) tokens[0].uppercase() else ""
                        val uri = if (tokens.size > 1) tokens[1] else ""
                        handleSourceRequest(out, method, uri, cseq, headers, body)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "RTSP client connection error", e)
            } finally {
                activeClients.remove(socket)
                synchronized(this@MiracastRtspServer) {
                    if (clientSocket === socket) {
                        clientSocket = null
                    }
                    val hasOtherClient = activeClients.any { it !== socket && it.isConnected && !it.isClosed }
                    if (!hasOtherClient) {
                        isStreamingActive = false
                        isWindowsSource = false
                        onStreamStateChanged(false)
                    }
                }
                try {
                    socket.close()
                } catch (ignored: Exception) {}
            }
        }
    }

    private fun handleSourceRequest(
        out: OutputStream,
        method: String,
        uri: String,
        reqCSeq: String,
        headers: Map<String, String>,
        body: String
    ) {
        val ua = headers["user-agent"] ?: ""
        if (ua.contains("Windows", ignoreCase = true) || ua.contains("Microsoft", ignoreCase = true)) {
            isWindowsSource = true
        }
        when (method) {
            "OPTIONS" -> {
                // WFD M1: Source -> Sink. Sink replies 200 OK.
                val response = "RTSP/1.0 200 OK\r\n" +
                    "CSeq: $reqCSeq\r\n" +
                    "Public: org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER\r\n\r\n"
                sendData(out, response)
                Log.i(TAG, "Replied M1 (OPTIONS) 200 OK to Source")

                // WFD M2: Sink -> Source query. Sink immediately sends OPTIONS to Source.
                val nextCSeq = sinkCSeq.getAndIncrement()
                val m2Request = "OPTIONS * RTSP/1.0\r\n" +
                    "CSeq: $nextCSeq\r\n" +
                    "Require: org.wfa.wfd1.0\r\n\r\n"
                sendData(out, m2Request)
                Log.i(TAG, "Sent M2 (OPTIONS) query to Source (CSeq: $nextCSeq)")
            }

            "GET_PARAMETER" -> {
                // WFD M3: Source queries Sink capabilities, or M16 KeepAlive check
                if (body.isBlank()) {
                    // Empty body = KeepAlive ping
                    val response = if (sourceSessionId != null) {
                        "RTSP/1.0 200 OK\r\nCSeq: $reqCSeq\r\nSession: $sourceSessionId\r\n\r\n"
                    } else {
                        "RTSP/1.0 200 OK\r\nCSeq: $reqCSeq\r\n\r\n"
                    }
                    sendData(out, response)
                    Log.d(TAG, "Replied to KeepAlive GET_PARAMETER (CSeq: $reqCSeq)")
                } else {
                    // M3: Capabilities list based on quality tier (High / Medium / Low)
                    // CEA-861-E bitmask:
                    // bit 5: 1280x720p30 (0x0020), bit 6: 1280x720p60 (0x0040) -> 0x0060 (720p 16:9)
                    // bit 7: 1920x1080p30 (0x0080), bit 8: 1920x1080p60 (0x0100) -> 0x0180 (1080p 16:9)
                    val quality = qualityProvider()
                    val wfdVideoFormats = when (quality) {
                        0 -> "wfd_video_formats: 00 00 02 10 00000180 00000000 00000000 00 0000 0000 00 none none\r\n" // 超清 1080p (High Profile Level 4.2, 1080p60/30, 严格16:9)
                        2 -> "wfd_video_formats: 00 00 01 02 00000060 00000000 00000000 00 0000 0000 00 none none\r\n" // 流畅 720p (Baseline Level 3.2, 720p60/30, 严格16:9)
                        else -> "wfd_video_formats: 00 00 02 10 000001e0 00000000 00000000 00 0000 0000 00 none none\r\n" // 均衡 720p/1080p (High Profile, 1080p/720p, 严格16:9)
                    }
                    Log.i(TAG, "M3 generating format for quality tier $quality: $wfdVideoFormats")

                    // Always negotiate audio capability so Source Kirin encoder continuously transmits audio;
                    // local sink muting/unmuting is handled in real-time by MiracastAudioPlayer
                    val audioCodecs = "wfd_audio_codecs: LPCM 00000002 00, AAC 00000001 00\r\n"
                    Log.i(TAG, "M3 Sink audio capability: $audioCodecs (audioSinkEnabled: ${audioEnabledProvider()})")

                    val responseBody = StringBuilder().apply {
                        append(wfdVideoFormats)
                        append(audioCodecs)
                        append("wfd_client_rtp_ports: RTP/AVP/UDP;unicast $rtpPort 0 mode=play\r\n")
                        append("wfd_content_protection: none\r\n")
                        append("wfd_display_edid: none\r\n")
                        append("wfd_connector_type: 05\r\n")
                        append("wfd_standby_resume_capability: none\r\n")
                        append("wfd_uibc_capability: input_category_list=GENERIC, HIDC;generic_cap_list=Keyboard, Mouse, SingleTouch;hidc_cap_list=Keyboard/USB, Mouse/USB;port=none\r\n")
                    }.toString()

                    val response = "RTSP/1.0 200 OK\r\n" +
                        "CSeq: $reqCSeq\r\n" +
                        "Content-Type: text/parameters\r\n" +
                        "Content-Length: ${responseBody.toByteArray(Charsets.UTF_8).size}\r\n\r\n" +
                        responseBody
                    sendData(out, response)
                    Log.i(TAG, "Replied M3 (GET_PARAMETER) with Sink capabilities (CSeq: $reqCSeq)")
                }
            }

            "SET_PARAMETER" -> {
                Log.i(TAG, "Source SET_PARAMETER body:\n$body")
                // Check if Source is setting presentation URL (M4)
                if (body.contains("wfd_presentation_url", ignoreCase = true)) {
                    val match = Regex("""wfd_presentation_url:\s*([^\s\r\n]+)""", RegexOption.IGNORE_CASE).find(body)
                    if (match != null) {
                        val parsedUrl = match.groupValues[1].trim()
                        if (parsedUrl.startsWith("rtsp://")) {
                            currentPresentationUrl = parsedUrl
                            Log.i(TAG, "Extracted presentation URL from M4: $currentPresentationUrl")
                        }
                    }
                }

                // Check if Source is configuring UIBC (e.g. Huawei PC Mode)
                if (body.contains("wfd_uibc_capability", ignoreCase = true)) {
                    val portMatch = Regex("""port\s*=\s*(\d+)""", RegexOption.IGNORE_CASE).find(body)
                    if (portMatch != null) {
                        val uPort = portMatch.groupValues[1].toIntOrNull()
                        if (uPort != null && uPort > 0) {
                            pendingUibcPort = uPort
                            val categoryMatch = Regex("""input_category_list\s*=\s*([^;\r\n]+)""", RegexOption.IGNORE_CASE).find(body)
                            val categories = categoryMatch?.groupValues?.get(1)
                                ?.split(',', ';')
                                ?.map { it.trim().uppercase() }
                                ?: emptyList()
                            if (body.contains("SingleTouch", ignoreCase = true)) {
                                isWindowsSource = true
                            }
                            // 默认严格按 input_category_list 选择；Windows 优先走 GENERIC SingleTouch
                            pendingUibcIsHidc = if (isWindowsSource && categories.contains("GENERIC")) {
                                false
                            } else {
                                !UibcExperiment.FORCE_GENERIC &&
                                    categories.contains("HIDC") && !categories.contains("GENERIC")
                            }
                            Log.i(TAG, "Source negotiated UIBC port: $uPort, categories=$categories, isHidc=$pendingUibcIsHidc, isWindows=$isWindowsSource")
                        }
                    }
                }

                // WFD M4/M14 要求 Sink 在响应中原样回显 uibc 参数，华为据此确认 UIBC 已建立
                val uibcEchoBody = body.lineSequence()
                    .map { it.trim() }
                    .filter {
                        it.startsWith("wfd_uibc_capability", ignoreCase = true) ||
                            it.startsWith("wfd_uibc_setting", ignoreCase = true)
                    }
                    .joinToString("\r\n", postfix = "\r\n")

                val response = if (uibcEchoBody.isNotBlank()) {
                    "RTSP/1.0 200 OK\r\n" +
                        "CSeq: $reqCSeq\r\n" +
                        "Content-Type: text/parameters\r\n" +
                        "Content-Length: ${uibcEchoBody.toByteArray(Charsets.UTF_8).size}\r\n\r\n" +
                        uibcEchoBody
                } else {
                    "RTSP/1.0 200 OK\r\nCSeq: $reqCSeq\r\n\r\n"
                }
                sendData(out, response)
                Log.i(TAG, "Replied SET_PARAMETER 200 OK (CSeq: $reqCSeq, uibcEcho=${uibcEchoBody.isNotBlank()})")

                // If streaming is already active (e.g. mode switch to Huawei PC desktop while mirroring), launch UIBC immediately
                if (isStreamingActive && pendingUibcPort > 0) {
                    triggerPendingUibc(out)
                }

                // Check if this is M5 (wfd_trigger_method: SETUP / PLAY / PAUSE / TEARDOWN)
                if (body.contains("wfd_trigger_method: SETUP", ignoreCase = true)) {
                    Log.i(TAG, "Source sent M5 trigger SETUP! Now sending M6 (SETUP) to Source at $currentPresentationUrl")
                    val nextCSeq = sinkCSeq.getAndIncrement()
                    pendingSetupCSeq = nextCSeq
                    val setupRequest = "SETUP $currentPresentationUrl RTSP/1.0\r\n" +
                        "CSeq: $nextCSeq\r\n" +
                        "Transport: RTP/AVP/UDP;unicast;client_port=$rtpPort-${rtpPort + 1}\r\n\r\n"
                    sendData(out, setupRequest)
                    Log.i(TAG, "Sent M6 (SETUP) to Source (CSeq: $nextCSeq, port: $rtpPort)")
                } else if (body.contains("wfd_trigger_method: PLAY", ignoreCase = true)) {
                    Log.i(TAG, "Source sent M5 trigger PLAY! (Mode switch / resume) Sending PLAY to Source")
                    val sid = sourceSessionId ?: DEFAULT_SESSION_ID
                    val nextCSeq = sinkCSeq.getAndIncrement()
                    pendingPlayCSeq = nextCSeq
                    val playRequest = "PLAY $currentPresentationUrl RTSP/1.0\r\n" +
                        "CSeq: $nextCSeq\r\n" +
                        "Session: $sid\r\n\r\n"
                    sendData(out, playRequest)
                    Log.i(TAG, "Sent PLAY to Source (CSeq: $nextCSeq, Session: $sid)")
                    onStreamStateChanged(true)
                } else if (body.contains("wfd_trigger_method: PAUSE", ignoreCase = true)) {
                    Log.i(TAG, "Source sent M5 trigger PAUSE! (Mode switch) Sending PAUSE to Source")
                    val sid = sourceSessionId ?: DEFAULT_SESSION_ID
                    val nextCSeq = sinkCSeq.getAndIncrement()
                    val pauseRequest = "PAUSE $currentPresentationUrl RTSP/1.0\r\n" +
                        "CSeq: $nextCSeq\r\n" +
                        "Session: $sid\r\n\r\n"
                    sendData(out, pauseRequest)
                    Log.i(TAG, "Sent PAUSE to Source (CSeq: $nextCSeq, Session: $sid)")
                } else if (body.contains("wfd_trigger_method: TEARDOWN", ignoreCase = true)) {
                    Log.i(TAG, "Source requested TEARDOWN trigger")
                    val nextCSeq = sinkCSeq.getAndIncrement()
                    val teardownReq = "TEARDOWN $currentPresentationUrl RTSP/1.0\r\n" +
                        "CSeq: $nextCSeq\r\n" +
                        (if (sourceSessionId != null) "Session: $sourceSessionId\r\n\r\n" else "\r\n")
                    sendData(out, teardownReq)
                    isStreamingActive = false
                    onStreamStateChanged(false)
                }
            }

            "SETUP" -> {
                val response = "RTSP/1.0 200 OK\r\n" +
                    "CSeq: $reqCSeq\r\n" +
                    "Session: $DEFAULT_SESSION_ID;timeout=60\r\n" +
                    "Transport: RTP/AVP/UDP;unicast;client_port=$rtpPort-${rtpPort + 1}\r\n\r\n"
                sendData(out, response)
                sourceSessionId = DEFAULT_SESSION_ID
                Log.i(TAG, "Replied SETUP 200 OK (CSeq: $reqCSeq)")
            }

            "PLAY" -> {
                val sid = sourceSessionId ?: DEFAULT_SESSION_ID
                val response = "RTSP/1.0 200 OK\r\n" +
                    "CSeq: $reqCSeq\r\n" +
                    "Session: $sid\r\n\r\n"
                sendData(out, response)
                Log.i(TAG, "Replied PLAY 200 OK (CSeq: $reqCSeq) - Video streaming starting!")
                isStreamingActive = true
                onStreamStateChanged(true)
                if (pendingUibcPort > 0 && !uibcLaunchInProgress && !uibcEnableConfirmed) {
                    triggerPendingUibc(out)
                }
            }

            "PAUSE" -> {
                val sid = sourceSessionId ?: DEFAULT_SESSION_ID
                val response = "RTSP/1.0 200 OK\r\n" +
                    "CSeq: $reqCSeq\r\n" +
                    "Session: $sid\r\n\r\n"
                sendData(out, response)
                Log.i(TAG, "Replied PAUSE 200 OK (CSeq: $reqCSeq, Session: $sid)")
            }

            "TEARDOWN" -> {
                val response = "RTSP/1.0 200 OK\r\n" +
                    "CSeq: $reqCSeq\r\n\r\n"
                sendData(out, response)
                Log.i(TAG, "Replied TEARDOWN 200 OK")
                isStreamingActive = false
                onStreamStateChanged(false)
            }

            else -> {
                val response = "RTSP/1.0 200 OK\r\nCSeq: $reqCSeq\r\n\r\n"
                sendData(out, response)
            }
        }
    }

    private fun handleSourceResponse(
        out: OutputStream,
        statusLine: String,
        cseqStr: String,
        headers: Map<String, String>,
        body: String
    ) {
        if (!statusLine.contains("200 OK")) {
            Log.w(TAG, "Source responded with non-200 status: $statusLine")
            return
        }

        val cseq = cseqStr.toIntOrNull() ?: -1

        if (pendingUibcEnableCSeq != -1 && cseq == pendingUibcEnableCSeq) {
            pendingUibcEnableCSeq = -1
            uibcEnableConfirmed = true
            Log.i(TAG, "Source confirmed UIBC enable (CSeq: $cseq)")
            return
        }

        // 1. Response to M6 (SETUP): Capture Session ID and trigger M7 (PLAY)
        if (pendingSetupCSeq != -1 && (cseq == pendingSetupCSeq || headers.containsKey("session"))) {
            pendingSetupCSeq = -1
            val sessionHeader = headers["session"]
            if (!sessionHeader.isNullOrBlank()) {
                val sid = sessionHeader.split(";")[0].trim()
                if (sid.isNotEmpty()) {
                    sourceSessionId = sid
                    Log.i(TAG, "Obtained Session ID from M6 SETUP response: $sourceSessionId")
                }
            }

            val sid = sourceSessionId ?: DEFAULT_SESSION_ID
            val nextCSeq = sinkCSeq.getAndIncrement()
            pendingPlayCSeq = nextCSeq
            val playRequest = "PLAY $currentPresentationUrl RTSP/1.0\r\n" +
                "CSeq: $nextCSeq\r\n" +
                "Session: $sid\r\n\r\n"
            sendData(out, playRequest)
            Log.i(TAG, "Sent M7 (PLAY) to Source (CSeq: $nextCSeq, Session: $sid)")
            return
        }

        // 2. Response to M7 (PLAY): Streaming is confirmed active
        if (pendingPlayCSeq != -1 && cseq == pendingPlayCSeq) {
            pendingPlayCSeq = -1
            isStreamingActive = true
            Log.i(TAG, "Source confirmed M7 (PLAY) 200 OK! Video streaming is active!")
            onStreamStateChanged(true)
            triggerPendingUibc(out)
            return
        }

        // 3. Response to M2 (OPTIONS): Source supported methods acknowledged
        Log.d(TAG, "Source confirmed M2 (OPTIONS) 200 OK")
    }

    private fun triggerPendingUibc(out: OutputStream? = null) {
        val port = pendingUibcPort
        if (port > 0 && !uibcLaunchInProgress) {
            uibcLaunchInProgress = true
            // 永远优先使用真实 TCP 对端地址；只有完全拿不到连接时才回退到记录值
            val host = (clientSocket ?: activeClients.firstOrNull { it.isConnected && !it.isClosed })
                ?.inetAddress?.hostAddress
                ?: currentSourceHost
            val isHidc = pendingUibcIsHidc
            val sid = sourceSessionId ?: DEFAULT_SESSION_ID
            val url = currentPresentationUrl
            val oStream = out ?: clientSocket?.getOutputStream()

            thread(name = "UibcDelayedLaunchThread") {
                try {
                    // Send RTSP SET_PARAMETER wfd_uibc_setting: enable to tell Source's UIBC server to bind and listen
                    if (oStream != null && url.isNotBlank()) {
                        val nextCSeq = sinkCSeq.getAndIncrement()
                        pendingUibcEnableCSeq = nextCSeq
                        uibcEnableConfirmed = false
                        val uibcSettingBody = "wfd_uibc_setting: enable\r\n"
                        val bodyBytes = uibcSettingBody.toByteArray(Charsets.UTF_8)
                        val req = "SET_PARAMETER $url RTSP/1.0\r\n" +
                            "CSeq: $nextCSeq\r\n" +
                            "Session: $sid\r\n" +
                            "Content-Type: text/parameters\r\n" +
                            "Content-Length: ${bodyBytes.size}\r\n\r\n" +
                            uibcSettingBody
                        sendData(oStream, req)
                        Log.i(TAG, "Sent SET_PARAMETER wfd_uibc_setting: enable to Source (CSeq: $nextCSeq)")
                        val deadline = System.currentTimeMillis() + 2000L
                        while (!uibcEnableConfirmed && System.currentTimeMillis() < deadline) {
                            Thread.sleep(20)
                        }
                        Log.i(TAG, "UIBC enable confirmed=$uibcEnableConfirmed")
                    }
                } catch (ignored: Exception) {
                    uibcLaunchInProgress = false
                    return@thread
                }
                uibcLaunchInProgress = false
                if (isStreamingActive && isRunning) {
                    Log.i(TAG, "Triggering UIBC connection to $host:$port (isHidc=$isHidc)")
                    onUibcRequested?.invoke(host, port, isHidc)
                }
            }
        }
    }

    /**
     * Some Huawei sources close the UIBC listening socket after a while.
     * Re-send M15 so the source rebinds the same UIBC port without restarting casting.
     */
    fun resendUibcSetting() {
        val now = System.currentTimeMillis()
        if (now - lastUibcReenableMs < 1500L) return
        lastUibcReenableMs = now

        val body = "wfd_uibc_setting: enable\r\n"
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val sid = sourceSessionId ?: DEFAULT_SESSION_ID
        val url = currentPresentationUrl
        val nextCSeq = sinkCSeq.getAndIncrement()
        pendingUibcEnableCSeq = nextCSeq
        uibcEnableConfirmed = false
        val req = "SET_PARAMETER $url RTSP/1.0\r\n" +
            "CSeq: $nextCSeq\r\n" +
            "Session: $sid\r\n" +
            "Content-Type: text/parameters\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n\r\n" +
            body

        synchronized(this) {
            activeClients.forEach { socket ->
                try {
                    if (socket.isConnected && !socket.isClosed) {
                        sendData(socket.getOutputStream(), req)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to re-enable UIBC on ${socket.inetAddress}: ${e.message}")
                }
            }
        }
        Log.i(TAG, "Re-sent SET_PARAMETER wfd_uibc_setting: enable to Source")
    }

    fun triggerTeardown() {
        thread(name = "MiracastTeardownThread") {
            val sockets = synchronized(this@MiracastRtspServer) {
                activeClients.toList()
            }
            try {
                val sid = sourceSessionId ?: DEFAULT_SESSION_ID
                val nextCSeq = sinkCSeq.getAndIncrement()
                val teardownReq = "TEARDOWN $currentPresentationUrl RTSP/1.0\r\n" +
                    "CSeq: $nextCSeq\r\n" +
                    "Session: $sid\r\n\r\n"
                sockets.forEach { socket ->
                    try {
                        sendData(socket.getOutputStream(), teardownReq)
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to send TEARDOWN to ${socket.inetAddress}: ${e.message}")
                    }
                }
                Log.i(TAG, "Sent TEARDOWN to ${sockets.size} Source connection(s) at $currentPresentationUrl (Session: $sid)")
                Thread.sleep(150)
            } catch (e: Exception) {
                Log.d(TAG, "Error sending TEARDOWN: ${e.message}")
            }
            synchronized(this@MiracastRtspServer) {
                sockets.forEach { socket ->
                    try {
                        socket.close()
                    } catch (ignored: Exception) {}
                }
                activeClients.clear()
                clientSocket = null
                isStreamingActive = false
                pendingUibcPort = -1
                pendingUibcIsHidc = false
            }
            onStreamStateChanged(false)
        }
    }

    private fun sendData(out: OutputStream, text: String) {
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    fun stopServer() {
        isRunning = false
        isStreamingActive = false
        closeServer()
        interrupt()
    }

    private fun closeServer() {
        try {
            synchronized(this) {
                activeClients.forEach { socket ->
                    try {
                        socket.close()
                    } catch (ignored: Exception) {}
                }
                activeClients.clear()
                clientSocket = null
            }
            serverSocket?.close()
            serverSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing RTSP server", e)
        }
    }
}
