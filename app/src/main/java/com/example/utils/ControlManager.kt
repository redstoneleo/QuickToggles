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
    
    // Memory state for flashlight to sync updates
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

    // --- SIM CARD MANAGEMENT ---
    fun getSimCardList(context: Context): List<SimInfo> {
        val list = mutableListOf<SimInfo>()
        try {
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager ?: return emptyList()
            
            val activeList = try {
                subscriptionManager.activeSubscriptionInfoList ?: emptyList()
            } catch (e: SecurityException) {
                emptyList()
            }
            val allList = try {
                subscriptionManager.allSubscriptionInfoList ?: emptyList()
            } catch (e: SecurityException) {
                emptyList()
            }

            val processedSubIds = mutableSetOf<Int>()

            for (info in activeList) {
                if (info.simSlotIndex in 0..1) {
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

            for (info in allList) {
                if (info.simSlotIndex in 0..1 && info.subscriptionId !in processedSubIds) {
                    list.add(
                        SimInfo(
                            subId = info.subscriptionId,
                            slotIndex = info.simSlotIndex,
                            displayName = info.displayName?.toString() ?: "SIM ${info.simSlotIndex + 1}",
                            isActive = false,
                            isEmbedded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.isEmbedded else false,
                            number = info.number ?: ""
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch getSimCardList: ${e.message}")
        }

        // --- ROOT FALLBACK IF APIS RETURN EMPTY ---
        if (list.isEmpty()) {
            Log.i(TAG, "Standard subscriptionManager API returned empty, attempting Root Content Provider query fallback...")
            try {
                val rootResult = ShellUtils.runCommand("content query --uri content://telephony/siminfo", useRoot = true)
                if (rootResult.isSuccess && rootResult.stdout.isNotEmpty()) {
                    val lines = rootResult.stdout.split("\n")
                    for (line in lines) {
                        val trimmedLine = line.trim()
                        if (trimmedLine.startsWith("Row:")) {
                            val parts = trimmedLine.substringAfter("Row:").trim().split(",")
                            var subId = -1
                            var slotIndex = -1
                            var displayName = ""
                            var number = ""
                            
                            for (part in parts) {
                                val trimmedPart = part.trim()
                                val key = trimmedPart.substringBefore("=").trim()
                                val rawValue = trimmedPart.substringAfter("=").trim()
                                val value = if (rawValue.startsWith("\"") && rawValue.endsWith("\"")) {
                                    rawValue.substring(1, rawValue.length - 1)
                                } else {
                                    rawValue
                                }
                                
                                when (key) {
                                    "_id" -> subId = value.toIntOrNull() ?: -1
                                    "sim_id", "slot_index" -> slotIndex = value.toIntOrNull() ?: -1
                                    "phone_id" -> {
                                        val pId = value.toIntOrNull() ?: -1
                                        if (pId >= 0) slotIndex = pId
                                    }
                                    "display_name" -> displayName = value
                                    "number" -> number = value
                                }
                            }
                            
                            if (subId != -1) { // We don't care if slotIndex is strictly 0 or 1, we want all subIds!
                                val isActive = true
                                val finalSlotIndex = if (slotIndex >= 0) slotIndex else 0 // Default to 0
                                if (list.none { it.subId == subId }) {
                                    list.add(
                                        SimInfo(
                                            subId = subId,
                                            slotIndex = finalSlotIndex,
                                            displayName = if (displayName.isNotEmpty()) displayName else "SIM (Sub $subId)",
                                            isActive = isActive,
                                            isEmbedded = false,
                                            number = number
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Failed Root SIM info fallback query: ${ex.message}")
            }
        }

        list.sortBy { it.slotIndex }
        return list
    }

    fun setSubscriptionEnabled(context: Context, subId: Int, enable: Boolean): Boolean {
        val state = if (enable) "1" else "0"
        val cmd = "cmd phone set-subscription-enabled $subId $state"
        val result = ShellUtils.runCommand(cmd, useRoot = true)
        
        if (result.isSuccess) {
            Log.i(TAG, "Shell cmd phone set-subscription-enabled $subId $state succeeded")
            return true
        } else {
            Log.e(TAG, "Shell command failed for setSubscriptionEnabled: ${result.stderr}")
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
        
        // Query active mobile SIM cards and configure them
        val sims = getSimCardList(context)
        for (sim in sims) {
            val subId = sim.subId
            val slotIndex = sim.slotIndex
            Log.d(TAG, "Setting preferred network mode $actualMode for SubId: $subId, Slot: $slotIndex")
            
            // Try standard AOSP command with the target network type
            commands.add("cmd phone set-preferred-network-type $subId $targetValStr1")

            // On some Custom ROMs (e.g. LineageOS patches), set-preferred-network-type expects the slot index (0 or 1)
            if (slotIndex != subId && slotIndex >= 0) {
                commands.add("cmd phone set-preferred-network-type $slotIndex $targetValStr1")
            }
            
            // Settings DB tables
            commands.add("settings put global preferred_network_mode_$subId $targetValStr1")
            commands.add("settings put global preferred_network_mode$slotIndex $targetValStr1")
            commands.add("settings put global preferred_network_mode_${slotIndex} $targetValStr1")
        }
        
        // Fallback for logic if sim list somehow empty
        if (sims.isEmpty()) {
            for (fallbackSubId in 0..2) {
                commands.add("cmd phone set-preferred-network-type $fallbackSubId $targetValStr1")
                commands.add("settings put global preferred_network_mode_$fallbackSubId $targetValStr1")
            }
        }
        
        val runResult = ShellUtils.runCommands(commands, useRoot = true)
        Log.i(TAG, "setPreferredNetworkType executed with result: ${runResult.isSuccess}")
        
        // Broadcast a generic connectivity intent to force settings refresh on some OEMs
        ShellUtils.runCommand("am broadcast -a android.intent.action.CONFIGURATION_CHANGED", useRoot = true)
        
        return runResult.isSuccess
    }
}
