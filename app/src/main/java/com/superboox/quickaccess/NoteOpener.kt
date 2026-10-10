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
 *    Back until we're in the library (so you can jump from one note to another);
 *  - in the library: look for the next path segment ("Work / Standup" = open
 *    "Work", then "Standup"), turning pages forward and then backward;
 *  - tap it and **confirm the screen changed**; if an accessibility click did
 *    nothing (some BOOX views only react to real touches), tap it with a touch
 *    gesture instead;
 *  - not in this folder: Back up a level and look again.
 * BOOX apps often flip pages instead of scrolling, so page turns fall back to
 * swipe gestures when the list isn't scrollable. See [NotesScreen] for how
 * the editor and library are told apart.
 */
class NoteOpener(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private var job: Job? = null

    private class Job(val packages: Set<String>, val notesPackage: String, val steps: List<String>, val deadline: Long) {
        /** While > 0: we asked BOOX to open the note by id and are waiting for it to appear. */
        var directUntil = 0L

        /** Set when we opened the BOOX launcher and still need to tap its "Notes" tab. */
        var needsNotesTab = false
        var index = 0
        var backs = 0
        var relaunches = 0
        var notBefore = 0L
        var missingSince = 0L
        var lastState = ""

        // Page turning within the current folder.
        var rewound = false
        var pageTurns = 0
        var direction = 1 // 1 = forward, -1 = backward
        var pageMethod = 0 // 0 = scroll action, 1 = vertical swipe, 2 = horizontal swipe
        var signatureBeforeTurn: String? = null

        // A tap waiting to be confirmed.
        /** After Back: wait (polling) until the screen differs from this, instead of a fixed delay. */
        var awaitChangeFrom: String? = null
        var awaitUntil = 0L

        var tapSignature: String? = null
        var tapAt = 0L
        var tapBounds: Rect? = null
        var tapAttempts = 0

        fun resetFolder() {
            rewound = false
            pageTurns = 0
            direction = 1
            pageMethod = 0
            signatureBeforeTurn = null
        }
    }

    /**
     * @param inNotes BOOX Notes (library or a note) is already on screen, so
     *   there's nothing to launch: the loop navigates from where we are.
     */
    fun open(notesPackage: String, path: String, inNotes: Boolean) {
        cancel()
        val steps = BooxNotes.splitPath(path)
        if (steps.isEmpty()) return
        toast("Opening \"${steps.last()}\"…")
        // Look the note's id up off the main thread, then try opening it directly.
        val token = Any()
        pending = token
        io.execute {
            val id = NotesDb.findNoteId(service, steps)
            handler.post { if (pending === token) start(notesPackage, steps, inNotes, id) }
        }
    }

    private var pending: Any? = null

    private fun start(notesPackage: String, steps: List<String>, inNotes: Boolean, id: String?) {
        val j = Job(BooxNotes.packages(notesPackage), notesPackage, steps, SystemClock.uptimeMillis() + TIMEOUT_MS)
        val direct = id?.let { NotesDb.openIntent(service, notesPackage, it) }
        if (direct != null && Launcher.tryStart(service, direct)) {
            // Fast path: BOOX opens the note itself. The loop confirms it, or falls back.
            DebugLog.i("note", "opening by id")
            j.directUntil = SystemClock.uptimeMillis() + DIRECT_WAIT_MS
        } else if (!inNotes && !launchNotes(j)) {
            toast("Couldn't open BOOX Notes on this device")
            return
        }
        val now = SystemClock.uptimeMillis()
        job = j.also { it.notBefore = now + FIRST_DELAY_MS }
        handler.postDelayed(::tick, FIRST_DELAY_MS)
    }

    /**
     * Brings up Notes. Doesn't restart it: restarting could drop the last
     * strokes of an open note. The loop backs out of an open note itself.
     */
    private fun launchNotes(j: Job): Boolean {
        for ((how, intent) in BooxNotes.launchCandidates(service, j.notesPackage)) {
            if (Launcher.tryStart(service, intent)) {
                DebugLog.i("note", "opened Notes via $how")
                j.needsNotesTab = how == "BOOX launcher"
                return true
            }
        }
        DebugLog.w("note", "no way to open Notes. Activities: ${BooxNotes.describeActivities(service, j.notesPackage)}")
        return false
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        job = null
        pending = null
    }

    private fun tick() {
        val j = job ?: return
        val now = SystemClock.uptimeMillis()
        if (now > j.deadline) return fail(j, "timed out")
        if (now < j.notBefore) return later(j.notBefore - now)

        // Waiting on "open by id": succeed when the note shows up, otherwise fall back to
        // finding it in the library. Never press Back while waiting (the old note may linger).
        if (j.directUntil > 0) {
            val state = notesRoot(j.packages)?.let { NotesScreen.read(it) }
            if (state is NotesScreen.State.Editor && NotesScreen.matches(state.title, j.steps.last())) {
                DebugLog.i("note", "opened by id")
                return succeed(j)
            }
            if (now < j.directUntil) return later(POLL_MS)
            DebugLog.w("note", "open by id didn't show \"${j.steps.last()}\" (screen: ${state?.let { NotesScreen.signature(it) }}); finding it in the library instead")
            j.directUntil = 0
            if (notesRoot(j.packages) == null && !launchNotes(j)) return fail(j, "couldn't open BOOX Notes")
            return wait(j, now, FIRST_DELAY_MS)
        }

        val root = notesRoot(j.packages)
        if (root == null) {
            // Backed out of Notes entirely (or it's still starting): bring it back once or twice.
            if (j.missingSince == 0L) j.missingSince = now
            if (now - j.missingSince > MISSING_MS && j.relaunches < MAX_RELAUNCHES) {
                j.relaunches++
                j.missingSince = 0L
                j.resetFolder()
                DebugLog.i("note", "Notes not in front; relaunching (${j.relaunches})")
                launchNotes(j)
                return wait(j, now, AFTER_BACK_MS)
            }
            return later(POLL_MS)
        }
        j.missingSince = 0L

        val state = NotesScreen.read(root)
        val signature = NotesScreen.signature(state)
        // Still showing the screen we just pressed Back on: keep polling (e-ink is slow).
        j.awaitChangeFrom?.let { from ->
            if (signature == from && now < j.awaitUntil) return later(POLL_MS)
            j.awaitChangeFrom = null
            j.notBefore = now // the load grace below counts from when the new screen appeared
        }
        logState(j, state)

        // 1. Confirm the last tap did something before moving on.
        j.tapSignature?.let { before ->
            // BOOX shows a blank hand-off screen between library and editor; wait it out.
            if (state is NotesScreen.State.Unknown && signature != before && now - j.tapAt < AFTER_TAP_MS * 2) return later(POLL_MS)
            if (signature != before) {
                DebugLog.i("note", "tap on \"${j.steps[j.index]}\" worked")
                j.tapSignature = null
                j.notBefore = now
                j.index++
                j.resetFolder()
                if (j.index >= j.steps.size) return succeed(j)
                // fall through and handle the new screen
            } else if (now - j.tapAt < AFTER_TAP_MS) {
                return later(POLL_MS) // give the tap time to land before retrying
            } else if (j.tapAttempts < MAX_TAP_ATTEMPTS) {
                val bounds = (state as? NotesScreen.State.Library)?.cards
                    ?.firstOrNull { NotesScreen.matches(it.label, j.steps[j.index]) }?.bounds ?: j.tapBounds
                    ?: return fail(j, "the note disappeared after tapping it")
                j.tapAttempts++
                DebugLog.i("note", "tap didn't change the screen; touching ${bounds.toShortString()} (attempt ${j.tapAttempts})")
                touch(bounds)
                j.tapAt = now
                return later(POLL_MS)
            } else {
                return fail(j, "tapping \"${j.steps[j.index]}\" didn't open it")
            }
        }

        val target = j.steps[j.index]
        when (state) {
            is NotesScreen.State.Editor -> {
                if (NotesScreen.matches(state.title, j.steps.last())) return succeed(j)
                back(j, now, signature, "another note is open (\"${state.title}\")")
            }

            is NotesScreen.State.Library -> {
                val list = state.list
                if (!j.rewound) {
                    // Start each folder from the top when the list scrolls; paged
                    // lists are searched forward, then backward, instead.
                    j.rewound = true
                    if (list != null) {
                        var n = 0
                        while (n < MAX_PAGE_TURNS && list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) n++
                        if (n > 0) {
                            DebugLog.i("note", "scrolled list to top ($n)")
                            return wait(j, now, AFTER_PAGE_MS)
                        }
                    }
                }

                val card = state.cards.firstOrNull { NotesScreen.matches(it.label, target) }
                val node = card?.node ?: findLabel(root, target)
                if (node != null) {
                    DebugLog.i("note", "found \"$target\" (${node.className}) step ${j.index + 1}/${j.steps.size}")
                    j.tapSignature = signature
                    j.tapBounds = card?.bounds ?: Rect().also(node::getBoundsInScreen)
                    j.tapAttempts = 0
                    j.tapAt = now
                    click(node, card?.bounds)
                    return later(POLL_MS)
                }
                if (now < j.notBefore + LOAD_GRACE_MS) return later(POLL_MS) // let the page finish loading
                // Opened via the BOOX launcher and the note isn't on its home screen: go to its Notes tab.
                if (openNotesTab(j, root, now, signature)) return

                if (turnPage(j, state, signature)) return wait(j, now, AFTER_PAGE_MS)
                // Not in this folder. Only the first segment is searched from wherever
                // we are; later segments must be inside the folder we just opened.
                if (j.index == 0) back(j, now, signature, "\"$target\" not in this folder") else fail(j, "\"$target\" isn't in that folder")
            }

            NotesScreen.State.Unknown -> {
                // Usually a screen still loading (the BOOX home takes ~2 s on e-ink): be patient.
                if (now < j.notBefore + UNKNOWN_GRACE_MS) return later(POLL_MS)
                if (openNotesTab(j, root, now, signature)) return
                back(j, now, signature, "unrecognised Notes screen")
            }
        }
    }

    /**
     * Shows the next (or previous) page of the library. Tries the list's scroll
     * action, then a vertical swipe, then a horizontal one, and notices when a
     * method stops changing the page: that's the end in this direction.
     */
    private fun turnPage(j: Job, state: NotesScreen.State.Library, signature: String): Boolean {
        if (j.signatureBeforeTurn == signature) {
            // The last turn did nothing: try the next method, then the other direction.
            j.pageMethod++
            if (j.pageMethod > 2) {
                if (j.direction == -1) return false
                DebugLog.i("note", "end of list; searching backwards")
                j.direction = -1
                j.pageMethod = 0
            }
        }
        if (j.pageTurns >= MAX_PAGE_TURNS) return false
        j.signatureBeforeTurn = signature
        j.pageTurns++

        val list = state.list
        if (j.pageMethod == 0) {
            if (list == null) {
                j.pageMethod = 1
            } else {
                val action = if (j.direction > 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                val ok = list.performAction(action)
                DebugLog.i("note", "page ${if (j.direction > 0) "forward" else "back"} (scroll) -> $ok")
                if (ok) return true
                j.pageMethod = 1
            }
        }
        val area = Rect()
        if (list != null) list.getBoundsInScreen(area) else state.cards.forEach { area.union(it.bounds) }
        if (area.isEmpty) return false
        val vertical = j.pageMethod == 1
        DebugLog.i("note", "page ${if (j.direction > 0) "forward" else "back"} (${if (vertical) "vertical" else "horizontal"} swipe) in ${area.toShortString()}")
        swipe(area, vertical, forward = j.direction > 0)
        return true
    }

    /** Taps the BOOX launcher's "Notes" tab, once, when we came in through the launcher. */
    private fun openNotesTab(j: Job, root: AccessibilityNodeInfo, now: Long, signature: String): Boolean {
        if (!j.needsNotesTab || root.packageName?.toString() != BooxNotes.BOOX_HOME_PACKAGE) return false
        j.needsNotesTab = false
        val tab = findFirst(root) { n ->
            val t = NotesScreen.textOf(n).lowercase()
            (t == "notes" || t == "note") && n.isVisibleToUser
        }
        if (tab == null) {
            DebugLog.w("note", "no Notes tab found in the BOOX launcher")
            return false
        }
        DebugLog.i("note", "not on the BOOX home screen; tapping its Notes tab")
        click(tab, null)
        j.resetFolder()
        j.awaitChangeFrom = signature
        j.awaitUntil = now + AFTER_BACK_MS
        later(POLL_MS)
        return true
    }

    private fun back(j: Job, now: Long, signature: String, why: String) {
        if (j.backs >= MAX_BACKS) return fail(j, "pressed Back $MAX_BACKS times without finding it")
        j.backs++
        j.resetFolder()
        DebugLog.i("note", "Back ($why) ${j.backs}/$MAX_BACKS")
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        j.awaitChangeFrom = signature
        j.awaitUntil = now + AFTER_BACK_MS
        later(POLL_MS)
    }

    private fun succeed(j: Job) {
        job = null
        DebugLog.i("note", "opened \"${j.steps.joinToString(" / ")}\"")
        (service as? QuickAccessService)?.noteOpened(j.steps.last())
    }

    private fun fail(j: Job, why: String) {
        job = null
        val root = notesRoot(j.packages)
        DebugLog.w(
            "note",
            "gave up on \"${j.steps.getOrNull(j.index)}\" (step ${j.index + 1}/${j.steps.size}): $why. " +
                if (root == null) "Notes window not found." else "Screen:\n" + DebugReport.describeTree(root),
        )
        toast("Couldn't open \"${j.steps.last()}\": $why")
    }

    private fun wait(j: Job, now: Long, ms: Long) {
        j.notBefore = now + ms
        later(ms)
    }

    private fun later(ms: Long) {
        handler.postDelayed(::tick, ms)
    }

    private fun logState(j: Job, state: NotesScreen.State) {
        val text = when (state) {
            is NotesScreen.State.Editor -> "editor \"${state.title}\""
            is NotesScreen.State.Library -> "library (${if (state.list != null) "scrollable" else "not scrollable"}): " +
                state.cards.joinToString { it.label }.take(300)
            NotesScreen.State.Unknown -> "unknown screen"
        }
        if (text != j.lastState) DebugLog.i("note", "screen: $text")
        j.lastState = text
    }

    /** The Notes window on screen: the editor package or the library inside the BOOX launcher. */
    private fun notesRoot(packages: Set<String>): AccessibilityNodeInfo? {
        val fromWindows = runCatching {
            service.windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName?.toString() in packages } }
        }.getOrNull()
        return fromWindows ?: service.rootInActiveWindow?.takeIf { it.packageName?.toString() in packages }
    }

    private fun findLabel(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val candidates = root.findAccessibilityNodeInfosByText(label).orEmpty()
        return candidates.firstOrNull { NotesScreen.matches(NotesScreen.textOf(it), label) }
            // findAccessibilityNodeInfosByText skips some custom views and ellipsized names; walk the tree too.
            ?: findFirst(root) { NotesScreen.matches(NotesScreen.textOf(it), label) }
    }

    /** Accessibility click on the item (or its clickable parent); a touch if nothing is clickable. */
    private fun click(node: AccessibilityNodeInfo, bounds: Rect?) {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 8) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            n = n.parent
            depth++
        }
        DebugLog.i("note", "no clickable parent; touching instead")
        touch(bounds ?: Rect().also(node::getBoundsInScreen))
    }

    /** A real tap at the centre of [r], for views that ignore accessibility clicks. */
    private fun touch(r: Rect) {
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        gesture(path, 80)
    }

    private fun swipe(area: Rect, vertical: Boolean, forward: Boolean) {
        val path = Path()
        if (vertical) {
            val x = area.exactCenterX()
            val (from, to) = area.top + area.height() * 0.8f to area.top + area.height() * 0.2f
            path.moveTo(x, if (forward) from else to)
            path.lineTo(x, if (forward) to else from)
        } else {
            val y = area.exactCenterY()
            val (from, to) = area.left + area.width() * 0.85f to area.left + area.width() * 0.15f
            path.moveTo(if (forward) from else to, y)
            path.lineTo(if (forward) to else from, y)
        }
        gesture(path, 250)
    }

    private fun gesture(path: Path, durationMs: Long) {
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        if (!service.dispatchGesture(g, null, null)) DebugLog.w("note", "gesture was rejected")
    }

    private fun toast(msg: String) = Toast.makeText(service, msg, Toast.LENGTH_SHORT).show()

    companion object {
        // E-ink redraws are slow; these are deliberately generous.
        private const val TIMEOUT_MS = 30_000L
        // Waits are upper bounds: the loop polls and moves on as soon as the screen changes.
        private const val FIRST_DELAY_MS = 250L
        private const val POLL_MS = 300L
        private const val AFTER_TAP_MS = 1_500L
        private const val AFTER_BACK_MS = 2_000L
        private const val AFTER_PAGE_MS = 1_000L
        private const val LOAD_GRACE_MS = 800L
        private const val UNKNOWN_GRACE_MS = 3_000L
        private const val MISSING_MS = 2_000L
        private const val MAX_PAGE_TURNS = 25
        private const val MAX_BACKS = 5
        private const val MAX_RELAUNCHES = 2
        private const val MAX_TAP_ATTEMPTS = 2
        private const val DIRECT_WAIT_MS = 3_500L
        private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

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
