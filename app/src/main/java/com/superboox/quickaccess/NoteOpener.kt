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
 * Notes has no public "open note" intent, so we do what a person would, one
 * step per tick of a polling loop (independent of how Notes reports events):
 *  - bring Notes to the front;
 *  - if a note is already open: done if it's the one we want, otherwise press
 *    Back until we're in the library (this is what lets you jump from one open
 *    note straight to another);
 *  - in the library: scroll the list to the top, then look for the next path
 *    segment ("Work / Standup" = tap "Work", then "Standup"), paging down;
 *  - not in this folder: Back up a level and look again.
 * See [NotesScreen] for how the editor and library are told apart.
 */
class NoteOpener(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private var job: Job? = null

    private class Job(val pkg: String, val steps: List<String>, val deadline: Long) {
        var index = 0
        var scrolls = 0
        var backs = 0
        var relaunches = 0
        var rewound = false
        var notBefore = 0L
        var missingSince = 0L
        var lastState = ""
    }

    fun open(pkg: String, path: String) {
        cancel()
        val steps = BooxNotes.splitPath(path)
        // Bring the existing task forward instead of restarting it: restarting
        // can drop the last strokes of a note that's open, and BOOX may reopen
        // the last note anyway. The loop below backs out of an open note.
        val intent = BooxNotes.launchIntent(service, pkg, fresh = false)
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
        if (now > j.deadline) return fail(j)
        if (now < j.notBefore) return later(j.notBefore - now)

        val root = notesRoot(j.pkg)
        if (root == null) {
            // Backed out of Notes entirely (or it's still starting): bring it back once or twice.
            if (j.missingSince == 0L) j.missingSince = now
            if (now - j.missingSince > MISSING_MS && j.relaunches < MAX_RELAUNCHES) {
                j.relaunches++
                j.missingSince = 0L
                j.rewound = false
                DebugLog.i("note", "Notes not in front; relaunching (${j.relaunches})")
                BooxNotes.launchIntent(service, j.pkg, fresh = false)?.let { Launcher.start(service, it) }
                return wait(j, now, AFTER_BACK_MS)
            }
            return later(POLL_MS)
        }
        j.missingSince = 0L

        val target = j.steps[j.index]
        val last = j.steps.last()
        when (val state = NotesScreen.read(root)) {
            is NotesScreen.State.Editor -> {
                logState(j, "editor \"${state.title}\"")
                if (NotesScreen.matches(state.title, last)) {
                    DebugLog.i("note", "\"$last\" is open")
                    job = null
                    return
                }
                back(j, now, "another note is open")
            }

            is NotesScreen.State.Library -> {
                logState(j, "library: ${state.cards.joinToString { it.label }.take(300)}")
                val list = state.list
                if (!j.rewound) {
                    // Start every folder from the top so paging down sees everything.
                    j.rewound = true
                    if (list != null) {
                        var n = 0
                        while (n < MAX_SCROLLS && list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) n++
                        if (n > 0) {
                            DebugLog.i("note", "scrolled list to top ($n)")
                            return wait(j, now, AFTER_SCROLL_MS)
                        }
                    }
                }
                val card = state.cards.firstOrNull { NotesScreen.matches(it.label, target) }
                    ?: findLabel(root, target)?.let { NotesScreen.Card(target, it) }
                if (card != null) {
                    DebugLog.i("note", "found \"$target\" (${card.node.className}) step ${j.index + 1}/${j.steps.size}")
                    tap(card.node)
                    j.index++
                    j.scrolls = 0
                    j.rewound = false
                    if (j.index >= j.steps.size) {
                        job = null
                        return
                    }
                    return wait(j, now, AFTER_TAP_MS)
                }
                if (now < j.notBefore + LOAD_GRACE_MS) return later(POLL_MS) // let the list finish loading
                if (list != null && j.scrolls < MAX_SCROLLS && list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    j.scrolls++
                    DebugLog.i("note", "\"$target\" not visible; page ${j.scrolls}")
                    return wait(j, now, AFTER_SCROLL_MS)
                }
                // Not in this folder. Only the first segment is searched from wherever
                // we are; later segments must be inside the folder we just opened.
                if (j.index == 0) back(j, now, "\"$target\" not in this folder") else fail(j)
            }

            NotesScreen.State.Unknown -> {
                logState(j, "unknown screen")
                if (now < j.notBefore + LOAD_GRACE_MS) return later(POLL_MS)
                back(j, now, "unrecognised Notes screen")
            }
        }
    }

    private fun back(j: Job, now: Long, why: String) {
        if (j.backs >= MAX_BACKS) return fail(j)
        j.backs++
        j.scrolls = 0
        j.rewound = false
        DebugLog.i("note", "Back ($why) ${j.backs}/$MAX_BACKS")
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        wait(j, now, AFTER_BACK_MS)
    }

    private fun wait(j: Job, now: Long, ms: Long) {
        j.notBefore = now + ms
        later(ms)
    }

    private fun later(ms: Long) {
        handler.postDelayed(::tick, ms)
    }

    private fun logState(j: Job, state: String) {
        if (state != j.lastState) DebugLog.i("note", "screen: $state")
        j.lastState = state
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
        val candidates = root.findAccessibilityNodeInfosByText(label).orEmpty()
        return candidates.firstOrNull { NotesScreen.matches(NotesScreen.textOf(it), label) }
            // findAccessibilityNodeInfosByText skips some custom views and ellipsized names; walk the tree too.
            ?: findFirst(root) { NotesScreen.matches(NotesScreen.textOf(it), label) }
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
        // E-ink redraws are slow; these are deliberately generous.
        private const val TIMEOUT_MS = 25_000L
        private const val FIRST_DELAY_MS = 900L
        private const val POLL_MS = 350L
        private const val AFTER_TAP_MS = 1_200L
        private const val AFTER_BACK_MS = 1_200L
        private const val AFTER_SCROLL_MS = 700L
        private const val LOAD_GRACE_MS = 800L
        private const val MISSING_MS = 2_000L
        private const val MAX_SCROLLS = 25
        private const val MAX_BACKS = 5
        private const val MAX_RELAUNCHES = 2

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

        private const val MAX_NODES = 3_000
    }
}
