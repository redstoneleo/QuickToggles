package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.utils.ControlManager
import com.example.utils.PrefsManager
import com.example.widget.QuickControlWidget

class ScreenStateService : Service() {

    private companion object {
        private const val TAG = "ScreenStateService"
        private const val CHANNEL_ID = "screen_state_channel"
        private const val NOTIFICATION_ID = 4132
    }

    private var screenReceiver: BroadcastReceiver? = null
    private var wasFlashlightOnWhenScreenOff = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var usb5gRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ScreenStateService Created, starting foreground")
        
        // 1. Create and Start Foreground notification ASAP to avoid crash
        createNotificationChannel()
        val notification = createNotification()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed startForeground with specialUse, fallback to standard: ${e.message}")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (ex: Exception) {
                Log.e(TAG, "Fatal startForeground exception: ${ex.message}")
            }
        }

        // 2. Register Screen ON / SCREEN OFF broadcast receiver
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand triggering")
        // Double check configuration state
        val isAutoData = PrefsManager.isAutoDataToggleEnabled(this)
        val isFlashlightCtrl = PrefsManager.isFlashlightPowerControlEnabled(this)
        val isUsb5g = PrefsManager.isUsb5gToggleEnabled(this)
        val isSleepMode = PrefsManager.isSleepModeEnabled(this)
        if (!isAutoData && !isFlashlightCtrl && !isUsb5g && !isSleepMode) {
            Log.d(TAG, "All core background features are disabled, self stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        
        if (isUsb5g) {
            checkAndApplyUsb5gAdjustment(this)
        }
        if (isSleepMode) {
            startSleepModeChecker()
        }
        
        // Return START_STICKY to guarantee restart under memory pressure
        return START_STICKY
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return

        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Log.d(TAG, "Screen state broadcast received: ${intent.action}")
                
                val isAutoData = PrefsManager.isAutoDataToggleEnabled(context)
                val isFlashlightCtrl = PrefsManager.isFlashlightPowerControlEnabled(context)
                val isUsb5g = PrefsManager.isUsb5gToggleEnabled(context)
                
                // Double check user preference to avoid doing logic if disabled
                if (!isAutoData && !isFlashlightCtrl && !isUsb5g) {
                    Log.d(TAG, "Receiver saw screen event but core features are disabled, stopping service.")
                    stopSelf()
                    return
                }

                val action = intent.action ?: return

                // 1. Fast-Path for Flashlight screen power control (Instant main-thread check ensures zero-latency)
                if (isFlashlightCtrl) {
                    if (action == Intent.ACTION_SCREEN_OFF) {
                        val isFlashOn = ControlManager.isFlashlightEnabled()
                        if (isFlashOn) {
                            wasFlashlightOnWhenScreenOff = true
                            Log.i(TAG, "Armed Flashlight Smart-Shutoff. Flashlight is currently ON.")
                        } else {
                            wasFlashlightOnWhenScreenOff = false
                        }
                    } else if (action == Intent.ACTION_SCREEN_ON) {
                        val isFlashOn = ControlManager.isFlashlightEnabled()
                        if (isFlashOn) {
                            Log.i(TAG, "Flashlight Smart-Shutoff Intercept! Turning off flashlight and sending Sleep key to keep screen OFF.")
                            wasFlashlightOnWhenScreenOff = false
                            
                            // Toggle flashlight OFF
                            ControlManager.setFlashlightEnabled(context, false)
                            refreshWidget(context)
                            
                            // Send KEYCODE_SLEEP (223) repeatedly with minor progressive delays
                            // to ensure we reliably catch the PowerManager's window and force display OFF
                            Thread {
                                try {
                                    com.example.utils.ShellUtils.runCommand("input keyevent 223", useRoot = true)
                                    val delays = listOf(50L, 100L, 150L, 250L, 400L)
                                    for (d in delays) {
                                        Thread.sleep(d)
                                        com.example.utils.ShellUtils.runCommand("input keyevent 223", useRoot = true)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to turn off screen via keyevent 223: ${e.message}")
                                }
                            }.start()
                            
                            // Bypasses mobile data trigger since screen is forced back off
                            return
                        }
                        wasFlashlightOnWhenScreenOff = false
                    }
                }

                // 2. Mobile Data Auto-Switch Logic (spawns separate IO worker since it does networking/shell polling)
                if (isAutoData) {
                    val pendingResult = goAsync()
                    Thread {
                        try {
                            val isUsbConnected = isUsbConnectedToComputer(context)
                            val isTetheringConfigured = isUsbTetheringConfigured(context)
                            
                            Log.d(TAG, "Receiver event action: $action, isUsbConnected: $isUsbConnected, isTetheringConfigured: $isTetheringConfigured")

                            when (action) {
                                Intent.ACTION_SCREEN_ON -> {
                                    Log.i(TAG, "Screen is ON -> Delaying 0.35s before turning mobile data ON")
                                    try {
                                        Thread.sleep(350)
                                    } catch (e: InterruptedException) {
                                        Log.e(TAG, "Delay interrupted: ${e.message}")
                                    }
                                    Log.i(TAG, "Screen is ON -> (bg) Turning mobile data ON after delay")
                                    ControlManager.setMobileDataEnabled(context, true)
                                    refreshWidget(context)
                                }
                                Intent.ACTION_POWER_CONNECTED,
                                Intent.ACTION_POWER_DISCONNECTED,
                                "android.hardware.usb.action.USB_STATE" -> {
                                    val isUsbTethering = isUsbTetheringConfigured(context)
                                    val isPcConnected = isUsbConnectedToComputer(context)
                                    if (isUsbTethering && isPcConnected) {
                                        Log.i(TAG, "USB Tethering to PC ($action) detected. Assuring mobile data is ON.")
                                        val currentOn = ControlManager.isMobileDataEnabled(context)
                                        if (!currentOn) {
                                            ControlManager.setMobileDataEnabled(context, true)
                                            refreshWidget(context)
                                        }
                                    } else {
                                        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                                        if (!pm.isInteractive) {
                                            Log.i(TAG, "State changed ($action): Not Tethered to PC while screen is OFF. Evaluating shutoff...")
                                            evaluateScreenOffState(context)
                                        }
                                    }
                                }
                                Intent.ACTION_SCREEN_OFF -> {
                                    evaluateScreenOffState(context)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error performing background screen-toggle or USB check: ${e.message}")
                        } finally {
                            pendingResult.finish()
                        }
                    }.start()
                }

                // 3. USB Smart 5G Dynamic Adjustment
                if (isUsb5g) {
                    val delay = if (action == Intent.ACTION_SCREEN_ON && isAutoData) 1800L else 500L
                    checkAndApplyUsb5gAdjustment(context, delay)
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction("android.hardware.usb.action.USB_STATE")
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    private fun evaluateScreenOffState(context: Context) {
        val isWifiAp = isWifiApEnabled(context)
        val isUsbTethering = isUsbTetheringConfigured(context)
        val isBtTethering = isBluetoothTetheringActive()
        val isAudioActive = isAudioPlaying(context)
        val isPcConnected = isUsbConnectedToComputer(context)

        Log.i(TAG, "Screen is OFF. Sharing state: WifiAp=$isWifiAp, UsbTethering=$isUsbTethering, BtTethering=$isBtTethering, AudioActive=$isAudioActive, PcConnected=$isPcConnected")

        if (isUsbTethering && isPcConnected) {
            Log.i(TAG, "Device is connected to PC and USB Tethering is configured. Force keeping mobile data ONLINE while screen is OFF.")
            val currentOn = ControlManager.isMobileDataEnabled(context)
            if (!currentOn) {
                ControlManager.setMobileDataEnabled(context, true)
                refreshWidget(context)
            }
            return
        }

        if (isAudioActive) {
            Log.i(TAG, "Audio playback detected. Maintaining mobile data/cellular connection while screen is OFF.")
            return
        }

        if (isBtTethering) {
            Log.i(TAG, "Bluetooth tethering active. Force keeping cellular data ONLINE.")
            return
        }

        if (isWifiAp) {
            val clientCount = getSharingClientCount()
            Log.i(TAG, "Wi-Fi Hotspot is active. Active clients count: $clientCount")
            if (clientCount > 0) {
                Log.i(TAG, "Active Wi-Fi Hotspot clients ($clientCount) detected. Maintaining mobile data/cellular connection.")
                return
            } else {
                Log.i(TAG, "Wi-Fi Hotspot active but has 0 connected clients. Proceeding to turn mobile data OFF.")
            }
        }

        Log.i(TAG, "Screen is OFF -> (bg) Turning mobile data OFF")
        ControlManager.setMobileDataEnabled(context, false)
        refreshWidget(context)
    }

    private fun getSystemProperty(key: String, defaultValue: String = ""): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val getMethod = clazz.getMethod("get", String::class.java, String::class.java)
            getMethod.invoke(null, key, defaultValue) as String
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get system property $key via reflection: ${e.message}")
            defaultValue
        }
    }

    private fun isUsbTetheringConfigured(context: Context): Boolean {
        var persistConfig = getSystemProperty("persist.sys.usb.config").lowercase()
        var usbConfig = getSystemProperty("sys.usb.config").lowercase()
        var usbState = getSystemProperty("sys.usb.state").lowercase()

        var checkedSysProp = persistConfig.contains("rndis") || 
                             persistConfig.contains("ncm") || 
                             persistConfig.contains("tethering") ||
                             usbConfig.contains("rndis") || 
                             usbConfig.contains("ncm") || 
                             usbConfig.contains("tethering") ||
                             usbState.contains("rndis") ||
                             usbState.contains("ncm") ||
                             usbState.contains("tethering")

        // Root fallback for sysprops (bypasses SELinux restrictions that block reflection on Android 10+)
        if (!checkedSysProp) {
            try {
                val rootProps = com.example.utils.ShellUtils.runCommand("getprop sys.usb.config && getprop sys.usb.state && getprop persist.sys.usb.config", useRoot = true)
                if (rootProps.isSuccess) {
                    val rootOutput = rootProps.stdout.lowercase()
                    if (rootOutput.contains("rndis") || rootOutput.contains("ncm") || rootOutput.contains("tethering")) {
                        checkedSysProp = true
                        Log.d(TAG, "isUsbTetheringConfigured: sysprop detected via root Shell")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check usb properties via root: ${e.message}")
            }
        }

        var isTetheringActiveByApi = false
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    val name = networkInterface.name.lowercase()
                    if (networkInterface.isUp && !networkInterface.isLoopback) {
                        if (name.contains("rndis") || name.contains("usb") || name.contains("ncm") || name.contains("ecm") || name.contains("tether")) {
                            isTetheringActiveByApi = true
                            Log.d(TAG, "isUsbTetheringConfigured: API detected active interface $name")
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inspect network interfaces via JDK API: ${e.message}")
        }

        // Root fallback for active interfaces (in case JVM is sandboxed from seeing network interfaces)
        if (!isTetheringActiveByApi) {
            try {
                val ipLink = com.example.utils.ShellUtils.runCommand("ip link show up", useRoot = true)
                if (ipLink.isSuccess) {
                    val output = ipLink.stdout.lowercase()
                    if (output.contains("rndis") || output.contains("usb") || output.contains("ncm") || output.contains("ecm") || output.contains("tether")) {
                        isTetheringActiveByApi = true
                        Log.d(TAG, "isUsbTetheringConfigured: root ip link detected active interface")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed ip link fallback: ${e.message}")
            }
        }

        return checkedSysProp || isTetheringActiveByApi
    }

    private fun getBatteryPluggedState(context: Context): Int {
        try {
            val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, batteryFilter)
            if (batteryStatus != null) {
                return batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed registerReceiver for battery status: ${e.message}")
        }
        
        // Fallback using BatteryManager direct system API if available
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            if (bm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val isCharging = bm.isCharging
                    if (!isCharging) return 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed BatteryManager query fallback: ${e.message}")
        }
        
        return 0 // Assume unplugged if we can't read it
    }

    private fun isUsbConnectedToComputer(context: Context): Boolean {
        // High compatibility: We do not limit USB detection strictly to BatteryManager.BATTERY_PLUGGED_USB (2) 
        // since modern high-power computer ports or Thunderbolt connections are often classified by Android as AC (1).
        val plugged = getBatteryPluggedState(context)
        if (plugged != android.os.BatteryManager.BATTERY_PLUGGED_USB && plugged != android.os.BatteryManager.BATTERY_PLUGGED_AC) {
            Log.d(TAG, "Not plugged into any USB/AC charging port (plugged code = $plugged). Not connected to PC.")
            return false
        }

        var isUsbActive = false
        try {
            val usbFilter = IntentFilter("android.hardware.usb.action.USB_STATE")
            val usbStatus = context.registerReceiver(null, usbFilter)
            if (usbStatus != null) {
                isUsbActive = usbStatus.getBooleanExtra("connected", false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get dynamic USB_STATE: ${e.message}")
        }

        var usbState = getSystemProperty("sys.usb.state").lowercase()
        var isPcHandshake = false
        
        // Root fallback to check physical USB state instead of just properties which can lie on wall charger
        try {
            val rootState = com.example.utils.ShellUtils.runCommand("cat /sys/class/android_usb/android0/state", useRoot = true)
            if (rootState.isSuccess) {
                val state = rootState.stdout.trim().uppercase()
                if (state == "CONFIGURED" || state == "CONNECTED") {
                    isPcHandshake = true
                    Log.d(TAG, "isUsbConnectedToComputer: /sys/class/android_usb state is $state")
                }
            } else {
                // If /sys/class/android_usb doesn't exist (modern devices), check dumpsys usb
                val dumpsysUsb = com.example.utils.ShellUtils.runCommand("dumpsys usb | grep 'Connected:'", useRoot = true)
                if (dumpsysUsb.isSuccess && dumpsysUsb.stdout.contains("Connected: true", ignoreCase = true)) {
                     isPcHandshake = true
                     Log.d(TAG, "isUsbConnectedToComputer: dumpsys usb confirmed connected")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check low-level usb state: ${e.message}")
        }

        // If root fallback failed/didn't run, trust the sticky Intent's 'connected' extra 
        // We DO NOT trust sys.usb.config or sys.usb.state as they can persist when plugged into a wall charger
        
        Log.d(TAG, "USB computer connection check: activeUsbIntent=$isUsbActive, rootPcHandshake=$isPcHandshake, sysUsbState=$usbState")
        
        return isUsbActive || isPcHandshake
    }

    private fun isWifiApEnabled(context: Context): Boolean {
        // Multi-layer high availability hot-spot check design
        // Layer 1: standard reflection 'isWifiApEnabled'
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            if (wifiManager != null) {
                val method = wifiManager.javaClass.getMethod("isWifiApEnabled")
                val res = method.invoke(wifiManager) as? Boolean
                if (res == true) return true
            }
        } catch (e: Exception) {
            Log.v(TAG, "Layer 1 Wi-Fi AP check failed: ${e.message}")
        }

        // Layer 2: standard reflection to get getWifiApState directly
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            if (wifiManager != null) {
                val method = wifiManager.javaClass.getMethod("getWifiApState")
                val state = method.invoke(wifiManager) as? Int
                if (state == 13) { // 13 is WIFI_AP_STATE_ENABLED in AOSP sources
                    return true
                }
            }
        } catch (e: Exception) {
            Log.v(TAG, "Layer 2 Wi-Fi AP state reflection failed: ${e.message}")
        }

        // Layer 3: Network interface lookup for active wireless AP interfaces
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    val name = networkInterface.name.lowercase()
                    if (networkInterface.isUp) {
                        if (name.contains("ap0") || name.contains("ap1") || name.contains("softap") || name.contains("wap0") || name.contains("wlan1") || name.contains("wlan2")) {
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Layer 3 interface scanner failed for Wi-Fi AP: ${e.message}")
        }

        return false
    }

    private fun isBluetoothTetheringActive(): Boolean {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    val name = networkInterface.name.lowercase()
                    if (networkInterface.isUp) {
                        if (name.contains("pan") || name.contains("bnep") || name.contains("bt-pan")) {
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check bluetooth tethering interface: ${e.message}")
        }
        return false
    }

    private fun isAudioPlaying(context: Context): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            if (audioManager != null) {
                // To prevent false positives when audio sessions are passive or paused without output,
                // we require music stream volume to be active (> 0).
                audioManager.isMusicActive && audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) > 0
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check if audio is playing: ${e.message}")
            false
        }
    }

    private fun getSharingClientCount(): Int {
        var count = 0
        val activeMacs = mutableSetOf<String>()
        
        fun isSharingInterface(device: String): Boolean {
            val dev = device.lowercase()
            return dev.contains("wlan") || 
                   dev.contains("ap") || 
                   dev.contains("softap") ||
                   dev.contains("rndis") ||
                   dev.contains("usb") ||
                   dev.contains("ncm") ||
                   dev.contains("pan") ||
                   dev.contains("bnep") ||
                   dev.contains("bt")
        }

        // Method 1: Read /proc/net/arp which lists IPv4 client entries in hotspot mode under Root
        try {
            val result = com.example.utils.ShellUtils.runCommand("cat /proc/net/arp", useRoot = true)
            if (result.isSuccess) {
                val lines = result.stdout.split("\n")
                for (line in lines) {
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 6) {
                        val mac = parts[3].lowercase()
                        val device = parts[5].lowercase()
                        if (mac != "00:00:00:00:00:00" && mac.isNotEmpty() && isSharingInterface(device)) {
                            activeMacs.add(mac)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed /proc/net/arp client check: ${e.message}")
        }

        // Method 2: Comprehensive IPv4 & IPv6 Neighbors show (via ip neigh) under Root 
        try {
            val result = com.example.utils.ShellUtils.runCommand("ip neigh show", useRoot = true)
            if (result.isSuccess) {
                val lines = result.stdout.split("\n")
                for (line in lines) {
                    val lineLower = line.lowercase().trim()
                    // Filter out failed/incomplete entries. Every other entry is a potential active neighbor.
                    if (lineLower.isNotEmpty() && !lineLower.contains("failed") && !lineLower.contains("incomplete")) {
                        val parts = lineLower.split("\\s+".toRegex())
                        val devIndex = parts.indexOf("dev")
                        val lladdrIndex = parts.indexOf("lladdr")
                        if (devIndex != -1 && devIndex + 1 < parts.size) {
                            val device = parts[devIndex + 1]
                            if (isSharingInterface(device)) {
                                if (lladdrIndex != -1 && lladdrIndex + 1 < parts.size) {
                                    val mac = parts[lladdrIndex + 1]
                                    if (mac != "00:00:00:00:00:00" && mac.isNotEmpty()) {
                                        activeMacs.add(mac)
                                    }
                                } else {
                                    count++
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed ip neigh client count fallback: ${e.message}")
        }

        // Method 3: Parse AOSP dumpsys wifi service logs for active stations associated as client under Root
        try {
            val result = com.example.utils.ShellUtils.runCommand("dumpsys wifi", useRoot = true)
            if (result.isSuccess) {
                val lines = result.stdout.split("\n")
                for (line in lines) {
                    val lineLower = line.lowercase().trim()
                    if (lineLower.contains("connected stations") || lineLower.contains("numassociatedstations") || lineLower.contains("associated stations")) {
                        val match = "\\d+".toRegex().find(lineLower)
                        if (match != null) {
                            val clientNum = match.value.toIntOrNull() ?: 0
                            if (clientNum > 0) {
                                Log.i(TAG, "Dumpsys wifi returned active station count: $clientNum")
                                return clientNum
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed dumpsys wifi station count scanner: ${e.message}")
        }

        val finalCount = if (activeMacs.isNotEmpty()) activeMacs.size else count
        Log.d(TAG, "getSharingClientCount: final calculated clients = $finalCount")
        return finalCount
    }

    private fun checkAndApplyUsb5gAdjustment(context: Context, delayMillis: Long = 500L) {
        val isUsb5g = PrefsManager.isUsb5gToggleEnabled(context)
        if (!isUsb5g) return

        usb5gRunnable?.let { handler.removeCallbacks(it) }

        val runnable = Runnable {
            Thread {
                try {
                    val isDataOn = ControlManager.isMobileDataEnabled(context)
                    val isPcConnected = isUsbConnectedToComputer(context)
                    val isUsbTethering = isUsbTetheringConfigured(context)
                    val isWifiAp = isWifiApEnabled(context)
                    
                    val isSharingEnabled = (isPcConnected && isUsbTethering) || isWifiAp
                    
                    val targetMode = if (isDataOn && isSharingEnabled) "5G" else "4G"
                    
                    if (targetMode == "4G") {
                        val waitMsg = "ScreenStateService: Sharing stopped. Waiting 20s before switching back to 4G..."
                        Log.i(TAG, waitMsg)
                        ControlManager.addShellLog(waitMsg)
                        
                        handler.post {
                            val fallbackRunnable = Runnable {
                                Thread {
                                    try {
                                        val finalDataOn = ControlManager.isMobileDataEnabled(context)
                                        val finalPcConnected = isUsbConnectedToComputer(context)
                                        val finalUsbTethering = isUsbTetheringConfigured(context)
                                        val finalWifiAp = isWifiApEnabled(context)
                                        
                                        val finalSharingEnabled = (finalPcConnected && finalUsbTethering) || finalWifiAp
                                        val finalTargetMode = if (finalDataOn && finalSharingEnabled) "5G" else "4G"
                                        
                                        if (finalTargetMode == "4G") {
                                            val msg = "ScreenStateService: 20s grace period ended, sharing still stopped -> Setting to 4G"
                                            Log.i(TAG, msg)
                                            ControlManager.addShellLog(msg)
                                            ControlManager.setPreferredNetworkType(context, "4G")
                                        } else {
                                            val msg = "ScreenStateService: 20s grace period ended, sharing resumed -> Keeping 5G"
                                            Log.i(TAG, msg)
                                            ControlManager.addShellLog(msg)
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error adjusting USB 4G fallback: ${e.message}")
                                    }
                                }.start()
                            }
                            usb5gRunnable = fallbackRunnable
                            handler.postDelayed(fallbackRunnable, 20000L)
                        }
                    } else {
                        val msg = "ScreenStateService checkAndApplyUsb5gAdjustment: isDataOn=$isDataOn, SharingEnabled=$isSharingEnabled -> Setting preferred network to 5G"
                        Log.i(TAG, msg)
                        ControlManager.addShellLog(msg)
                        ControlManager.setPreferredNetworkType(context, "5G")
                    }
                } catch (e: Exception) {
                    val errMsg = "Error adjusting USB 5G network mode: ${e.message}"
                    Log.e(TAG, errMsg)
                    ControlManager.addShellLog(errMsg)
                }
            }.start()
        }
        usb5gRunnable = runnable
        handler.postDelayed(runnable, delayMillis)
    }

    private fun refreshWidget(context: Context) {
        val updateIntent = Intent(context, QuickControlWidget::class.java).apply {
            action = QuickControlWidget.ACTION_REFRESH_WIDGET
        }
        context.sendBroadcast(updateIntent)
    }

    override fun onDestroy() {
        Log.d(TAG, "ScreenStateService Destroyed, cleaning up receivers")
        usb5gRunnable?.let { handler.removeCallbacks(it) }
        sleepModeRunnable?.let { handler.removeCallbacks(it) }
        try {
            screenReceiver?.let {
                unregisterReceiver(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister receiver: ${e.message}")
        }
        screenReceiver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d(TAG, "onTrimMemory level: $level. Minimizing service resources.")
        System.gc()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "快捷控制后台服务"
            val descriptionText = "保持屏幕亮屏和息屏时自动切换移动数据功能的正常运行"
            val importance = NotificationManager.IMPORTANCE_MIN
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val title = "快捷控制面板核心服务"
        val contentText = "正在运行后台自动化服务"
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cellular_data)
            .setContentTitle(title)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    private var sleepModeRunnable: Runnable? = null
    private var isCurrentlyInSleepMode = false

    private fun startSleepModeChecker() {
        sleepModeRunnable?.let { handler.removeCallbacks(it) }
        val checkInterval = 60_000L // every minute
        
        sleepModeRunnable = object : Runnable {
            override fun run() {
                if (PrefsManager.isSleepModeEnabled(this@ScreenStateService)) {
                    checkAndApplySleepMode()
                    handler.postDelayed(this, checkInterval)
                }
            }
        }
        handler.post(sleepModeRunnable!!)
    }

    private fun checkAndApplySleepMode() {
        val calendar = java.util.Calendar.getInstance()
        val currentHour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(java.util.Calendar.MINUTE)
        val currentTotalMinutes = currentHour * 60 + currentMinute
        
        val startPair = PrefsManager.getSleepStartTime(this)
        val endPair = PrefsManager.getSleepEndTime(this)
        
        val startTotalMinutes = startPair.first * 60 + startPair.second
        val endTotalMinutes = endPair.first * 60 + endPair.second
        
        var inTimeWindow = false
        if (startTotalMinutes <= endTotalMinutes) {
            // e.g. 01:00 to 06:00
            inTimeWindow = currentTotalMinutes in startTotalMinutes until endTotalMinutes
        } else {
            // e.g. 23:00 to 07:00
            inTimeWindow = currentTotalMinutes >= startTotalMinutes || currentTotalMinutes < endTotalMinutes
        }

        // Check if manual sleep is active
        val manualExitTime = PrefsManager.getManualSleepExitTime(this)
        var isManualActive = false
        if (manualExitTime > 0) {
            if (System.currentTimeMillis() >= manualExitTime) {
                // Time passed, auto-cancel manual mode
                PrefsManager.cancelManualSleep(this)
                Log.i(TAG, "Manual sleep exit time reached. Canceling manual sleep.")
            } else {
                isManualActive = true
            }
        }
        
        val shouldBeInSleepMode = inTimeWindow || isManualActive
        
        if (shouldBeInSleepMode && !isCurrentlyInSleepMode) {
            isCurrentlyInSleepMode = true
            Log.i(TAG, "Entering Sleep Mode...")
            Thread {
                ControlManager.setSleepModeActive(this, true)
            }.start()
        } else if (!shouldBeInSleepMode && isCurrentlyInSleepMode) {
            isCurrentlyInSleepMode = false
            Log.i(TAG, "Exiting Sleep Mode...")
            Thread {
                ControlManager.setSleepModeActive(this, false)
            }.start()
        }
    }
}
