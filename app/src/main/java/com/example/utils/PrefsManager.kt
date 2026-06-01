package com.example.utils

import android.content.Context
import android.graphics.Color

object PrefsManager {
    private const val PREFS_NAME = "QuickControlPrefs"
    private const val KEY_AUTO_DATA_TOGGLE = "auto_data_toggle"
    private const val KEY_FLASHLIGHT_POWER_CONTROL = "flashlight_power_control"
    private const val KEY_USB_5G_TOGGLE = "usb_5g_toggle"
    private const val KEY_ACTIVE_COLOR = "active_color"
    private const val KEY_INACTIVE_COLOR = "inactive_color"
    private const val DEFAULT_ACTIVE_COLOR = "#FF00E676" // Vibrant Bright Green
    private const val DEFAULT_INACTIVE_COLOR = "#FF757575" // Standard neutral grey
    private const val KEY_WORKING_STRATEGY = "working_strategy"

    fun isUsb5gToggleEnabled(context: Context): Boolean {
        val sharedPref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sharedPref.getBoolean(KEY_USB_5G_TOGGLE, false)
    }

    fun setUsb5gToggleEnabled(context: Context, enabled: Boolean) {
        val sharedPref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPref.edit().putBoolean(KEY_USB_5G_TOGGLE, enabled).apply()
    }

    fun getWorkingStrategy(context: Context): Int {
        val sharedPref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sharedPref.getInt(KEY_WORKING_STRATEGY, -1)
    }

    fun setWorkingStrategy(context: Context, strategyIndex: Int) {
        val sharedPref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPref.edit().putInt(KEY_WORKING_STRATEGY, strategyIndex).apply()
    }

    fun isAutoDataToggleEnabled(context: Context): Boolean {
        val sharedPref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sharedPref.getBoolean(KEY_AUTO_DATA_TOGGLE, false)
    }

    fun setAutoDataToggleEnabled(context: Context, enabled: Boolean) {
        val sharedPref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPref.edit().putBoolean(KEY_AUTO_DATA_TOGGLE, enabled).apply()
    }

    fun isFlashlightPowerControlEnabled(context: Context): Boolean {
        val sharedPref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sharedPref.getBoolean(KEY_FLASHLIGHT_POWER_CONTROL, false)
    }

    fun setFlashlightPowerControlEnabled(context: Context, enabled: Boolean) {
        val sharedPref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPref.edit().putBoolean(KEY_FLASHLIGHT_POWER_CONTROL, enabled).apply()
    }

    fun getActiveColor(context: Context): String {
        val sharedPref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sharedPref.getString(KEY_ACTIVE_COLOR, DEFAULT_ACTIVE_COLOR) ?: DEFAULT_ACTIVE_COLOR
    }

    fun setActiveColor(context: Context, hexColor: String) {
        val sharedPref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPref.edit().putString(KEY_ACTIVE_COLOR, hexColor).apply()
    }

    fun getInactiveColor(context: Context): String {
        val sharedPref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sharedPref.getString(KEY_INACTIVE_COLOR, DEFAULT_INACTIVE_COLOR) ?: DEFAULT_INACTIVE_COLOR
    }

    fun setInactiveColor(context: Context, hexColor: String) {
        val sharedPref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPref.edit().putString(KEY_INACTIVE_COLOR, hexColor).apply()
    }
}
