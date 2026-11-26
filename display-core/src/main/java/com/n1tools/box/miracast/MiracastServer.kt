package com.n1tools.box.miracast

import android.content.Context
import android.util.Log
import com.n1tools.box.airplay.player.VideoPlayer
import com.n1tools.box.miracast.p2p.MiracastP2pManager
import com.n1tools.box.miracast.player.MiracastAudioPlayer
import com.n1tools.box.miracast.rtp.MpegTsReceiver
import com.n1tools.box.miracast.rtsp.MiracastRtspServer

class MiracastServer(
    private val context: Context,
    private val deviceName: String = "Free Display",
    private val videoPlayerProvider: () -> VideoPlayer?,
    private val qualityProvider: () -> Int = { 0 },
    private val audioEnabledProvider: () -> Boolean = { false },
    private val isVideoAllowedProvider: () -> Boolean = { true },
    private val onDeviceNameDetected: ((String) -> Unit)? = null,
    private val onConnectionStateChanged: (Boolean) -> Unit,
    private val onFirstFrameReceived: (() -> Unit)? = null,
    private val onSourceHostDetected: ((String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "MiracastServer"
        private const val RTSP_PORT = 7236
        private const val RTP_PORT = 19000
    }

    private var p2pManager: MiracastP2pManager? = null
    private var rtspServer: MiracastRtspServer? = null
    private var tsReceiver: MpegTsReceiver? = null
    private var audioPlayer: MiracastAudioPlayer? = null
    private var uibcClient: com.n1tools.box.miracast.uibc.UibcClient? = null

    @Volatile
    private var isRunning = false

    fun sendTouchEvent(action: Int, xPixel: Float, yPixel: Float, viewWidth: Int, viewHeight: Int) {
        uibcClient?.sendTouchEvent(action, xPixel, yPixel, viewWidth, viewHeight)
    }

    fun sendTouchEvent(
        action: Int,
        xPixel: Float,
        yPixel: Float,
        viewWidth: Int,
        viewHeight: Int,
        contentWidth: Int,
        contentHeight: Int
    ) {
        uibcClient?.sendTouchEvent(action, xPixel, yPixel, viewWidth, viewHeight, contentWidth, contentHeight)
    }

    fun sendTouchEvent(action: Int, xNorm: Int, yNorm: Int) {
        uibcClient?.sendTouchEvent(action, xNorm, yNorm)
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "Starting Miracast Server...")

        // 1. Start RTSP M1~M7 State Machine Server
        rtspServer = MiracastRtspServer(RTSP_PORT, RTP_PORT, qualityProvider, audioEnabledProvider) { isStreaming ->
            Log.i(TAG, "Miracast Streaming status: $isStreaming")
            if (isStreaming) {
                tsReceiver?.resetFirstFrameState()
            } else {
                uibcClient?.stopClient()
                uibcClient = null
            }
            onConnectionStateChanged(isStreaming)
        }.apply {
            onUibcRequested = { sourceHost, uibcPort, isHidc ->
                onSourceHostDetected?.invoke(sourceHost)
                val winSource = rtspServer?.isWindowsSource == true
                if (uibcClient?.isConnected == true && uibcClient?.matches(sourceHost, uibcPort, isHidc, winSource) == true) {
                    Log.i(TAG, "UIBC client is already active and connected to $sourceHost:$uibcPort")
                } else {
                    Log.i(TAG, "Starting UIBC client connection to $sourceHost:$uibcPort (isHidc=$isHidc, win=$winSource)")
                    val oldClient = uibcClient
                    oldClient?.stopClient()
                    try {
                        oldClient?.join(500)
                    } catch (ignored: InterruptedException) {}
                    uibcClient = com.n1tools.box.miracast.uibc.UibcClient(
                        host = sourceHost,
                        port = uibcPort,
                        isHidc = isHidc,
                        useSingleTouch = winSource,
                        allowMove = winSource
                    ).apply {
                        onConnectionReady = {
                            onUibcStateChanged?.invoke()
                        }
                        onConnectionLost = {
                            Log.i(TAG, "UIBC connection lost, asking Source to re-enable UIBC port")
                            onUibcStateChanged?.invoke()
                            rtspServer?.resendUibcSetting()
                        }
                        start()
                    }
                }
            }
            start()
        }

        // 2. Start RTP UDP TS demuxer with lazy / dynamic audio player provider
        tsReceiver = MpegTsReceiver(
            rtpPort = RTP_PORT,
            videoPlayerProvider = videoPlayerProvider,
            audioPlayerProvider = { getAudioPlayer() },
            isAudioEnabled = audioEnabledProvider,
            isVideoAllowed = isVideoAllowedProvider,
            onFirstFrame = onFirstFrameReceived
        ).apply {
            start()
        }

        // 3. Start Wi-Fi Direct P2P Advertisement
        p2pManager = MiracastP2pManager(
            context = context,
            deviceName = deviceName,
            onConnected = { hostIp ->
                Log.i(TAG, "Wi-Fi Direct Connected, host IP: $hostIp")
                if (hostIp.isNotEmpty()) {
                    onSourceHostDetected?.invoke(hostIp)
                    rtspServer?.connectToSource(hostIp, 7236)
                }
            },
            onDisconnected = {
                Log.i(TAG, "Wi-Fi Direct Disconnected")
                onConnectionStateChanged(false)
            },
            onDeviceNameDetected = onDeviceNameDetected
        ).apply {
            start()
        }

        Log.i(TAG, "Miracast Server ready! Listening on RTSP $RTSP_PORT, RTP $RTP_PORT")
    }

    @Synchronized
    private fun getAudioPlayer(): MiracastAudioPlayer? {
        if (audioPlayer == null) {
            try {
                audioPlayer = MiracastAudioPlayer().apply {
                    isAudioEnabled = audioEnabledProvider()
                    start()
                }
                Log.i(TAG, "MiracastAudioPlayer lazy initialized (enabled=${audioEnabledProvider()})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to lazy init MiracastAudioPlayer", e)
            }
        }
        return audioPlayer
    }

    @Synchronized
    fun setAudioEnabled(enabled: Boolean) {
        val player = getAudioPlayer()
        player?.isAudioEnabled = enabled
        Log.i(TAG, "Miracast real-time audio state toggled: enabled=$enabled")
    }

    fun triggerTeardown() {
        Log.i(TAG, "Triggering Miracast Teardown (disconnect from Source)...")
        uibcClient?.stopClient()
        uibcClient = null
        rtspServer?.triggerTeardown()
    }

    /**
     * 彻底终止当前 Miracast 会话，避免“切换后旧投屏仍在后台推流”。
     * - 停 UIBC 触控回传
     * - 发 RTSP TEARDOWN 并关闭控制 socket
     * - 在 suppressMs 时间窗内丢弃旧 UDP 流
     * - 断开 WFD P2P 组，迫使源端退出投屏
     */
    fun terminateCurrentSession(suppressMs: Long = 0L) {
        Log.i(TAG, "Terminating current Miracast session (suppress=${suppressMs}ms)")
        uibcClient?.stopClient()
        uibcClient = null
        tsReceiver?.suspend(suppressMs.coerceAtLeast(0L))
        rtspServer?.triggerTeardown()
        p2pManager?.disconnectGroup()
    }

    var onUibcStateChanged: (() -> Unit)? = null

    val isWindowsSource: Boolean
        get() = rtspServer?.isWindowsSource == true

    val isUibcConnected: Boolean
        get() = uibcClient?.isConnected == true

    fun reconnectUibc() {
        rtspServer?.resendUibcSetting()
    }

    fun ensureDiscoverable() {
        p2pManager?.ensureDiscoverable()
    }

    fun updateDeviceName(newName: String) {
        Log.i(TAG, "Updating Miracast P2P Device Name to: $newName")
        p2pManager?.setDeviceName(newName)
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        Log.i(TAG, "Stopping Miracast Server...")

        p2pManager?.stop()
        p2pManager = null

        rtspServer?.stopServer()
        rtspServer = null

        uibcClient?.stopClient()
        uibcClient = null

        audioPlayer?.stopPlayer()
        audioPlayer = null

        tsReceiver?.stopReceiver()
        tsReceiver = null

        Log.i(TAG, "Miracast Server stopped")
    }
}
