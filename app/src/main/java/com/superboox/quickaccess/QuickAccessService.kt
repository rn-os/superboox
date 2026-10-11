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
        ClipHistory.onStart(this, prefs)
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching { registerReceiver(wakeReceiver, filter) }
    }

    /**
     * BOOX puts its own front light level back when the screen wakes. If one of
     * your presets was on, set it again once BOOX has done that.
     */
    private val wakeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            // Going to sleep: close the panel so it doesn't sit on top of BOOX's screensaver.
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                if (isPanelShowing) DebugLog.i("panel", "closed: screen off")
                hidePanel()
                return
            }
            val id = QuickControls.activeMine(prefs) ?: return
            if (!AdvancedAccess.independentPresets(prefs)) return
            val preset = QuickControls.myPresets(prefs).firstOrNull { it.id == id } ?: return
            DebugLog.i("controls", "screen on (${intent.action?.substringAfterLast('.')}): reapplying ${preset.name}")
            // BOOX restores its level a moment after waking; apply after it, then once more to be sure.
            listOf(900L, 2_500L).forEach { delay ->
                handler.postDelayed({ AdvancedAccess.setLight(preset.levels, AdvancedAccess.lightMethod(prefs)) }, delay)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // A tap or a toast ("Copied") may have been a copy: with Advanced access, check the clipboard.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val from = event.packageName?.toString()
            if (from != packageName && ::prefs.isInitialized) ClipHistory.checkInBackground(this, prefs, from ?: foregroundPackage)
            return
        }
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
                if (newApp) foregroundPackage?.let { ClipHistory.checkInBackground(this, prefs, it) }
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
            showPanel(fresh = false)
        }
        // Rotated while Settings shows the preview: redo it at the new size.
        if (previewPanel != null) showPreview(previewClipboard)
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
        runCatching { unregisterReceiver(wakeReceiver) }
        if (::prefs.isInitialized) prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        if (::noteOpener.isInitialized) noteOpener.cancel()
        if (::cover.isInitialized) cover.hide()
        hidePanel()
        hidePreview()
        detachHandle()
    }

    // ---- Recent notes & documents --------------------------------------------------------

    private val recordOpenNote = Runnable {
        // Only BOOX's own title view: tool pop-ups ("Pen 0.30mm") would otherwise be read as the note's name.
        val root = runCatching {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName?.toString() == prefs.notesPackage } }
        }.getOrNull() ?: return@Runnable
        NotesScreen.trustedTitle(root)?.let { title ->
            Recents.record(prefs, RecentItem(RecentItem.Kind.NOTE, title, prefs.notesPackage, System.currentTimeMillis()))
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
        val view = EdgeHandleView(this, edge, prefs.trigger, prefs.showHandle, solid = stripPreview) {
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

    /**
     * @param keepCover leave the screen hold up (it's lifted by whoever put it there).
     * @param fresh the user is opening it: start Quick Controls and apps on page 1
     *   unless "Remember last page" is on. False when it's only being rebuilt.
     */
    // ---- Settings' live preview ----------------------------------------------------------

    /** Settings › Gesture is open: the swipe strip is drawn in black. */
    var stripPreview = false
        set(v) {
            if (field == v) return
            field = v
            if (::prefs.isInitialized) attachHandle()
        }

    private var previewPanel: QuickAccessPanel? = null
    private var previewClipboard = false

    /**
     * The real panel, at its real size on its edge, for Settings to show while a
     * setting changes. Taps pass through it to Settings underneath.
     */
    fun showPreview(clipboard: Boolean) {
        hidePreview()
        if (panel != null) return
        val p = QuickAccessPanel(this, prefs, foregroundPackage, previewOfClipboard = clipboard,
            previewTag = if (clipboard) "PREVIEW · CLIPBOARD" else "PREVIEW · ${prefs.panelWidthPct}%")
        val params = overlayParams(previewWidth(), WindowManager.LayoutParams.MATCH_PARENT, focusable = false).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            gravity = Gravity.TOP or (if (prefs.edge == Edge.RIGHT) Gravity.RIGHT else Gravity.LEFT)
        }
        runCatching { wm.addView(p.root, params) }
            .onSuccess { previewPanel = p; previewClipboard = clipboard; DebugLog.i("panel", "preview shown (${params.width}px${if (clipboard) ", clipboard" else ""})") }
            .onFailure { DebugLog.w("panel", "preview failed: ${it.message}") }
    }

    fun hidePreview() {
        previewPanel?.let { runCatching { wm.removeView(it.root) } }
        previewPanel = null
    }

    val isPreviewShowing: Boolean get() = previewPanel != null

    /** The panel's width in pixels on this screen right now. */
    fun previewWidth(): Int = QuickAccessPanel.sheetWidth(this, prefs, screenWidth())

    fun showPanel(keepCover: Boolean = false, fresh: Boolean = true) {
        if (panel != null) return
        hidePreview()
        if (!keepCover) cover.hide()
        if (fresh && !prefs.rememberPages) {
            quickControlsPage = 0
            dockPage = 0
        }
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

    /** Hold New Note: BOOX's new-note screen, through the Notes library's own button. */
    fun openNewNoteSetup() {
        noteOpener.openNewNoteSetup(prefs.notesPackage, inEditor = foregroundPackage == prefs.notesPackage)
    }

    /** After the panel closes: pastes the clipboard into the text box that has focus in the app. */
    fun pasteIntoApp() {
        handler.postDelayed({
            val target = runCatching { rootInActiveWindow?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            val ok = target != null && target.isEditable && target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE)
            DebugLog.i("clipboard", "paste in ${foregroundPackage}: ${if (ok) "done" else if (target == null) "no text box has focus" else "refused"}")
            if (!ok) Toast.makeText(this, "Copied. Tap a text box and paste.", Toast.LENGTH_SHORT).show()
        }, 450L)
    }

    fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it.root) } }
        panel = null
    }

    /** Re-render the panel in place (after pinning/unpinning from inside it). */
    fun refreshPanel() {
        if (panel == null) return
        hidePanel()
        showPanel(fresh = false)
    }

    // ---- Actions used by the panel ------------------------------------------------------

    fun systemAction(action: Int) {
        hidePanel()
        if (action == GLOBAL_ACTION_NOTIFICATIONS) return run { handler.postDelayed({ QuickControls.openNotifications(this) }, 50L) }
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

    /** The Quick Controls and apps pages shown, kept while the panel is rebuilt. */
    var quickControlsPage = 0
    var dockPage = 0

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
        // Auto-rotate switches directly (never through its BOOX tile, which opens a menu),
        // also while other tiles are being switched.
        if (control.id == "rotate" && !hold) {
            val on = QuickControls.toggleRotation(this)
            if (on != null) {
                optimistic[control.id] = on to SystemClock.uptimeMillis() + OPTIMISTIC_MS
                panel?.refresh()
            } else if (qcBusy) {
                Toast.makeText(this, "Allow \"Modify system settings\" for Auto-rotate (tap it again in a moment)", Toast.LENGTH_SHORT).show()
            } else {
                // One-time Android permission so tapping skips BOOX's rotation menu.
                hidePanel()
                Toast.makeText(this, "Allow \"Modify system settings\" so Auto-rotate switches in one tap", Toast.LENGTH_LONG).show()
                Launcher.start(
                    this,
                    Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        // Front Light: hold shows the presets in the panel; tap goes off, or back to the last preset.
        if (control.id == QuickControls.FRONT_LIGHT) {
            if (hold) return
            val on = quickControlStates()[control.id] ?: true
            frontLight(if (on) QuickControls.PRESET_OFF else QuickControls.lastOnPreset(prefs))
            return
        }
        if (control.kind == QuickControls.Kind.TOGGLE && !hold && !control.momentary) {
            val now = quickControlStates()[control.id] ?: false
            optimistic[control.id] = !now to SystemClock.uptimeMillis() + OPTIMISTIC_MS
            panel?.refresh()
        }
        qcQueue.addLast(QcTask(control, hold))
        if (!qcBusy) startSession()
    }

    /**
     * Switches BOOX's front light to [preset] (Bright, Soft, Custom or Off).
     * Custom is always your BOOX Custom: if one of SuperBoox's presets was
     * borrowing it, your own levels are put back.
     */
    fun frontLight(preset: String) {
        // A SuperBoox preset set the light directly: if BOOX already shows [preset], tapping it
        // would do nothing, so put back the light it had.
        val directBefore = prefs.sp.getString(Prefs.K_FL_DIRECT_BEFORE, null)
        if (QuickControls.activeMine(prefs) != null && directBefore != null && preset == QuickControls.savedPreset(prefs) && AdvancedAccess.independentPresets(prefs)) {
            val (b, w) = directBefore.split(",").map { it.toInt() }
            QuickControls.setActiveMine(prefs, null)
            prefs.sp.edit().remove(Prefs.K_FL_DIRECT_BEFORE).apply()
            AdvancedAccess.setLight(QuickControls.Levels(b, w), AdvancedAccess.lightMethod(prefs)) { panel?.refresh() }
            DebugLog.i("controls", "front light: back to BOOX's $preset ($b/$w)")
            return
        }
        prefs.sp.edit().remove(Prefs.K_FL_DIRECT_BEFORE).apply()
        val restore = if (preset == "Custom" && QuickControls.activeMine(prefs) != null) QuickControls.booxCustom(prefs) else null
        queuePreset(preset, levels = restore, read = preset == "Custom", mine = null)
    }

    /**
     * Front Light on Custom at [levels]: Custom's own sliders, or one of your
     * presets ([mine]), which BOOX only knows as Custom.
     */
    fun frontLightLevels(levels: QuickControls.Levels, mine: String? = null) {
        // Advanced access: SuperBoox presets set the light directly and leave BOOX's presets alone.
        if (mine != null && AdvancedAccess.independentPresets(prefs)) {
            if (QuickControls.activeMine(prefs) == null) {
                AdvancedAccess.currentLight(this)?.let { prefs.sp.edit().putString(Prefs.K_FL_DIRECT_BEFORE, "${it.brightness},${it.warmth}").apply() }
            }
            QuickControls.setActiveMine(prefs, mine)
            QuickControls.byId(QuickControls.FRONT_LIGHT)?.let { optimistic[it.id] = true to SystemClock.uptimeMillis() + OPTIMISTIC_MS }
            AdvancedAccess.markUsed(prefs)
            AdvancedAccess.setLight(levels, AdvancedAccess.lightMethod(prefs)) { ok ->
                if (ok == null) Toast.makeText(this, "Couldn't set the front light (Advanced access)", Toast.LENGTH_SHORT).show()
                panel?.refresh()
            }
            panel?.refresh()
            return
        }
        // Before a SuperBoox preset first borrows Custom, read your BOOX Custom so it can be put back.
        if (mine != null && QuickControls.booxCustom(prefs) == null && QuickControls.activeMine(prefs) == null) {
            queuePreset("Custom", levels = null, read = true, mine = null)
        }
        pendingLevels = levels.clamp()
        pendingMine = mine
        // Slider steps come quickly: only the latest is applied.
        if (qcQueue.none { it.levels != null }) queuePreset("Custom", levels = levels.clamp(), read = false, mine = mine)
    }

    /** Switches to your BOOX Custom and reads its sliders, so the panel shows its levels. */
    fun frontLightReadCustom() = frontLight("Custom")

    /** Your BOOX Custom levels to show: one being applied, else the last known. */
    fun customLevels(): QuickControls.Levels? =
        (if (pendingMine == null) pendingLevels else null) ?: QuickControls.booxCustom(prefs) ?: QuickControls.savedLevels(prefs)

    private var pendingMine: String? = null

    private var pendingLevels: QuickControls.Levels? = null

    private fun queuePreset(preset: String, levels: QuickControls.Levels?, read: Boolean, mine: String?) {
        val control = QuickControls.byId(QuickControls.FRONT_LIGHT) ?: return
        optimistic[control.id] = (preset != QuickControls.PRESET_OFF) to SystemClock.uptimeMillis() + OPTIMISTIC_MS
        optimisticPreset = preset to SystemClock.uptimeMillis() + OPTIMISTIC_MS
        panel?.refresh()
        qcQueue.addLast(QcTask(control, hold = false, preset = preset, levels = levels, read = read, mine = mine))
        if (!qcBusy) startSession()
    }

    /** The Front Light preset to show as selected (null: not known yet). */
    fun frontLightPreset(): String? {
        optimisticPreset?.let { (p, until) -> if (until >= SystemClock.uptimeMillis()) return p }
        return QuickControls.savedPreset(prefs)
    }

    private var optimisticPreset: Pair<String, Long>? = null

    private class QcTask(
        val control: QuickControls.Control,
        val hold: Boolean,
        val preset: String? = null,
        /** Custom's levels to set after selecting it ([pendingLevels] wins if newer). */
        val levels: QuickControls.Levels? = null,
        /** Read Custom's levels afterwards. */
        val read: Boolean = false,
        /** The SuperBoox preset this applies (null: BOOX's own presets). */
        val mine: String? = null,
        val scrolled: Boolean = false,
    )

    /** States to show: the last known ones, with taps still being applied on top. */
    fun quickControlStates(): Map<String, Boolean> {
        val out = QuickControls.states(this, prefs).toMutableMap()
        val now = SystemClock.uptimeMillis()
        optimistic.entries.removeAll { it.value.second < now }
        optimistic.forEach { (id, v) -> out[id] = v.first }
        return out
    }

    private val qcQueue = ArrayDeque<QcTask>()
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
        val task = qcQueue.removeFirstOrNull() ?: return finishSession(closeShade = true, keepPanel = true)
        task.preset?.let { return nextPreset(task, it) }
        val control = task.control
        val hold = task.hold
        val press = QuickControls.press(this, prefs, control, hold)
        if (!press.found || !press.pressed) {
            optimistic.remove(control.id)
            panel?.refresh()
            Toast.makeText(this, "Couldn't find ${control.label} in the Control Center", Toast.LENGTH_SHORT).show()
            return nextControl()
        }
        handler.postDelayed({
            if (control.kind == QuickControls.Kind.TOGGLE && !hold) {
                if (!control.momentary) {
                    QuickControls.settle(this, prefs, control, press.before)?.let {
                        optimistic[control.id] = it to SystemClock.uptimeMillis() + OPTIMISTIC_MS
                    }
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

    private fun nextPreset(task: QcTask, preset: String) {
        // Which levels Custom holds decides where they're remembered (yours or BOOX's own).
        QuickControls.setActiveMine(prefs, if (preset == "Custom") task.mine else null)
        val press = QuickControls.pressPreset(this, prefs, preset)
        if (!press.found && !task.scrolled) {
            // The presets sit under the tiles: scroll the Control Center down and look again.
            QuickControls.scrollShade(this)
            qcQueue.addFirst(QcTask(task.control, false, preset, task.levels, task.read, task.mine, scrolled = true))
            handler.postDelayed({ nextControl() }, 400)
            return
        }
        if (!press.found || !press.pressed) {
            optimistic.remove(task.control.id)
            optimisticPreset = null
            panel?.refresh()
            Toast.makeText(this, "Couldn't find the Front Light presets in the Control Center", Toast.LENGTH_SHORT).show()
            return nextControl()
        }
        handler.postDelayed({
            QuickControls.settlePreset(this, prefs, preset)
            val levels = task.levels?.let { if (pendingLevels != null && pendingMine == task.mine) pendingLevels else it }
            if (levels == null && !task.read) {
                panel?.refresh()
                return@postDelayed nextControl()
            }
            // Custom's sliders: set them, then read back what BOOX shows.
            if (levels != null) QuickControls.setLevels(this, prefs, levels)
            if (levels != null) pendingLevels = null
            handler.postDelayed({
                QuickControls.readLevels(this, prefs)
                panel?.refresh()
                nextControl()
            }, if (levels != null) 400L else 0L)
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
        // Typing in the panel (renaming, searching the clipboard): slide it up if the keyboard would cover the
        // field, and don't bring the keyboard up until a field is tapped.
        if (focusable) softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
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
