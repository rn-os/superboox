package com.superboox.quickaccess

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

/**
 * Opens a note in BOOX Notes by name.
 *
 * Notes has no public "open note" intent, so we do what a person would:
 * start Notes fresh at its library, find the item whose label matches each
 * path segment ("Work / Standup" = tap "Work", then tap "Standup"), scrolling
 * the list if it isn't visible yet. Driven by a polling loop rather than
 * accessibility events so it doesn't depend on how Notes reports changes.
 */
class NoteOpener(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private var job: Job? = null

    private class Job(val pkg: String, val steps: List<String>, val deadline: Long) {
        var index = 0
        var scrolls = 0
        var notBefore = 0L
        var loggedNoRoot = false
    }

    fun open(pkg: String, path: String) {
        cancel()
        val steps = BooxNotes.splitPath(path)
        val intent = BooxNotes.launchIntent(service, pkg, fresh = true)
        if (intent == null) {
            DebugLog.w("note", "no launch intent for $pkg")
            toast("BOOX Notes ($pkg) is not installed")
            return
        }
        if (!Launcher.start(service, intent) || steps.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        job = Job(pkg, steps, now + TIMEOUT_MS).also { it.notBefore = now + FIRST_DELAY_MS }
        handler.postDelayed(::tick, FIRST_DELAY_MS)
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        job = null
    }

    private fun tick() {
        val j = job ?: return
        val now = SystemClock.uptimeMillis()
        if (now > j.deadline) {
            fail(j)
            return
        }
        if (now < j.notBefore) {
            handler.postDelayed(::tick, j.notBefore - now)
            return
        }
        val root = notesRoot(j.pkg)
        if (root == null) {
            if (!j.loggedNoRoot) {
                j.loggedNoRoot = true
                DebugLog.i("note", "waiting for ${j.pkg} window (active=${service.rootInActiveWindow?.packageName})")
            }
            handler.postDelayed(::tick, POLL_MS)
            return
        }

        val target = j.steps[j.index]
        val node = findLabel(root, target)
        if (node != null) {
            DebugLog.i("note", "found \"$target\" (${node.className}, clickable=${node.isClickable}) step ${j.index + 1}/${j.steps.size}")
            tap(node)
            j.index++
            j.scrolls = 0
            if (j.index >= j.steps.size) {
                job = null
                return
            }
            j.notBefore = now + AFTER_TAP_MS
            handler.postDelayed(::tick, AFTER_TAP_MS)
            return
        }

        // Not on screen yet: give the list a moment to load, then page through it.
        val scrollable = findFirst(root) { it.isScrollable }
        if (scrollable != null && j.scrolls < MAX_SCROLLS && now > j.notBefore + LOAD_GRACE_MS) {
            val moved = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            DebugLog.i("note", "\"$target\" not visible; scroll ${j.scrolls + 1} -> $moved")
            if (!moved) {
                fail(j)
                return
            }
            j.scrolls++
            j.notBefore = now + AFTER_SCROLL_MS
        }
        handler.postDelayed(::tick, POLL_MS)
    }

    private fun fail(j: Job) {
        job = null
        val root = notesRoot(j.pkg)
        DebugLog.w(
            "note",
            "gave up on \"${j.steps.getOrNull(j.index)}\" (step ${j.index + 1}/${j.steps.size}, scrolls ${j.scrolls}). " +
                if (root == null) "Notes window not found." else "Screen:\n" + DebugReport.describeTree(root),
        )
        toast("Couldn't find \"${j.steps.getOrNull(j.index) ?: ""}\" in BOOX Notes")
    }

    private fun notesRoot(pkg: String): AccessibilityNodeInfo? {
        val fromWindows = runCatching {
            service.windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName == pkg } }
        }.getOrNull()
        return fromWindows ?: service.rootInActiveWindow?.takeIf { it.packageName == pkg }
    }

    private fun findLabel(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val want = normalize(label)
        val candidates = root.findAccessibilityNodeInfosByText(label).orEmpty()
        return candidates.firstOrNull { labelOf(it) == want }
            ?: candidates.firstOrNull { labelOf(it).startsWith(want) }
            // findAccessibilityNodeInfosByText skips some custom views; fall back to a full walk.
            ?: findFirst(root) { labelOf(it) == want }
    }

    private fun tap(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 8) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            n = n.parent
            depth++
        }
        DebugLog.i("note", "no clickable ancestor; tapping bounds instead")
        // Nothing clickable in the hierarchy (custom-drawn list): tap its center instead.
        val r = Rect()
        node.getBoundsInScreen(r)
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        service.dispatchGesture(gesture, null, null)
    }

    private fun toast(msg: String) = Toast.makeText(service, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TIMEOUT_MS = 15_000L
        private const val FIRST_DELAY_MS = 900L
        private const val POLL_MS = 350L
        private const val AFTER_TAP_MS = 1_000L
        private const val AFTER_SCROLL_MS = 700L
        private const val LOAD_GRACE_MS = 800L
        private const val MAX_SCROLLS = 25

        fun normalize(s: CharSequence?): String = s?.toString()?.trim()?.lowercase().orEmpty()

        fun labelOf(n: AccessibilityNodeInfo): String =
            normalize(n.text).ifEmpty { normalize(n.contentDescription) }

        fun findFirst(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val n = queue.removeFirst()
                visited++
                if (predicate(n)) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
            }
            return null
        }

        /**
         * Visible text labels in a window, in reading order. Used for "pin a
         * note from this screen": the user picks which label is the note.
         */
        fun visibleLabels(root: AccessibilityNodeInfo, limit: Int = 80): List<String> {
            val out = LinkedHashSet<String>()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < MAX_NODES && out.size < limit) {
                val n = queue.removeFirst()
                visited++
                val t = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
                if (t.length in 1..80 && n.isVisibleToUser) out += t
                for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
            }
            return out.toList()
        }

        private const val MAX_NODES = 3_000
    }
}
