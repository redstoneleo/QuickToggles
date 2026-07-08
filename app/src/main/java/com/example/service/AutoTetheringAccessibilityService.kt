package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

enum class AutoTask(val targetTexts: List<String>) {
    NONE(emptyList()),
    TOGGLE_USB_TETHERING(listOf("USB", "Tethering", "网络共享", "热点")),
    TOGGLE_BLUETOOTH_TETHERING(listOf("Bluetooth tethering", "蓝牙网络共享", "Bluetooth", "蓝牙")),
    TOGGLE_MOBILE_DATA(listOf("Mobile data", "Data connection", "移动数据", "数据网络")),
    TOGGLE_WIFI(listOf("Wi-Fi", "WLAN")),
    TOGGLE_GPS(listOf("Location", "位置信息", "定位")),
    TOGGLE_AIRPLANE_MODE(listOf("Airplane mode", "飞行模式"))
}

class AutoTetheringAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "AutoTetheringService"
        var currentTask: AutoTask = AutoTask.NONE
        var currentTargetState: Boolean? = null
        
        fun startTask(context: Context, task: AutoTask, intent: Intent, targetState: Boolean? = null) {
            currentTask = task
            currentTargetState = targetState
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(intent)
        }

        fun startTask(context: Context, task: AutoTask, action: String, targetState: Boolean? = null) {
            currentTask = task
            currentTargetState = targetState
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
    }

    override fun onServiceConnected() {
        Log.i(TAG, "Accessibility Service Connected")
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        serviceInfo = info
    }

    private fun findAssociatedCheckableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isCheckable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isCheckable) return child
            val found = findAssociatedCheckableNode(child)
            if (found != null) return found
        }
        return null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (currentTask == AutoTask.NONE) return
        
        val rootNode = rootInActiveWindow ?: return

        var targetNode: AccessibilityNodeInfo? = null

        for (targetText in currentTask.targetTexts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(targetText)
            
            // First pass: try to find a node that has a checkable element
            for (node in nodes) {
                val text = node.text?.toString() ?: node.contentDescription?.toString()
                if (text != null && text.contains(targetText, ignoreCase = true)) {
                    var clickableNode: AccessibilityNodeInfo? = node
                    while (clickableNode != null && !clickableNode.isClickable) {
                        clickableNode = clickableNode.parent
                    }
                    if (clickableNode != null) {
                        val checkable = findAssociatedCheckableNode(clickableNode)
                        if (checkable != null) {
                            targetNode = clickableNode
                            break
                        }
                    }
                }
            }
            
            if (targetNode != null) break
            
            // Second pass: fallback if no checkable element is found
            for (node in nodes) {
                val text = node.text?.toString() ?: node.contentDescription?.toString()
                if (text != null && text.contains(targetText, ignoreCase = true)) {
                    var clickableNode: AccessibilityNodeInfo? = node
                    while (clickableNode != null && !clickableNode.isClickable) {
                        clickableNode = clickableNode.parent
                    }
                    if (clickableNode != null) {
                        targetNode = clickableNode
                        break
                    }
                }
            }
            if (targetNode != null) break
        }

        targetNode?.let { node ->
            var needsClick = true
            val checkableNode = findAssociatedCheckableNode(node)

            if (currentTargetState != null) {
                if (checkableNode != null) {
                    val isCurrentlyChecked = checkableNode.isChecked
                    Log.i(TAG, "Checkable node found. isChecked: $isCurrentlyChecked, target: $currentTargetState")
                    if (isCurrentlyChecked == currentTargetState) {
                        Log.i(TAG, "Already in desired state. Skipping click.")
                        needsClick = false
                    }
                } else {
                    Log.w(TAG, "Checkable node NOT found for this setting. Clicking anyway just in case.")
                }
            }

            if (needsClick) {
                // If the item (or its checkable child) is disabled, wait.
                val isNodeEnabled = node.isEnabled && (checkableNode?.isEnabled ?: true)
                if (!isNodeEnabled) {
                    Log.i(TAG, "Target node is disabled. Waiting for it to become enabled...")
                    return // Wait for the next event, do not clear the task yet.
                }

                Log.i(TAG, "Found target node to click, performing click...")
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            
            // We just let the framework recycle it or it gets gc'd.
            
            currentTask = AutoTask.NONE // 成功点击后重置
            currentTargetState = null
            
            // Small delay before back press to allow UI to update
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                performGlobalAction(GLOBAL_ACTION_BACK)
            }, 500)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service Interrupted")
    }
}
