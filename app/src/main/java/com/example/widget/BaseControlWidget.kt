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

abstract class BaseControlWidget : AppWidgetProvider() {

    abstract val iconResId: Int
    abstract val textOn: String
    abstract val textOff: String
    abstract val actionToggle: String
    
    abstract fun isFeatureOn(context: Context): Boolean
    abstract fun toggleFeature(context: Context, currentState: Boolean)
    
    companion object {
        const val ACTION_REFRESH_ALL_WIDGETS = "com.example.widget.ACTION_REFRESH_ALL_WIDGETS"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        ControlManager.initTorchListener(context)
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_single_layout)
        
        val isOn = isFeatureOn(context)
        val activeColorStr = PrefsManager.getActiveColor(context)
        val inactiveColorStr = PrefsManager.getInactiveColor(context)
        
        val colorActive = Color.parseColor(activeColorStr)
        val colorInactive = Color.parseColor(inactiveColorStr)
        
        val color = if (isOn) colorActive else colorInactive
        
        views.setImageViewResource(R.id.btn_icon, iconResId)
        views.setInt(R.id.btn_icon, "setColorFilter", color)
        views.setTextColor(R.id.btn_text, color)
        views.setTextViewText(R.id.btn_text, if (isOn) textOn else textOff)
        
        val intent = Intent(context, this::class.java).apply {
            action = actionToggle
        }
        
        // Android allows Widget click PendingIntents to start activities from the background gracefully.
        // We can use getBroadcast to avoid opening an activity entirely unless we absolutely have to.
        // For Android 14+ we should use PendingIntent.getBroadcast and we can pass ActivityOptions to grant background start privileges,
        // but by default widgets have it.
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            actionToggle.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btn_container, pendingIntent)
        
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == actionToggle) {
            val pendingResult = goAsync() // keep the broadcast alive during coroutines
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val appContext = context.applicationContext
                    val current = isFeatureOn(appContext)
                    toggleFeature(appContext, current)
                    
                    // Poll for state change up to 3 seconds before sending broadcast
                    // This ensures the widget UI updates precisely when the state actually flips
                    for (i in 1..30) {
                        kotlinx.coroutines.delay(100)
                        if (isFeatureOn(appContext) != current) {
                            break
                        }
                    }
                    
                    // Also trigger an update for all widgets to stay in sync
                    val refreshIntent = Intent(appContext, this@BaseControlWidget::class.java).apply {
                        action = ACTION_REFRESH_ALL_WIDGETS
                    }
                    appContext.sendBroadcast(refreshIntent)
                } finally {
                    pendingResult.finish()
                }
            }
        } else if (intent.action == ACTION_REFRESH_ALL_WIDGETS || intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, this::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId)
            }
        }
    }
    
    protected fun notifyUsb5gAdjustment(context: Context) {
        val intent = Intent(context, com.example.service.ScreenStateService::class.java)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e("BaseControlWidget", "Failed to start ScreenStateService: ${e.message}")
        }
    }
}
