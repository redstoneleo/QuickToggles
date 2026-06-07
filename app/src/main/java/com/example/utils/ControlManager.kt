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
                if (info.subscriptionId !in processedSubIds) {
                    val finalSlotIndex = if (info.simSlotIndex >= 0) info.simSlotIndex else {
                        if (list.any { it.slotIndex == 0 }) 1 else 0
                    }
                    if (finalSlotIndex in 0..1) {
                        list.add(
                            SimInfo(
                                subId = info.subscriptionId,
                                slotIndex = finalSlotIndex,
                                displayName = info.displayName?.toString() ?: "SIM ${finalSlotIndex + 1}",
                                isActive = false,
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

        // --- ROOT FALLBACK TO AUGMENT APIS ---
        if (list.size < 2) {
            Log.i(TAG, "Standard API found < 2 SIMs, attempting Root Content Provider query fallback...")
            try {
                val rootResult = ShellUtils.runCommand("content query --uri content://telephony/siminfo", useRoot = true)
                if (rootResult.isSuccess && rootResult.stdout.isNotEmpty()) {
                    val lines = rootResult.stdout.split("\n")
                    
                    data class RootSimRecord(
                        val subId: Int,
                        val slotIndex: Int,
                        val displayName: String,
                        val number: String,
                        val isActiveRoot: Boolean
                    )
                    
                    val rootSims = mutableListOf<RootSimRecord>()
                    
                    for (line in lines) {
                        val trimmedLine = line.trim()
                        if (trimmedLine.startsWith("Row:")) {
                            val parts = trimmedLine.substringAfter("Row:").trim().split(",")
                            var subId = -1
                            var slotIndex = -1
                            var displayName = ""
                            var number = ""
                            var isActiveRoot = false
                            
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
                                    "is_active" -> isActiveRoot = value == "1" || value.equals("true", ignoreCase = true)
                                }
                            }
                            
                            if (subId != -1) {
                                rootSims.add(RootSimRecord(subId, slotIndex, displayName, number, isActiveRoot))
                            }
                        }
                    }
                    
                    // Sort descending by subId to prioritize newer SIM insertions if slotIndex implies unassigned or history
                    rootSims.sortByDescending { it.subId }
                    
                    for (record in rootSims) {
                        if (list.size >= 2) break // Allow max 2 SIMs
                        
                        if (list.none { it.subId == record.subId }) {
                            val finalSlotIndex = if (record.slotIndex >= 0) record.slotIndex else {
                                if (list.any { it.slotIndex == 0 }) 1 else 0
                            }
                            
                            // Only add if we don't already have a SIM in this guessed slot, or force it strictly if needed
                            if (list.none { it.slotIndex == finalSlotIndex }) {
                                list.add(
                                    SimInfo(
                                        subId = record.subId,
                                        slotIndex = finalSlotIndex,
                                        displayName = if (record.displayName.isNotEmpty()) record.displayName else "SIM (Sub ${record.subId})",
                                        isActive = record.isActiveRoot,
                                        isEmbedded = false,
                                        number = record.number
                                    )
                                )
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

    var lastSetSubscriptionError = ""

    fun setSubscriptionEnabled(context: Context, subId: Int, enable: Boolean): Boolean {
        val stateStr = if (enable) "true" else "false"
        val stateInt = if (enable) "1" else "0"
        
        // standard AOSP parseBoolean requires "true"/"false", but some ROMs might have custom implementations
        val cmd1 = "cmd phone set-subscription-enabled $subId $stateStr"
        val cmd2 = "cmd phone set-subscription-enabled $subId $stateInt"
        
        val isBadResult = fun(r: ShellUtils.CommandResult): Boolean {
            val combined = r.stdout + " " + r.stderr
            return !r.isSuccess || combined.contains("Exception", ignoreCase = true) || 
                   combined.contains("Error", ignoreCase = true) || combined.contains("bad ", ignoreCase = true) || 
                   combined.contains("Unknown", ignoreCase = true) || combined.contains("usage:", ignoreCase = true) ||
                   combined.contains("not found", ignoreCase = true)
        }

        var result = ShellUtils.runCommand(cmd1, useRoot = true)
        var errorAcc = result.stderr + "|" + result.stdout
        
        if (isBadResult(result)) {
           val result2 = ShellUtils.runCommand(cmd2, useRoot = true)
           if (!isBadResult(result2)) {
               result = result2
               errorAcc += "\n[cmd2]: Success"
           } else {
               errorAcc += " // " + result2.stderr + "|" + result2.stdout
               
               // Dump all columns of siminfo to know what we have
               val allColsCmd = ShellUtils.runCommand("content query --uri content://telephony/siminfo", useRoot = true)
               errorAcc += "\n[Columns]: " + allColsCmd.stdout.split('\n').firstOrNull()

               // Try Java API using reflection inside the app process (usually fails due to MODIFY_PHONE_STATE signature protection)
               try {
                   val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                   var javaSuccess = false
                   try {
                       val method1 = sm.javaClass.getMethod("setUiccApplicationsEnabled", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                       method1.invoke(sm, subId, enable)
                       javaSuccess = true
                       errorAcc += "\n[API 1]: setUiccApplicationsEnabled Success!"
                   } catch (e1: Exception) {
                       errorAcc += "\n[API 1 Error]: ${e1.cause?.message ?: e1.message}"
                       try {
                           val method2 = sm.javaClass.getMethod("setSubscriptionEnabled", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                           method2.invoke(sm, subId, enable)
                           javaSuccess = true
                           errorAcc += "\n[API 2]: setSubscriptionEnabled Success!"
                       } catch (e2: Exception) {
                           errorAcc += "\n[API 2 Error]: ${e2.cause?.message ?: e2.message}"
                       }
                   }
                   if (javaSuccess) {
                       result = ShellUtils.CommandResult(0, "Java API success", "")
                   }
               } catch (ex: Exception) {
                   errorAcc += "\n[Java API Exception]: ${ex.message}"
               }

               if (isBadResult(result)) {
                   // Fallback logic for content provider (often not working due to missing columns or read-only, but let's try AOSP & MTK columns)
                   val columnsToTry = listOf("uicc_applications_enabled", "sub_state", "sim_status")
                   var contentSuccess = false
                   for (col in columnsToTry) {
                       val contentCmd = "content update --uri content://telephony/siminfo --bind $col:i:$stateInt --where \"_id=$subId\""
                       val result3 = ShellUtils.runCommand(contentCmd, useRoot = true)
                       if (result3.isSuccess && !result3.stderr.contains("Exception") && !result3.stderr.contains("no such column") && !result3.stdout.contains("usage:")) {
                           errorAcc += "\n[Content Update]: Success with column $col"
                           contentSuccess = true
                           break
                       } else {
                           errorAcc += "\n[Content Update $col Error]: " + result3.stderr.trim() + " " + result3.stdout.trim()
                       }
                   }
                   
                   if (contentSuccess) {
                       ShellUtils.runCommand("am broadcast -a android.intent.action.ACTION_SUBINFO_RECORD_UPDATED", useRoot = true)
                       result = ShellUtils.CommandResult(0, "Content provider success", "")
                   } else {
                       // Grab full help info for debugging if all failed
                       val phoneHelp = ShellUtils.runCommand("cmd phone", useRoot = true).stdout.take(800)
                       errorAcc += "\n===========\n[Phone Help]:\n$phoneHelp"
                   }
               }
           }
        }
        
        if (!isBadResult(result)) {
            Log.i(TAG, "Shell cmd phone set-subscription-enabled $subId succeeded")
            lastSetSubscriptionError = errorAcc
            return true
        } else {
            Log.e(TAG, "Shell command failed for setSubscriptionEnabled: ${result.stderr} / ${result.stdout}")
            lastSetSubscriptionError = errorAcc // Keep full log for copying
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
            
            // Try standard AOSP command with 33
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
