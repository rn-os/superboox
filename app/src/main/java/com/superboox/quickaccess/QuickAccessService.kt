package com.superboox.quickaccess

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

/**
 * The heart of SuperBoox. An AccessibilityService is used instead of a plain
 * "draw over other apps" service because:
 *  - TYPE_ACCESSIBILITY_OVERLAY windows sit above everything, including the
 *    BOOX launcher and system dialogs, so the gesture works "on any page";
 *  - BOOX's aggressive background-app freezing leaves accessibility services alone;
 *  - it can run Back/Home/Recents/Screenshot and drive BOOX Notes (see [NoteOpener]).
 */
class QuickAccessService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private lateinit var noteOpener: NoteOpener
    private lateinit var cover: OpeningCover
    private val handler = Handler(Looper.getMainLooper())

    private var handleView: EdgeHandleView? = null
    private var panel: QuickAccessPanel? = null

    /** Package of the app the user was in before opening the panel. */
    var foregroundPackage: String? = null
        private set

    /** Activity class of [foregroundPackage], e.g. to tell the BOOX Notes library apart from the launcher. */
    var foregroundActivity: String? = null
        private set

    val isNotesInFront: Boolean
        get() = BooxNotes.isNotesScreen(foregroundPackage, foregroundActivity, prefs.notesPackage)

    override fun onServiceConnected() {
        super.onServiceConnected()
        DebugLog.init(this)
        DebugLog.i("service", "connected")
        prefs = Prefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        noteOpener = NoteOpener(this)
        cover = OpeningCover(this, wm)
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        attachHandle()
        instance = this
        NoteIds.capture(this, prefs)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in IGNORED_PACKAGES || pkg.contains("inputmethod")) return
        // Dialogs/toasts from other apps also fire this event; only count real app windows.
        val windowType = runCatching { windows.firstOrNull { it.id == event.windowId }?.type }.getOrNull()
        if (windowType == null || windowType == AccessibilityWindowInfo.TYPE_APPLICATION) {
            val cls = event.className?.toString()
            // Only real activities; views inside an app also report their class names here.
            val isActivity = cls != null && !cls.startsWith("android.widget.") && !cls.startsWith("android.view.")
            val newApp = pkg != foregroundPackage
            if (newApp || (isActivity && cls != foregroundActivity)) {
                foregroundPackage = pkg
                if (isActivity) foregroundActivity = cls else if (newApp) foregroundActivity = null
                DebugLog.i("foreground", "$pkg ($cls)")
                if (newApp) rememberApp(pkg)
            }
            trackRecent(pkg, event)
        }
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::prefs.isInitialized) return
        // Rotation / split-screen: re-measure the strip and the panel.
        attachHandle()
        if (panel != null) {
            hidePanel()
            showPanel()
        }
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == null || key in Prefs.HANDLE_KEYS) attachHandle()
        if (key == Prefs.K_PINS) NoteIds.capture(this, prefs)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        DebugLog.w("service", "unbound (accessibility service turned off or killed)")
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (instance === this) instance = null
        if (::prefs.isInitialized) prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        if (::noteOpener.isInitialized) noteOpener.cancel()
        if (::cover.isInitialized) cover.hide()
        hidePanel()
        detachHandle()
    }

    // ---- Recent notes & documents --------------------------------------------------------

    private val recordOpenNote = Runnable {
        val state = notesState(log = false)
        if (state is NotesScreen.State.Editor) {
            Recents.record(prefs, RecentItem(RecentItem.Kind.NOTE, state.title, prefs.notesPackage, System.currentTimeMillis()))
        }
    }

    /** Remembers the open note (read from the editor's title) and the last reading app used. */
    private fun trackRecent(pkg: String, event: AccessibilityEvent) {
        if (pkg == prefs.notesPackage) {
            // The editor's views settle after the window appears; read the title a moment later.
            handler.removeCallbacks(recordOpenNote)
            handler.postDelayed(recordOpenNote, 1_200)
        } else if (pkg == ReaderLibrary.NEOREADER) {
            // NeoReader records the open document in its library; read it once it has.
            handler.removeCallbacks(recordOpenDocument)
            handler.postDelayed(recordOpenDocument, 1_500)
        } else if (Recents.isReader(pkg, prefs.notesPackage)) {
            val app = Launcher.appLabel(this, pkg)
            // The window event's text is often the document title; fall back to the app name.
            val text = event.text.joinToString(" ").trim()
            val title = text.takeIf { it.isNotEmpty() && !it.equals(app, ignoreCase = true) && it.length <= 120 } ?: app
            Recents.record(prefs, RecentItem(RecentItem.Kind.DOCUMENT, title, pkg, System.currentTimeMillis()))
        }
    }

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val recordOpenDocument = Runnable {
        if (foregroundPackage != ReaderLibrary.NEOREADER) return@Runnable
        io.execute {
            val doc = ReaderLibrary.lastOpened(this) ?: return@execute
            Recents.record(prefs, RecentItem(RecentItem.Kind.DOCUMENT, doc.title, ReaderLibrary.NEOREADER, System.currentTimeMillis(), doc.path))
        }
    }

    /** Called by [NoteOpener] when it has opened a note. */
    fun noteOpened(title: String) {
        Recents.record(prefs, RecentItem(RecentItem.Kind.NOTE, title, prefs.notesPackage, System.currentTimeMillis()))
    }

    // ---- Edge handle ---------------------------------------------------------------------

    private fun attachHandle() {
        detachHandle()
        if (!prefs.handleEnabled) return
        val screen = screenSize()
        val edge = prefs.edge
        val view = EdgeHandleView(this, edge, prefs.trigger, prefs.showHandle) {
            DebugLog.i("gesture", "${prefs.trigger} on $edge edge")
            showPanel()
        }
        val params = overlayParams(
            width = dp(prefs.handleWidthDp.coerceIn(6, 48)),
            height = (screen.second * prefs.handleHeightPct.coerceIn(10, 100) / 100f).toInt(),
            focusable = false,
        ).apply {
            gravity = Gravity.TOP or (if (edge == Edge.RIGHT) Gravity.RIGHT else Gravity.LEFT)
            y = (screen.second * prefs.handleTopPct.coerceIn(0, 90) / 100f).toInt()
        }
        runCatching { wm.addView(view, params) }
            .onSuccess {
                handleView = view
                DebugLog.i("handle", "attached ${params.width}x${params.height}px at y=${params.y} on $edge (screen ${screen.first}x${screen.second})")
            }
            .onFailure {
                DebugLog.e("handle", "attach failed", it)
                Toast.makeText(this, "Couldn't add edge handle: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun detachHandle() {
        handleView?.let { runCatching { wm.removeView(it) } }
        handleView = null
    }

    // ---- Panel ----------------------------------------------------------------------------

    val isPanelShowing: Boolean get() = panel != null

    fun togglePanel() = if (isPanelShowing) hidePanel() else showPanel()

    /** @param keepCover leave the screen hold up (it's lifted by whoever put it there). */
    fun showPanel(keepCover: Boolean = false) {
        if (panel != null) return
        if (!keepCover) cover.hide()
        val p = QuickAccessPanel(this, prefs, foregroundPackage)
        val params = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, focusable = true)
        runCatching { wm.addView(p.root, params) }
            .onSuccess {
                panel = p
                DebugLog.i("panel", "shown over $foregroundPackage")
            }
            .onFailure {
                DebugLog.e("panel", "show failed", it)
                Toast.makeText(this, "Couldn't open panel: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it.root) } }
        panel = null
    }

    /** Re-render the panel in place (after pinning/unpinning from inside it). */
    fun refreshPanel() {
        if (panel == null) return
        hidePanel()
        showPanel()
    }

    // ---- Actions used by the panel ------------------------------------------------------

    fun systemAction(action: Int) {
        hidePanel()
        // Let the e-ink screen redraw without the panel before screenshots etc.
        handler.postDelayed({
            val ok = performGlobalAction(action)
            DebugLog.i("action", "global action $action -> $ok")
        }, if (action == GLOBAL_ACTION_TAKE_SCREENSHOT) 600L else 50L)
    }

    fun openBooxNote(path: String) = openBooxNote(path, null)

    /** Opens a pinned note: by its saved id when it has one (following renames), else by name. */
    fun openBooxNote(pin: Pin) {
        hidePanel()
        NoteIds.resolve(this, prefs, pin) { path, id -> handler.post { openBooxNote(path, id) } }
    }

    private fun openBooxNote(path: String, noteId: String?) {
        hidePanel()
        val inNotes = isNotesInFront
        DebugLog.i("note", "open \"$path\"${noteId?.let { " (id ${it.take(8)}…)" } ?: ""} (Notes in front: $inNotes, $foregroundPackage/$foregroundActivity)")
        // Hold the current screen so the BOOX home screen doesn't flash by on the way.
        cover.show { noteOpener.open(prefs.notesPackage, path, inNotes, noteId) }
    }

    /**
     * Quick Controls test (debug report): reads the BOOX Control Center behind
     * the screen hold, taps nothing.
     */
    fun probeControlCenter(done: (List<String>) -> Unit) {
        hidePanel()
        cover.show {
            ControlCenterProbe.run(this) { lines ->
                cover.hide(600)
                lines.forEach { DebugLog.i("control-center", it) }
                done(lines)
            }
        }
    }

    // ---- Quick Controls -----------------------------------------------------------------

    /** The Quick Controls page shown, kept while the panel is rebuilt. */
    var quickControlsPage = 0

    /**
     * Runs a Quick Control. Actions go straight to Android. Control Center
     * tiles are pressed in the real Control Center behind the screen hold,
     * while the panel stays on top and usable: each tap flips its tile at once
     * and is queued, so quick taps one after another all land.
     */
    fun quickControl(control: QuickControls.Control, hold: Boolean) {
        if (control.kind == QuickControls.Kind.ACTION) {
            if (hold) return
            // Back/Home with the Control Center open would act on it instead: wait.
            if (qcBusy) afterSession.addLast(control) else systemAction(control.globalAction)
            return
        }
        if (control.kind == QuickControls.Kind.TOGGLE && !hold) {
            val now = quickControlStates()[control.id] ?: false
            optimistic[control.id] = !now to SystemClock.uptimeMillis() + OPTIMISTIC_MS
            panel?.refresh()
        }
        qcQueue.addLast(control to hold)
        if (!qcBusy) startSession()
    }

    /** States to show: the last known ones, with taps still being applied on top. */
    fun quickControlStates(): Map<String, Boolean> {
        val out = QuickControls.states(this, prefs).toMutableMap()
        val now = SystemClock.uptimeMillis()
        optimistic.entries.removeAll { it.value.second < now }
        optimistic.forEach { (id, v) -> out[id] = v.first }
        return out
    }

    private val qcQueue = ArrayDeque<Pair<QuickControls.Control, Boolean>>()
    private val afterSession = ArrayDeque<QuickControls.Control>()
    private val optimistic = HashMap<String, Pair<Boolean, Long>>()
    private var qcBusy = false

    private fun startSession() {
        qcBusy = true
        // The still also swallows taps outside the panel, so none reach the Control Center.
        cover.show(touchable = true) {
            raisePanel(focusable = false)
            QuickControls.open(this) { ok ->
                if (ok) nextControl() else {
                    qcQueue.clear()
                    finishSession(closeShade = true, keepPanel = true, error = "Couldn't open the Control Center")
                }
            }
        }
    }

    private fun nextControl() {
        val (control, hold) = qcQueue.removeFirstOrNull() ?: return finishSession(closeShade = true, keepPanel = true)
        val press = QuickControls.press(this, prefs, control, hold)
        if (!press.found || !press.pressed) {
            optimistic.remove(control.id)
            panel?.refresh()
            Toast.makeText(this, "Couldn't find ${control.label} in the Control Center", Toast.LENGTH_SHORT).show()
            return nextControl()
        }
        handler.postDelayed({
            if (control.kind == QuickControls.Kind.TOGGLE && !hold) {
                QuickControls.settle(this, prefs, control, press.before)?.let {
                    optimistic[control.id] = it to SystemClock.uptimeMillis() + OPTIMISTIC_MS
                }
                panel?.refresh()
                nextControl()
            } else {
                // A hold opens the tile's settings; other tiles start their own screen. Either
                // way that screen takes over: the panel goes, and any later taps are dropped.
                qcQueue.clear()
                finishSession(closeShade = !hold, keepPanel = false)
            }
        }, QuickControls.AFTER_TAP_MS)
    }

    private fun finishSession(closeShade: Boolean, keepPanel: Boolean, error: String? = null) {
        if (closeShade) QuickControls.close(this)
        if (keepPanel) raisePanel(focusable = true, reAdd = false) else hidePanel()
        cover.hide(250)
        qcBusy = false
        error?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        var delay = 300L
        while (afterSession.isNotEmpty()) {
            val action = afterSession.removeFirst()
            handler.postDelayed({ systemAction(action.globalAction) }, delay)
            delay += 400L
        }
    }

    /** Puts the panel above the screen hold (re-adding its window), or just restores its focus. */
    private fun raisePanel(focusable: Boolean, reAdd: Boolean = true) {
        val root = panel?.root ?: return
        val params = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, focusable)
        runCatching {
            if (reAdd) {
                wm.removeView(root)
                wm.addView(root, params)
            } else {
                wm.updateViewLayout(root, params)
            }
        }.onFailure { DebugLog.w("controls", "panel layering: ${it.message}") }
    }

    // ---- Previous app -------------------------------------------------------------------

    /** Apps used recently, newest first (launchers and system screens left out). */
    private val recentApps = ArrayDeque<String>()

    private val homePackages: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.toSet() + BooxNotes.BOOX_HOME_PACKAGE
    }

    private fun rememberApp(pkg: String) {
        if (pkg in homePackages || pkg.contains("quickstep") || pkg.contains("launcher") || pkg in NOT_APPS) return
        recentApps.remove(pkg)
        recentApps.addFirst(pkg)
        while (recentApps.size > 6) recentApps.removeLast()
    }

    /** The app used before the one in front (or the last app, from the home screen). */
    val previousApp: String?
        get() = recentApps.firstOrNull { it != foregroundPackage }

    /** Goes back to [previousApp]: Notes and NeoReader reopen the note or book you were in. */
    fun openPreviousApp() {
        val pkg = previousApp ?: return
        hidePanel()
        DebugLog.i("previous", "back to $pkg")
        when (pkg) {
            prefs.notesPackage -> Recents.lastNote(prefs)?.let { openBooxNote(it.title) }
                ?: BooxNotes.launchCandidates(this, prefs.notesPackage).firstOrNull()?.let { Launcher.start(this, it.second) }
            ReaderLibrary.NEOREADER -> Recents.list(prefs).firstOrNull { it.pkg == pkg && it.path.isNotEmpty() }
                ?.let { ReaderLibrary.open(this, it.path) } ?: Launcher.launchApp(this, pkg)
            else -> Launcher.launchApp(this, pkg)
        }
    }

    /** Called by [NoteOpener] when it's done, either way. */
    fun noteOpenFinished(opened: Boolean) {
        // On success give the note a moment to draw before revealing it.
        cover.hide(if (opened) REVEAL_DELAY_MS else 0)
    }

    /** What BOOX Notes is showing (open note or library), for pinning from the panel. */
    fun notesState(log: Boolean = true): NotesScreen.State? {
        val packages = BooxNotes.packages(prefs.notesPackage)
        val pkg = packages.joinToString("/")
        val root = runCatching {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName?.toString() in packages } }
        }.getOrNull()
        if (root == null) {
            if (log) DebugLog.w("picker", "no $pkg window found; windows=${runCatching { windows.map { "${it.type}:${it.root?.packageName}" } }.getOrNull()}")
            return null
        }
        val state = NotesScreen.read(root)
        val summary = when (state) {
            is NotesScreen.State.Editor -> "editor, title \"${state.title}\""
            is NotesScreen.State.Library -> "library, ${state.cards.size} cards: ${state.cards.joinToString { it.label }.take(300)}"
            NotesScreen.State.Unknown -> "unrecognised screen"
        }
        // The screen layout only when it wasn't recognised; otherwise the summary says enough.
        if (log) DebugLog.i("picker", "$pkg: $summary" + if (state is NotesScreen.State.Unknown) "\n" + DebugReport.describeTree(root) else "")
        return state
    }

    // ---- Window helpers ----------------------------------------------------------------

    private fun overlayParams(width: Int, height: Int, focusable: Boolean) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        windowAnimations = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            return b.width() to b.height()
        }
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    fun screenWidth(): Int = screenSize().first

    companion object {
        private const val REVEAL_DELAY_MS = 400L
        /** How long a tapped tile shows its new state before the system's own reading wins. */
        private const val OPTIMISTIC_MS = 8_000L

        @Volatile
        var instance: QuickAccessService? = null
            private set

        /** System screens that pass through the foreground but aren't apps you'd go back to. */
        private val NOT_APPS = setOf(
            "com.android.intentresolver", "com.android.providers.media.module", "com.android.permissioncontroller",
            "com.google.android.permissioncontroller", "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.onyx.kime", // BOOX keyboard
        )

        private val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.onyx.floatingbutton", // BOOX Navigation Ball
        )
    }
}
