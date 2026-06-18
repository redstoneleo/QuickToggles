package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoTetheringAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "AutoTetheringService"
        var shouldAutoClick = false
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!shouldAutoClick) return
        
        event ?: return
        val rootNode = rootInActiveWindow ?: return

        // 寻找包含 "USB" 文本的节点
        val nodes = rootNode.findAccessibilityNodeInfosByText("USB")
        var targetNode: AccessibilityNodeInfo? = null

        for (node in nodes) {
            val text = node.text?.toString() ?: node.contentDescription?.toString()
            if (text != null && text.contains("USB", ignoreCase = true)) {
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

        targetNode?.let { node ->
            Log.i(TAG, "Found target node to click, performing click...")
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            shouldAutoClick = false // 成功点击后重置
            
            performGlobalAction(GLOBAL_ACTION_BACK)
            
            // We just let the framework recycle it or it gets gc'd.
        }
        
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service Interrupted")
    }
}
