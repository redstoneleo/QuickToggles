package com.example.utils

import android.content.Context
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log

data class SimInfo(
    val subId: Int,
    val slotIndex: Int,
    val displayName: String,
    val isActive: Boolean,
    val isEmbedded: Boolean,
    val number: String
)

object ControlManager {
    private const val TAG = "ControlManager"
    
    // Logs for UI consumption
    val shellLogFlow = kotlinx.coroutines.flow.MutableStateFlow("")

    fun addShellLog(msg: String) {
        android.util.Log.i(TAG, msg)
        val timeNow = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
        val current = shellLogFlow.value
        val newLog = "[$timeNow] $msg\n$current"
        shellLogFlow.value = newLog.take(50000)
    }

    private var isTorchOn = false

    // Initialize torch listener to monitor physical state changes as well
    fun initTorchListener(context: Context) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        super.onTorchModeChanged(cameraId, enabled)
                        isTorchOn = enabled
                    }
                }, null)
            } else {
                cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        super.onTorchModeChanged(cameraId, enabled)
                        isTorchOn = enabled
                    }
                }, android.os.Handler(context.mainLooper))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register torch callback: ${e.message}")
        }
    }

    // --- MOBILE DATA ---
    fun isMobileDataEnabled(context: Context): Boolean {
        // Method 1: Check Settings.Global directly (Instant, reliable, uses zero permissions or shell overhead)
        try {
            val mobileDataLocal = android.provider.Settings.Global.getInt(context.contentResolver, "mobile_data", -1)
            if (mobileDataLocal != -1) {
                return mobileDataLocal == 1
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read mobile_data from Settings.Global: ${e.message}")
        }

        // Method 2: Check TelephonyManager API as fallback (Needs READ_PHONE_STATE, might fail if restricted/no access)
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            if (telephonyManager != null) {
                return telephonyManager.isDataEnabled
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read mobile_data from TelephonyManager: ${e.message}")
        }

        // Method 3: Shell read fallback
        val result = ShellUtils.runCommand("settings get global mobile_data", useRoot = false)
        return result.stdout.trim() == "1"
    }

    fun setMobileDataEnabled(context: Context, enabled: Boolean): Boolean {
        val op = if (enabled) "enable" else "disable"
        val valInt = if (enabled) "1" else "0"
        val valBool = if (enabled) "true" else "false"
        
        val cachedStrategy = PrefsManager.getWorkingStrategy(context)
        
        fun getCommandsForStrategy(strategyIndex: Int): List<String> {
            return when (strategyIndex) {
                0 -> listOf("svc data $op")
                1 -> listOf("cmd phone data_enabled $valBool")
                2 -> listOf("cmd phone data $op")
                else -> emptyList()
            }
        }
        
        // If a working strategy is cached, use it directly but verify it actually works
        if (cachedStrategy in 0..2) {
            val cmds = getCommandsForStrategy(cachedStrategy)
            if (cmds.isNotEmpty()) {
                Log.d(TAG, "Using cached mobile data strategy: $cachedStrategy")
                val currentStateBefore = isMobileDataEnabled(context)
                
                // If current state already matches target, just run commands to be sure and return true
                if (currentStateBefore == enabled) {
                    ShellUtils.runCommands(cmds, useRoot = true)
                    ShellUtils.runCommand("settings put global mobile_data $valInt", useRoot = true)
                    return true
                }
                
                val result = ShellUtils.runCommands(cmds, useRoot = true)
                if (result.isSuccess) {
                    // Sync settings database
                    ShellUtils.runCommand("settings put global mobile_data $valInt", useRoot = true)
                    
                    // Poll and verify if state actually transitioned
                    var verified = false
                    for (t in 1..8) {
                        if (isMobileDataEnabled(context) == enabled) {
                            verified = true
                            break
                        }
                        try {
                            Thread.sleep(100)
                        } catch (e: Exception) {}
                    }
                    if (verified) {
                        return true
                    } else {
                        Log.w(TAG, "Cached strategy $cachedStrategy failed to actually change state. Clearing cache.")
                        PrefsManager.setWorkingStrategy(context, -1)
                    }
                } else {
                    Log.w(TAG, "Cached strategy $cachedStrategy execution shell error. Clearing cache.")
                    PrefsManager.setWorkingStrategy(context, -1)
                }
            }
        }
        
        // States already match, cannot test strategy because we need a delta.
        // Run complete fallback pipeline directly in this case.
        val currentState = isMobileDataEnabled(context)
        if (currentState == enabled) {
            Log.d(TAG, "State already matches target: $enabled. Execution completed via fallback pipeline.")
            val allCommands = listOf(
                "svc data $op",
                "cmd phone data_enabled $valBool",
                "cmd phone data $op",
                "settings put global mobile_data $valInt",
                "settings put global mobile_data1 $valInt",
                "settings put global mobile_data2 $valInt"
            )
            ShellUtils.runCommands(allCommands, useRoot = true)
            return true
        }
        
        // Active discovery of working strategy (excluding database-only write settings)
        Log.i(TAG, "Detecting optimal mobile data control strategy (current: $currentState -> target: $enabled)...")
        for (strategyIndex in 0..2) {
            val cmds = getCommandsForStrategy(strategyIndex)
            val result = ShellUtils.runCommands(cmds, useRoot = true)
            if (result.isSuccess) {
                // Sync settings database
                ShellUtils.runCommand("settings put global mobile_data $valInt", useRoot = true)
                
                // Poll check up to 8 times for real hardware state transition
                var verified = false
                for (t in 1..8) {
                    if (isMobileDataEnabled(context) == enabled) {
                        verified = true
                        break
                    }
                    try {
                        Thread.sleep(100)
                    } catch (e: Exception) {}
                }
                if (verified) {
                    Log.i(TAG, "SUCCESS: Strategy $strategyIndex is effective! Saving to cache.")
                    PrefsManager.setWorkingStrategy(context, strategyIndex)
                    return true
                }
            }
        }
        
        // Final fallback: execute ALL commands in sequence to guarantee the toggle works on any OEM ROM
        Log.w(TAG, "No cached or detected strategy worked perfectly under time limits. Executing complete fallback pipeline.")
        val allCommands = listOf(
            "svc data $op",
            "cmd phone data_enabled $valBool",
            "cmd phone data $op",
            "settings put global mobile_data $valInt",
            "settings put global mobile_data1 $valInt",
            "settings put global mobile_data2 $valInt"
        )
        val finalResult = ShellUtils.runCommands(allCommands, useRoot = true)
        return finalResult.isSuccess
    }

    // --- SLEEP MODE ---
    fun setSleepModeActive(context: Context, active: Boolean): Boolean {
        val opState = if (active) "1" else "0"
        val opBool = if (active) "true" else "false"
        
        val commands = listOf(
            // Airplane mode
            "settings put global airplane_mode_on $opState",
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $opBool",
            // Battery saver (Low Power Mode)
            "settings put global low_power $opState",
            "am broadcast -a android.os.action.POWER_SAVE_MODE_CHANGED"
        )
        val logMsg = "Setting Sleep Mode active: $active"
        Log.i(TAG, logMsg)
        addShellLog(logMsg)
        
        val result = ShellUtils.runCommands(commands, useRoot = true)
        
        // Also force disable/enable wifi according to airplane mode state just to be safe
        if (active) {
            ShellUtils.runCommand("svc wifi disable", useRoot = true)
            ShellUtils.runCommand("svc bluetooth disable", useRoot = true)
        } else {
            // Restore wifi if the user usually has it on? It's better to just toggle airplane mode.
            // When airplane mode is off, Android usually restores the previous states of Wifi/BT.
        }

        return result.isSuccess
    }

    // --- WI-FI ---
    fun isWifiEnabled(context: Context): Boolean {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager != null) {
            return wifiManager.isWifiEnabled
        }
        return try {
            android.provider.Settings.Global.getInt(context.contentResolver, "wifi_on", 0) == 1
        } catch (e: Exception) {
            val result = ShellUtils.runCommand("settings get global wifi_on", useRoot = false)
            result.stdout.trim() == "1"
        }
    }

    fun setWifiEnabled(context: Context, enabled: Boolean): Boolean {
        // Try standard Android SDK api first (Instant, zero shell overhead on compatible SDKs or when authorized/system app)
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager != null) {
            try {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = enabled
                // Check if state actually changed or matches target (since API might return without error but block on Android 10+)
                if (wifiManager.isWifiEnabled == enabled) {
                    Log.i(TAG, "Successfully toggled Wi-Fi via WifiManager API")
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "WifiManager API toggle not allowed: ${e.message}")
            }
        }

        // Root/Shell toggle (highly optimal commands first)
        val op = if (enabled) "enable" else "disable"
        val cmdWifiOp = if (enabled) "enabled" else "disabled"
        
        // 'cmd wifi' is direct Binder communication, which is vastly faster than 'svc' (no heavy JVM startup)
        val commands = listOf(
            "cmd wifi set-wifi-enabled $cmdWifiOp",
            "svc wifi $op"
        )
        val result = ShellUtils.runCommands(commands, useRoot = true)
        return result.isSuccess
    }

    // --- GPS / LOCATION ---
    fun isGpsEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager != null) {
            try {
                return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            } catch (e: Exception) {
                Log.e(TAG, "Error checking GPS via LocationManager: ${e.message}")
            }
        }
        return try {
            android.provider.Settings.Secure.getInt(context.contentResolver, "location_mode", 0) > 0
        } catch (e: Exception) {
            val result = ShellUtils.runCommand("settings get secure location_mode", useRoot = false)
            val mode = result.stdout.trim().toIntOrNull() ?: 0
            mode > 0
        }
    }

    fun setGpsEnabled(context: Context, enabled: Boolean): Boolean {
        val opValue = if (enabled) "true" else "false"
        val modeValue = if (enabled) "3" else "0" // 3 = HIGH_ACCURACY, 0 = OFF

        val commands = listOf(
            "cmd location set-location-enabled $opValue",
            "settings put secure location_mode $modeValue"
        )
        val result = ShellUtils.runCommands(commands, useRoot = true)
        return result.isSuccess
    }

    // --- FLASHLIGHT ---
    fun isFlashlightEnabled(): Boolean {
        return isTorchOn
    }

    fun setFlashlightEnabled(context: Context, enabled: Boolean): Boolean {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
        try {
            val cameraIds = cameraManager.cameraIdList
            if (cameraIds.isNotEmpty()) {
                val cameraId = cameraIds[0]
                cameraManager.setTorchMode(cameraId, enabled)
                isTorchOn = enabled
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling flashlight: ${e.message}")
        }
        return false
    }

    fun isSimSlotInserted(context: Context, slotIndex: Int): Boolean {
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return false
            val simState = if (Build.VERSION.SDK_INT >= 26) {
                telephonyManager.getSimState(slotIndex)
            } else {
                try {
                    telephonyManager.javaClass.getMethod("getSimState", Int::class.javaPrimitiveType).invoke(telephonyManager, slotIndex) as Int
                } catch (e: Exception) {
                    telephonyManager.simState
                }
            }
            return simState != TelephonyManager.SIM_STATE_ABSENT && simState != TelephonyManager.SIM_STATE_UNKNOWN
        } catch (e: Exception) {
            Log.w(TAG, "isSimSlotInserted error: ${e.message}")
            return false
        }
    }

    // --- SIM CARD MANAGEMENT ---
    fun getSimCardList(context: Context): List<SimInfo> {
        val list = mutableListOf<SimInfo>()
        try {
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                ?: return emptyList()
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

            val phoneCount = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    telephonyManager?.activeModemCount ?: 1
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    telephonyManager?.phoneCount ?: 1
                } else {
                    1
                }
            } catch (e: Exception) { 1 }

            val processedSubIds = mutableSetOf<Int>()

            for (slot in 0 until phoneCount) {
                try {
                    val infoForSlot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            subscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(slot)
                        } catch (e: Exception) { null }
                    } else {
                        try {
                            subscriptionManager.javaClass.getMethod("getActiveSubscriptionInfoForSimSlotIndex", Int::class.javaPrimitiveType)
                                .invoke(subscriptionManager, slot) as? SubscriptionInfo
                        } catch (e: Exception) { null }
                    }

                    if (infoForSlot != null) {
                        list.add(
                            SimInfo(
                                subId = infoForSlot.subscriptionId,
                                slotIndex = slot,
                                displayName = infoForSlot.displayName?.toString() ?: "SIM ${slot + 1}",
                                isActive = true,
                                isEmbedded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) infoForSlot.isEmbedded else false,
                                number = infoForSlot.number ?: ""
                            )
                        )
                        processedSubIds.add(infoForSlot.subscriptionId)
                        continue
                    }

                    if (isSimSlotInserted(context, slot)) {
                        val allList = try { subscriptionManager.allSubscriptionInfoList ?: emptyList() } catch (e: SecurityException) { emptyList() }
                        var match = allList.filter { it.simSlotIndex == slot && it.subscriptionId !in processedSubIds }
                            .maxByOrNull { it.subscriptionId }
                        
                        // Fallback: disabled SIMs might have simSlotIndex == -1 in allList, check via Root DB
                        if (match == null) {
                            val candidates = allList.filter { it.simSlotIndex == -1 && it.subscriptionId !in processedSubIds }
                            for (candidate in candidates) {
                                val rootResult = ShellUtils.runCommand(
                                    "content query --uri content://telephony/siminfo --where \"_id=${candidate.subscriptionId}\"",
                                    useRoot = true
                                )
                                var rootSlot = -1
                                if (rootResult.isSuccess && rootResult.stdout.isNotEmpty()) {
                                    val rowPart = rootResult.stdout.substringAfter("Row:", "").substringBefore("\n")
                                    val parts = rowPart.split(",")
                                    for (part in parts) {
                                        val p = part.trim()
                                        if (!p.contains("=")) continue
                                        val key = p.substringBefore("=").trim().lowercase()
                                        val value = p.substringAfter("=").trim().trim('"')
                                        if (key in listOf("slot_index", "phone_id", "sim_slot_index", "sim_slot", "slot", "simslotindex", "sim_id")) {
                                            val t = value.toIntOrNull()
                                            if (t != null && t >= 0) {
                                                rootSlot = t
                                            }
                                        }
                                    }
                                }
                                if (rootSlot == slot) {
                                    match = candidate
                                    break
                                }
                            }
                        }

                        if (match != null) {
                            list.add(
                                SimInfo(
                                    subId = match.subscriptionId,
                                    slotIndex = slot,
                                    displayName = match.displayName?.toString() ?: "SIM ${slot + 1}",
                                    isActive = false,
                                    isEmbedded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) match.isEmbedded else false,
                                    number = match.number ?: ""
                                )
                            )
                            processedSubIds.add(match.subscriptionId)
                        } else {
                            list.add(
                                SimInfo(
                                    subId = -1,
                                    slotIndex = slot,
                                    displayName = "SIM ${slot + 1}",
                                    isActive = false,
                                    isEmbedded = false,
                                    number = ""
                                )
                            )
                        }
                    }
                } catch (ignore: Exception) {}
            }

            if (list.isEmpty()) {
                val activeList = try { subscriptionManager.activeSubscriptionInfoList ?: emptyList() } catch (e: SecurityException) { emptyList() }
                for (info in activeList) {
                    if (info.simSlotIndex in 0..1 && list.none { it.slotIndex == info.simSlotIndex }) {
                        list.add(
                            SimInfo(
                                subId = info.subscriptionId,
                                slotIndex = info.simSlotIndex,
                                displayName = info.displayName?.toString() ?: "SIM ${info.simSlotIndex + 1}",
                                isActive = true,
                                isEmbedded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.isEmbedded else false,
                                number = info.number ?: ""
                            )
                        )
                        processedSubIds.add(info.subscriptionId)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch getSimCardList: ${e.message}")
        }

        try {
            val rootResult = ShellUtils.runCommand("content query --uri content://telephony/siminfo", useRoot = true)
            if (rootResult.isSuccess && rootResult.stdout.isNotEmpty()) {
                val rootLines = rootResult.stdout.lines()
                data class RootSimRecord(val subId: Int, val slotIndex: Int, val displayName: String, val number: String, val isActiveRoot: Boolean)
                val rootSims = mutableListOf<RootSimRecord>()
                for (line in rootLines) {
                    val t = line.trim()
                    if (!t.startsWith("Row:")) continue
                    val parts = t.substringAfter("Row:").split(",")
                    var subId = -1
                    var slotIndex = -1
                    var displayName = ""
                    var carrierName = ""
                    var number = ""
                    var isActiveRoot = false
                    for (part in parts) {
                        val p = part.trim()
                        if (!p.contains("=")) continue
                        val key = p.substringBefore("=").trim().lowercase()
                        var value = p.substringAfter("=").trim().trim('"')
                        when (key) {
                            "_id", "subscription_id", "sub_id", "sim_id" -> subId = value.toIntOrNull() ?: subId
                            "slot_index", "phone_id", "sim_slot_index", "sim_slot", "slot" -> slotIndex = value.toIntOrNull() ?: slotIndex
                            "simslotindex", "simslot" -> slotIndex = value.toIntOrNull() ?: slotIndex
                            "display_name", "name" -> displayName = value
                            "carrier_name", "carrier" -> carrierName = value
                            "number", "address" -> number = value
                            "is_active", "isactive", "active", "sub_state", "sim_status", "subscription_status" -> {
                                isActiveRoot = (value == "1" || value.equals("true", true))
                            }
                        }
                    }
                    if (displayName.isEmpty() || displayName.startsWith("CARD ", ignoreCase = true) || displayName.startsWith("SIM ", ignoreCase = true)) {
                        if (carrierName.isNotEmpty() && carrierName != "null") {
                            displayName = carrierName
                        }
                    }
                    if (subId != -1) {
                        rootSims.add(RootSimRecord(subId, slotIndex, displayName, number, isActiveRoot))
                    }
                }
                rootSims.sortWith(compareBy({ it.slotIndex }, { -it.subId }))
                for (rec in rootSims) {
                    if (list.any { it.slotIndex == rec.slotIndex && rec.slotIndex >= 0 }) {
                        val existing = list.firstOrNull { it.slotIndex == rec.slotIndex }
                        if (existing != null && existing.subId == -1 && rec.subId != -1) {
                            val index = list.indexOf(existing)
                            list[index] = existing.copy(
                                subId = rec.subId,
                                isActive = rec.isActiveRoot,
                                displayName = if (rec.displayName.isNotEmpty()) rec.displayName else existing.displayName
                            )
                        } else if (existing != null) {
                            // Update display name if root query has a better name and existing is just a placeholder
                            if (rec.displayName.isNotEmpty() && !rec.displayName.startsWith("SIM ", ignoreCase = true) && !rec.displayName.startsWith("CARD ", ignoreCase = true) && 
                                (existing.displayName.isEmpty() || existing.displayName.startsWith("SIM ", ignoreCase = true) || existing.displayName.startsWith("CARD ", ignoreCase = true))) {
                                val index = list.indexOf(existing)
                                list[index] = existing.copy(
                                    displayName = rec.displayName
                                )
                            }
                        }
                        continue
                    }
                    if (rec.slotIndex in 0..10 && isSimSlotInserted(context, rec.slotIndex)) {
                        list.add(
                            SimInfo(
                                subId = rec.subId,
                                slotIndex = rec.slotIndex,
                                displayName = if (rec.displayName.isNotEmpty()) rec.displayName else "SIM ${rec.slotIndex + 1}",
                                isActive = rec.isActiveRoot,
                                isEmbedded = false,
                                number = rec.number
                            )
                        )
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Root SIM info fallback failed: ${ex.message}")
        }

        return list.sortedBy { it.slotIndex }
    }

    var lastSetSubscriptionError = ""

    fun setSubscriptionEnabled(context: Context, subId: Int, enable: Boolean): Boolean {
        val stateStr = if (enable) "true" else "false"
        val stateInt = if (enable) "1" else "0"
        
        val isBadResult = fun(r: ShellUtils.CommandResult): Boolean {
            val combined = r.stdout + " " + r.stderr
            if (!r.isSuccess) return true
            val combinedLower = combined.lowercase()
            return combinedLower.contains("exception") || 
                   combinedLower.contains("error") || 
                   combinedLower.contains("bad ") || 
                   combinedLower.contains("unknown") || 
                   combinedLower.contains("usage:") ||
                   combinedLower.contains("not found") ||
                   combinedLower.contains("cmd: can't find service:")
        }

        var errorAcc = ""
        var result = ShellUtils.CommandResult(-1, "", "")
        var isRealSuccess = false
        
        var remainingSubId = -1
        if (!enable) {
            val list = getSimCardList(context)
            val activeSims = list.filter { it.isActive && it.subId != subId }
            if (activeSims.size == 1) {
                remainingSubId = activeSims[0].subId
            }
        } else {
            // Aggressive database preset for new SIM enablement:
            // When enabling a potentially inactive/new SIM, make it visible to the OS first
            val cols = listOf("uicc_applications_enabled", "is_active", "sim_status", "sub_state")
            for (col in cols) {
                ShellUtils.runCommand("content update --uri content://telephony/siminfo --bind $col:i:1 --where \"_id=$subId\"", useRoot = true)
            }
            ShellUtils.runCommand("am broadcast -a android.intent.action.ACTION_SUBINFO_RECORD_UPDATED", useRoot = true)
        }

        // 1. Try app_process root java api
        val apkPath = context.applicationInfo.sourceDir
        val className = "com.example.utils.RootSimTool"
        val appProcessCmd = "export CLASSPATH=$apkPath && app_process /system/bin $className $subId $enable $remainingSubId"
        
        val rAppProcess = ShellUtils.runCommand(appProcessCmd, useRoot = true)
        val outputProcess = rAppProcess.stdout.trim() + " " + rAppProcess.stderr.trim()
        if (outputProcess.contains("SUCCESS_ROOT_API")) {
            errorAcc += "\n[app_process SUCCESS] Root API execution succeeded"
            result = rAppProcess
            isRealSuccess = true
        } else {
            errorAcc += "\n[app_process FAIL] => ${outputProcess.take(300)}"
        }

        // 2. Try the different cmd phone commands if app_process failed
        if (!isRealSuccess) {
            val commandsToTry = listOf(
                "cmd phone set-subscription-enabled $subId $stateStr",
                "cmd phone set-subscription-enabled $subId $stateInt",
                "cmd phone set-uicc-applications-enabled $subId $stateStr",
                "cmd phone set-uicc-applications-enabled $subId $stateInt",
                "cmd phone uicc-applications-enable $subId $stateStr",
                "cmd phone uicc-applications-enable $subId $stateInt",
                "cmd phone enable-subscription $subId $stateStr",
                "cmd phone enable-subscription $subId $stateInt",
                "cmd phone subinfo-set-active $subId $stateStr",
                "cmd phone subinfo-set-active $subId $stateInt",
                // content call
                "content call --uri content://telephony/siminfo --method setUiccApplicationsEnabled --extra subId:i:$subId --extra enable:b:$stateStr",
                "content call --uri content://telephony/siminfo --method setSubscriptionEnabled --extra subId:i:$subId --extra enable:b:$stateStr"
            )
            
            for (cmd in commandsToTry) {
                val r = ShellUtils.runCommand(cmd, useRoot = true)
                if (!isBadResult(r)) {
                    errorAcc += "\n[SUCCESS] $cmd"
                    result = r
                    isRealSuccess = true
                    break
                } else {
                    errorAcc += "\n[FAIL] $cmd => " + (r.stdout.trim() + " " + r.stderr.trim()).take(100)
                }
            }
        }

        if (!isRealSuccess) {
            // Try fallback Java API via Context just in case (usually fails due to MODIFY_PHONE_STATE)
            try {
                val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                val methodsToTry = listOf("setUiccApplicationsEnabled", "setSubscriptionEnabled")
                for (mName in methodsToTry) {
                    try {
                        val method = sm.javaClass.getMethod(mName, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                        method.invoke(sm, subId, enable)
                        isRealSuccess = true
                        result = ShellUtils.CommandResult(0, "Java API success", "")
                        errorAcc += "\n[API SUCCESS] $mName"
                        break
                    } catch (e: Exception) {
                        errorAcc += "\n[API FAIL] $mName => ${e.cause?.message ?: e.message}"
                    }
                }
            } catch (ex: Exception) {
                errorAcc += "\n[API Exception] => ${ex.message}"
            }
        }

        if (!isRealSuccess) {
            // content update
             val columnsToTry = listOf("uicc_applications_enabled", "sub_state", "sim_status", "is_active")
             for (col in columnsToTry) {
                 val contentCmd = "content update --uri content://telephony/siminfo --bind $col:i:$stateInt --where \"_id=$subId\""
                 val r = ShellUtils.runCommand(contentCmd, useRoot = true)
                 if (!isBadResult(r) && !r.stderr.contains("no such column")) {
                     errorAcc += "\n[Content Update warning] Success with $col, but this may NOT actually enable the hardware SIM."
                     ShellUtils.runCommand("am broadcast -a android.intent.action.ACTION_SUBINFO_RECORD_UPDATED", useRoot = true)
                     // NOT setting isRealSuccess=true because content update doesn't activate radio
                     break
                 } else {
                     errorAcc += "\n[Content Update FAIL] $col => " + (r.stdout.trim() + " " + r.stderr.trim()).take(60)
                 }
             }
             
             // Grab phone help for debugging
             val phoneHelp = ShellUtils.runCommand("cmd phone", useRoot = true).stdout.take(800)
             errorAcc += "\n\n[Phone Help Dump]:\n$phoneHelp"
             
             val isubHelp = ShellUtils.runCommand("cmd isub", useRoot = true).stdout.take(200)
             errorAcc += "\n\n[Isub Help Dump]:\n$isubHelp"
        }
        
        lastSetSubscriptionError = errorAcc
        addShellLog("\n--- setSubscriptionEnabled($subId, $enable) ---$errorAcc")
        
        if (isRealSuccess) {
            Log.i(TAG, "Shell cmd phone set-subscription-enabled $subId succeeded")
            
            // Set voice/sms defaults to "Ask every time" (-1)
            ShellUtils.runCommand("settings put global multi_sim_voice_call -1", useRoot = true)
            ShellUtils.runCommand("settings put global multi_sim_sms -1", useRoot = true)

            // If we have a single remaining active SIM, set it as default data to prevent system dialog
            if (remainingSubId != -1) {
                ShellUtils.runCommand("settings put global multi_sim_data_call $remainingSubId", useRoot = true)
            }
            
            return true
        } else {
            Log.e(TAG, "Shell command failed for setSubscriptionEnabled: $errorAcc")
            return false
        }
    }

    fun setDefaultSimsToAsk(): Boolean {
        val commands = listOf(
            "settings put global multi_sim_voice_call -1",
            "settings put global multi_sim_sms -1",
            "settings put global multi_sim_voice_call_subscription -1",
            "settings put global multi_sim_sms_subscription -1"
        )
        val result = ShellUtils.runCommands(commands, useRoot = true)
        return result.isSuccess
    }

    fun setPreferredNetworkType(context: Context, mode: String): Boolean {
        // mode is "4G" or "5G"
        Log.i(TAG, "setPreferredNetworkType requested: $mode")
        
        val actualMode = mode

        // Android standard / manufacturer specific values
        // 26 = NR_LTE_GSM_WCDMA (5G Auto)
        // 33 = NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA (5G Global/China)
        // 9  = LTE_GSM_WCDMA (4G Auto)
        // 22 = LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA (4G Global/China)
        val targetValStr1 = if (actualMode == "5G") "33" else "22"
        val is5G = if (actualMode == "5G") "1" else "0"
        
        val commands = mutableListOf<String>()
        
        // Brand-specific 5G Toggles (Xiaomi, Oppo, Vivo, Samsung, etc)
        commands.add("settings put global fiveg_user_enable $is5G")
        commands.add("settings put global fiveg_user_enable_1 $is5G")
        commands.add("settings put global fiveg_user_enable_2 $is5G")
        commands.add("settings put global nr_mode_enabled $is5G")
        commands.add("settings put global fiveg_switch $is5G")
        
        // AOSP / LineageOS 12+ specific toggles
        commands.add("settings put global user_enabled_nr_config $is5G")
        commands.add("settings put global user_fiveg_switch $is5G")
        commands.add("settings put global setup_wizard_fiveg_toggle $is5G")
        
        // Settings database entries for global defaults
        commands.add("settings put global preferred_network_mode $targetValStr1")
        commands.add("settings put global preferred_network_mode1 $targetValStr1")
        commands.add("settings put global preferred_network_mode2 $targetValStr1")
        
        // Find default data SIM
        var defaultDataSubId = -1
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                defaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
            }
        } catch (e: Exception) {}
        
        if (defaultDataSubId == -1) {
            val result = ShellUtils.runCommand("settings get global multi_sim_data_call", useRoot = false)
            defaultDataSubId = result.stdout.trim().toIntOrNull() ?: -1
        }
        
        addShellLog("Detected Data SubId: $defaultDataSubId for 5G switch.")
        
        // Query active mobile SIM cards and configure them
        val sims = getSimCardList(context)
        val dataSims = if (defaultDataSubId != -1) sims.filter { it.subId == defaultDataSubId } else sims
        val targetSims = if (dataSims.isEmpty() && sims.isNotEmpty()) listOf(sims.first()) else dataSims

        if (targetSims.isEmpty()) {
            addShellLog("No target SIMs found for 5G switch!")
        }

        for (sim in targetSims) {
            val subId = sim.subId
            val slotIndex = sim.slotIndex
            val logMsg = "Setting preferred network mode $actualMode for SubId: $subId, Slot: $slotIndex"
            Log.d(TAG, logMsg)
            addShellLog(logMsg)
            
            // Try modern bitmask command via shell (Android 12+)
            // 5G Network Type Bitmask is 1 << 19. If 5G, we pass mask 2g3g4g5g, else 2g3g4g
            val mask2g3g4g = ((1L shl 19) - 1)
            val targetMask = if (actualMode == "5G") mask2g3g4g or (1L shl 19) else mask2g3g4g
            commands.add("cmd phone set-allowed-network-types-bitmask $slotIndex $targetMask")

            // standard AOSP command typically expects phoneId (slotIndex)
            if (slotIndex >= 0) {
                commands.add("cmd phone set-preferred-network-type $slotIndex $targetValStr1")
            }
            // Fallback to subId just in case some OEM modified it to take subId
            commands.add("cmd phone set-preferred-network-type $subId $targetValStr1")
            
            // Settings DB tables
            commands.add("settings put global preferred_network_mode_$subId $targetValStr1")
            commands.add("settings put global preferred_network_mode$slotIndex $targetValStr1")
            commands.add("settings put global preferred_network_mode_${slotIndex} $targetValStr1")
            
            // Other common OEM settings DB keys
            commands.add("settings put global preferred_network_type_$subId $targetValStr1")
            commands.add("settings put global preferred_network_type_${slotIndex} $targetValStr1")
            commands.add("settings put global user_network_mode_$subId $targetValStr1")
            commands.add("settings put global user_network_mode_${slotIndex} $targetValStr1")
            
            // App_process java call to invoke ITelephony
            val javaCmd = "export CLASSPATH=${context.packageCodePath}; app_process /system/bin com.example.utils.RootSimTool network $subId $targetValStr1 $slotIndex"
            commands.add(javaCmd)
            
            // Force settings update
            commands.add("setprop persist.radio.preferred_network_mode $targetValStr1")
            commands.add("setprop persist.radio.preferred_network_mode_$subId $targetValStr1")
            commands.add("setprop persist.radio.preferred_network_mode$slotIndex $targetValStr1")
            
            // Force stop settings app so it re-reads from framework
            commands.add("am force-stop com.android.settings")
        }
        
        // Commenting out the last-resort hammer parameter so it does not forcibly drop connection
        // commands.add("svc data disable && sleep 1 && svc data enable")
        
        // Fallback for logic if sim list somehow empty
        if (targetSims.isEmpty() && sims.isEmpty()) {
            for (fallbackSubId in 0..2) {
                commands.add("cmd phone set-preferred-network-type $fallbackSubId $targetValStr1")
                commands.add("settings put global preferred_network_mode_$fallbackSubId $targetValStr1")
            }
        }
        
        val runResult = ShellUtils.runCommands(commands, useRoot = true)
        val logMsg = "setPreferredNetworkType ($actualMode, val=$targetValStr1) executed with result: ${runResult.isSuccess}\nStdout: ${runResult.stdout}\nStderr: ${runResult.stderr}"
        Log.i(TAG, logMsg)
        addShellLog(logMsg)
        
        // Broadcast a generic connectivity intent to force settings refresh on some OEMs
        ShellUtils.runCommand("am broadcast -a android.intent.action.CONFIGURATION_CHANGED", useRoot = true)
        
        return runResult.isSuccess
    }
}
