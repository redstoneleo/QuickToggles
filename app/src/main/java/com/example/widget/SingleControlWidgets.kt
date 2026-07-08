package com.example.widget

import android.content.Context
import com.example.R
import com.example.utils.ControlManager

class DataControlWidget : BaseControlWidget() {
    override val iconResId = R.drawable.ic_cellular_data
    override val textOn = "流量"
    override val textOff = "流量"
    override val actionToggle = "com.example.widget.ACTION_TOGGLE_DATA"

    override fun isFeatureOn(context: Context): Boolean = ControlManager.isMobileDataEnabled(context)
    
    override fun toggleFeature(context: Context, currentState: Boolean) {
        ControlManager.setMobileDataEnabled(context, !currentState)
        notifyUsb5gAdjustment(context)
    }
}

class WifiControlWidget : BaseControlWidget() {
    override val iconResId = R.drawable.ic_wifi
    override val textOn = "WiFi"
    override val textOff = "WiFi"
    override val actionToggle = "com.example.widget.ACTION_TOGGLE_WIFI"

    override fun isFeatureOn(context: Context): Boolean = ControlManager.isWifiEnabled(context)
    
    override fun toggleFeature(context: Context, currentState: Boolean) {
        ControlManager.setWifiEnabled(context, !currentState)
    }
}

class GpsControlWidget : BaseControlWidget() {
    override val iconResId = R.drawable.ic_gps
    override val textOn = "GPS"
    override val textOff = "GPS"
    override val actionToggle = "com.example.widget.ACTION_TOGGLE_GPS"

    override fun isFeatureOn(context: Context): Boolean = ControlManager.isGpsEnabled(context)
    
    override fun toggleFeature(context: Context, currentState: Boolean) {
        ControlManager.setGpsEnabled(context, !currentState)
    }
}

class FlashControlWidget : BaseControlWidget() {
    override val iconResId = R.drawable.ic_flashlight
    override val textOn = "手电"
    override val textOff = "手电"
    override val actionToggle = "com.example.widget.ACTION_TOGGLE_FLASH"

    override fun isFeatureOn(context: Context): Boolean = ControlManager.isFlashlightEnabled()
    
    override fun toggleFeature(context: Context, currentState: Boolean) {
        ControlManager.setFlashlightEnabled(context, !currentState)
    }
}

class BtTetherControlWidget : BaseControlWidget() {
    override val iconResId = R.drawable.ic_bluetooth
    override val textOn = "蓝牙共享"
    override val textOff = "蓝牙共享"
    override val actionToggle = "com.example.widget.ACTION_TOGGLE_BT_TETHER"

    override fun isFeatureOn(context: Context): Boolean = ControlManager.isBluetoothTetheringActive(context)
    
    override fun toggleFeature(context: Context, currentState: Boolean) {
        ControlManager.setBluetoothTetheringEnabled(context, !currentState)
    }
}
