package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.service.ScreenStateService
import com.example.utils.PrefsManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed intent received.")
            val isAutoData = PrefsManager.isAutoDataToggleEnabled(context)
            val isFlashlightCtrl = PrefsManager.isFlashlightPowerControlEnabled(context)
            if (isAutoData || isFlashlightCtrl) {
                Log.d("BootReceiver", "Core background features are active. Starting ScreenStateService...")
                val serviceIntent = Intent(context, ScreenStateService::class.java)
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed starting ScreenStateService from boot: ${e.message}")
                }
            }
        }
    }
}
