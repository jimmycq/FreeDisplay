package com.n1tools.box.miracast.p2p

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.Method

class MiracastP2pManager(
    private val context: Context,
    private val deviceName: String = "Free Display",
    private val onConnected: (String) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onDeviceNameDetected: ((String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "MiracastP2pManager"
        private const val WFD_PRIMARY_SINK = 1
        private const val WFD_CONTROL_PORT = 7236
        private const val DISCOVERY_INTERVAL_MS = 25000L
    }

    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var isStarted = false

    @Volatile
    private var isConnected = false
    private var watcherThread: Thread? = null

    private val discoveryRunnable = object : Runnable {
        override fun run() {
            if (isStarted && !isConnected) {
                triggerDiscovery()
                handler.postDelayed(this, DISCOVERY_INTERVAL_MS)
            }
        }
    }

    fun start() {
        if (isStarted) return
        isStarted = true
        isConnected = false

        // 确保关闭系统级的无线显示发送端，避免 Intel WiDi 作为 Source 抢占 7236 端口导致连接断开
        try {
            android.provider.Settings.Global.putInt(context.contentResolver, "wifi_display_on", 0)
        } catch (ignored: Exception) {}
        tryExecuteRoot("settings put global wifi_display_on 0")

        p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        p2pChannel = p2pManager?.initialize(context, context.mainLooper) {
            Log.w(TAG, "P2P Channel lost, reinitializing...")
            isStarted = false
        }

        registerP2pReceiver()
        setDeviceName(deviceName)
        setWfdSinkInfo()

        // Enable Miracast Sink Mode in DisplayManager if available
        setMiracastSinkMode()

        // Start dynamic P2P interface watcher to assign 192.168.49.x immediately
        startInterfaceWatcher()

        // Trigger P2P discovery immediately and periodically keep it alive
        handler.removeCallbacks(discoveryRunnable)
        handler.post(discoveryRunnable)

        Log.i(TAG, "Miracast Wi-Fi P2P Primary Sink started as '$deviceName'")
    }

    private fun startInterfaceWatcher() {
        watcherThread = kotlin.concurrent.thread(name = "MiracastIfaceWatcher") {
            Log.i(TAG, "Miracast dynamic interface watcher started")
            val configuredIfaces = java.util.Collections.synchronizedSet(mutableSetOf<String>())
            val lastAttemptTimes = mutableMapOf<String, Long>()
            val attemptCounts = mutableMapOf<String, Int>()

            while (isStarted) {
                try {
                    val file = java.io.File("/proc/net/dev")
                    if (file.exists()) {
                        val activeIfaces = mutableSetOf<String>()
                        val lines = file.readLines()
                        for (line in lines) {
                            val parts = line.trim().split(":")
                            if (parts.size >= 2) {
                                val iface = parts[0].trim()
                                if (iface.startsWith("p2p-wlan") || iface == "p2p0") {
                                    activeIfaces.add(iface)
                                    val nif = java.net.NetworkInterface.getByName(iface)
                                    val hasP2pIp = nif?.inetAddresses?.asSequence()?.any {
                                        it.hostAddress?.startsWith("192.168.49.") == true
                                    } == true

                                    if (hasP2pIp) {
                                        configuredIfaces.add(iface)
                                        attemptCounts.remove(iface)
                                    } else if (!configuredIfaces.contains(iface)) {
                                        val now = System.currentTimeMillis()
                                        val lastAttempt = lastAttemptTimes[iface] ?: 0L
                                        val count = attemptCounts[iface] ?: 0
                                        // 间隔至少 2000ms 尝试，最多连试 3 次；若仍未成功，每隔 4000ms 允许再次重试，杜绝死锁
                                        val canAttempt = (count < 3 && now - lastAttempt > 2000) || (now - lastAttempt > 4000)
                                        if (canAttempt) {
                                            lastAttemptTimes[iface] = now
                                            attemptCounts[iface] = if (count >= 3) 1 else count + 1
                                            Log.i(TAG, "Detected dynamic P2P interface '$iface' (attempt ${attemptCounts[iface]})! Configuring IP & routing...")
                                            val cmds = arrayOf(
                                                "ip rule add from all to 192.168.49.0/24 lookup main pref 1000 2>/dev/null",
                                                "ip rule add from 192.168.49.0/24 lookup main pref 1001 2>/dev/null",
                                                "ip rule add iif $iface lookup main pref 1002 2>/dev/null",
                                                "setprop init.svc.dhcpcd_$iface running",
                                                "setprop dhcp.$iface.ipaddress 192.168.49.213",
                                                "setprop dhcp.$iface.gateway 192.168.49.1",
                                                "setprop dhcp.$iface.mask 255.255.255.0",
                                                "setprop dhcp.$iface.server 192.168.49.1",
                                                "setprop dhcp.$iface.dns1 192.168.49.1",
                                                "setprop dhcp.$iface.leasetime 3600",
                                                "setprop dhcp.$iface.result ok",
                                                "ifconfig $iface 192.168.49.213 netmask 255.255.255.0 up",
                                                "ip route add 192.168.49.0/24 dev $iface proto kernel scope link src 192.168.49.213 table main 2>/dev/null",
                                                "echo 0 > /proc/sys/net/ipv4/conf/$iface/rp_filter 2>/dev/null",
                                                "echo 0 > /proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null"
                                            )
                                            val ok = tryExecuteRoot(cmds.joinToString(" && "))
                                            if (ok) {
                                                configuredIfaces.add(iface)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // 清理已注销接口的状态，确保设备重新连接时立即能够重新配置 IP
                        val removedIfaces = attemptCounts.keys.filter { !activeIfaces.contains(it) }
                        for (removed in removedIfaces) {
                            attemptCounts.remove(removed)
                            lastAttemptTimes.remove(removed)
                            Log.i(TAG, "Dynamic P2P interface '$removed' removed from system, reset attempt counters")
                        }
                        configuredIfaces.retainAll(activeIfaces)
                    }
                } catch (e: Exception) {
                    // ignore
                }
                try {
                    Thread.sleep(500)
                } catch (e: InterruptedException) {
                    break
                }
            }
            Log.i(TAG, "Miracast dynamic interface watcher stopped")
        }
    }

    private fun registerP2pReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        Log.i(TAG, "P2P State: ${if (enabled) "ENABLED" else "DISABLED"}")
                        if (enabled && isStarted && !isConnected) {
                            triggerDiscovery()
                        }
                    }

                    WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1)
                        val active = state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                        Log.i(TAG, "P2P Discovery state: ${if (active) "STARTED" else "STOPPED"}")
                        if (!active && isStarted && !isConnected) {
                            handler.postDelayed({
                                if (isStarted && !isConnected) {
                                    triggerDiscovery()
                                }
                            }, 3000)
                        }
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        val p2pInfo = intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)

                        if (networkInfo?.isConnected == true && p2pInfo != null) {
                            if (!isConnected) {
                                isConnected = true
                                handler.removeCallbacksAndMessages(null)
                                stopDiscovery()

                                val p2pGroup = intent.getParcelableExtra<WifiP2pGroup>(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                                val detectedName = if (p2pGroup != null) {
                                    if (!p2pInfo.isGroupOwner && p2pGroup.owner != null && !p2pGroup.owner.deviceName.isNullOrBlank()) {
                                        p2pGroup.owner.deviceName
                                    } else {
                                        p2pGroup.clientList.firstOrNull()?.deviceName ?: "华为手机"
                                    }
                                } else {
                                    "华为手机"
                                }
                                Log.i(TAG, "Wi-Fi Direct Peer Device Name: $detectedName")
                                onDeviceNameDetected?.invoke(detectedName)

                                val hostAddress = p2pInfo.groupOwnerAddress?.hostAddress ?: ""
                                Log.i(TAG, "Wi-Fi Direct Connected! Group Owner: $hostAddress, isGroupOwner: ${p2pInfo.isGroupOwner}")
                                onConnected(hostAddress)
                            }
                        } else {
                            if (isConnected) {
                                isConnected = false
                                Log.i(TAG, "Wi-Fi Direct Disconnected")
                                onDisconnected()
                                // Resume discovery quickly after connection ends and ensure WFD IE is re-registered
                                handler.postDelayed({
                                    if (isStarted && !isConnected) {
                                        setWfdSinkInfo()
                                        triggerDiscovery()
                                    }
                                }, 800)
                            }
                        }
                    }

                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        Log.i(TAG, "This P2P device: name='${device?.deviceName}', address='${device?.deviceAddress}', status=${device?.status}")
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        Log.d(TAG, "WIFI_P2P_PEERS_CHANGED_ACTION received")
                    }
                }
            }
        }
        context.registerReceiver(receiver, intentFilter)
    }

    private fun triggerDiscovery() {
        if (!isStarted || isConnected) return
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        setWfdSinkInfo()
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "discoverPeers SUCCESS: P2P listen/find mode active, device is discoverable")
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "discoverPeers FAILED with code: $reason (0=ERROR, 2=BUSY)")
                if (reason == 2 && isStarted && !isConnected) {
                    // 若驱动忙，延迟 1000ms 自动重试，确保绝不永久脱离发现状态
                    handler.postDelayed({
                        if (isStarted && !isConnected) {
                            triggerDiscovery()
                        }
                    }, 1000)
                }
            }
        })
    }

    private fun stopDiscovery() {
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "stopPeerDiscovery SUCCESS: channel locked to P2P session")
            }

            override fun onFailure(reason: Int) {
                Log.d(TAG, "stopPeerDiscovery failed: $reason")
            }
        })
    }

    fun setDeviceName(name: String) {
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        try {
            val setDeviceNameMethod: Method? = manager.javaClass.methods.find {
                it.name.equals("setDeviceName", ignoreCase = true) && it.parameterTypes.size == 3
            }
            if (setDeviceNameMethod != null) {
                setDeviceNameMethod.invoke(manager, channel, name, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.i(TAG, "setDeviceName '$name' SUCCESS")
                    }
                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "setDeviceName '$name' FAILED: $reason")
                    }
                })
            } else {
                Log.w(TAG, "setDeviceName method not found on WifiP2pManager")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not set P2P Device Name via reflection", e)
        }
    }

    private fun setWfdSinkInfo() {
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        try {
            val wfdInfoClass = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo")
            val wfdInfo = wfdInfoClass.newInstance()

            // Invoke setters case-insensitively
            invokeMethodIgnoreCase(wfdInfo, "setWfdEnabled", true)
            invokeMethodIgnoreCase(wfdInfo, "setDeviceType", WFD_PRIMARY_SINK)
            invokeMethodIgnoreCase(wfdInfo, "setSessionAvailable", true)
            invokeMethodIgnoreCase(wfdInfo, "setControlPort", WFD_CONTROL_PORT)
            invokeMethodIgnoreCase(wfdInfo, "setMaxThroughput", 50)

            // Look for setWFDInfo or setWfdInfo
            val setWfdMethod = manager.javaClass.methods.find {
                it.name.equals("setWFDInfo", ignoreCase = true) &&
                        it.parameterTypes.size == 3 &&
                        it.parameterTypes[1].isAssignableFrom(wfdInfoClass)
            }

            if (setWfdMethod != null) {
                val actionListener = object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.i(TAG, "setWFDInfo SUCCESS: WFD Sink IE registered with Wi-Fi Direct framework!")
                    }

                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "setWFDInfo FAILED with code: $reason")
                    }
                }
                setWfdMethod.invoke(manager, channel, wfdInfo, actionListener)
                Log.i(TAG, "Invoked ${setWfdMethod.name} successfully")
            } else {
                Log.w(TAG, "setWFDInfo method not found on WifiP2pManager. Declared methods: " +
                        manager.javaClass.methods.filter { it.name.contains("wfd", ignoreCase = true) }.map { it.name })
            }
        } catch (e: Exception) {
            Log.e(TAG, "setWfdSinkInfo reflection failed", e)
        }
    }

    private fun setMiracastSinkMode() {
        try {
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE)
            val setMiracastModeMethod = displayManager?.javaClass?.methods?.find {
                it.name.equals("setMiracastMode", ignoreCase = true)
            }
            if (setMiracastModeMethod != null) {
                // MIRACAST_MODE_SINK = 2 in DisplayManager
                setMiracastModeMethod.invoke(displayManager, 2)
                Log.i(TAG, "DisplayManager setMiracastMode(2/SINK) invoked")
            }
        } catch (e: Exception) {
            Log.d(TAG, "setMiracastMode not available: ${e.message}")
        }
    }

    private fun invokeMethodIgnoreCase(target: Any, name: String, vararg args: Any?) {
        val method = target.javaClass.methods.find {
            it.name.equals(name, ignoreCase = true) && it.parameterTypes.size == args.size
        }
        if (method != null) {
            method.invoke(target, *args)
        } else {
            Log.w(TAG, "Method $name not found on ${target.javaClass.simpleName}")
        }
    }

    fun disconnectGroup() {
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        try {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "P2P group removed: source has been disconnected")
                    scheduleRecoveryDiscovery(800)
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "P2P group remove failed with code: $reason")
                    scheduleRecoveryDiscovery(1000)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove P2P group", e)
            scheduleRecoveryDiscovery(1000)
        }
    }

    private fun scheduleRecoveryDiscovery(delayMs: Long) {
        handler.postDelayed({
            if (isStarted && !isConnected) {
                setWfdSinkInfo()
                triggerDiscovery()
                Log.i(TAG, "Post-disconnect recovery discovery triggered")
            }
        }, delayMs)
    }

    fun ensureDiscoverable() {
        if (!isStarted || isConnected) return
        handler.removeCallbacks(discoveryRunnable)
        setWfdSinkInfo()
        triggerDiscovery()
        handler.postDelayed(discoveryRunnable, DISCOVERY_INTERVAL_MS)
        Log.i(TAG, "ensureDiscoverable: WFD Sink broadcast refreshed and listen mode triggered")
    }

    fun stop() {
        isStarted = false
        isConnected = false
        handler.removeCallbacks(discoveryRunnable)
        try {
            watcherThread?.interrupt()
            watcherThread = null
        } catch (ignored: Exception) {}

        try {
            receiver?.let { context.unregisterReceiver(it) }
            receiver = null
        } catch (ignored: Exception) {}

        try {
            p2pManager?.stopPeerDiscovery(p2pChannel, null)
            p2pManager?.removeGroup(p2pChannel, null)
        } catch (ignored: Exception) {}

        Log.i(TAG, "Miracast Wi-Fi P2P Sink stopped")
    }

    private fun tryExecuteRoot(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = java.io.DataOutputStream(process.outputStream)
            os.writeBytes("$command\nexit\n")
            os.flush()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
}

