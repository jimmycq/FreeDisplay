package com.n1tools.box.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.github.serezhka.jap2lib.rtsp.AudioStreamInfo
import com.github.serezhka.jap2lib.rtsp.VideoStreamInfo
import com.github.serezhka.jap2server.AirPlayServer
import com.github.serezhka.jap2server.AirplayDataConsumer
import com.n1tools.display.R
import com.n1tools.box.airplay.model.NALPacket
import com.n1tools.box.airplay.model.PCMPacket
import com.n1tools.box.airplay.player.AudioPlayer
import com.n1tools.box.airplay.player.VideoPlayer
import com.n1tools.box.airplay.view.PlaySurfaceView
import com.n1tools.box.control.AdbInputController
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.LinkedList
import kotlin.concurrent.thread

class AirPlayDisplayActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "AirPlayDisplay"
        private const val LONG_PRESS_TIMEOUT_MS = 500L
        private const val LONG_PRESS_SLOP_PX = 24f
        private const val RC_PERMISSIONS = 1001
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var currentInstance: AirPlayDisplayActivity? = null
            private set
    }

    private lateinit var mSurfaceView: PlaySurfaceView
    private lateinit var mWaitingOverlay: LinearLayout
    private lateinit var mTvAirPlayStatus: TextView
    private lateinit var mTvNetworkInfo: TextView
    private lateinit var mBtnExit: TextView
    private lateinit var mBtnQualityMode: TextView
    private lateinit var mBtnAudioMode: TextView
    private lateinit var mBtnScaleMode: TextView
    private lateinit var mBtnRotate: TextView
    private lateinit var mBtnCleanStandby: TextView
    private lateinit var mBtnHotspot: TextView
    private var isCleanStandby: Boolean = false
    private lateinit var mBtnShare: TextView
    private lateinit var mBtnAbout: TextView
    private lateinit var mBtnReverseTouch: TextView
    private lateinit var mBtnMoreControls: TextView
    private lateinit var mLayoutMorePanel: View
    private lateinit var mBtnTouchSettings: TextView
    private var adbPort: Int = 5555
    private lateinit var mTvDisplayTitle: TextView
    private lateinit var mTvInstructionGuide: TextView
    private lateinit var layoutControlCard: HorizontalScrollView
    private lateinit var btnOpenDrawer: View
    private lateinit var layoutSwitchDeviceOverlay: View
    private lateinit var tvSwitchTitle: TextView
    private lateinit var tvSwitchDesc: TextView
    private lateinit var btnKeepCurrentDevice: TextView
    private lateinit var btnSwitchToNewDevice: TextView

    private val hideControlsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideControlsRunnable = Runnable {
        collapseControlsToDrawer()
    }

    private var currentQualityMode = 0 // 0: 超清(1080p), 1: 均衡(720p/1080p), 2: 流畅(极速)
    private var isAudioSinkEnabled = false // false: 设备端自身发声(声明不支持音频), true: 副屏发声(启用AAC解码器)

    private var airPlayServer: AirPlayServer? = null
    private var miracastServer: com.n1tools.box.miracast.MiracastServer? = null
    private var mVideoPlayer: VideoPlayer? = null
    private var mAudioPlayer: AudioPlayer? = null
    private val mVideoCacheList = LinkedList<NALPacket>()
    @Volatile
    private var cachedCodecConfig: NALPacket? = null
    @Volatile
    private var cachedIdrKeyframe: NALPacket? = null
    private val mMacPendingGop = LinkedList<NALPacket>()
    @Volatile
    private var macPendingGopHasIdr = false
    private var macVideoWidth = 1920
    private var macVideoHeight = 1080

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var aboutDialog: android.app.AlertDialog? = null
    private var activeDialogCount: Int = 0

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (aboutDialog?.isShowing == true) {
            aboutDialog?.dismiss()
            showAboutDialog()
        }
    }
    private val bgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var hasReceivedFrame = false
    private var isAntiInterferenceEnabled = true
    private var hasDisconnectedWifi = false
    private var currentScaleMode = PlaySurfaceView.SCALE_FIT
    private var currentOrientationMode = 0 // 0: SENSOR, 1: LANDSCAPE, 2: PORTRAIT

    enum class DisplaySource {
        NONE,
        MAC,
        HUAWEI
    }

    @Volatile
    private var activeSource = DisplaySource.NONE
    @Volatile
    private var huaweiSourceHost: String? = null
    @Volatile
    private var adbInputController: AdbInputController? = null
    private var adbDownX = 0f
    private var adbDownY = 0f
    private var adbDownTime = 0L
    private var adbDownSourceX = 0
    private var adbDownSourceY = 0
    private var adbLongPressHandled = false
    private val adbLongPressRunnable = Runnable {
        if (!adbLongPressHandled) {
            adbLongPressHandled = true
            Log.i(TAG, "ADB long press -> double tap: ($adbDownSourceX,$adbDownSourceY)")
            adbInputController?.longPressDoubleTap(adbDownSourceX, adbDownSourceY)
        }
    }
    private var macDeviceName = "电脑投屏"
    private var huaweiDeviceName = "移动设备"
    private var displayDeviceName = "Free Display"
    private var isSwitchOverlayShowing = false
    @Volatile
    private var switchOverlayGeneration = 0
    @Volatile
    private var lastSwitchDecisionTimeMs = 0L

    // 冲突处理时间窗：在窗口内彻底拒绝新请求方，避免“保持当前”时新投屏仍在后台连接/推流
    @Volatile
    private var macRejectedUntilMs = 0L
    @Volatile
    private var macSessionGeneration = 0
    @Volatile
    private var huaweiRejectedUntilMs = 0L
    @Volatile
    private var lastMacRejectDisconnectMs = 0L

    private val uiUpdateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val networkInfoUpdater = object : Runnable {
        override fun run() {
            if (isRunning) {
                bgExecutor.execute {
                    val info = getNetworkInfoString()
                    if (isRunning) {
                        runOnUiThread {
                            if (isRunning) {
                                mTvNetworkInfo.text = info
                            }
                        }
                    }
                }
                uiUpdateHandler.postDelayed(this, 3000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate instance=${System.identityHashCode(this)}")
        isRunning = true
        currentInstance = this

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()

        // 自动终止另一个可能在后台占用 7000/7100/7236 端口的兄弟应用并关闭系统发送端，杜绝端口与 WFD 争抢
        thread(name = "kill-conflicting-display-apps") {
            val peerPkg = if (packageName == "com.freedisplay.app") "com.n1tools.box" else "com.freedisplay.app"
            try {
                val p = Runtime.getRuntime().exec("su")
                val os = java.io.DataOutputStream(p.outputStream)
                os.writeBytes("am force-stop $peerPkg\nsettings put global wifi_display_on 0\nexit\n")
                os.flush()
                p.waitFor()
            } catch (ignored: Throwable) {}
        }

        setContentView(R.layout.activity_airplay_display)

        mSurfaceView = findViewById(R.id.surfaceView)
        layoutControlCard = findViewById(R.id.layoutControlCard)
        layoutControlCard.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_MOVE) {
                scheduleAutoHideControls(5000L)
            }
            false
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            layoutControlCard.setOnScrollChangeListener { _, _, _, _, _ ->
                scheduleAutoHideControls(5000L)
            }
        }
        btnOpenDrawer = findViewById(R.id.btnOpenDrawer)
        btnOpenDrawer.setOnClickListener {
            expandControlsFromDrawer()
        }

        layoutSwitchDeviceOverlay = findViewById(R.id.layoutSwitchDeviceOverlay)
        tvSwitchTitle = findViewById(R.id.tvSwitchTitle)
        tvSwitchDesc = findViewById(R.id.tvSwitchDesc)
        btnKeepCurrentDevice = findViewById(R.id.btnKeepCurrentDevice)
        btnSwitchToNewDevice = findViewById(R.id.btnSwitchToNewDevice)

        mWaitingOverlay = findViewById(R.id.waitingOverlay)
        mWaitingOverlay.setOnClickListener {
            // 轻触卡片直接隐藏，避免任何情况下卡片遮挡画面
            mWaitingOverlay.visibility = View.GONE
            scheduleAutoHideControls(5000L)
        }
        mTvAirPlayStatus = findViewById(R.id.tvAirPlayStatus)
        mTvNetworkInfo = findViewById(R.id.tvNetworkInfo)
        mBtnExit = findViewById(R.id.btnExitAirPlay)
        mBtnQualityMode = findViewById(R.id.btnQualityMode)
        mBtnAudioMode = findViewById(R.id.btnAudioMode)
        mBtnScaleMode = findViewById(R.id.btnScaleMode)
        mBtnRotate = findViewById(R.id.btnRotate)
        mBtnCleanStandby = findViewById(R.id.btnCleanStandby)
        mBtnHotspot = findViewById(R.id.btnHotspot)
        mBtnShare = findViewById(R.id.btnShare)
        mBtnAbout = findViewById(R.id.btnAbout)
        mBtnReverseTouch = findViewById(R.id.btnReverseTouch)
        mBtnMoreControls = findViewById(R.id.btnMoreControls)
        mLayoutMorePanel = findViewById(R.id.layoutMorePanel)
        mBtnTouchSettings = findViewById(R.id.btnTouchSettings)
        mTvDisplayTitle = findViewById(R.id.tvDisplayTitle)
        mTvInstructionGuide = findViewById(R.id.tvInstructionGuide)

        mSurfaceView.holder.addCallback(this)
        mBtnExit.setOnClickListener {
            scheduleAutoHideControls(10000L)
            showManagedDialog(
                com.n1tools.box.ui.glass.GlassUiHelper.createGlassMessageDialog(
                    context = this,
                    title = "退出副屏",
                    message = "确定要退出无线副屏投屏吗？",
                    positiveText = "退出",
                    onPositive = { finish() },
                    negativeText = "取消"
                )
            )
        }


        mBtnCleanStandby.setOnClickListener {
            scheduleAutoHideControls(5000L)
            isCleanStandby = !isCleanStandby
            getSharedPreferences("display_settings", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("clean_standby_enabled", isCleanStandby)
                .apply()
            updateCleanStandbyUi()
            val msg = if (isCleanStandby) {
                "已开启纯净待机（沉浸极简黑屏，轻触屏幕呼出控制栏，随时可投屏）"
            } else {
                "已退出纯净待机（显示连接指南与设备信息）"
            }
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        }

        mBtnHotspot.setOnClickListener {
            scheduleAutoHideControls(8000L)
            showHotspotGuideDialog()
        }

        mBtnShare.setOnClickListener {
            scheduleAutoHideControls(10000L)
            shareApk()
        }

        mBtnAbout.setOnClickListener {
            scheduleAutoHideControls(5000L)
            mLayoutMorePanel.visibility = View.GONE
            showAboutDialog()
        }

        mBtnMoreControls.setOnClickListener {
            val show = mLayoutMorePanel.visibility != View.VISIBLE
            mLayoutMorePanel.visibility = if (show) View.VISIBLE else View.GONE
            mBtnMoreControls.text = if (show) "▲ 收起" else "⋯ 更多"
            scheduleAutoHideControls(if (show) 9000L else 5000L)
        }

        mBtnReverseTouch.setOnClickListener {
            scheduleAutoHideControls(9000L)
            onReverseTouchButtonClick()
        }

        mBtnTouchSettings.setOnClickListener {
            scheduleAutoHideControls(9000L)
            mLayoutMorePanel.visibility = View.GONE
            mBtnMoreControls.text = "⋯ 更多"
            onReverseTouchButtonClick()
        }

        updateReverseTouchUi()

        // 绑定待机界面的控制与关于入口
        findViewById<TextView?>(R.id.btnStandbyRename)?.setOnClickListener {
            showRenameDialog()
        }
        findViewById<TextView?>(R.id.btnStandbyAbout)?.setOnClickListener {
            showAboutDialog()
        }
        findViewById<TextView?>(R.id.btnStandbyShare)?.setOnClickListener {
            shareApk()
        }
        findViewById<TextView?>(R.id.btnStandbyFeedback)?.setOnClickListener {
            openFeedbackUrl()
        }
        findViewById<TextView?>(R.id.btnFeedback)?.setOnClickListener {
            scheduleAutoHideControls(5000L)
            openFeedbackUrl()
        }

        // Apple Glass 轻触微缩回弹动效
        com.n1tools.box.ui.glass.GlassUiHelper.applyPressEffect(
            mBtnExit, mBtnAudioMode, mBtnScaleMode, mBtnRotate, mBtnQualityMode,
            mBtnReverseTouch, mBtnMoreControls, mBtnTouchSettings,
            mBtnCleanStandby, mBtnHotspot, mBtnShare, mBtnAbout,
            findViewById(R.id.btnOpenDrawer),
            findViewById(R.id.btnFeedback),
            findViewById(R.id.btnStandbyRename),
            findViewById(R.id.btnSwitchNetworkMode),
            findViewById(R.id.btnStandbyQrCode),
            findViewById(R.id.btnStandbyShare),
            findViewById(R.id.btnStandbyFeedback),
            findViewById(R.id.btnStandbyAbout),
            findViewById(R.id.btnKeepCurrentDevice),
            findViewById(R.id.btnSwitchToNewDevice)
        )

        // 启动后延迟 2.5 秒在后台静默检查 GitHub Releases 最新版本更新
        uiUpdateHandler.postDelayed({
            if (isRunning && !isFinishing) {
                com.n1tools.box.update.UpdateChecker.check(this, manual = false)
            }
        }, 2500L)

        val prefs = getSharedPreferences("display_settings", Context.MODE_PRIVATE)
        displayDeviceName = prefs.getString("free_display_name", "Free Display") ?: "Free Display"
        updateDeviceNameUi()
        isCleanStandby = prefs.getBoolean("clean_standby_enabled", false)
        updateCleanStandbyUi()
        currentScaleMode = prefs.getInt("video_scale_mode", PlaySurfaceView.SCALE_FIT)
        mSurfaceView.setScaleMode(currentScaleMode)
        currentQualityMode = prefs.getInt("video_quality", 0)
        isAudioSinkEnabled = prefs.getBoolean("audio_sink_enabled", false) // 默认让设备端保留声音，更符合副屏便携需求
        updateQualityModeUi()
        updateAudioModeUi()

        mBtnAudioMode.setOnClickListener {
            scheduleAutoHideControls(5000L)
            isAudioSinkEnabled = !isAudioSinkEnabled
            prefs.edit().putBoolean("audio_sink_enabled", isAudioSinkEnabled).apply()
            updateAudioModeUi()
            miracastServer?.setAudioEnabled(isAudioSinkEnabled)
            mAudioPlayer?.setAudioEnabled(isAudioSinkEnabled)
            val msg = if (isAudioSinkEnabled) {
                "已开启副屏发声 (音画同步低延迟)"
            } else {
                "已设为设备端发声 (副屏静音，省电低延迟)"
            }
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        }

        mBtnQualityMode.setOnClickListener {
            scheduleAutoHideControls(5000L)
            currentQualityMode = (currentQualityMode + 1) % 3
            prefs.edit().putInt("video_quality", currentQualityMode).apply()
            updateQualityModeUi()
            val desc = when (currentQualityMode) {
                0 -> "超清 1080p (最高码率与锐度)"
                1 -> "均衡 720p/1080p (兼顾清晰与流畅)"
                else -> "流畅 极速 (低延迟/弱网优先)"
            }
            android.widget.Toast.makeText(this, "已切换画质: $desc", android.widget.Toast.LENGTH_SHORT).show()
        }

        var lastScaleClickTimeMs = 0L
        mBtnScaleMode.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastScaleClickTimeMs < 250L) return@setOnClickListener
            lastScaleClickTimeMs = now
            scheduleAutoHideControls(5000L)
            currentScaleMode = when (currentScaleMode) {
                PlaySurfaceView.SCALE_FIT -> PlaySurfaceView.SCALE_ZOOM
                PlaySurfaceView.SCALE_ZOOM -> PlaySurfaceView.SCALE_FILL
                PlaySurfaceView.SCALE_FILL -> PlaySurfaceView.SCALE_ORIGINAL
                else -> PlaySurfaceView.SCALE_FIT
            }
            mSurfaceView.setScaleMode(currentScaleMode)
            prefs.edit().putInt("video_scale_mode", currentScaleMode).apply()
            updateScaleModeUi()
        }
        updateScaleModeUi()

        mBtnRotate.setOnClickListener {
            scheduleAutoHideControls(5000L)
            currentOrientationMode = (currentOrientationMode + 1) % 3
            when (currentOrientationMode) {
                0 -> {
                    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
                    mBtnRotate.text = "🔄 旋转: 自动"
                }
                1 -> {
                    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    mBtnRotate.text = "🔄 旋转: 横屏"
                }
                2 -> {
                    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    mBtnRotate.text = "🔄 旋转: 竖屏"
                }
            }
        }

        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if ((visibility and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                uiUpdateHandler.postDelayed({
                    if (isRunning && !isFinishing) {
                        applyImmersiveMode()
                    }
                }, 300)
            }
        }

        scheduleAutoHideControls(5000L)



        fun mapToSource(v: View, x: Float, y: Float): Pair<Int, Int> {
            val viewW = v.width.coerceAtLeast(1)
            val viewH = v.height.coerceAtLeast(1)
            val controller = adbInputController
            val sourceW = controller?.sourceWidth?.takeIf { it > 0 }
                ?: mSurfaceView.getVideoWidth().toInt().coerceAtLeast(1)
            val sourceH = controller?.sourceHeight?.takeIf { it > 0 }
                ?: mSurfaceView.getVideoHeight().toInt().coerceAtLeast(1)
            val sx = ((x / viewW) * sourceW).toInt().coerceIn(0, sourceW - 1)
            val sy = ((y / viewH) * sourceH).toInt().coerceIn(0, sourceH - 1)
            return sx to sy
        }

        mSurfaceView.setOnTouchListener { v, event ->
            if (mWaitingOverlay.visibility == View.VISIBLE && activeSource != DisplaySource.NONE) {
                mWaitingOverlay.visibility = View.GONE
            }
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                Log.i(TAG, "Touch down: source=$activeSource hasFrame=$hasReceivedFrame size=${v.width}x${v.height}")
            }
            if (hasReceivedFrame && activeSource == DisplaySource.HUAWEI) {
                if (miracastServer?.isWindowsSource == true) {
                    val viewWidth = v.width
                    val viewHeight = v.height
                    if (viewWidth > 0 && viewHeight > 0) {
                        miracastServer?.sendTouchEvent(
                            event.actionMasked, event.x, event.y, viewWidth, viewHeight,
                            mSurfaceView.getVideoWidth().toInt(), mSurfaceView.getVideoHeight().toInt()
                        )
                    }
                    return@setOnTouchListener true
                }
                var controller = adbInputController
                if (controller == null) {
                    val host = huaweiSourceHost ?: "192.168.49.1"
                    controller = attachAdbController(host, adbPort, forceReconnect = false)
                    Log.i(TAG, "Auto-connected ADB input controller on touch for $host:$adbPort")
                } else if (!controller.isReady) {
                    // 带 5 秒节流的断线自愈重连，解决授权晚点确认后永久失灵的问题
                    controller.ensureConnectedAsync(force = false)
                }
                if (controller.isReady) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            adbDownX = event.x
                            adbDownY = event.y
                            adbDownTime = System.currentTimeMillis()
                            val (sx, sy) = mapToSource(v, event.x, event.y)
                            adbDownSourceX = sx
                            adbDownSourceY = sy
                            adbLongPressHandled = false
                            uiUpdateHandler.removeCallbacks(adbLongPressRunnable)
                            uiUpdateHandler.postDelayed(adbLongPressRunnable, LONG_PRESS_TIMEOUT_MS)
                            Log.i(
                                TAG,
                                "ADB input basis: display=${controller.sourceDisplayId} " +
                                    "source=${controller.sourceWidth}x${controller.sourceHeight} " +
                                    "trackpad=${controller.trackpadWidth}x${controller.trackpadHeight} " +
                                    "view=${v.width}x${v.height}"
                            )
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!adbLongPressHandled) {
                                val moveDist = Math.hypot(
                                    (event.x - adbDownX).toDouble(),
                                    (event.y - adbDownY).toDouble()
                                ).toFloat()
                                if (moveDist > LONG_PRESS_SLOP_PX) {
                                    uiUpdateHandler.removeCallbacks(adbLongPressRunnable)
                                    val (sx, sy) = mapToSource(v, event.x, event.y)
                                    controller.mouseMove(sx, sy)
                                }
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            uiUpdateHandler.removeCallbacks(adbLongPressRunnable)
                            adbLongPressHandled = false
                        }
                        MotionEvent.ACTION_UP -> {
                            uiUpdateHandler.removeCallbacks(adbLongPressRunnable)
                            if (adbLongPressHandled) {
                                adbLongPressHandled = false
                                Log.i(TAG, "ADB long press double tap done")
                                return@setOnTouchListener true
                            }
                            val dist = Math.hypot((event.x - adbDownX).toDouble(), (event.y - adbDownY).toDouble())
                            val duration = System.currentTimeMillis() - adbDownTime
                            if (duration < 350 && dist < 24) {
                                val (sx, sy) = mapToSource(v, event.x, event.y)
                                Log.i(TAG, "ADB mouse tap: ($sx,$sy) display=${controller.sourceDisplayId}")
                                controller.mouseTap(sx, sy)
                            } else {
                                val (endSx, endSy) = mapToSource(v, event.x, event.y)
                                Log.i(TAG, "ADB drag: from ($adbDownSourceX,$adbDownSourceY) to ($endSx,$endSy) duration=$duration")
                                controller.mouseDrag(adbDownSourceX, adbDownSourceY, endSx, endSy, duration.toInt())
                            }
                        }
                    }
                } else {
                    val viewWidth = v.width
                    val viewHeight = v.height
                    if (viewWidth > 0 && viewHeight > 0) {
                        miracastServer?.sendTouchEvent(
                            event.actionMasked, event.x, event.y, viewWidth, viewHeight,
                            mSurfaceView.getVideoWidth().toInt(), mSurfaceView.getVideoHeight().toInt()
                        )
                    }
                }
            }
            true
        }

        uiUpdateHandler.post(networkInfoUpdater)
        acquireWifiLocks()
        thread(name = "cpu-perf-boost") {
            trySetPerformanceGovernor(true)
        }

        mAudioPlayer = AudioPlayer().apply {
            setAudioEnabled(isAudioSinkEnabled)
            start()
        }

        startAirPlayServer()
        checkAndPromptPermissions()
    }

    private fun checkAndPromptPermissions() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
            // Android 5.x 及以下系统在安装时授予所有权限，无需运行时动态申请
            return
        }

        val requiredPermissions = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (requiredPermissions.isEmpty()) {
            return
        }

        val prefs = getSharedPreferences("display_settings", Context.MODE_PRIVATE)
        val hasShownGuide = prefs.getBoolean("has_shown_permission_guide", false)

        showPermissionGuideDialog(requiredPermissions)
    }

    private fun showPermissionGuideDialog(permissions: List<String>) {
        val guideMessage = StringBuilder().apply {
            append("为了确保无线副屏与投屏直连功能正常工作，需向您说明以下必要权限的使用场景：\n\n")
            append("📡 【设备发现与点对点直连（位置 / 附近设备）】\n")
            append("• 依据 Android 系统底层安全规范，使用 Wi-Fi Direct 点对点技术搜索附近的投屏设备时，系统强制要求调用该权限。\n\n")
            append("🔒 【隐私安全承诺】\n")
            append("• Free Display 为开源独立工具，绝不收集、记录或上传您的真实地理位置、GPS 等任何个人隐私数据。\n")
            append("• 所有投屏音视频流与反向触控报文均仅在局域网内点对点传输，不经过任何外部服务器。\n\n")
            append("点击「理解并授权」后，请在随后的系统弹窗中选择「允许」。")
        }.toString()

        val tv = TextView(this).apply {
            text = guideMessage
            setTextColor(androidx.core.content.ContextCompat.getColor(this@AirPlayDisplayActivity, R.color.glass_text_secondary))
            textSize = 13.5f
            setLineSpacing(6f, 1.15f)
            setBackgroundResource(R.drawable.bg_instruction_box)
        }
        val scrollView = android.widget.ScrollView(this).apply {
            addView(tv)
        }

        showManagedDialog(
            com.n1tools.box.ui.glass.GlassUiHelper.createGlassDialog(
                context = this,
                title = "权限使用向导与隐私说明",
                contentView = scrollView,
                positiveText = "理解并授权",
                onPositive = {
                    val prefs = getSharedPreferences("display_settings", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("has_shown_permission_guide", true).apply()
                    ActivityCompat.requestPermissions(
                        this,
                        permissions.toTypedArray(),
                        RC_PERMISSIONS
                    )
                },
                negativeText = "稍后设置",
                onNegative = {
                    android.widget.Toast.makeText(this, "未授予直连权限，移动端点对点直连可能受限；电脑端局域网镜像不受影响", android.widget.Toast.LENGTH_LONG).show()
                },
                cancelable = false
            )
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_PERMISSIONS) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                Log.i(TAG, "All required permissions granted by user")
                android.widget.Toast.makeText(this, "无线投屏直连权限已就绪", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                Log.w(TAG, "Some permissions were denied by user")
            }
        }
    }

    private fun applyImmersiveMode() {
        val decorView = window.decorView
        decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    private fun scheduleAutoHideControls(delayMs: Long = 5000L) {
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, delayMs)
    }

    private fun collapseControlsToDrawer() {
        if (!isRunning || isFinishing) return
        if (layoutControlCard.visibility != View.VISIBLE) return

        mLayoutMorePanel.visibility = View.GONE
        mBtnMoreControls.text = "⋯ 更多"
        layoutControlCard.animate().cancel()
        btnOpenDrawer.animate().cancel()

        layoutControlCard.animate()
            .alpha(0f)
            .translationY(-15f)
            .setDuration(220)
            .withEndAction {
                if (!isRunning || isFinishing) return@withEndAction
                layoutControlCard.visibility = View.GONE
                layoutControlCard.alpha = 1f
                layoutControlCard.translationY = 0f

                btnOpenDrawer.visibility = View.VISIBLE
                btnOpenDrawer.alpha = 0f
                btnOpenDrawer.translationY = -15f
                btnOpenDrawer.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220)
                    .start()
            }
            .start()
    }

    private fun expandControlsFromDrawer() {
        if (!isRunning || isFinishing) return
        hideControlsHandler.removeCallbacks(hideControlsRunnable)

        if (layoutControlCard.visibility == View.VISIBLE && layoutControlCard.alpha > 0.8f) {
            scheduleAutoHideControls(5000L)
            return
        }

        btnOpenDrawer.animate().cancel()
        layoutControlCard.animate().cancel()

        if (btnOpenDrawer.visibility == View.VISIBLE) {
            btnOpenDrawer.animate()
                .alpha(0f)
                .translationY(-15f)
                .setDuration(180)
                .withEndAction {
                    if (!isRunning || isFinishing) return@withEndAction
                    btnOpenDrawer.visibility = View.GONE
                    btnOpenDrawer.alpha = 1f
                    btnOpenDrawer.translationY = 0f
                    showControlCardAnimated()
                }
                .start()
        } else {
            showControlCardAnimated()
        }
    }

    private fun showControlCardAnimated() {
        layoutControlCard.scrollTo(0, 0)
        layoutControlCard.visibility = View.VISIBLE
        layoutControlCard.alpha = 0f
        layoutControlCard.translationY = -15f
        layoutControlCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220)
            .start()
        scheduleAutoHideControls(5000L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
    }

    override fun onBackPressed() {
        Log.i(TAG, "onBackPressed ignored to prevent accidental exit from Free Display session")
        if (layoutControlCard.visibility == View.VISIBLE) {
            collapseControlsToDrawer()
        } else {
            expandControlsFromDrawer()
        }
    }

    override fun finish() {
        Log.i(TAG, "AirPlayDisplayActivity finish() called! Call stack:\n" + Log.getStackTraceString(Throwable()))
        super.finish()
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null && mWaitingOverlay.visibility == View.VISIBLE && activeSource != DisplaySource.NONE) {
            mWaitingOverlay.visibility = View.GONE
        }
        if (event != null && activeSource == DisplaySource.HUAWEI && hasReceivedFrame) {
            val location = IntArray(2)
            mSurfaceView.getLocationOnScreen(location)
            val svX = event.rawX - location[0]
            val svY = event.rawY - location[1]
            val svW = mSurfaceView.width
            val svH = mSurfaceView.height
            if (svW > 0 && svH > 0 && svX >= 0 && svX <= svW && svY >= 0 && svY <= svH) {
                miracastServer?.sendTouchEvent(
                    event.actionMasked, svX, svY, svW, svH,
                    mSurfaceView.getVideoWidth().toInt(), mSurfaceView.getVideoHeight().toInt()
                )
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun acquireWifiLocks() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("N1_AirPlay_mDNS")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "N1_AirPlay_HighPerf")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "WifiLocks acquired: multicast=${multicastLock?.isHeld}, highPerf=${wifiLock?.isHeld}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WifiLocks", e)
        }
    }

    private fun releaseWifiLocks() {
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
            multicastLock = null
            if (wifiLock?.isHeld == true) wifiLock?.release()
            wifiLock = null
            Log.i(TAG, "WifiLocks released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WifiLocks", e)
        }
    }

    private fun getNetworkInfoString(): String {
        val ips = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                if (iface.name.startsWith("tun")) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    val hostAddress = addr.hostAddress
                    if (hostAddress != null && !hostAddress.contains(":")) {
                        val label = when {
                            iface.name.startsWith("wlan") -> "局域网 (Wi-Fi)"
                            iface.name.startsWith("p2p") -> "直连 (P2P)"
                            iface.name.startsWith("eth") -> "有线网络"
                            else -> iface.name
                        }
                        ips.add("$label: $hostAddress")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get IP address", e)
        }

        return if (ips.isNotEmpty()) {
            ips.joinToString(" | ")
        } else if (isAntiInterferenceEnabled) {
            "⚡ 独占抗干扰模式 (已锁定P2P专线，退出时自动恢复Wi-Fi)"
        } else {
            "Wi-Fi Direct 点对点直连就绪 (设备名: $displayDeviceName)"
        }
    }

    private fun getOrCreateAirPlayDeviceId(): String {
        val prefs = getSharedPreferences("display_settings", Context.MODE_PRIVATE)
        var id = prefs.getString("airplay_device_id", null)
        if (id.isNullOrEmpty() || id == "01:02:03:04:05:06") {
            val randomBytes = ByteArray(6)
            java.security.SecureRandom().nextBytes(randomBytes)
            // Locally administered unicast MAC: set bit 1 (0x02) and clear bit 0 (0x01)
            randomBytes[0] = ((randomBytes[0].toInt() and 0xFE) or 0x02).toByte()
            id = randomBytes.joinToString(":") { "%02X".format(it) }
            prefs.edit().putString("airplay_device_id", id).apply()
        }
        return id
    }

    private fun getActiveWifiIpv4Address(): InetAddress? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var fallback: InetAddress? = null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback || iface.name.startsWith("tun") || iface.name.startsWith("p2p")) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        if (iface.name.startsWith("wlan") || iface.name.startsWith("eth")) {
                            return addr
                        }
                        if (fallback == null) {
                            fallback = addr
                        }
                    }
                }
            }
            return fallback
        } catch (e: Exception) {
            Log.e(TAG, "Error getting active wifi ipv4 address", e)
            return null
        }
    }

    private fun startAirPlayServer() {
        Log.i(TAG, "startAirPlayServer instance=${System.identityHashCode(this)}")
        val deviceId = getOrCreateAirPlayDeviceId()
        val bindAddress = getActiveWifiIpv4Address()
        Log.i(TAG, "Starting AirPlayServer name=$displayDeviceName deviceId=$deviceId bindAddress=$bindAddress")
        // 1. Apple AirPlay 2 Receiver (Port 7000 / 7100) bound to active IPv4 interface
        airPlayServer = AirPlayServer(displayDeviceName, deviceId, bindAddress, 7100, 7000, airplayDataConsumer)
        thread(name = "airplay-server-start") {
            try {
                airPlayServer?.start()
                Log.i(TAG, "AirPlayServer started on ports: control=7000, video=7100")
            } catch (e: Exception) {
                Log.e(TAG, "AirPlayServer start failed", e)
                runOnUiThread {
                    mTvAirPlayStatus.text = "AirPlay 启动失败: ${e.message}"
                }
            }
        }

        // 2. HarmonyOS / Android / Windows Miracast Sink (Port 7236 / 19000)
        try {
            miracastServer = com.n1tools.box.miracast.MiracastServer(
                context = this,
                deviceName = displayDeviceName,
                videoPlayerProvider = { mVideoPlayer },
                qualityProvider = { currentQualityMode },
                audioEnabledProvider = { isAudioSinkEnabled },
                isVideoAllowedProvider = {
                    (activeSource == DisplaySource.HUAWEI || activeSource == DisplaySource.NONE) &&
                        System.currentTimeMillis() >= huaweiRejectedUntilMs
                },
                onDeviceNameDetected = { name ->
                    runOnUiThread {
                        huaweiDeviceName = name
                        Log.i(TAG, "Detected Miracast Device Name: $huaweiDeviceName")
                    }
                },
                onConnectionStateChanged = { isConnected ->
                    runOnUiThread {
                        if (!isConnected && activeSource == DisplaySource.HUAWEI) {
                            activeSource = DisplaySource.NONE
                            hasReceivedFrame = false
                            updateWaitingOverlayVisibility()
                            mTvAirPlayStatus.text = "等待设备连接中..."
                            miracastServer?.ensureDiscoverable()
                            adbInputController?.disconnect()
                            adbInputController = null
                            updateReverseTouchUi()
                            mVideoPlayer?.reset()
                        }
                    }
                },
                onFirstFrameReceived = {
                    runOnUiThread {
                        if (System.currentTimeMillis() < huaweiRejectedUntilMs) {
                            // 已被用户拒绝的华为请求，不再弹出提示、不抢占画面
                            return@runOnUiThread
                        }
                        if (activeSource == DisplaySource.MAC) {
                            // Mac 正在投屏中，华为尝试投屏 -> 仅弹出确认提示，不抢占画面与解码器
                            showSwitchDeviceOverlay(requestFromMac = false)
                        } else {
                            activeSource = DisplaySource.HUAWEI
                            hasReceivedFrame = true
                            mWaitingOverlay.visibility = View.GONE
                            updateReverseTouchUi()
                            // 华为/安卓首帧渲染时，立即预连接 ADB 输入控制器；Windows 走 UIBC 不连接 ADB
                            if (miracastServer?.isWindowsSource != true && adbInputController == null) {
                                val host = huaweiSourceHost ?: "192.168.49.1"
                                attachAdbController(host, adbPort, forceReconnect = true)
                                Log.i(TAG, "Pre-connected ADB input controller for Huawei source $host:$adbPort on first frame")
                            }
                        }
                    }
                },
                onSourceHostDetected = { host ->
                    if (miracastServer?.isWindowsSource != true && (huaweiSourceHost != host || adbInputController == null)) {
                        attachAdbController(host, adbPort, forceReconnect = true)
                        Log.i(TAG, "ADB input controller created for Huawei source $host:$adbPort")
                    }
                }
            ).apply {
                onUibcStateChanged = {
                    runOnUiThread {
                        updateReverseTouchUi()
                    }
                }
                start()
            }
            Log.i(TAG, "MiracastServer started (WFD Sink)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MiracastServer", e)
        }
    }

    private fun rejectMacSession(durationMs: Long = 5000L) {
        macRejectedUntilMs = System.currentTimeMillis() + durationMs
        lastMacRejectDisconnectMs = 0L
        airPlayServer?.disconnectActiveSessions()
        Log.i(TAG, "Mac casting rejected for ${durationMs}ms")
    }

    private fun ensureMacSessionRejected() {
        val now = System.currentTimeMillis()
        if (now < macRejectedUntilMs && now - lastMacRejectDisconnectMs > 800L) {
            lastMacRejectDisconnectMs = now
            airPlayServer?.disconnectActiveSessions()
        }
    }

    @Synchronized
    private fun rememberCodecConfig(packet: NALPacket) {
        if (!hasConfigNalu(packet)) return
        cachedCodecConfig = copyPacket(packet)
        Log.i(TAG, "Cached AirPlay codec config (SPS/PPS): ${packet.length} bytes")
    }

    @Synchronized
    private fun rememberIdrKeyframe(packet: NALPacket) {
        if (!hasIdrSlice(packet)) return
        cachedIdrKeyframe = copyPacket(packet)
        Log.i(TAG, "Cached AirPlay IDR keyframe: ${packet.length} bytes")
    }

    @Synchronized
    private fun cachedCodecConfigCopy(): NALPacket? = cachedCodecConfig?.let { copyPacket(it) }

    @Synchronized
    private fun cachedIdrKeyframeCopy(): NALPacket? = cachedIdrKeyframe?.let { copyPacket(it) }

    private fun copyPacket(packet: NALPacket): NALPacket = NALPacket().apply {
        nalData = packet.nalData?.copyOf()
        offset = packet.offset
        length = packet.length
        nalType = packet.nalType
        pts = System.nanoTime() / 1000
    }

    private fun hasConfigNalu(packet: NALPacket): Boolean {
        val data = packet.nalData ?: return false
        val start = packet.offset
        val end = if (packet.length > 0) start + packet.length else data.size
        if (end - start < 5) return false
        for (i in start until end - 4) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                val type = when {
                    data[i + 2].toInt() == 1 -> data[i + 3].toInt() and 0x1F
                    data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1 && i + 4 < end ->
                        data[i + 4].toInt() and 0x1F
                    else -> -1
                }
                if (type == 7 || type == 8) return true
            }
        }
        return false
    }

    private fun hasIdrSlice(packet: NALPacket): Boolean {
        val data = packet.nalData ?: return false
        val start = packet.offset
        val end = if (packet.length > 0) start + packet.length else data.size
        if (end - start < 5) return false
        for (i in start until end - 4) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                val type = when {
                    data[i + 2].toInt() == 1 -> data[i + 3].toInt() and 0x1F
                    data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1 && i + 4 < end ->
                        data[i + 4].toInt() and 0x1F
                    else -> -1
                }
                if (type == 5) return true
            }
        }
        return false
    }

    private fun replayCachedCodecConfig(player: VideoPlayer?) {
        val config = cachedCodecConfigCopy() ?: return
        if (player != null) {
            player.addPacker(config)
        } else {
            synchronized(mVideoCacheList) { mVideoCacheList.add(config) }
        }
        Log.i(TAG, "Replayed cached AirPlay codec config after reset")
    }

    private fun replayCachedIdrKeyframe(player: VideoPlayer?) {
        val idr = cachedIdrKeyframeCopy() ?: return
        if (player != null) {
            player.addPacker(idr)
        } else {
            synchronized(mVideoCacheList) { mVideoCacheList.add(idr) }
        }
        Log.i(TAG, "Replayed cached AirPlay IDR keyframe after reset")
    }

    private val airplayDataConsumer = object : AirplayDataConsumer {
        override fun onVideo(video: ByteArray) {
            val packet = NALPacket().apply {
                nalData = video
                offset = 0
                length = video.size
                pts = System.nanoTime() / 1000
            }
            rememberCodecConfig(packet)
            rememberIdrKeyframe(packet)
            // 用户选择“保持当前”后，新 Mac 会话必须被真正切断，而不只是丢弃视频包
            if (System.currentTimeMillis() < macRejectedUntilMs) {
                ensureMacSessionRejected()
                return
            }
            // 如果华为正在投屏且用户未确认切换，弹出确认 Overlay，并缓存连续 GOP 序列防止花屏
            if (activeSource == DisplaySource.HUAWEI) {
                runOnUiThread {
                    showSwitchDeviceOverlay(requestFromMac = true)
                }
                val isIdr = hasIdrSlice(packet)
                synchronized(mMacPendingGop) {
                    if (isIdr) {
                        mMacPendingGop.clear()
                        macPendingGopHasIdr = true
                        mMacPendingGop.add(copyPacket(packet))
                        Log.i(TAG, "Mac pending GOP started with IDR keyframe: ${packet.length} bytes")
                    } else if (macPendingGopHasIdr) {
                        if (mMacPendingGop.size < 300) {
                            mMacPendingGop.add(copyPacket(packet))
                        }
                    }
                    Unit
                }
                return
            }

            if (activeSource != DisplaySource.MAC) macSessionGeneration++
            activeSource = DisplaySource.MAC

            if (!hasReceivedFrame) {
                hasReceivedFrame = true
                runOnUiThread {
                    mWaitingOverlay.visibility = View.GONE
                }
            }

            val player = mVideoPlayer
            if (player != null) {
                synchronized(mVideoCacheList) {
                    while (!mVideoCacheList.isEmpty()) {
                        player.addPacker(mVideoCacheList.removeFirst())
                    }
                }
                player.addPacker(packet)
            } else {
                synchronized(mVideoCacheList) { mVideoCacheList.add(packet) }
            }
        }

        override fun onMirroringStarted() {
            Log.i(TAG, "AirPlay mirroring started by Mac (RTSP RECORD)")
            if (activeSource != DisplaySource.HUAWEI) {
                if (activeSource != DisplaySource.MAC) macSessionGeneration++
                activeSource = DisplaySource.MAC
                runOnUiThread {
                    mWaitingOverlay.visibility = View.GONE
                    mTvAirPlayStatus.text = "Mac 屏幕镜像中"
                    scheduleAutoHideControls(3000L)
                }
            }
        }

        override fun onVideoSize(width: Int, height: Int) {
            Log.i(TAG, "onVideoSize: ${width}x$height")
            macVideoWidth = width
            macVideoHeight = height
            if (activeSource != DisplaySource.HUAWEI) {
                if (activeSource != DisplaySource.MAC) macSessionGeneration++
                activeSource = DisplaySource.MAC
                runOnUiThread {
                    mWaitingOverlay.visibility = View.GONE
                    mSurfaceView.setMeasure(width, height)
                }
            }
        }

        override fun onVideoFormat(videoStreamInfo: VideoStreamInfo?) {
            Log.i(TAG, "onVideoFormat: $videoStreamInfo")
            if (System.currentTimeMillis() < macRejectedUntilMs) {
                ensureMacSessionRejected()
                return
            }
            if (activeSource == DisplaySource.HUAWEI) {
                runOnUiThread {
                    showSwitchDeviceOverlay(requestFromMac = true)
                }
            } else {
                if (activeSource != DisplaySource.MAC) macSessionGeneration++
                activeSource = DisplaySource.MAC
                runOnUiThread {
                    mWaitingOverlay.visibility = View.GONE
                    mTvAirPlayStatus.text = "已连接 Mac，正在接收画面..."
                    updateReverseTouchUi()
                    scheduleAutoHideControls(3000L)
                }
            }
        }

        override fun onAudio(audio: ByteArray) {
            if (!isAudioSinkEnabled || activeSource != DisplaySource.MAC) return
            val pcmPacket = PCMPacket().apply {
                data = audio
            }
            mAudioPlayer?.addPacker(pcmPacket)
        }

        override fun onAudioFormat(audioInfo: AudioStreamInfo?) {
            Log.i(TAG, "onAudioFormat: $audioInfo")
            if (System.currentTimeMillis() < macRejectedUntilMs) {
                ensureMacSessionRejected()
                return
            }
            if (activeSource == DisplaySource.HUAWEI) {
                runOnUiThread {
                    showSwitchDeviceOverlay(requestFromMac = true)
                }
            }
        }

        override fun onDisconnected() {
            val generation = macSessionGeneration
            Log.i(TAG, "AirPlay client disconnected -> activeSource: $activeSource generation=$generation")
            runOnUiThread {
                if (generation != macSessionGeneration || activeSource != DisplaySource.MAC) {
                    Log.i(TAG, "Ignore stale AirPlay disconnect generation=$generation current=$macSessionGeneration")
                    return@runOnUiThread
                }
                activeSource = DisplaySource.NONE
                hasReceivedFrame = false
                updateWaitingOverlayVisibility()
                updateReverseTouchUi()
                mTvAirPlayStatus.text = "Mac 已断开连接，等待重新连接..."
                miracastServer?.ensureDiscoverable()
                mVideoPlayer?.reset()
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "surfaceCreated")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.i(TAG, "surfaceChanged: ${width}x$height")
        if (mVideoPlayer == null) {
            val player = VideoPlayer(holder.surface, 1920, 1080).apply {
                setOnVideoSizeChangedListener { w, h ->
                    Log.i(TAG, "Video output size changed: ${w}x$h")
                    runOnUiThread {
                        mSurfaceView.setMeasure(w, h)
                    }
                }
                setOnFirstFrameRenderedListener {
                    runOnUiThread {
                        mWaitingOverlay.visibility = View.GONE
                    }
                }
            }
            mVideoPlayer = player
            replayCachedCodecConfig(player)
            replayCachedIdrKeyframe(player)
            synchronized(mVideoCacheList) {
                while (!mVideoCacheList.isEmpty()) {
                    player.addPacker(mVideoCacheList.removeFirst())
                }
            }
            player.start()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "surfaceDestroyed: isFinishing=$isFinishing isRunning=$isRunning")
        if (isFinishing || !isRunning) {
            mVideoPlayer?.stopVideoPlay()
            mVideoPlayer = null
        }
    }

    private fun updateQualityModeUi() {
        when (currentQualityMode) {
            0 -> {
                mBtnQualityMode.text = "🌟 画质: 超清"
                mBtnQualityMode.setTextColor(android.graphics.Color.parseColor("#22c55e"))
            }
            1 -> {
                mBtnQualityMode.text = "🌟 画质: 均衡"
                mBtnQualityMode.setTextColor(android.graphics.Color.parseColor("#38bdf8"))
            }
            2 -> {
                mBtnQualityMode.text = "🌟 画质: 流畅"
                mBtnQualityMode.setTextColor(android.graphics.Color.parseColor("#eab308"))
            }
        }
    }

    private fun updateAudioModeUi() {
        if (!::mBtnAudioMode.isInitialized) return
        if (isAudioSinkEnabled) {
            mBtnAudioMode.text = "🔊 声音: 副屏"
            mBtnAudioMode.setTextColor(android.graphics.Color.parseColor("#22c55e"))
        } else {
            mBtnAudioMode.text = "🔇 声音: 本机"
            mBtnAudioMode.setTextColor(android.graphics.Color.parseColor("#ffffff"))
        }
    }

    private fun updateDeviceNameUi() {
        if (::mTvDisplayTitle.isInitialized) {
            mTvDisplayTitle.text = displayDeviceName
        }
        if (::mTvInstructionGuide.isInitialized) {
            mTvInstructionGuide.text = "• 鸿蒙 / 安卓：控制中心 ➔「无线投屏」➔ $displayDeviceName\n  (投屏后手机下拉可切换「电脑模式」获得全屏PC桌面)\n• Mac：控制中心 ➔「屏幕镜像」➔ $displayDeviceName\n• Windows：快捷键 Win + K ➔ 选择 $displayDeviceName\n• 声音：右上角按钮可切换「副屏发声」或「设备端发声」"
        }
    }

    private fun updateScaleModeUi() {
        if (!::mBtnScaleMode.isInitialized) return
        when (currentScaleMode) {
            PlaySurfaceView.SCALE_ZOOM -> {
                mBtnScaleMode.text = "🔍 画面: 等比充满"
                mBtnScaleMode.setTextColor(android.graphics.Color.parseColor("#38bdf8"))
            }
            PlaySurfaceView.SCALE_FILL -> {
                mBtnScaleMode.text = "↔️ 画面: 拉伸全屏"
                mBtnScaleMode.setTextColor(android.graphics.Color.parseColor("#22c55e"))
            }
            PlaySurfaceView.SCALE_ORIGINAL -> {
                mBtnScaleMode.text = "🎯 画面: 1:1原生"
                mBtnScaleMode.setTextColor(android.graphics.Color.parseColor("#f59e0b"))
            }
            else -> {
                mBtnScaleMode.text = "📐 画面: 比例适应"
                mBtnScaleMode.setTextColor(android.graphics.Color.parseColor("#ffffff"))
            }
        }
    }

    private fun showRenameDialog() {
        val dp = { v: Int -> com.n1tools.box.ui.glass.GlassUiHelper.dp(this, v) }
        val descTv = TextView(this).apply {
            text = "修改后，手机无线投屏、电脑屏幕镜像及系统投影中搜索到的副屏名称将同步更新。"
            setTextColor(androidx.core.content.ContextCompat.getColor(this@AirPlayDisplayActivity, R.color.glass_text_secondary))
            textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1.15f)
            setPadding(0, 0, 0, dp(12))
        }

        val input = EditText(this).apply {
            setText(displayDeviceName)
            setSelection(text.length)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@AirPlayDisplayActivity, R.color.glass_text_primary))
            setHintTextColor(androidx.core.content.ContextCompat.getColor(this@AirPlayDisplayActivity, R.color.glass_text_hint))
            hint = "请输入副屏名称 (如 Free Display)"
            setSingleLine(true)
            textSize = 14.5f
            setBackgroundResource(R.drawable.bg_glass_input)
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(descTv)
            addView(input)
        }

        showManagedDialog(
            com.n1tools.box.ui.glass.GlassUiHelper.createGlassDialog(
                context = this,
                title = "重命名副屏设备",
                contentView = container,
                positiveText = "保存",
                onPositive = {
                    val newName = input.text.toString().trim()
                    if (newName.isNotEmpty() && newName != displayDeviceName) {
                        displayDeviceName = newName
                        val prefs = getSharedPreferences("display_settings", Context.MODE_PRIVATE)
                        prefs.edit().putString("free_display_name", displayDeviceName).apply()
                        updateDeviceNameUi()
                        isCleanStandby = prefs.getBoolean("clean_standby_enabled", false)
                        updateCleanStandbyUi()
                        miracastServer?.updateDeviceName(displayDeviceName)

                        thread(name = "RestartAirPlayWithName") {
                            try {
                                airPlayServer?.stop()
                                val deviceId = getOrCreateAirPlayDeviceId()
                                val bindAddress = getActiveWifiIpv4Address()
                                airPlayServer = AirPlayServer(displayDeviceName, deviceId, bindAddress, 7100, 7000, airplayDataConsumer)
                                airPlayServer?.start()
                                Log.i(TAG, "AirPlay server re-registered with new name: $displayDeviceName")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to restart AirPlay with new name", e)
                            }
                        }
                        android.widget.Toast.makeText(this, "副屏设备名已更新为: $displayDeviceName", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                negativeText = "取消"
            )
        )
    }

    private fun shareApk() {
        scheduleAutoHideControls(10000L)
        try {
            val appInfo = applicationInfo
            val sourceApk = java.io.File(appInfo.sourceDir)
            if (!sourceApk.exists()) {
                android.widget.Toast.makeText(this, "未找到当前安装包", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            val shareDir = java.io.File(cacheDir, "shared_apk")
            if (!shareDir.exists()) shareDir.mkdirs()

            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = pInfo.versionName ?: "1.0"
            val targetApk = java.io.File(shareDir, "FreeDisplay_v${versionName}.apk")

            sourceApk.inputStream().use { input ->
                targetApk.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val apkUri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                targetApk
            )

            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(android.content.Intent.EXTRA_STREAM, apkUri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Free Display 安装包")
                putExtra(android.content.Intent.EXTRA_TEXT, "Free Display 无线副屏应用安装包（v${versionName}），支持电脑无线扩展与手机投屏，可直接安装使用。")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = android.content.Intent.createChooser(shareIntent, "分享 Free Display 安装包")
            val resInfoList = packageManager.queryIntentActivities(chooser, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            for (resolveInfo in resInfoList) {
                val pkgName = resolveInfo.activityInfo.packageName
                grantUriPermission(pkgName, apkUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share APK", e)
            android.widget.Toast.makeText(this, "分享安装包失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun showAboutDialog() {
        val dp = { v: Int -> com.n1tools.box.ui.glass.GlassUiHelper.dp(this, v) }
        val isLandscape = com.n1tools.box.ui.glass.GlassUiHelper.isLandscape(this)
        val primaryColor = androidx.core.content.ContextCompat.getColor(this, R.color.glass_text_primary)
        val secondaryColor = androidx.core.content.ContextCompat.getColor(this, R.color.glass_text_secondary)
        val cyanColor = androidx.core.content.ContextCompat.getColor(this, R.color.glass_apple_cyan)
        val orangeColor = androidx.core.content.ContextCompat.getColor(this, R.color.glass_apple_orange)

        fun buildGlassSectionCard(title: String, body: String, bottomMarginDp: Int = 10): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_instruction_box)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(bottomMarginDp)
                }

                addView(TextView(this@AirPlayDisplayActivity).apply {
                    text = title
                    setTextColor(primaryColor)
                    textSize = 13.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, 0, 0, dp(5))
                })
                addView(TextView(this@AirPlayDisplayActivity).apply {
                    text = body
                    setTextColor(secondaryColor)
                    textSize = 12.2f
                    setLineSpacing(dp(3).toFloat(), 1.15f)
                })
            }
        }

        val infoColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val appVersionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.1.0"
        } catch (_: Exception) {
            "1.1.0"
        }

        // 1. 版本与产品定位卡
        infoColumn.addView(
            buildGlassSectionCard(
                title = "📅 版本与产品定位 · v$appVersionName (2026-11-26)",
                body = "Free Display 是一款通用、轻量级、低延迟的开源无线显示接收端软件。\n" +
                    "通过深度优化硬件解码管线、Wi-Fi Direct P2P 直连与流媒体解包协议，将闲置 Android 手机、平板及显示终端转变为专业级无线副屏。"
            )
        )

        // 2. 核心功能卡
        infoColumn.addView(
            buildGlassSectionCard(
                title = "📱 核心功能",
                body = "• 无线扩展与镜像：最高支持 60fps 硬件直通低延迟解码。\n" +
                    "• 移动端与 Windows 投屏：兼容 Wi-Fi Display (Miracast) 与 AirPlay。\n" +
                    "• 双向反向触控：支持 Android/鸿蒙 (ADB) 与 Windows 10/11 (UIBC) 触控与拖拽。\n" +
                    "• 多档画面与音频：适应/拉伸/点对点缩放，副屏与发射端音频无缝切换。"
            )
        )

        // 3. 快速连接指南卡
        infoColumn.addView(
            buildGlassSectionCard(
                title = "📖 快速连接指南",
                body = "1. 电脑端：控制中心 ➔ 屏幕镜像 / 投影 ➔ 连接「$displayDeviceName」。\n" +
                    "2. 移动端：控制中心 ➔ 无线投屏 / 多屏互动 ➔ 连接「$displayDeviceName」。\n" +
                    "3. 悬浮控制中心：轻触屏幕呼出，5 秒自动收起为顶部胶囊。"
            )
        )

        // 4. 开源鸣谢与合规声明卡
        infoColumn.addView(
            buildGlassSectionCard(
                title = "🤝 开源鸣谢与合规声明",
                body = "• 核心组件：Netty、JmDNS、AOSP 媒体编解码与图形管线 (Apache 2.0)。\n" +
                    "• 合规声明：独立第三方开源软件，与商业 OS 或硬件厂商无隶属或背书关系，文中技术名词权益归各自权利人所有。",
                bottomMarginDp = if (isLandscape) 0 else 10
            )
        )

        // 5. ☕ 赞赏与支持开发者卡（与所有卡片保持统一的玻璃边框与层次）
        val rewardCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setBackgroundResource(R.drawable.bg_instruction_box)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        rewardCard.addView(TextView(this).apply {
            text = "☕ 赞赏与支持作者"
            setTextColor(primaryColor)
            textSize = 13.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, dp(6))
        })

        rewardCard.addView(TextView(this).apply {
            text = "Free Display 坚持永久免费、无广告与纯净免打扰。\n若本工具解决了您的无线副屏需求，欢迎微信扫码自愿请作者喝杯咖啡（纯属自愿鼓励，不与任何功能捆绑）。"
            setTextColor(secondaryColor)
            textSize = 12f
            setLineSpacing(dp(3).toFloat(), 1.15f)
            setPadding(0, 0, 0, dp(8))
        })

        rewardCard.addView(TextView(this).apply {
            text = "🌐 GitHub：github.com/jimmycq/FreeDisplay"
            setTextColor(cyanColor)
            textSize = 12.2f
            paint.isUnderlineText = true
            setPadding(0, 0, 0, dp(12))
            setOnClickListener { openFeedbackUrl() }
        })

        rewardCard.addView(ImageView(this).apply {
            val qrSize = dp(if (isLandscape) 172 else 200)
            layoutParams = LinearLayout.LayoutParams(qrSize, qrSize).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            setImageResource(R.drawable.img_reward_qrcode)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.WHITE)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        })

        rewardCard.addView(TextView(this).apply {
            text = "Jimmy 的微信赞赏码 · 感谢支持开源"
            setTextColor(orangeColor)
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(10), 0, dp(2))
        })

        // 根据横竖屏动态切换单栏/双栏布局
        val contentView: View = if (isLandscape) {
            val leftScroll = android.widget.ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.35f).apply {
                    marginEnd = dp(12)
                }
                addView(infoColumn)
            }
            val rightScroll = android.widget.ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(rewardCard)
            }
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.TOP
                addView(leftScroll)
                addView(rightScroll)
            }
        } else {
            infoColumn.addView(rewardCard)
            android.widget.ScrollView(this).apply {
                addView(infoColumn)
            }
        }

        aboutDialog?.dismiss()
        val dialog = com.n1tools.box.ui.glass.GlassUiHelper.createGlassDialog(
            context = this,
            title = "关于 Free Display",
            contentView = contentView,
            positiveText = "知道了",
            neutralText = "🚀 检查更新",
            onNeutral = {
                com.n1tools.box.update.UpdateChecker.check(this, manual = true)
            },
            portraitMaxWidthDp = 460,
            landscapeMaxWidthDp = 880
        )
        aboutDialog = dialog
        showManagedDialog(dialog) {
            if (aboutDialog === dialog) {
                aboutDialog = null
            }
        }
    }

    private fun updateWaitingOverlayVisibility() {
        if (!::mWaitingOverlay.isInitialized) return
        val switchOverlayVisible = ::layoutSwitchDeviceOverlay.isInitialized &&
            layoutSwitchDeviceOverlay.visibility == View.VISIBLE
        val shouldShow = activeSource == DisplaySource.NONE &&
            !isCleanStandby &&
            activeDialogCount == 0 &&
            !switchOverlayVisible
        mWaitingOverlay.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun showManagedDialog(
        dialog: android.app.AlertDialog,
        onDismissExtra: (() -> Unit)? = null
    ): android.app.AlertDialog {
        activeDialogCount++
        updateWaitingOverlayVisibility()
        dialog.setOnDismissListener {
            activeDialogCount = (activeDialogCount - 1).coerceAtLeast(0)
            onDismissExtra?.invoke()
            updateWaitingOverlayVisibility()
        }
        dialog.show()
        return dialog
    }


    private fun updateCleanStandbyUi() {
        if (!::mBtnCleanStandby.isInitialized) return
        if (isCleanStandby) {
            mBtnCleanStandby.text = "🖼 纯净待机: 开"
            mBtnCleanStandby.setTextColor(android.graphics.Color.parseColor("#22c55e"))
        } else {
            mBtnCleanStandby.text = "🖼 纯净待机: 关"
            mBtnCleanStandby.setTextColor(android.graphics.Color.parseColor("#ffffff"))
        }
        updateWaitingOverlayVisibility()
    }

    private fun attachAdbController(
        host: String,
        port: Int = adbPort,
        forceReconnect: Boolean = true
    ): AdbInputController {
        huaweiSourceHost = host
        adbPort = port
        val existing = adbInputController
        if (existing != null && existing.getTargetAddress() == "$host:$port") {
            if (forceReconnect) {
                existing.ensureConnectedAsync(force = true)
            }
            return existing
        }
        existing?.disconnect()
        val controller = AdbInputController(this, host, port).apply {
            onStateChanged = {
                updateReverseTouchUi()
            }
        }
        adbInputController = controller
        updateReverseTouchUi()
        controller.ensureConnectedAsync(force = true)
        return controller
    }

    private fun onReverseTouchButtonClick() {
        when (activeSource) {
            DisplaySource.NONE -> {
                android.widget.Toast.makeText(
                    this,
                    "尚未连接投屏，请先使用安卓设备投屏后再启用反向触控",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            DisplaySource.MAC -> {
                android.widget.Toast.makeText(
                    this,
                    "当前为 AirPlay 投屏（Mac / iOS），暂不支持反向触控；仅支持安卓或 Windows 无线投屏",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            DisplaySource.HUAWEI -> {
                if (miracastServer?.isWindowsSource == true) {
                    showWindowsReverseTouchDialog()
                } else {
                    showReverseTouchDialog()
                }
            }
        }
    }

    private fun updateReverseTouchUi() {
        if (!::mBtnReverseTouch.isInitialized) return
        val ctrl = adbInputController
        val isMiracast = (activeSource == DisplaySource.HUAWEI)
        val isWindows = isMiracast && (miracastServer?.isWindowsSource == true)
        mBtnReverseTouch.alpha = if (isMiracast) 1.0f else 0.5f
        if (::mBtnTouchSettings.isInitialized) {
            mBtnTouchSettings.alpha = if (isMiracast) 1.0f else 0.5f
        }
        when {
            activeSource == DisplaySource.NONE -> {
                mBtnReverseTouch.text = "🖱 触控: 未投屏"
                mBtnReverseTouch.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            }
            activeSource == DisplaySource.MAC -> {
                mBtnReverseTouch.text = "🖱 触控: 不可用"
                mBtnReverseTouch.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            }
            isWindows -> {
                if (miracastServer?.isUibcConnected == true) {
                    mBtnReverseTouch.text = "🖱 触控: Win已就绪"
                    mBtnReverseTouch.setTextColor(android.graphics.Color.parseColor("#30D158"))
                } else {
                    mBtnReverseTouch.text = "🖱 触控: Win未开启"
                    mBtnReverseTouch.setTextColor(android.graphics.Color.parseColor("#FF9F0A"))
                }
            }
            ctrl == null -> {
                mBtnReverseTouch.text = "🖱 触控: 未连接"
                mBtnReverseTouch.setTextColor(android.graphics.Color.parseColor("#B8C4D6"))
            }
            ctrl.connectionState == AdbInputController.ConnectionState.CONNECTED -> {
                mBtnReverseTouch.text = "🖱 触控: 已就绪"
                mBtnReverseTouch.setTextColor(android.graphics.Color.parseColor("#30D158"))
            }
            ctrl.connectionState == AdbInputController.ConnectionState.CONNECTING -> {
                mBtnReverseTouch.text = "🖱 触控: 连接中"
                mBtnReverseTouch.setTextColor(android.graphics.Color.parseColor("#FF9F0A"))
            }
            else -> {
                mBtnReverseTouch.text = "🖱 触控: 未连接"
                mBtnReverseTouch.setTextColor(android.graphics.Color.parseColor("#FF6961"))
            }
        }
    }

    private fun showReverseTouchDialog() {
        val dp = { v: Int -> com.n1tools.box.ui.glass.GlassUiHelper.dp(this, v) }
        val primaryColor = androidx.core.content.ContextCompat.getColor(this, R.color.glass_text_primary)
        val secondaryColor = androidx.core.content.ContextCompat.getColor(this, R.color.glass_text_secondary)
        val cyanColor = androidx.core.content.ContextCompat.getColor(this, R.color.glass_apple_cyan)
        val orangeColor = androidx.core.content.ContextCompat.getColor(this, R.color.glass_apple_orange)

        val currentHost = huaweiSourceHost ?: "192.168.49.1"
        val ctrl = adbInputController
        val stateLabel = when (ctrl?.connectionState) {
            AdbInputController.ConnectionState.CONNECTED -> "✅ 已连接 (${ctrl.getTargetAddress()})"
            AdbInputController.ConnectionState.CONNECTING -> "⏳ 正在连接 (${ctrl.getTargetAddress()})..."
            AdbInputController.ConnectionState.UNAUTHORIZED -> "⚠️ 等待手机授权 RSA 调试弹窗"
            AdbInputController.ConnectionState.REFUSED -> "❌ 手机端口 $adbPort 未开启"
            AdbInputController.ConnectionState.FAILED -> "❌ 连接超时 (${ctrl.getTargetAddress()})"
            else -> if (activeSource == DisplaySource.HUAWEI) "⚪ 尚未连接 ($currentHost:$adbPort)" else "⚪ 当前未处于无线投屏会话"
        }

        // 1. 顶部苹果风格分段切换栏（同一弹窗内动态切换「连接与端口」与「开启向导」）
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_glass_input)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        val tabConnect = TextView(this).apply {
            text = "🔗 连接与端口"
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tabGuide = TextView(this).apply {
            text = "📖 开启向导"
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tabBar.addView(tabConnect)
        tabBar.addView(tabGuide)

        // 2. 动态内容容器（单弹窗内动态切换内容，不嵌套新弹窗）
        val dynamicContentHost = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // --- 视图 A：连接与端口 ---
        val detailMsg = buildString {
            append("目标手机地址：$currentHost:$adbPort\n")
            append("当前状态：$stateLabel\n")
            if (!ctrl?.lastErrorMessage.isNullOrBlank()) {
                append("诊断信息：${ctrl?.lastErrorMessage}\n")
            }
            if (ctrl != null && ctrl.isReady) {
                append("投屏分辨率：${ctrl.sourceWidth}×${ctrl.sourceHeight} (displayId=${ctrl.sourceDisplayId})")
            } else {
                append("\n提示：若手机已开启调试但端口不是 5555，可在下方修改端口后点击「重新发起连接」。")
            }
        }

        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_instruction_box)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
            addView(TextView(this@AirPlayDisplayActivity).apply {
                text = detailMsg
                setTextColor(secondaryColor)
                textSize = 12.5f
                setLineSpacing(dp(3).toFloat(), 1.15f)
            })
        }

        val portLabel = TextView(this).apply {
            text = "目标手机 ADB 端口（默认 5555）："
            setTextColor(primaryColor)
            textSize = 12.5f
            setPadding(0, 0, 0, dp(6))
        }

        val etPort = EditText(this).apply {
            setText(adbPort.toString())
            setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(primaryColor)
            setHintTextColor(cyanColor)
            hint = "5555"
            textSize = 14f
            setSingleLine(true)
            setBackgroundResource(R.drawable.bg_glass_input)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val btnSwitchToGuide = TextView(this).apply {
            text = "📖 不会开启手机调试？点此切换到「开启向导」 ➔"
            setTextColor(cyanColor)
            textSize = 12.5f
            gravity = android.view.Gravity.CENTER
            setBackgroundResource(R.drawable.bg_glass_pill)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
            com.n1tools.box.ui.glass.GlassUiHelper.applyPressEffect(this)
        }

        val connectView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(infoCard)
            addView(portLabel)
            addView(etPort)
            addView(btnSwitchToGuide)
        }

        // --- 视图 B：按当前设备 Android 版本动态生成的开启向导 ---
        fun buildGuideCard(title: String, body: String, bottomMarginDp: Int = 10): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_instruction_box)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(bottomMarginDp)
                }
                addView(TextView(this@AirPlayDisplayActivity).apply {
                    text = title
                    setTextColor(primaryColor)
                    textSize = 13.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, 0, 0, dp(4))
                })
                addView(TextView(this@AirPlayDisplayActivity).apply {
                    text = body
                    setTextColor(secondaryColor)
                    textSize = 12.2f
                    setLineSpacing(dp(3).toFloat(), 1.15f)
                })
            }
        }

        fun buildDynamicGuideView(): LinearLayout {
            val sdkInt = android.os.Build.VERSION.SDK_INT
            val androidVer = android.os.Build.VERSION.RELEASE ?: "未知"
            val manufacturer = (android.os.Build.MANUFACTURER ?: "").trim()
            val model = (android.os.Build.MODEL ?: "").trim()
            val isHarmonyOrHuawei = manufacturer.equals("HUAWEI", ignoreCase = true) ||
                manufacturer.equals("HONOR", ignoreCase = true)
            val isXiaomi = manufacturer.equals("Xiaomi", ignoreCase = true) ||
                manufacturer.equals("Redmi", ignoreCase = true)

            val envAdvice = if (sdkInt < android.os.Build.VERSION_CODES.R) {
                "当前副屏接收端：$manufacturer $model · Android $androidVer (API $sdkInt)\n" +
                    "• 兼容性说明：当前接收端系统低于 Android 11，内置 ADB 不支持「6位配对码」无线配对。\n" +
                    "• 推荐方案：被控手机需先开启「USB 调试」并执行一次 adb tcpip 5555（若为华为/鸿蒙，还须打开「仅充电模式下允许 ADB 调试」）。"
            } else {
                "当前副屏接收端：$manufacturer $model · Android $androidVer (API $sdkInt)\n" +
                    "• 兼容性说明：当前接收端为 Android 11+，系统支持现代无线调试环境。\n" +
                    "• 推荐方案：若被控手机尚未与本平板完成无线配对，仍建议先执行一次 adb tcpip 5555 固定端口，或确保已授权后再填入随机端口。"
            }

            val step1Body = when {
                isHarmonyOrHuawei ->
                    "• 华为 / 鸿蒙（HarmonyOS 4.2 等）：设置 ➔ 关于手机 ➔ 连续点击「HarmonyOS 版本」7 次（输入锁屏密码即可）。\n" +
                        "• 其他机型：小米连点「OS/MIUI 版本」7 次；原生/OPPO/vivo 连点「版本号」7 次。"
                isXiaomi ->
                    "• 小米 / 澎湃 OS：设置 ➔ 我的设备 ➔ 全部参数与信息 ➔ 连续点击「OS / MIUI 版本」7 次。\n" +
                        "• 华为 / 鸿蒙 4.2：设置 ➔ 关于手机 ➔ 连续点击「HarmonyOS 版本」7 次。"
                else ->
                    "• 华为 / 鸿蒙 4.2：设置 ➔ 关于手机 ➔ 连续点击「HarmonyOS 版本」7 次。\n" +
                        "• 小米 / 澎湃 OS：设置 ➔ 我的设备 ➔ 全部参数与信息 ➔ 连点「OS/MIUI 版本」7 次。\n" +
                        "• 原生 / OPPO / vivo：设置 ➔ 关于手机 ➔ 连续点击「版本号」7 次。"
            }

            val step2Body = if (sdkInt >= android.os.Build.VERSION_CODES.R) {
                "进入「设置 ➔ 系统和更新 ➔ 开发人员选项」（部分机型在「更多设置」）：\n" +
                    "• 打开「USB 调试」与「无线调试」（可在无线调试内查看当前动态端口）。\n" +
                    "• 若被控手机为华为/鸿蒙，务必同时打开「“仅充电”模式下允许 ADB 调试」。"
            } else {
                "在被控手机进入「设置 ➔ 系统和更新 ➔ 开发人员选项」：\n" +
                    "• 打开「USB 调试」。\n" +
                    "• 若被控手机为华为/鸿蒙 4.2，务必同时打开「“仅充电”模式下允许 ADB 调试」（否则会被系统拦截）。"
            }

            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(buildGuideCard("📱 当前设备检测与适配建议", envAdvice))
                addView(buildGuideCard("1️⃣ 第一步：开启「开发者模式」", step1Body))
                addView(buildGuideCard("2️⃣ 第二步：开启调试开关", step2Body))
                addView(
                    buildGuideCard(
                        "3️⃣ 第三步：开启 5555 端口与授权确认",
                        "• 用数据线将手机连一次电脑，执行：adb tcpip 5555，提示 restarting in TCP mode 后即可拔线。\n" +
                            "• 有效期：手机不重启就一直有效（切换 Wi-Fi/热点不影响），重启后需重设。\n" +
                            "• 回到本弹窗点「🔄 重新发起连接」，在手机弹窗勾选「始终允许」并确定。",
                        bottomMarginDp = 4
                    )
                )
                addView(TextView(this@AirPlayDisplayActivity).apply {
                    text = "💡 提示：切换到上方「🔗 连接与端口」可随时修改端口号并立即重连。"
                    setTextColor(orangeColor)
                    textSize = 11.8f
                    setPadding(dp(2), dp(6), dp(2), 0)
                })
            }
        }

        var guideViewCache: LinearLayout? = null
        fun selectTab(showGuide: Boolean) {
            if (showGuide) {
                tabGuide.setBackgroundResource(R.drawable.bg_glass_pill_accent)
                tabGuide.setTextColor(android.graphics.Color.WHITE)
                tabGuide.setTypeface(tabGuide.typeface, android.graphics.Typeface.BOLD)
                tabConnect.background = null
                tabConnect.setTextColor(secondaryColor)
                tabConnect.setTypeface(null, android.graphics.Typeface.NORMAL)

                if (guideViewCache == null) {
                    guideViewCache = buildDynamicGuideView()
                }
                dynamicContentHost.removeAllViews()
                dynamicContentHost.addView(guideViewCache)
                dynamicContentHost.scrollTo(0, 0)
            } else {
                tabConnect.setBackgroundResource(R.drawable.bg_glass_pill_accent)
                tabConnect.setTextColor(android.graphics.Color.WHITE)
                tabConnect.setTypeface(tabConnect.typeface, android.graphics.Typeface.BOLD)
                tabGuide.background = null
                tabGuide.setTextColor(secondaryColor)
                tabGuide.setTypeface(null, android.graphics.Typeface.NORMAL)

                dynamicContentHost.removeAllViews()
                dynamicContentHost.addView(connectView)
                dynamicContentHost.scrollTo(0, 0)
            }
        }

        tabConnect.setOnClickListener { selectTab(false) }
        tabGuide.setOnClickListener { selectTab(true) }
        btnSwitchToGuide.setOnClickListener { selectTab(true) }
        selectTab(false)

        val rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tabBar)
            addView(dynamicContentHost)
        }

        showManagedDialog(
            com.n1tools.box.ui.glass.GlassUiHelper.createGlassDialog(
                context = this,
                title = "🖱 副屏反向触控 (ADB)",
                contentView = rootContainer,
                positiveText = "🔄 重新发起连接",
                onPositive = {
                    val parsedPort = etPort.text.toString().trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 5555
                    attachAdbController(currentHost, parsedPort, forceReconnect = true)
                    android.widget.Toast.makeText(this, "正在向 $currentHost:$parsedPort 发起连接申请...", android.widget.Toast.LENGTH_SHORT).show()
                },
                neutralText = if (ctrl?.isReady == true) "📐 刷新坐标" else null,
                onNeutral = if (ctrl?.isReady == true) {
                    {
                        ctrl.refreshSourceDisplayConfigAsync()
                        android.widget.Toast.makeText(this, "已刷新手机虚拟屏坐标", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else null,
                negativeText = "关闭",
                portraitMaxWidthDp = 460,
                landscapeMaxWidthDp = 660
            )
        )
    }

    private fun showWindowsReverseTouchDialog() {
        val dp = { v: Int -> com.n1tools.box.ui.glass.GlassUiHelper.dp(this, v) }
        val primaryColor = androidx.core.content.ContextCompat.getColor(this, R.color.glass_text_primary)
        val secondaryColor = androidx.core.content.ContextCompat.getColor(this, R.color.glass_text_secondary)
        val cyanColor = androidx.core.content.ContextCompat.getColor(this, R.color.glass_apple_cyan)
        val orangeColor = androidx.core.content.ContextCompat.getColor(this, R.color.glass_apple_orange)

        val uibcConnected = miracastServer?.isUibcConnected == true
        val statusText = if (uibcConnected) {
            "✅ UIBC 反向触控已连接（支持点击与拖拽滑动）"
        } else {
            "⚠️ UIBC 通道尚未建立（请在 Windows 端勾选允许输入）"
        }

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_glass_input)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }
        val tabStatus = TextView(this).apply {
            text = "🔗 连接状态"
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tabGuide = TextView(this).apply {
            text = "📖 Windows 开启向导"
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tabBar.addView(tabStatus)
        tabBar.addView(tabGuide)

        val dynamicHost = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun buildWinCard(title: String, body: String, bottomMarginDp: Int = 10): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_instruction_box)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(bottomMarginDp)
                }
                addView(TextView(this@AirPlayDisplayActivity).apply {
                    text = title
                    setTextColor(primaryColor)
                    textSize = 13.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, 0, 0, dp(4))
                })
                addView(TextView(this@AirPlayDisplayActivity).apply {
                    text = body
                    setTextColor(secondaryColor)
                    textSize = 12.2f
                    setLineSpacing(dp(3).toFloat(), 1.15f)
                })
            }
        }

        val btnSwitchToGuide = TextView(this).apply {
            text = "📖 不知道如何在 Windows 开启？点击查看设置向导 ➔"
            setTextColor(cyanColor)
            textSize = 12.5f
            gravity = android.view.Gravity.CENTER
            setBackgroundResource(R.drawable.bg_glass_pill)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            com.n1tools.box.ui.glass.GlassUiHelper.applyPressEffect(this)
        }

        val statusView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                buildWinCard(
                    title = "💻 Windows 无线副屏触控 (Miracast UIBC)",
                    body = "当前投屏源：$huaweiDeviceName (${huaweiSourceHost ?: "192.168.49.1"})\n" +
                        "触控状态：$statusText\n\n" +
                        "说明：Windows 10/11 原生通过 Wi-Fi Display UIBC 协议回传触摸坐标，无需安装驱动也无需开启 ADB。"
                )
            )
            addView(btnSwitchToGuide)
        }

        val guideView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                buildWinCard(
                    title = "1️⃣ 第一步：电脑端发起无线投屏",
                    body = "在 Windows 10 / 11 电脑上按快捷键「Win + K」打开无线投放面板，选择「$displayDeviceName」完成连接。"
                )
            )
            addView(
                buildWinCard(
                    title = "2️⃣ 第二步：勾选「允许反向输入」（关键）",
                    body = "连接成功后，Windows 屏幕顶部会弹出黑色连接状态栏（或再次按 Win + K 展开已连接设备）：\n" +
                        "• 找到并勾选「允许来自此设备的鼠标、键盘、触摸和笔输入」。\n" +
                        "• 勾选后，Windows 会立即下发 UIBC 端口并与本平板建立触控通道。"
                )
            )
            addView(
                buildWinCard(
                    title = "3️⃣ 第三步：多屏模式与手势说明",
                    body = "• 可按「Win + P」切换「扩展」或「复制」模式，扩展模式下平板可作为独立触控副屏。\n" +
                        "• 支持单指点击、长按拖动窗口与滑动页面；若勾选后仍未生效，请点击下方「🔄 重新协商 UIBC」。",
                    bottomMarginDp = 4
                )
            )
            addView(TextView(this@AirPlayDisplayActivity).apply {
                text = "💡 提示：部分公司受控电脑可能通过组策略禁用了 Miracast 输入回传，个人版/专业版默认均支持。"
                setTextColor(orangeColor)
                textSize = 11.8f
                setPadding(dp(2), dp(6), dp(2), 0)
            })
        }

        fun selectTab(showGuide: Boolean) {
            if (showGuide) {
                tabGuide.setBackgroundResource(R.drawable.bg_glass_pill_accent)
                tabGuide.setTextColor(android.graphics.Color.WHITE)
                tabGuide.setTypeface(tabGuide.typeface, android.graphics.Typeface.BOLD)
                tabStatus.background = null
                tabStatus.setTextColor(secondaryColor)
                tabStatus.setTypeface(null, android.graphics.Typeface.NORMAL)
                dynamicHost.removeAllViews()
                dynamicHost.addView(guideView)
            } else {
                tabStatus.setBackgroundResource(R.drawable.bg_glass_pill_accent)
                tabStatus.setTextColor(android.graphics.Color.WHITE)
                tabStatus.setTypeface(tabStatus.typeface, android.graphics.Typeface.BOLD)
                tabGuide.background = null
                tabGuide.setTextColor(secondaryColor)
                tabGuide.setTypeface(null, android.graphics.Typeface.NORMAL)
                dynamicHost.removeAllViews()
                dynamicHost.addView(statusView)
            }
            dynamicHost.scrollTo(0, 0)
        }

        tabStatus.setOnClickListener { selectTab(false) }
        tabGuide.setOnClickListener { selectTab(true) }
        btnSwitchToGuide.setOnClickListener { selectTab(true) }
        selectTab(false)

        val rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tabBar)
            addView(dynamicHost)
        }

        showManagedDialog(
            com.n1tools.box.ui.glass.GlassUiHelper.createGlassDialog(
                context = this,
                title = "🖱 Windows 反向触控 (UIBC)",
                contentView = rootContainer,
                positiveText = "🔄 重新协商 UIBC",
                onPositive = {
                    miracastServer?.reconnectUibc()
                    android.widget.Toast.makeText(this, "已向 Windows 发送 UIBC 重新协商请求", android.widget.Toast.LENGTH_SHORT).show()
                },
                negativeText = "关闭",
                portraitMaxWidthDp = 460,
                landscapeMaxWidthDp = 660
            )
        )
    }

    private fun showHotspotGuideDialog() {
        val currentInfo = getNetworkInfoString()
        showManagedDialog(
            com.n1tools.box.ui.glass.GlassUiHelper.createGlassMessageDialog(
                context = this,
                title = "🔥 2.4GHz 快速热点直连模式",
                message = "在没有 Wi-Fi 路由器或户外环境下，可将本平板开启为独立便携式热点，电脑或手机连接热点后可实现超低延迟无线副屏。\n\n当前网络状态：\n$currentInfo\n\n• 点击下方「打开热点设置」可一键进入系统网络共享页面开启热点与查看密码。",
                positiveText = "打开热点设置",
                onPositive = {
                    try {
                        val intent = Intent("android.settings.TETHER_SETTINGS").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                        } catch (e2: Exception) {
                            android.widget.Toast.makeText(this, "请在系统设置中手动开启便携式热点", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                neutralText = "刷新网络信息",
                onNeutral = {
                    if (::mTvNetworkInfo.isInitialized) {
                        mTvNetworkInfo.text = getNetworkInfoString()
                    }
                    android.widget.Toast.makeText(this, "网络信息已刷新", android.widget.Toast.LENGTH_SHORT).show()
                },
                negativeText = "知道了"
            )
        )
    }

    private fun openFeedbackUrl() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/jimmycq/FreeDisplay/issues")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "未能调起浏览器，请在 GitHub Issues 提交反馈", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSwitchDeviceOverlay(requestFromMac: Boolean) {
        val now = System.currentTimeMillis()
        if (isFinishing || !isRunning) return
        // 决策防抖：用户在 3000ms 内刚刚做过确认操作，直接忽略任何弹窗请求
        if (now - lastSwitchDecisionTimeMs < 3000L) return
        // 冷却期检查
        if (requestFromMac && now < macRejectedUntilMs) return
        if (!requestFromMac && now < huaweiRejectedUntilMs) return
        // 若当前已经处于该设备源，无需弹出切换提示
        if (requestFromMac && activeSource == DisplaySource.MAC) return
        if (!requestFromMac && activeSource == DisplaySource.HUAWEI) return
        if (isSwitchOverlayShowing || layoutSwitchDeviceOverlay.visibility == View.VISIBLE) return

        Log.i(TAG, "Show switch overlay: requestFromMac=$requestFromMac active=$activeSource")
        isSwitchOverlayShowing = true
        val targetGen = switchOverlayGeneration
        runOnUiThread {
            if (isFinishing || !isRunning || targetGen != switchOverlayGeneration) {
                isSwitchOverlayShowing = false
                return@runOnUiThread
            }
            if (layoutSwitchDeviceOverlay.visibility == View.VISIBLE) {
                return@runOnUiThread
            }
            // 确保不被等待引导页遮挡
            mWaitingOverlay.visibility = View.GONE

            fun dismiss() {
                layoutSwitchDeviceOverlay.visibility = View.GONE
                isSwitchOverlayShowing = false
                lastSwitchDecisionTimeMs = System.currentTimeMillis()
                updateWaitingOverlayVisibility()
                switchOverlayGeneration++
            }

            if (requestFromMac) {
                tvSwitchTitle.text = "切换投屏设备"
                tvSwitchDesc.text = "检测到来自「$macDeviceName」的屏幕镜像请求。\n当前正在显示「$huaweiDeviceName」，是否切换？"
                btnKeepCurrentDevice.text = "保持 $huaweiDeviceName"
                btnSwitchToNewDevice.text = "切换至 $macDeviceName"

                btnKeepCurrentDevice.setOnClickListener {
                    dismiss()
                    // 新 Mac 是请求方：保持华为时必须切断新 Mac 会话，并给 Mac 10秒拒绝拦截期
                    rejectMacSession(10000L)
                    synchronized(mMacPendingGop) {
                        mMacPendingGop.clear()
                        macPendingGopHasIdr = false
                    }
                    android.widget.Toast.makeText(this, "已保持当前 $huaweiDeviceName，并断开新投屏", android.widget.Toast.LENGTH_SHORT).show()
                }

                btnSwitchToNewDevice.setOnClickListener {
                    dismiss()
                    macRejectedUntilMs = 0L
                    macSessionGeneration++
                    // 切换后给旧华为一个拒绝窗口，防止其自动重连后再次抢占/弹窗
                    huaweiRejectedUntilMs = System.currentTimeMillis() + 10000L
                    activeSource = DisplaySource.MAC
                    hasReceivedFrame = true
                    mWaitingOverlay.visibility = View.GONE
                    val player = mVideoPlayer
                    Log.i(TAG, "Switch to Mac confirmed: cachedConfig=${cachedCodecConfig != null} player=${player != null}")
                    // 彻底重置解码器并清除陈旧的断层数据
                    player?.reset()
                    replayCachedCodecConfig(player)
                    var feedPackets = 0
                    synchronized(mMacPendingGop) {
                        if (macPendingGopHasIdr && mMacPendingGop.isNotEmpty()) {
                            Log.i(TAG, "Replaying pending Mac GOP queue (${mMacPendingGop.size} packets) starting with IDR into decoder")
                            while (mMacPendingGop.isNotEmpty()) {
                                val pkt = mMacPendingGop.removeFirst()
                                if (player != null) {
                                    player.addPacker(pkt)
                                } else {
                                    synchronized(mVideoCacheList) { mVideoCacheList.add(pkt) }
                                }
                                feedPackets++
                            }
                        } else {
                            mMacPendingGop.clear()
                        }
                        macPendingGopHasIdr = false
                    }
                    if (feedPackets == 0) {
                        Log.i(TAG, "No pending IDR GOP found; falling back to cached IDR keyframe")
                        replayCachedIdrKeyframe(player)
                    }
                    if (macVideoWidth > 0 && macVideoHeight > 0) {
                        mSurfaceView.setMeasure(macVideoWidth, macVideoHeight)
                    }
                    // VideoPlayer 自身具备 IDR 过滤门禁（reset 后必须收到真实 IDR 关键帧才开始解码）。
                    mTvAirPlayStatus.text = "正在显示: $macDeviceName"
                    // 切换时切断旧华为会话：RTSP TEARDOWN + UIBC + UDP 丢弃窗口 + P2P 断组
                    miracastServer?.terminateCurrentSession(10000L)
                    adbInputController?.disconnect()
                    adbInputController = null
                    android.widget.Toast.makeText(this, "已切换至 $macDeviceName，并断开旧投屏", android.widget.Toast.LENGTH_SHORT).show()
                }
            } else {
                tvSwitchTitle.text = "切换投屏设备"
                tvSwitchDesc.text = "检测到来自「$huaweiDeviceName」的无线投屏请求。\n当前正在显示「$macDeviceName」，是否切换？"
                btnKeepCurrentDevice.text = "保持 $macDeviceName"
                btnSwitchToNewDevice.text = "切换至 $huaweiDeviceName"

                btnKeepCurrentDevice.setOnClickListener {
                    dismiss()
                    // 新华为是请求方：保持 Mac 时切断新华为的 RTSP/UIBC/RTP 与 P2P 会话
                    huaweiRejectedUntilMs = System.currentTimeMillis() + 10000L
                    miracastServer?.terminateCurrentSession(10000L)
                    android.widget.Toast.makeText(this, "已保持当前 $macDeviceName，并断开新投屏", android.widget.Toast.LENGTH_SHORT).show()
                }

                btnSwitchToNewDevice.setOnClickListener {
                    dismiss()
                    // 核心关键：切换至华为时，第一时间彻底截断 Mac 并设置 10秒拦截期，清空 GOP，绝不让 Mac 残留包弹窗！
                    rejectMacSession(10000L)
                    synchronized(mMacPendingGop) {
                        mMacPendingGop.clear()
                        macPendingGopHasIdr = false
                    }
                    huaweiRejectedUntilMs = 0L
                    activeSource = DisplaySource.HUAWEI
                    hasReceivedFrame = true
                    mWaitingOverlay.visibility = View.GONE
                    mVideoPlayer?.reset()
                    mTvAirPlayStatus.text = "正在显示: $huaweiDeviceName"
                    // 切换至华为设备时，立即重新建立 ADB 输入控制器连接
                    if (adbInputController == null) {
                        val host = huaweiSourceHost ?: "192.168.49.1"
                        attachAdbController(host, adbPort, forceReconnect = true)
                        Log.i(TAG, "Reconnected ADB input controller for Huawei source $host:$adbPort on user switch")
                    }
                    android.widget.Toast.makeText(this, "已切换至 $huaweiDeviceName，并断开旧投屏", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            layoutSwitchDeviceOverlay.visibility = View.VISIBLE
            updateWaitingOverlayVisibility()
        }
    }

    private fun applyWifiAntiInterference(enable: Boolean) {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifi?.reconnect()
            uiUpdateHandler.post(networkInfoUpdater)
        } catch (e: Exception) {
            Log.e(TAG, "Error in Wi-Fi state handling", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        hideControlsHandler.removeCallbacksAndMessages(null)
        uiUpdateHandler.removeCallbacksAndMessages(null)
        bgExecutor.shutdown()

        thread(name = "cpu-perf-restore") {
            trySetPerformanceGovernor(false)
        }

        if (currentInstance == this) {
            currentInstance = null
        }

        // Restore external Wi-Fi connection if it was disconnected for anti-interference
        if (hasDisconnectedWifi) {
            try {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifi?.reconnect()
                Log.i(TAG, "Restored external Wi-Fi connection on activity exit")
            } catch (ignored: Exception) {}
        }

        mAudioPlayer?.stopPlay()
        mAudioPlayer = null

        mVideoPlayer?.stopVideoPlay()
        mVideoPlayer = null

        adbInputController?.disconnect()
        adbInputController = null

        val serverToStop = airPlayServer
        airPlayServer = null
        val miracastToStop = miracastServer
        miracastServer = null

        if (currentInstance == null) {
            miracastToStop?.stop()
            thread(name = "airplay-server-stop") {
                try {
                    serverToStop?.stop()
                    Log.i(TAG, "AirPlayServer stopped on activity exit")
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping AirPlayServer", e)
                }
            }
        } else {
            Log.i(TAG, "New Activity instance is running, keeping AirPlay/Miracast servers active")
        }

        releaseWifiLocks()
    }

    private fun trySetPerformanceGovernor(enabled: Boolean) {
        try {
            val clazz = Class.forName("com.n1tools.box.core.SystemControlHelper")
            val method = clazz.getMethod("setCpuGovernorPerformance", Boolean::class.javaPrimitiveType)
            method.invoke(null, enabled)
        } catch (ignored: Throwable) {}
    }
}
