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
    private val prefs by lazy { Prefs(service) }
    private var job: Job? = null

    private class Job(val packages: Set<String>, val notesPackage: String, val steps: List<String>, val deadline: Long) {
        /** While > 0: we asked BOOX to open the note by id and are waiting for it to appear. */
        var directUntil = 0L

        /** "Open by id" held back until the note that's open has been closed (so it's saved first). */
        var pendingDirect: android.content.Intent? = null

        /**
         * Steps whose name belongs to just one note or folder in BOOX's database. When one of
         * these is already on screen, the walk can start there instead of at the top folder.
         */
        var uniqueSteps: Set<Int> = emptySet()

        /** Hold Last Opened Note: [steps] is a folder path (maybe empty); stop there, open no note. */
        var folderOnly = false

        /** Hold Last Opened Note: left another BOOX screen (e.g. the book Library) once, after Back landed there. */
        var wentHome = false

        /** Real taps on BOOX home's Notes widget title bar so far. */
        var titleBarTaps = 0

        /** Set when we opened the BOOX launcher and still need to tap its "Notes" tab. */
        var needsNotesTab = false

        /** Icon-only launcher tabs already tried while looking for Notes (older firmware). */
        val tabsTried = HashSet<Int>()
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
    fun open(notesPackage: String, path: String, inNotes: Boolean, knownId: String? = null) {
        cancel()
        val steps = BooxNotes.splitPath(path)
        if (steps.isEmpty()) return
        toast("Opening \"${steps.last()}\"…")
        if (!DIRECT_OPEN) return start(notesPackage, steps, inNotes, null)
        // Look the note's id up off the main thread, then try opening it directly.
        val token = Any()
        pending = token
        io.execute {
            val id = knownId ?: NotesDb.findNoteId(service, steps)
            val unique = if (steps.size > 1) NotesDb.uniqueNames(service, steps) else emptySet()
            handler.post { if (pending === token) start(notesPackage, steps, inNotes, id, unique) }
        }
    }

    private var pending: Any? = null

    private fun start(notesPackage: String, steps: List<String>, inNotes: Boolean, id: String?, unique: Set<Int> = emptySet()) {
        val j = Job(BooxNotes.packages(notesPackage), notesPackage, steps, SystemClock.uptimeMillis() + TIMEOUT_MS)
        j.uniqueSteps = unique
        val direct = id?.let { NotesDb.openIntent(service, notesPackage, it) }
        if (direct != null && inNotes) {
            // Leave the open note the normal way first (Back saves it), then open by id.
            j.pendingDirect = direct
        } else if (direct != null && openDirect(j, direct)) {
            // Fast path: BOOX opens the note itself. The loop confirms it, or falls back.
        } else if (!inNotes && !launchNotes(j)) {
            toast("Couldn't open BOOX Notes on this device")
            return
        }
        val now = SystemClock.uptimeMillis()
        job = j.also { it.notBefore = now + FIRST_DELAY_MS }
        handler.postDelayed(::tick, FIRST_DELAY_MS)
    }

    private fun openDirect(j: Job, intent: android.content.Intent): Boolean {
        j.pendingDirect = null
        if (!Launcher.tryStart(service, intent)) return false
        DebugLog.i("note", "opening by id")
        j.directUntil = SystemClock.uptimeMillis() + DIRECT_WAIT_MS
        return true
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

    // ---- A folder in the Notes library (hold Last Opened Note) ------------------------------

    /**
     * Opens the Notes library in [folder] (empty = its top level), the way a note is opened
     * but stopping before any note is tapped. An open note is closed with Back first.
     */
    fun openFolder(notesPackage: String, folder: List<String>, inNotes: Boolean) {
        cancel()
        toast(if (folder.isEmpty()) "Opening BOOX Notes…" else "Opening \"${folder.last()}\"…")
        val j = Job(BooxNotes.packages(notesPackage), notesPackage, folder, SystemClock.uptimeMillis() + TIMEOUT_MS)
        j.folderOnly = true
        if (!inNotes && !launchNotes(j)) {
            (service as? QuickAccessService)?.noteOpenFinished(false)
            return toast("Couldn't open BOOX Notes on this device")
        }
        val now = SystemClock.uptimeMillis()
        job = j.also { it.notBefore = now + FIRST_DELAY_MS }
        handler.postDelayed(::tick, FIRST_DELAY_MS)
    }

    // ---- BOOX's Library tab (hold Last Opened Document, NeoReader) -------------------------

    /**
     * Opens BOOX home on its Library tab, where NeoReader's books are (NeoReader has no
     * library screen of its own). Without a tab labelled Library, BOOX home stays open.
     */
    fun openLibraryTab() {
        cancel()
        // BOOX's Library screen answers this action (Note Air 6C, firmware 4.3): no launcher driving needed.
        val direct = android.content.Intent(LIBRARY_ACTION).addCategory(android.content.Intent.CATEGORY_DEFAULT)
            .setPackage(BooxNotes.BOOX_HOME_PACKAGE).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Launcher.tryStart(service, direct)) {
            DebugLog.i("doc", "opened BOOX's Library directly")
            handler.postDelayed({ (service as? QuickAccessService)?.noteOpenFinished(true) }, AFTER_TAP_MS)
            return
        }
        val home = service.packageManager.getLaunchIntentForPackage(BooxNotes.BOOX_HOME_PACKAGE)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (home == null || !Launcher.tryStart(service, home)) {
            (service as? QuickAccessService)?.noteOpenFinished(false)
            return toast("Couldn't open BOOX's Library")
        }
        val until = SystemClock.uptimeMillis() + SETUP_TIMEOUT_MS
        handler.postDelayed({ libraryTick(until) }, POLL_MS)
    }

    private fun libraryTick(until: Long) {
        val root = notesRoot(setOf(BooxNotes.BOOX_HOME_PACKAGE))
        val tab = root?.let { r ->
            widgetTitleBar(r, "library", "书库")
                ?: findFirst(r) { n -> n.isVisibleToUser && n.isClickable && NotesScreen.textOf(n).trim().lowercase().let { it == "library" || it == "书库" } }
        }
        when {
            tab != null -> {
                val r = Rect().also(tab::getBoundsInScreen)
                DebugLog.i("doc", "BOOX home: tapping Library ${r.toShortString()}")
                touch(r)
                handler.postDelayed({ (service as? QuickAccessService)?.noteOpenFinished(true) }, AFTER_TAP_MS)
            }
            SystemClock.uptimeMillis() > until -> {
                DebugLog.w("doc", "no Library tab on BOOX home. Screen:\n" + (root?.let { DebugReport.describeTree(it) } ?: "BOOX home not found"))
                (service as? QuickAccessService)?.noteOpenFinished(true)
                toast("Tap Library on BOOX's home screen")
            }
            else -> handler.postDelayed({ libraryTick(until) }, POLL_MS)
        }
    }

    /** The title bar of a widget on BOOX home's desktop page whose title is one of [titles] (lowercase). */
    private fun widgetTitleBar(root: AccessibilityNodeInfo, vararg titles: String): AccessibilityNodeInfo? {
        fun matches(bar: AccessibilityNodeInfo) =
            bar.isVisibleToUser && findFirst(bar) { n -> NotesScreen.textOf(n).trim().lowercase() in titles } != null
        // By id first; widget views can be missed by that lookup, so walk the tree too.
        return runCatching { root.findAccessibilityNodeInfosByViewId("${BooxNotes.BOOX_HOME_PACKAGE}:id/title_bar") }.getOrNull().orEmpty()
            .firstOrNull(::matches)
            ?: findFirst(root) { n -> n.viewIdResourceName?.endsWith(":id/title_bar") == true && matches(n) }
    }

    // ---- BOOX's new-note screen (hold New Note) --------------------------------------------

    private var setupUntil = 0L
    private var setupBacks = 0
    private var setupTabTapped = false

    /**
     * Opens the Notes library and taps its own "new note" button, which brings up
     * BOOX's new-note screen (name, template). That screen can't be started directly.
     */
    fun openNewNoteSetup(notesPackage: String, inEditor: Boolean) {
        cancel()
        setupBacks = 0
        setupTabTapped = false
        setupUntil = SystemClock.uptimeMillis() + SETUP_TIMEOUT_MS
        // Always through BOOX's home screen (its Notes widget has the +). Back from an open note
        // can land on another launcher (build 59 report), so don't rely on it; BOOX saves the note itself.
        run {
            val how = BooxNotes.launchCandidates(service, notesPackage).firstOrNull { Launcher.tryStart(service, it.second) }?.first
                ?: return run {
                    (service as? QuickAccessService)?.noteOpenFinished(false)
                    toast("Couldn't open BOOX Notes on this device")
                }
            DebugLog.i("note", "new-note setup: opened Notes via $how")
            setupTabTapped = how != "BOOX launcher"
        }
        handler.postDelayed({ setupTick(notesPackage) }, AFTER_BACK_MS)
    }

    private fun setupTick(notesPackage: String) {
        val now = SystemClock.uptimeMillis()
        val packages = BooxNotes.packages(notesPackage)
        val root = notesRoot(packages)
        if (now > setupUntil) {
            DebugLog.w("note", "new-note setup: no new-note button found. Screen:\n" + (root?.let { DebugReport.describeTree(it) } ?: "Notes window not found"))
            (service as? QuickAccessService)?.noteOpenFinished(false)
            return toast("Couldn't find BOOX's new-note button. Send a debug report from Settings")
        }
        if (root == null) return run { handler.postDelayed({ setupTick(notesPackage) }, POLL_MS) }
        if (!setupTabTapped && root.packageName?.toString() == BooxNotes.BOOX_HOME_PACKAGE) {
            setupTabTapped = true
            val tab = findFirst(root) { n -> NotesScreen.textOf(n).lowercase().let { it == "notes" || it == "note" } && n.isVisibleToUser }
                // Icon-only tabs (older launchers): the Notes tab learned while opening a note.
                ?: prefs.launcherNotesTab.takeIf { !NotesScreen.isLauncherNotesLibrary(root) }
                    ?.let { NotesScreen.launcherTabs(root).getOrNull(it) }
            tab?.let {
                click(it, null)
                handler.postDelayed({ setupTick(notesPackage) }, AFTER_TAP_MS)
                return
            }
        }
        val button = newNoteButton(root)
        if (button == null) return run { handler.postDelayed({ setupTick(notesPackage) }, POLL_MS) }
        DebugLog.i("note", "new-note setup: tapping ${button.viewIdResourceName ?: NotesScreen.textOf(button)}")
        click(button, null)
        // BOOX's + opens its create-note screen (name, page layout, templates, Create / Cancel; build 59
        // report). Reveal it as soon as it's up.
        val until = SystemClock.uptimeMillis() + SETUP_TIMEOUT_MS
        handler.postDelayed({ createScreenTick(notesPackage, until) }, POLL_MS)
    }

    private fun createScreenTick(notesPackage: String, until: Long) {
        val root = notesRoot(BooxNotes.packages(notesPackage))
        val shown = root != null && NotesScreen.isCreateNoteScreen(root)
        when {
            shown -> {
                // BOOX opens on the last template's category (e.g. Work); Recent shows the template used last.
                val recent = findFirst(root!!) { n -> n.isVisibleToUser && NotesScreen.textOf(n).equals("Recent", ignoreCase = true) }
                DebugLog.i("note", "new-note setup: BOOX's create-note screen is up" + if (recent != null) "; choosing the Recent templates" else "; no Recent tab found")
                if (recent != null) click(recent, null)
                (service as? QuickAccessService)?.newNoteScreenShown()
                handler.postDelayed({ (service as? QuickAccessService)?.noteOpenFinished(true) }, if (recent != null) 500L else 0L)
            }
            SystemClock.uptimeMillis() > until -> {
                DebugLog.w("note", "new-note setup: the create-note screen didn't show. Screen:\n" +
                    (root?.let { DebugReport.describeTree(it) } ?: "Notes window not found"))
                (service as? QuickAccessService)?.noteOpenFinished(true)
            }
            else -> handler.postDelayed({ createScreenTick(notesPackage, until) }, POLL_MS)
        }
    }

    /**
     * The library's "new note" button. Its siblings make a text note or a folder
     * (build 56 tapped create_text_new and made a text note), so only a button
     * whose id or label says "note" counts; candidates are logged either way.
     */
    private fun newNoteButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = ArrayList<AccessibilityNodeInfo>()
        findFirst(root) { n ->
            val id = n.viewIdResourceName?.substringAfter(":id/")?.lowercase().orEmpty()
            val t = NotesScreen.textOf(n).lowercase()
            if (n.isVisibleToUser && (Regex("create|new|add").containsMatchIn(id) || Regex("^(new|create|add)\\b").containsMatchIn(t))) candidates += n
            false
        }
        fun idOf(n: AccessibilityNodeInfo) = n.viewIdResourceName?.substringAfter(":id/")?.lowercase().orEmpty()
        if (candidates.isNotEmpty()) DebugLog.i("note", "new-note setup: buttons " + candidates.joinToString { "${idOf(it)}\"${NotesScreen.textOf(it)}\"" })
        val notText = candidates.filterNot { idOf(it).contains("text") || idOf(it).contains("folder") || idOf(it).contains("search") || NotesScreen.textOf(it).lowercase().contains("text") }
        return notText.firstOrNull { idOf(it).contains("note") && Regex("create|new|add").containsMatchIn(idOf(it)) }
            // The Notes widget on BOOX's home screen: create_new is its "+" for a notebook (build 58 report).
            ?: notText.firstOrNull { idOf(it) == "create_new" }
            ?: notText.firstOrNull { NotesScreen.textOf(it).lowercase().let { t -> t == "new note" || t == "note" || t == "create note" || t == "新建笔记" } }
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
        // The open note has been closed (we're in the library, or out of Notes): open by id now.
        j.pendingDirect?.let { intent ->
            val closed = root == null || NotesScreen.read(root).let { it !is NotesScreen.State.Editor && it !is NotesScreen.State.Unknown }
            if (closed && openDirect(j, intent)) return later(POLL_MS)
        }
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

        // A folder job with no folder to open (the note is at the top level): the library is the goal.
        val target = j.steps.getOrNull(j.index)
        when (state) {
            is NotesScreen.State.Editor -> {
                if (!j.folderOnly && NotesScreen.matches(state.title, j.steps.last())) return succeed(j)
                back(j, now, signature, "another note is open (\"${state.title}\")")
            }

            is NotesScreen.State.Library -> {
                if (root.packageName?.toString() == BooxNotes.BOOX_HOME_PACKAGE && NotesScreen.isLauncherNotesLibrary(root)) learnNotesTab(root)
                // Hold Last Opened Note: Back out of a note can land on whatever BOOX screen was behind
                // Notes (BOOX's book Library, seen on the 6C). Open the Notes library directly, or
                // go Home and open it from the widget where the firmware has no direct way.
                if (j.folderOnly && !j.wentHome && !j.needsNotesTab && root.packageName?.toString() == BooxNotes.BOOX_HOME_PACKAGE &&
                    !NotesScreen.isLauncherNotesLibrary(root)
                ) {
                    // The Notes library may still be loading, unless another BOOX screen is known to be in front.
                    val qs = service as? QuickAccessService
                    val otherScreen = qs != null && qs.foregroundPackage == BooxNotes.BOOX_HOME_PACKAGE && qs.foregroundActivity != null &&
                        !BooxNotes.isNotesScreen(qs.foregroundPackage, qs.foregroundActivity, j.notesPackage)
                    if (!otherScreen && now < j.notBefore + UNKNOWN_GRACE_MS) return later(POLL_MS)
                    j.wentHome = true
                    j.resetFolder()
                    val direct = BooxNotes.notesLibraryIntent(service)
                    if (direct != null && Launcher.tryStart(service, direct)) {
                        DebugLog.i("note", "Back left Notes for another BOOX screen; opened the Notes library directly")
                    } else {
                        j.needsNotesTab = true
                        DebugLog.i("note", "Back left Notes for another BOOX screen; going Home to open Notes")
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    }
                    j.awaitChangeFrom = signature
                    j.awaitUntil = now + AFTER_BACK_MS
                    j.notBefore = now
                    return later(POLL_MS)
                }
                if (target == null) {
                    if (openNotesTab(j, root, now, signature)) return
                    // BOOX home's Notes widget also reads as a library: only the real one counts.
                    val library = root.packageName?.toString() != BooxNotes.BOOX_HOME_PACKAGE || NotesScreen.isLauncherNotesLibrary(root)
                    if (library) return succeed(j)
                    if (now < j.notBefore + UNKNOWN_GRACE_MS) return later(POLL_MS)
                    return fail(j, "BOOX Notes' library didn't open")
                }
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

                // A note in a folder, opened from another app: BOOX home's recent notes, or the folder
                // Notes was last left in, may already show the note (or a folder on its way). Start
                // there: Back from BOOX home leaves Notes instead of going up to the top folder.
                if (j.index == 0 && j.steps.size > 1) {
                    val deepest = (j.steps.size - 1 downTo 1).firstOrNull { k ->
                        k in j.uniqueSteps && state.cards.any { NotesScreen.matches(it.label, j.steps[k]) }
                    }
                    if (deepest != null) {
                        DebugLog.i("note", "\"${j.steps[deepest]}\" is already on screen; skipping ${j.steps.take(deepest).joinToString(" / ")}")
                        j.index = deepest
                        return later(0)
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

    /**
     * Taps the BOOX launcher's "Notes" tab when we came in through the launcher. Tabs with a
     * "Notes" label are tapped once. Older launchers (Note Air 2, 2022 firmware) have icons
     * only: there the tab learned earlier is tried first, then each tab in turn until the
     * Notes library shows (recognised by its own views), and that position is remembered.
     */
    private fun openNotesTab(j: Job, root: AccessibilityNodeInfo, now: Long, signature: String): Boolean {
        if (!j.needsNotesTab || root.packageName?.toString() != BooxNotes.BOOX_HOME_PACKAGE) return false
        if (NotesScreen.isLauncherNotesLibrary(root)) {
            j.needsNotesTab = false
            learnNotesTab(root)
            return false
        }
        // BOOX home as a desktop page (Note Air 6C, firmware 4.3): the Notes widget's title bar
        // opens the full library. Its label under the widget says "Notes" too, but opens nothing.
        // A widget may ignore accessibility clicks, so it gets a real tap (twice at most).
        widgetTitleBar(root, "notes", "note")?.let { bar ->
            if (j.titleBarTaps >= 2) return false
            j.titleBarTaps++
            val r = Rect().also(bar::getBoundsInScreen)
            DebugLog.i("note", "BOOX home: tapping the Notes widget's title bar ${r.toShortString()} (${j.titleBarTaps})")
            touch(r)
            j.resetFolder()
            j.awaitChangeFrom = signature
            j.awaitUntil = now + AFTER_BACK_MS
            j.notBefore = now
            later(POLL_MS)
            return true
        }
        val labelled = findFirst(root) { n ->
            val t = NotesScreen.textOf(n).lowercase()
            (t == "notes" || t == "note") && n.isVisibleToUser
        }
        val tab = if (labelled != null) {
            j.needsNotesTab = false
            DebugLog.i("note", "not on the BOOX home screen; tapping its Notes tab")
            labelled
        } else {
            val tabs = NotesScreen.launcherTabs(root)
            val selected = NotesScreen.selectedLauncherTab(root)
            j.tabsTried += selected
            val learned = prefs.launcherNotesTab
            val next = (listOf(learned) + tabs.indices).firstOrNull { it in tabs.indices && it !in j.tabsTried }
            if (next == null) {
                j.needsNotesTab = false
                DebugLog.w("note", "no Notes tab found in the BOOX launcher" + if (tabs.isEmpty()) "" else " (tried ${tabs.size} tabs)")
                return false
            }
            j.tabsTried += next
            DebugLog.i("note", "trying BOOX launcher tab ${next + 1}/${tabs.size} for Notes" + if (next == learned) " (learned)" else "")
            tabs[next]
        }
        click(tab, null)
        j.resetFolder()
        j.awaitChangeFrom = signature
        j.awaitUntil = now + AFTER_BACK_MS
        j.notBefore = now
        later(POLL_MS)
        return true
    }

    /** Remembers which icon-only launcher tab is Notes, when its library is showing. */
    private fun learnNotesTab(root: AccessibilityNodeInfo) {
        val selected = NotesScreen.selectedLauncherTab(root)
        if (selected < 0 || selected == prefs.launcherNotesTab) return
        prefs.launcherNotesTab = selected
        DebugLog.i("note", "BOOX launcher: Notes is tab ${selected + 1}")
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
        DebugLog.i("note", "opened \"${j.steps.joinToString(" / ")}\"" + if (j.folderOnly) " (folder)" else "")
        (service as? QuickAccessService)?.apply {
            if (!j.folderOnly) noteOpened(j.steps.last())
            noteOpenFinished(true)
        }
    }

    private fun fail(j: Job, why: String) {
        job = null
        val root = notesRoot(j.packages)
        DebugLog.w(
            "note",
            (if (j.folderOnly) "gave up on folder \"${j.steps.joinToString(" / ")}\": $why. "
            else "gave up on \"${j.steps.getOrNull(j.index)}\" (step ${j.index + 1}/${j.steps.size}): $why. ") +
                if (root == null) "Notes window not found." else "Screen:\n" + DebugReport.describeTree(root),
        )
        (service as? QuickAccessService)?.noteOpenFinished(false)
        toast("Couldn't open \"${j.steps.lastOrNull() ?: "BOOX Notes"}\": $why")
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
        private const val SETUP_TIMEOUT_MS = 6_000L

        /** Opens BOOX's Library screen (com.onyx LibraryActivity); seen on the Note Air 6C. */
        private const val LIBRARY_ACTION = "com.onyx.action.LIBRARY"

        /**
         * Open-by-id is off until we know the extra OpenNoteByDocIdActivity reads:
         * with guessed extras (build 15) BOOX accepted the launch but opened nothing.
         */
        private const val DIRECT_OPEN = true
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
