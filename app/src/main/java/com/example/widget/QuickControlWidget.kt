package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.widget.RemoteViews
import com.example.R
import com.example.utils.ControlManager
import com.example.utils.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class QuickControlWidget : AppWidgetProvider() {

    companion object {
        private const val TAG = "QuickControlWidget"
        
        // Custom Broadcast Actions for clicking each of the four button columns
        const val ACTION_TOGGLE_DATA = "com.example.widget.ACTION_TOGGLE_DATA"
        const val ACTION_TOGGLE_WIFI = "com.example.widget.ACTION_TOGGLE_WIFI"
        const val ACTION_TOGGLE_GPS = "com.example.widget.ACTION_TOGGLE_GPS"
        const val ACTION_TOGGLE_FLASH = "com.example.widget.ACTION_TOGGLE_FLASH"
        const val ACTION_REFRESH_WIDGET = "com.example.widget.ACTION_REFRESH_WIDGET"

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // 1. Get current status states of hardware switches
            val isDataOn = ControlManager.isMobileDataEnabled(context)
            val isWifiOn = ControlManager.isWifiEnabled(context)
            val isGpsOn = ControlManager.isGpsEnabled(context)
            val isFlashOn = ControlManager.isFlashlightEnabled()

            // 2. Fetch custom active/inactive colors specified in Preferences
            val activeColorStr = PrefsManager.getActiveColor(context)
            val inactiveColorStr = PrefsManager.getInactiveColor(context)
            
            val colorActive = Color.parseColor(activeColorStr)
            val colorInactive = Color.parseColor(inactiveColorStr)

            // Helper to style each button column dynamically
            fun styleButton(
                isOn: Boolean,
                iconViewId: Int,
                textViewId: Int,
                onText: String,
                offText: String
            ) {
                val color = if (isOn) colorActive else colorInactive
                views.setInt(iconViewId, "setColorFilter", color)
                views.setTextColor(textViewId, color)
                views.setTextViewText(textViewId, if (isOn) onText else offText)
            }

            styleButton(isDataOn, R.id.btn_data_icon, R.id.btn_data_text, "流量:开", "流量:关")
            styleButton(isWifiOn, R.id.btn_wifi_icon, R.id.btn_wifi_text, "WiFi:开", "WiFi:关")
            styleButton(isGpsOn, R.id.btn_gps_icon, R.id.btn_gps_text, "GPS:开", "GPS:关")
            styleButton(isFlashOn, R.id.btn_flash_icon, R.id.btn_flash_text, "手电:开", "手电:关")

            // 3. Setup click listeners
            views.setOnClickPendingIntent(R.id.btn_data_container, getPendingIntent(context, ACTION_TOGGLE_DATA))
            views.setOnClickPendingIntent(R.id.btn_wifi_container, getPendingIntent(context, ACTION_TOGGLE_WIFI))
            views.setOnClickPendingIntent(R.id.btn_gps_container, getPendingIntent(context, ACTION_TOGGLE_GPS))
            views.setOnClickPendingIntent(R.id.btn_flash_container, getPendingIntent(context, ACTION_TOGGLE_FLASH))

            // Instruct AppWidgetManager to update the widget
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun getPendingIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(context, QuickControlWidget::class.java).apply {
                this.action = action
            }
            return PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Init torch mode change listener
        ControlManager.initTorchListener(context)
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun notifyUsb5gAdjustment(context: Context) {
        val intent = Intent(context, com.example.service.ScreenStateService::class.java)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ScreenStateService: ${e.message}")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        Log.d(TAG, "Widget Broadcast received action: ${intent.action}")

        // Launch toggling in IO Coroutine scope to prevent Application Not Responding (ANR)
        CoroutineScope(Dispatchers.IO).launch {
            when (intent.action) {
                ACTION_TOGGLE_DATA -> {
                    val current = ControlManager.isMobileDataEnabled(context)
                    ControlManager.setMobileDataEnabled(context, !current)
                    notifyUsb5gAdjustment(context)
                }
                ACTION_TOGGLE_WIFI -> {
                    val current = ControlManager.isWifiEnabled(context)
                    ControlManager.setWifiEnabled(context, !current)
                }
                ACTION_TOGGLE_GPS -> {
                    val current = ControlManager.isGpsEnabled(context)
                    ControlManager.setGpsEnabled(context, !current)
                }
                ACTION_TOGGLE_FLASH -> {
                    val current = ControlManager.isFlashlightEnabled()
                    ControlManager.setFlashlightEnabled(context, !current)
                }
                Intent.ACTION_BOOT_COMPLETED, ACTION_REFRESH_WIDGET -> {
                    // Just refresh
                }
            }

            // Immediately refresh all layout views
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, QuickControlWidget::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId)
            }
        }
    }
}
