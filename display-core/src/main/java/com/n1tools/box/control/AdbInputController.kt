package com.n1tools.box.control

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * Injects input into the Miracast source phone over ADB.
 *
 * N1 already ships /system/bin/adb and supports `adb keygen`, so no native code
 * is needed. The source phone must have USB/Wireless debugging enabled and must
 * authorize the generated RSA key once.
 */
class AdbInputController(
    context: Context,
    private val host: String,
    val port: Int = 5555
) {
    enum class ConnectionState {
        IDLE,
        CONNECTING,
        CONNECTED,
        UNAUTHORIZED,
        REFUSED,
        FAILED
    }

    private val appContext = context.applicationContext
    private val adbHome = File(appContext.filesDir, ".android")
    private val keyPath = File(adbHome, "adbkey")
    private val device = "$host:$port"
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "AdbInputThread") }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile
    private var connected = false
    @Volatile
    var connectionState: ConnectionState = ConnectionState.IDLE
        private set
    @Volatile
    var lastErrorMessage: String = ""
        private set
    @Volatile
    private var lastAttemptMs: Long = 0L
    var onStateChanged: ((ConnectionState) -> Unit)? = null
    @Volatile
    var sourceWidth: Int = 0
        private set
    @Volatile
    var sourceHeight: Int = 0
        private set
    @Volatile
    var sourceDisplayId: Int = -1
        private set
    @Volatile
    var trackpadWidth: Int = 0
        private set
    @Volatile
    var trackpadHeight: Int = 0
        private set

    @Volatile
    var viewportDisplayId: Int = -1
        private set
    @Volatile
    var viewportLogicalWidth: Int = 0
        private set
    @Volatile
    var viewportLogicalHeight: Int = 0
        private set
    @Volatile
    var viewportPhysicalLeft: Int = 0
        private set
    @Volatile
    var viewportPhysicalTop: Int = 0
        private set
    @Volatile
    var viewportPhysicalRight: Int = 0
        private set
    @Volatile
    var viewportPhysicalBottom: Int = 0
        private set
    @Volatile
    var viewportDeviceWidth: Int = 0
        private set
    @Volatile
    var viewportDeviceHeight: Int = 0
        private set
    @Volatile
    var hasExternalViewport: Boolean = false
        private set

    @Volatile
    private var lastConfigRefreshMs: Long = 0L

    val isReady: Boolean
        get() = connected

    init {
        adbHome.mkdirs()
        if (!keyPath.exists()) {
            runAdb("keygen", keyPath.absolutePath, timeoutMs = 5000)
        }
    }

    fun getTargetAddress(): String = device

    fun connectAsync() {
        ensureConnectedAsync(force = true)
    }

    /**
     * Self-healing connect with 5s cooldown throttle so rapid touches do not flood adb.
     */
    fun ensureConnectedAsync(force: Boolean = false) {
        if (connected && !force) return
        if (connectionState == ConnectionState.CONNECTING) return
        val now = System.currentTimeMillis()
        if (!force && now - lastAttemptMs < 5000L) return
        lastAttemptMs = now
        updateState(ConnectionState.CONNECTING, "正在连接 $device...")

        executor.execute {
            val output = runAdb("connect", device, timeoutMs = 5000)
            var sawUnauthorized = output.contains("unauthorized", ignoreCase = true)
            var sawRefused = output.contains("refused", ignoreCase = true) ||
                output.contains("failed to connect", ignoreCase = true) ||
                output.contains("unable to connect", ignoreCase = true)

            if (sawUnauthorized) {
                Log.w(TAG, "ADB authorization required on $device; approve the RSA prompt on the phone")
            }

            var online = false
            repeat(5) { attempt ->
                if (isDeviceOnline()) {
                    online = true
                    return@repeat
                }
                val retryOut = runAdb("connect", device, timeoutMs = 3500)
                if (retryOut.contains("unauthorized", ignoreCase = true)) {
                    sawUnauthorized = true
                }
                if (retryOut.contains("refused", ignoreCase = true) ||
                    retryOut.contains("failed to connect", ignoreCase = true) ||
                    retryOut.contains("unable to connect", ignoreCase = true)
                ) {
                    sawRefused = true
                }
                if (isDeviceOnline()) {
                    online = true
                    return@repeat
                }
                if (attempt < 4) Thread.sleep(450)
            }

            connected = online
            Log.i(TAG, "ADB connect result: ${output.trim()}, online=$connected")
            if (connected) {
                updateState(ConnectionState.CONNECTED, "已连接 $device")
                refreshSourceDisplayConfig()
                // Schedule delayed re-checks in case desktop-mode virtual display is created after first frame
                if (sourceDisplayId <= 0) {
                    mainHandler.postDelayed({ if (connected) refreshSourceDisplayConfigAsync() }, 1500L)
                    mainHandler.postDelayed({ if (connected) refreshSourceDisplayConfigAsync() }, 3500L)
                }
            } else {
                when {
                    sawUnauthorized -> updateState(
                        ConnectionState.UNAUTHORIZED,
                        "等待手机端授权：请在手机弹窗点击「允许 USB/无线调试」"
                    )
                    sawRefused -> updateState(
                        ConnectionState.REFUSED,
                        "端口 $port 未开启：请在手机开启无线调试或执行 adb tcpip $port"
                    )
                    else -> updateState(
                        ConnectionState.FAILED,
                        "连接 $device 超时，请确认手机与平板在同一网络"
                    )
                }
            }
        }
    }

    private fun updateState(state: ConnectionState, message: String) {
        connectionState = state
        lastErrorMessage = message
        mainHandler.post {
            onStateChanged?.invoke(state)
        }
    }

    private fun waitForOnline(): Boolean {
        repeat(6) { attempt ->
            if (isDeviceOnline()) return true
            val output = runAdb("connect", device, timeoutMs = 4000)
            if (output.contains("unauthorized", ignoreCase = true)) {
                Log.w(TAG, "ADB authorization required on $device; approve the RSA prompt on the phone")
            }
            if (isDeviceOnline()) return true
            if (attempt < 5) Thread.sleep(400)
        }
        Log.w(TAG, "ADB device $device did not become online")
        return false
    }

    private fun isDeviceOnline(): Boolean {
        val output = runAdb("devices", timeoutMs = 3000)
        return output.lineSequence().any { line ->
            line.startsWith(device) &&
                line.contains("\tdevice") &&
                !line.contains("offline") &&
                !line.contains("unauthorized")
        }
    }

    private fun runAdbChecked(vararg args: String, timeoutMs: Long = 3000): String {
        var output = runAdb(*args, timeoutMs = timeoutMs)
        if (!isAdbTransportError(output)) return output
        Log.w(TAG, "ADB transport error, reconnecting: ${output.trim()}")
        connected = waitForOnline()
        if (!connected) return output
        output = runAdb(*args, timeoutMs = timeoutMs)
        return output
    }

    private fun isAdbTransportError(output: String): Boolean {
        return output.contains("device offline", ignoreCase = true) ||
            output.contains("connection refused", ignoreCase = true) ||
            output.contains("device not found", ignoreCase = true) ||
            output.contains("device unauthorized", ignoreCase = true)
    }

    fun refreshSourceDisplayConfigAsync() {
        executor.execute {
            if (connected) refreshSourceDisplayConfig()
        }
    }

    fun refreshIfStale(minIntervalMs: Long = 3000L) {
        val now = System.currentTimeMillis()
        if (now - lastConfigRefreshMs > minIntervalMs) {
            lastConfigRefreshMs = now
            refreshSourceDisplayConfigAsync()
        }
    }

    private fun refreshSourceDisplayConfig() {
        if (!isDeviceOnline() && !waitForOnline()) {
            connected = false
            return
        }
        val hasExternalDisplay = readExternalDisplayConfig()
        readDefaultDisplaySize(keepExternalSize = hasExternalDisplay)
    }

    private fun readExternalDisplayConfig(): Boolean {
        val output = runAdbChecked("-s", device, "shell", "dumpsys", "display", timeoutMs = 6000)
        if (output.isBlank()) return false

        // 1. 精确解析 DisplayViewport{type=EXTERNAL...}
        // 华为手机在手机镜像模式下，通过 physicalFrame 在 deviceWidth/deviceHeight 画面中居中渲染手机画面
        // 华为电脑模式下，displayId > 0，整屏 1920x1080 铺满
        val vpMatch = Regex(
            """DisplayViewport\{type=EXTERNAL[^}]*displayId=(\d+)[^}]*logicalFrame=Rect\((\d+),\s*(\d+)\s*-\s*(\d+),\s*(\d+)\)[^}]*physicalFrame=Rect\((\d+),\s*(\d+)\s*-\s*(\d+),\s*(\d+)\)[^}]*deviceWidth=(\d+),\s*deviceHeight=(\d+)"""
        ).find(output)

        if (vpMatch != null) {
            val vpDisplayId = vpMatch.groupValues[1].toIntOrNull() ?: 0
            val lLeft = vpMatch.groupValues[2].toIntOrNull() ?: 0
            val lTop = vpMatch.groupValues[3].toIntOrNull() ?: 0
            val lRight = vpMatch.groupValues[4].toIntOrNull() ?: 0
            val lBottom = vpMatch.groupValues[5].toIntOrNull() ?: 0
            val pLeft = vpMatch.groupValues[6].toIntOrNull() ?: 0
            val pTop = vpMatch.groupValues[7].toIntOrNull() ?: 0
            val pRight = vpMatch.groupValues[8].toIntOrNull() ?: 0
            val pBottom = vpMatch.groupValues[9].toIntOrNull() ?: 0
            val dWidth = vpMatch.groupValues[10].toIntOrNull() ?: 1920
            val dHeight = vpMatch.groupValues[11].toIntOrNull() ?: 1080

            viewportDisplayId = vpDisplayId
            viewportLogicalWidth = (lRight - lLeft).coerceAtLeast(1)
            viewportLogicalHeight = (lBottom - lTop).coerceAtLeast(1)
            viewportPhysicalLeft = pLeft
            viewportPhysicalTop = pTop
            viewportPhysicalRight = pRight
            viewportPhysicalBottom = pBottom
            viewportDeviceWidth = dWidth
            viewportDeviceHeight = dHeight
            hasExternalViewport = true

            sourceDisplayId = vpDisplayId
            if (vpDisplayId > 0) {
                // 华为电脑模式 (Desktop Mode)
                sourceWidth = dWidth
                sourceHeight = dHeight
            } else {
                // 华为手机镜像模式 (Phone Mirroring)
                sourceWidth = viewportLogicalWidth
                sourceHeight = viewportLogicalHeight
            }
            Log.i(
                TAG,
                "ADB external viewport: displayId=$vpDisplayId " +
                    "physical=[$pLeft,$pTop - $pRight,$pBottom] " +
                    "logical=[${viewportLogicalWidth}x${viewportLogicalHeight}] " +
                    "device=[${dWidth}x${dHeight}]"
            )
            return true
        }

        hasExternalViewport = false

        // 2. 备选方案：匹配 type WIFI 的外显行
        val wifiLine = output.lineSequence().firstOrNull {
            it.contains("DisplayDeviceInfo{") && (it.contains("type WIFI") || it.contains("Free Display"))
        }

        if (wifiLine != null) {
            val name = Regex("""DisplayDeviceInfo\{"([^"]+)"""").find(wifiLine)?.groupValues?.get(1)
            val size = Regex("""(\d+)\s+x\s+(\d+)""").find(wifiLine)
            val width = size?.groupValues?.get(1)?.toIntOrNull() ?: 1920
            val height = size?.groupValues?.get(2)?.toIntOrNull() ?: 1080

            // 提取 displayId
            var displayId = -1
            if (name != null) {
                val infoLine = output.lineSequence().firstOrNull {
                    it.contains("mBaseDisplayInfo=DisplayInfo{\"$name\"") || it.contains("DisplayInfo{\"$name\"")
                }
                if (infoLine != null) {
                    displayId = Regex("""displayId\s+(\d+)""").find(infoLine)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                }
            }
            if (displayId < 0) {
                // 尝试从 Display <id>: 段匹配
                displayId = Regex("""Display\s+(\d+):[\s\S]*?DisplayDeviceInfo\{[^}]*type WIFI""").find(output)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""mDisplayId=(\d+)""").find(wifiLine)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""modeId\s+(\d+)""").find(wifiLine)?.groupValues?.get(1)?.toIntOrNull()
                    ?: -1
            }

            sourceDisplayId = displayId
            sourceWidth = width
            sourceHeight = height
            Log.i(TAG, "ADB external display: id=$displayId name=$name size=${width}x${height}")
            return true
        }

        // 3. 兜底方案：查找是否有除 display 0 以外的有效 display
        val otherDisplays = Regex("""Display\s+(\d+):""").findAll(output).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it > 0 }.toList()
        if (otherDisplays.isNotEmpty()) {
            val candidateId = otherDisplays.first()
            sourceDisplayId = candidateId
            Log.i(TAG, "ADB found secondary display id: $candidateId")
            return true
        }

        return false
    }

    /**
     * 将平板 SurfaceView 触控坐标无缝映射为源端真实坐标，彻底消除画面黑边偏移与拉伸漂移
     */
    fun mapTouchToSource(
        touchX: Float,
        touchY: Float,
        viewW: Int,
        viewH: Int,
        videoW: Int,
        videoH: Int
    ): Pair<Int, Int> {
        val vw = viewW.coerceAtLeast(1)
        val vh = viewH.coerceAtLeast(1)
        val vWidth = if (videoW > 0) videoW else (viewportDeviceWidth.takeIf { it > 0 } ?: 1920)
        val vHeight = if (videoH > 0) videoH else (viewportDeviceHeight.takeIf { it > 0 } ?: 1080)

        // 1. 映射到视频画面像素坐标
        val vx = (touchX / vw.toFloat()) * vWidth
        val vy = (touchY / vh.toFloat()) * vHeight

        if (hasExternalViewport && sourceDisplayId == 0) {
            // 手机镜像模式：根据 physicalFrame 映射到 phone 真实 logicalFrame 坐标
            val pW = (viewportPhysicalRight - viewportPhysicalLeft).coerceAtLeast(1)
            val pH = (viewportPhysicalBottom - viewportPhysicalTop).coerceAtLeast(1)
            val lW = viewportLogicalWidth.coerceAtLeast(1)
            val lH = viewportLogicalHeight.coerceAtLeast(1)

            val clampedVx = vx.coerceIn(viewportPhysicalLeft.toFloat(), viewportPhysicalRight.toFloat())
            val clampedVy = vy.coerceIn(viewportPhysicalTop.toFloat(), viewportPhysicalBottom.toFloat())

            val phoneX = (((clampedVx - viewportPhysicalLeft) / pW.toFloat()) * lW).toInt().coerceIn(0, lW - 1)
            val phoneY = (((clampedVy - viewportPhysicalTop) / pH.toFloat()) * lH).toInt().coerceIn(0, lH - 1)
            return phoneX to phoneY
        } else {
            // 电脑模式或全屏通用映射
            val targetW = sourceWidth.takeIf { it > 0 } ?: vWidth
            val targetH = sourceHeight.takeIf { it > 0 } ?: vHeight
            val sx = ((touchX / vw.toFloat()) * targetW).toInt().coerceIn(0, targetW - 1)
            val sy = ((touchY / vh.toFloat()) * targetH).toInt().coerceIn(0, targetH - 1)
            return sx to sy
        }
    }

    private fun readDefaultDisplaySize(keepExternalSize: Boolean) {
        val output = runAdbChecked("-s", device, "shell", "wm", "size", timeoutMs = 4000)
        val override = Regex("Override size:\\s*(\\d+)x(\\d+)", RegexOption.IGNORE_CASE).find(output)
        val physical = Regex("Physical size:\\s*(\\d+)x(\\d+)", RegexOption.IGNORE_CASE).find(output)
        val match = override ?: physical
        if (match == null) {
            Log.w(TAG, "Unable to parse phone display size from wm size: ${output.trim()}")
            return
        }
        val width = match.groupValues[1].toIntOrNull() ?: 0
        val height = match.groupValues[2].toIntOrNull() ?: 0
        if (width > 0 && height > 0) {
            trackpadWidth = width
            trackpadHeight = height
            if (!keepExternalSize) {
                sourceDisplayId = 0
                sourceWidth = width
                sourceHeight = height
            }
            val kind = if (override != null) "override" else "physical"
            Log.i(TAG, "ADB phone trackpad size: ${width}x${height} ($kind), displayId=$sourceDisplayId")
        } else {
            Log.w(TAG, "Invalid phone display size from wm size: ${output.trim()}")
        }
    }

    fun mouseMove(x: Int, y: Int) {
        if (!connected) return
        executor.execute {
            val displayId = sourceDisplayId
            if (displayId > 0) {
                // 电脑模式：移动鼠标光标
                runAdbChecked("-s", device, "shell", "input mouse -d $displayId motionevent MOVE $x $y", timeoutMs = 2000)
            }
        }
    }

    fun mouseTap(x: Int, y: Int) {
        if (!connected) return
        executor.execute {
            val displayId = sourceDisplayId
            if (displayId > 0) {
                // 电脑模式：先移动光标显形，再点击
                val cmd = "input mouse -d $displayId motionevent MOVE $x $y; input mouse -d $displayId tap $x $y"
                runAdbChecked("-s", device, "shell", cmd, timeoutMs = 3000)
            } else {
                // 手机镜像模式或无特定 displayId：直接绝对坐标 tap
                runAdbChecked(
                    "-s", device, "shell", "input", "tap",
                    x.toString(), y.toString(),
                    timeoutMs = 3000
                )
            }
        }
    }

    fun mouseDoubleTap(x: Int, y: Int) {
        if (!connected) return
        executor.execute {
            val displayId = sourceDisplayId
            if (displayId > 0) {
                val cmd = "input mouse -d $displayId motionevent MOVE $x $y; input mouse -d $displayId tap $x $y; input mouse -d $displayId tap $x $y"
                runAdbChecked("-s", device, "shell", cmd, timeoutMs = 4000)
            } else {
                val cmd = "input tap $x $y; input tap $x $y"
                runAdbChecked("-s", device, "shell", cmd, timeoutMs = 4000)
            }
        }
    }

    fun longPressDoubleTap(x: Int, y: Int) {
        if (!connected) return
        executor.execute {
            val displayId = sourceDisplayId
            if (displayId > 0) {
                val cmd = "input mouse -d $displayId motionevent MOVE $x $y; " +
                    "input mouse -d $displayId tap $x $y; sleep 0.12; input keyevent 66"
                runAdbChecked("-s", device, "shell", cmd, timeoutMs = 4000)
            } else {
                val cmd = "input swipe $x $y $x $y 600"
                runAdbChecked("-s", device, "shell", cmd, timeoutMs = 4000)
            }
        }
    }

    fun mouseDrag(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int = 250) {
        if (!connected) return
        executor.execute {
            val displayId = sourceDisplayId
            val duration = durationMs.coerceIn(100, 600)
            if (displayId > 0) {
                val cmd = "input mouse -d $displayId motionevent MOVE $startX $startY; input swipe $startX $startY $endX $endY $duration"
                runAdbChecked("-s", device, "shell", cmd, timeoutMs = 4000)
            } else {
                val cmd = "input swipe $startX $startY $endX $endY $duration"
                runAdbChecked("-s", device, "shell", cmd, timeoutMs = 4000)
            }
        }
    }

    fun trackpadTap() {
        if (!connected) return
        executor.execute {
            val w = trackpadWidth
            val h = trackpadHeight
            if (w <= 0 || h <= 0) {
                Log.w(TAG, "Trackpad size unavailable, skip tap")
                return@execute
            }
            val x = w / 2
            val y = h / 2
            runAdbChecked("-s", device, "shell", "input", "tap", x.toString(), y.toString())
        }
    }

    fun trackpadMove(dx: Int, dy: Int, durationMs: Int = 150) {
        if (!connected || (dx == 0 && dy == 0)) return
        executor.execute {
            val w = trackpadWidth
            val h = trackpadHeight
            if (w <= 0 || h <= 0) {
                Log.w(TAG, "Trackpad size unavailable, skip move")
                return@execute
            }
            val scaledDx = (dx * TRACKPAD_SCALE).toInt()
            val scaledDy = (dy * TRACKPAD_SCALE).toInt()
            val startX = w / 2
            val startY = h / 2
            val maxStepX = (w / 2 - 16).coerceAtLeast(1)
            val maxStepY = (h / 2 - 16).coerceAtLeast(1)
            val segmentDuration = (durationMs / 2).coerceIn(60, 140)
            var remainingX = scaledDx
            var remainingY = scaledDy
            var segments = 0
            val commands = mutableListOf<String>()
            while ((remainingX != 0 || remainingY != 0) && segments < 6) {
                val stepX = remainingX.coerceIn(-maxStepX, maxStepX)
                val stepY = remainingY.coerceIn(-maxStepY, maxStepY)
                commands.add(
                    "input swipe $startX $startY ${startX + stepX} ${startY + stepY} $segmentDuration"
                )
                remainingX -= stepX
                remainingY -= stepY
                segments++
            }
            if (commands.isNotEmpty()) {
                runAdbChecked("-s", device, "shell", commands.joinToString("; "), timeoutMs = 4000)
            }
        }
    }

    fun keyEvent(keyCode: Int) {
        if (!connected) return
        executor.execute {
            runAdbChecked("-s", device, "shell", "input", "keyevent", keyCode.toString())
        }
    }

    fun disconnect() {
        executor.execute {
            runAdb("disconnect", device, timeoutMs = 3000)
            connected = false
        }
        executor.shutdown()
    }

    private fun runAdb(vararg args: String, timeoutMs: Long = 3000): String {
        return try {
            val cmd = mutableListOf("/system/bin/adb")
            cmd.addAll(args)
            val env = arrayOf(
                "HOME=${appContext.filesDir.absolutePath}",
                "PATH=/system/bin:/system/xbin"
            )
            val process = Runtime.getRuntime().exec(cmd.toTypedArray(), env)
            val output = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().use { output.append(it.readText()) }
                } catch (ignored: Exception) {}
            }
            val errorReader = Thread {
                try {
                    process.errorStream.bufferedReader().use { output.append(it.readText()) }
                } catch (ignored: Exception) {}
            }
            reader.start()
            errorReader.start()

            // Android 5.1 has no Process.waitFor(timeout, TimeUnit). Poll exitValue instead.
            val deadline = System.currentTimeMillis() + timeoutMs
            var exited = false
            while (System.currentTimeMillis() < deadline) {
                try {
                    process.exitValue()
                    exited = true
                    break
                } catch (ignored: IllegalThreadStateException) {
                    Thread.sleep(20)
                }
            }
            if (!exited) {
                process.destroy()
                Log.w(TAG, "adb ${args.firstOrNull()} timed out")
            }
            try {
                reader.join(300)
                errorReader.join(300)
            } catch (ignored: InterruptedException) {}
            output.toString()
        } catch (e: Exception) {
            Log.w(TAG, "adb ${args.firstOrNull()} failed: ${e.message}")
            ""
        }
    }

    companion object {
        private const val TAG = "AdbInputController"
        private const val TRACKPAD_SCALE = 2.5f
    }
}
