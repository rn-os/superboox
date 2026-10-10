package com.superboox.quickaccess

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Test for Quick Controls: can SuperBoox see the BOOX Control Center's tiles
 * and whether each is on? Opens the Control Center (Android's Quick Settings),
 * lists its windows and the tiles' names and states, then closes it. Taps
 * nothing. Runs only when the debug report is built.
 */
object ControlCenterProbe {
    fun run(service: AccessibilityService, done: (List<String>) -> Unit) {
        QuickControls.open(service) { confirmed ->
            val out = ArrayList<String>()
            out += "open Control Center: ${if (confirmed) "ok" else "not confirmed (notifications or nothing showed)"}"
            runCatching { read(service, out) }.onFailure { out += "read failed: ${it.javaClass.simpleName} ${it.message}" }
            val closed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            } else {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
            out += "close Control Center: ${if (closed) "ok" else "refused"}"
            done(out)
        }
    }

    private fun read(service: AccessibilityService, out: MutableList<String>) {
        val windows = service.windows
        for (w in windows) {
            val root = w.root
            val pkg = root?.packageName?.toString()
            out += "window type=${typeName(w.type)} pkg=$pkg title=${w.title}"
            if (root == null || pkg == service.packageName) continue
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM && pkg?.contains("systemui") != true) continue
            val tiles = LinkedHashSet<String>()
            fun walk(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > 40 || tiles.size >= 80) return
                val label = (n.contentDescription ?: n.text)?.toString()?.replace("\n", " ")?.trim().orEmpty()
                if (label.isNotEmpty() && (n.isClickable || n.isCheckable || n.isLongClickable)) {
                    val state = buildList {
                        if (n.isCheckable) add(if (n.isChecked) "on" else "off")
                        if (n.isSelected) add("selected")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) n.stateDescription?.let { add("\"$it\"") }
                        if (n.isLongClickable) add("hold")
                        n.viewIdResourceName?.substringAfter(":id/")?.let { add("#$it") }
                    }
                    tiles += "${label.take(40)} [${n.className?.toString()?.substringAfterLast('.')}${if (state.isEmpty()) "" else "; " + state.joinToString()}]"
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
            }
            walk(root, 0)
            out += "  tiles (${tiles.size}): " + tiles.joinToString(" | ").ifEmpty { "none readable" }
            if (pkg?.contains("systemui") == true) frontLight(root, out)
        }
    }

    /**
     * BOOX's Front Light presets (Bright / Soft / Custom / Off) sit under the
     * tiles. Lists the area around them, with which one is selected, to see
     * whether Quick Controls can switch presets.
     */
    private fun frontLight(root: AccessibilityNodeInfo, out: MutableList<String>) {
        val anchor = root.findAccessibilityNodeInfosByText("Soft").firstOrNull()
            ?: root.findAccessibilityNodeInfosByText("Front Light").firstOrNull()
        if (anchor == null) {
            out += "  front light: not visible (may need scrolling)"
            return
        }
        var area: AccessibilityNodeInfo = anchor
        repeat(4) { area = area.parent ?: return@repeat }
        out += "  front light area:"
        var count = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 10 || count >= 60) return
            count++
            val flags = buildList {
                if (n.isClickable) add("click")
                if (n.isLongClickable) add("hold")
                if (n.isSelected) add("selected")
                if (n.isCheckable) add(if (n.isChecked) "checked" else "unchecked")
                if (n.isScrollable) add("scroll")
                if (!n.isVisibleToUser) add("hidden")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) n.stateDescription?.let { add("\"$it\"") }
                n.rangeInfo?.let { add("range ${it.current} of ${it.min}-${it.max}") }
            }
            val label = (n.text ?: n.contentDescription)?.toString()?.replace("\n", " ")?.take(30)
            out += "    " + "  ".repeat(depth) + (n.className?.toString()?.substringAfterLast('.') ?: "?") +
                (n.viewIdResourceName?.substringAfter(":id/")?.let { " #$it" } ?: "") +
                (if (label.isNullOrBlank()) "" else " \"$label\"") +
                (if (flags.isEmpty()) "" else " [${flags.joinToString()}]")
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(area, 0)
    }

    private fun typeName(t: Int) = when (t) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "keyboard"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "overlay"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "divider"
        else -> t.toString()
    }
}
