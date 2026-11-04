package com.you.spy.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SpyAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: SpyAccessibilityService? = null
        @Volatile var lastText: String = ""
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val t = e.text?.joinToString("").orEmpty()
            if (t.isNotBlank()) lastText = "${e.packageName}: $t"
        }
    }

    override fun onInterrupt() {}

    fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        dispatch(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build())
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 250) {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatch(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, ms))
            .build())
    }

    fun type(text: String): Boolean {
        val node = rootInActiveWindow ?: return false
        val target = findEditable(node) ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun clickByText(text: String): Boolean {
        val node = rootInActiveWindow ?: return false
        return find(node, text)?.let {
            var n: AccessibilityNodeInfo? = it
            while (n != null && !n.isClickable) n = n.parent
            (n ?: it).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } ?: false
    }

    fun dumpScreen(): String {
        val root = rootInActiveWindow ?: return "no window"
        val sb = StringBuilder()
        walk(root, sb, 0)
        return sb.toString().take(6000)
    }

    private fun walk(n: AccessibilityNodeInfo?, sb: StringBuilder, d: Int) {
        if (n == null || d > 25) return
        val label = n.text?.toString() ?: n.contentDescription?.toString()
        if (!label.isNullOrBlank()) sb.appendLine("${" ".repeat(d)}$label")
        for (i in 0 until n.childCount) walk(n.getChild(i), sb, d + 1)
    }

    private fun find(n: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (n.text?.contains(text, true) == true ||
            n.contentDescription?.contains(text, true) == true) return n
        for (i in 0 until n.childCount) {
            find(n.getChild(i) ?: continue, text)?.let { return it }
        }
        return null
    }

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) findEditable(n.getChild(i))?.let { return it }
        return null
    }

    private fun dispatch(g: GestureDescription) {
        if (Build.VERSION.SDK_INT >= 24) dispatchGesture(g, null, null)
    }
}
