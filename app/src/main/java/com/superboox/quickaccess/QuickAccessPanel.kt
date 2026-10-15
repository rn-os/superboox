package com.superboox.quickaccess

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The Quick Access panel, laid out like the Supernote sidebar:
 *
 *   [ Clipboard | New Note | ⚙ ]   (Recent Files here when the Clipboard button is off)
 *   Last Opened Note
 *   Last Opened Document
 *   ── Quick Access ──────── Add
 *   pinned items (text rows; long-press to edit)
 *   ── apps, 2 × 2 per page ‹ 1/n › ──
 *
 * The middle switches between Quick Access, Recent Files and edit mode; the
 * library "Add" picker takes over the whole (wider) sheet, like Supernote's
 * file manager. Everything is sized through [s]/[sp] for the Menu size setting.
 */
class QuickAccessPanel(
    private val service: QuickAccessService,
    private val prefs: Prefs,
    private val foregroundPackage: String?,
    /** Settings' live preview: start on the clipboard, and show a PREVIEW tag. */
    private val previewOfClipboard: Boolean = false,
    private val previewTag: String? = null,
    /** The tour's Advanced access step: open on Front Light with your own presets showing. */
    private val previewLightPresets: Boolean = false,
) {
    private enum class Mode { MAIN, RECENT, EDIT, PICK, CLIPBOARD }

    private val ctx: Context = ContextThemeWrapper(service, R.style.Theme_SuperBoox)
    private val main = Handler(Looper.getMainLooper())
    private val scale = prefs.menuSize.scale
    private val expandedFolders = HashSet<String>()
    private var mode = Mode.MAIN
    private var dockPage: Int
        get() = service.dockPage
        set(v) { service.dockPage = v }
    private var dockCellCount = 0

    /** What BOOX Notes is showing, if it's the app behind the panel. */
    private val notesState: NotesScreen.State? = if (service.isNotesInFront) service.notesState() else null

    /** The note open behind the panel, if any: "last opened" means the one before it. */
    private val currentNote: String? = (notesState as? NotesScreen.State.Editor)?.title

    private fun isCurrentNote(item: RecentItem) =
        item.kind == RecentItem.Kind.NOTE && currentNote != null && NotesScreen.matches(item.title, currentNote)

    /**
     * The document open right now: the newest one remembered from the reading
     * app in front (recorded when it was opened). Skipped like the open note.
     */
    private val currentDocument: RecentItem? = foregroundPackage?.let { pkg ->
        Recents.list(prefs).firstOrNull { it.kind == RecentItem.Kind.DOCUMENT && it.pkg == pkg }
    }

    private fun isCurrent(item: RecentItem) =
        isCurrentNote(item) || (currentDocument != null && item.key == currentDocument.key)

    /** Library cards offered by the Add picker, and which are ticked. */
    private var pickChoices: List<NotesScreen.Card> = emptyList()

    /** When picking from BOOX's Library (books): each card's document. Empty when picking notes. */
    private var pickDocs: Map<String, ReaderLibrary.Doc> = emptyMap()
    private val picked = LinkedHashSet<String>()

    val root: FrameLayout = object : FrameLayout(ctx) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (renaming != null) endRename(save = false)
                    else if (mode == Mode.CLIPBOARD && clipConfirmClear) { clipConfirmClear = false; fillClips() }
                    else if (mode == Mode.CLIPBOARD && clipOpen != null) { clipOpen = null; fillClips() }
                    else if (mode != Mode.MAIN) show(Mode.MAIN) else service.hidePanel()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        // A tap anywhere else in the panel closes the Front Light box, and still does what it normally does.
        override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
            val outside = ev.actionMasked == android.view.MotionEvent.ACTION_DOWN && lightMenu && !touchesLight(ev)
            val handled = super.dispatchTouchEvent(ev)
            if (outside) post { if (lightMenu) closeLightMenu() }
            return handled
        }
    }

    /** The Front Light box and its tile, as last drawn: taps on them leave the box open. */
    private var lightBoxView: View? = null
    private var lightTileView: View? = null

    private fun touchesLight(ev: android.view.MotionEvent): Boolean = listOfNotNull(lightBoxView, lightTileView).any { v ->
        val at = IntArray(2)
        v.getLocationOnScreen(at)
        ev.rawX >= at[0] && ev.rawX <= at[0] + v.width && ev.rawY >= at[1] && ev.rawY <= at[1] + v.height
    }

    private val sheet = ctx.hbox()
    private val content = ctx.vbox()

    // Clipboard state, declared before init: a preview that opens on the clipboard draws it from
    // init, and fields declared later would still be null then (build 63 crash).
    private var clipFilter = ClipFilter.ALL
    private var clipQuery = ""
    private var clipPage = 0

    /** Handwriting pictures are being drawn in the background. */
    private var drawing = false

    /** The clip whose actions are showing (it was tapped or held). */
    private var clipOpen: String? = null
    private var clipSearch: android.widget.EditText? = null
    private var clipBody: LinearLayout? = null
    private var clipCount: TextView? = null
    private var clipClear: View? = null

    /** "Clear" in the clipboard's header was tapped and is waiting to be confirmed. */
    private var clipConfirmClear = false

    init {
        // Tapping outside the sheet closes it (no dimming: it only costs an e-ink refresh).
        root.setOnClickListener { service.hidePanel() }
        sheet.setBackgroundColor(Ink.PAPER)
        sheet.isClickable = true // swallow taps so they don't close the panel
        sheet.gravity = Gravity.NO_GRAVITY
        val line = View(ctx).apply { setBackgroundColor(Ink.INK) }
        if (prefs.edge == Edge.RIGHT) {
            sheet.addView(line, lp(ctx.dp(3), MATCH))
            sheet.addView(content, lp(0, MATCH, 1f))
        } else {
            sheet.addView(content, lp(0, MATCH, 1f))
            sheet.addView(line, lp(ctx.dp(3), MATCH))
        }
        root.addView(sheet)
        previewTag?.let { tag ->
            root.addView(ctx.text(tag, 12f, bold = true, color = Ink.PAPER).apply {
                setPadding(ctx.dp(10), ctx.dp(3), ctx.dp(10), ctx.dp(3))
                background = GradientDrawable().apply { setColor(Ink.INK); cornerRadius = ctx.dp(10).toFloat() }
            }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = ctx.dp(240) })
        }
        // The panel has focus once its window does: the moment Android lets SuperBoox read the clipboard.
        root.viewTreeObserver.addOnWindowFocusChangeListener { has ->
            if (has && root.isAttachedToWindow) {
                val before = ClipHistory.list(service).firstOrNull()?.let { it.id to it.time }
                ClipHistory.checkInForeground(service, prefs, foregroundPackage)
                val after = ClipHistory.list(service).firstOrNull()?.let { it.id to it.time }
                if (mode == Mode.CLIPBOARD && before != after) fillClips()
            }
        }
        show(if (previewOfClipboard) Mode.CLIPBOARD else Mode.MAIN)
        // After construction: the Front Light fields are declared (and initialised) below this block.
        if (previewLightPresets) root.post {
            lightMenu = true
            lightPage = 1
            show(mode)
        }
    }

    /** dp scaled by the "Menu size" setting. */
    private fun s(v: Int): Int = ctx.dp(v * scale)
    private fun sp(v: Float): Float = v * scale
    private fun iconPx(v: Int): Int = (v * scale).roundToInt()

    private fun show(newMode: Mode) {
        if (newMode != mode) clipConfirmClear = false
        mode = newMode
        val screenW = service.screenWidth()
        val width = if (mode == Mode.PICK) maxOf(s(300), screenW * maxOf(62, prefs.panelWidthPct) / 100).coerceAtMost(screenW)
        else sheetWidth(ctx, prefs, screenW)
        sheet.layoutParams = FrameLayout.LayoutParams(width, MATCH, if (prefs.edge == Edge.RIGHT) Gravity.END else Gravity.START)

        content.removeAllViews()
        if (mode == Mode.PICK) {
            content.addView(pickerBar(), lp(MATCH, WRAP))
            content.addView(scrolling(pickerList()), lp(MATCH, 0, 1f))
            return
        }
        content.addView(topRow(), lp(MATCH, WRAP))
        content.addView(rule(2))
        if (mode == Mode.CLIPBOARD) {
            // Settings → Clipboard → "While the clipboard is open, still show".
            if (prefs.showSystemActions && prefs.clipShowControls) {
                content.addView(quickControlsRow(), lp(MATCH, WRAP))
                content.addView(rule(2))
            }
            if (prefs.clipShowLast) {
                content.addView(lastNoteRow())
                content.addView(insetRule())
                content.addView(lastDocumentRow())
                content.addView(rule(3))
            }
            content.addView(clipboardHub(), lp(MATCH, 0, 1f))
            if (prefs.clipShowApps) {
                content.addView(rule(3))
                content.addView(dock(), lp(MATCH, WRAP))
            }
            return
        }
        if (prefs.showSystemActions) {
            content.addView(quickControlsRow(), lp(MATCH, WRAP))
            content.addView(rule(2))
        }
        content.addView(lastNoteRow())
        content.addView(insetRule())
        content.addView(lastDocumentRow())
        content.addView(rule(3))
        val middle = when (mode) {
            Mode.RECENT -> recentList()
            Mode.EDIT -> editList()
            else -> quickAccessList()
        }
        content.addView(scrolling(middle), lp(MATCH, 0, 1f))
        content.addView(rule(3))
        thisAppStrip()?.let {
            content.addView(it, lp(MATCH, WRAP))
            content.addView(rule(1))
        }
        content.addView(dock(), lp(MATCH, WRAP))
    }

    // ---- Top: Recent Files | New Note | ⚙ --------------------------------------------------

    private fun topRow(): View = with(ctx) {
        hbox().apply {
            gravity = Gravity.NO_GRAVITY
            if (prefs.clipShowButton) {
                addView(topCell(R.drawable.ic_clipboard, "Clipboard", lit = mode == Mode.CLIPBOARD, onHold = { openSettingsPage("CLIPBOARD") }) { toggleClipboard() }, lp(0, WRAP, 1f))
            } else {
                addView(topCell(R.drawable.ic_clock, "Recent Files") { show(if (mode == Mode.RECENT) Mode.MAIN else Mode.RECENT) }, lp(0, WRAP, 1f))
            }
            addView(vRule(), lp(dp(2), MATCH))
            addView(topCell(R.drawable.ic_note_add, "New Note", onHold = { newNoteSetup() }) { newNote() }, lp(0, WRAP, 1f))
            addView(vRule(), lp(dp(2), MATCH))
            addView(FrameLayout(ctx).apply {
                contentDescription = "SuperBoox settings"
                addView(icon(getDrawable(R.drawable.ic_settings), iconPx(32)), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                pressable()
                setOnClickListener { openSettings() }
            }, lp(s(52), MATCH))
        }
    }

    private fun topCell(iconRes: Int, label: String, lit: Boolean = false, onHold: (() -> Unit)? = null, onClick: () -> Unit): View = with(ctx) {
        vbox().apply {
            gravity = Gravity.CENTER
            setPadding(0, s(12), 0, s(10))
            contentDescription = if (lit) "$label, open" else label
            addView(icon(getDrawable(iconRes), iconPx(32)).apply { if (lit) imageTintList = ColorStateList.valueOf(Ink.PAPER) })
            // Text views in a vertical LinearLayout default to full width: centre the text itself.
            addView(text(label, sp(15f), medium = true, color = if (lit) Ink.PAPER else Ink.INK).apply {
                gravity = Gravity.CENTER
                setPadding(0, s(4), 0, 0)
            })
            if (lit) pressable(android.graphics.drawable.ColorDrawable(Ink.INK)) else pressable()
            setOnClickListener { onClick() }
            onHold?.let { hold -> setOnLongClickListener { hold(); true } }
        }
    }

    /** Settings, opened on one of its pages (a [SettingsActivity.Page] name). */
    private fun openSettingsPage(page: String) {
        service.hidePanel()
        Launcher.start(service, Intent(service, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_PAGE, page)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    /** Hold New Note: BOOX's own new-note screen (name, template), from the Notes library. */
    private fun newNoteSetup() {
        service.hidePanel()
        service.openNewNoteSetup()
    }

    private fun newNote() {
        val intent = BooxNotes.newNoteIntent(service, prefs.notesPackage)
        if (intent != null && Launcher.tryStart(service, intent)) {
            DebugLog.i("panel", "new note")
            service.hidePanel()
            return
        }
        // Not available on this firmware: open Notes so the user can tap its own "+".
        DebugLog.w("panel", "quick-note screen unavailable; opening Notes instead")
        toast("Opening BOOX Notes. Tap + there for a new note")
        if (BooxNotes.launchCandidates(service, prefs.notesPackage).any { Launcher.tryStart(service, it.second) }) service.hidePanel()
    }

    private fun openSettings() {
        service.hidePanel()
        Launcher.start(service, Intent(service, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- Quick Controls: one row of round tiles, paged ----------------------------------

    /** Front Light's presets are showing under the Quick Controls (it was held). */
    private var lightMenu = false

    /**
     * The Front Light tile was tapped and will go to [next]. The box opens by itself on Custom, where
     * you'd adjust, and stays open while you keep tapping; stepping off Custom hides its sliders.
     */
    fun onLightStep(next: String) {
        commitNow()
        editLevels = null
        if (next == "Custom") {
            lightMenu = true
            lightPage = 0
            lightEdit = "custom"
        } else if (lightMenu) {
            lightEdit = null
            lightPage = 0
        }
    }

    /** Redraws in place (Quick Controls after a tap). */
    fun refresh() = show(mode)

    /** The Front Light preset changed outside the panel: redraw, on the page holding the one now active. */
    fun refreshLight() {
        if (lightMenu && lightEdit == null) lightPage = activeLightPage()
        show(mode)
    }

    private fun quickControlsRow(): View = with(ctx) {
        val controls = QuickControls.selected(prefs)
        val box = vbox().apply { setPadding(s(4), s(10), s(4), s(2)) }
        if (controls.isEmpty()) {
            box.addView(hint("Add Quick Controls in Settings."))
            return@with box
        }
        val pages = controls.chunked(QC_PER_PAGE)
        val page = service.quickControlsPage.coerceIn(0, pages.size - 1)
        service.quickControlsPage = page
        val states = service.quickControlStates()
        fun go(to: Int) {
            if (to in pages.indices && to != page) {
                service.quickControlsPage = to
                show(mode)
            }
        }
        val row = SwipeRow(ctx, s(40)) { go(page + it) }
        pages[page].forEach { row.addView(controlTile(it, states[it.id] == true), lp(0, WRAP, 1f)) }
        repeat(QC_PER_PAGE - pages[page].size) { row.addView(View(ctx), lp(0, 1, 1f)) }
        box.addView(row, lp(MATCH, WRAP))
        if (pages.size > 1) {
            box.addView(hbox().apply {
                gravity = Gravity.CENTER
                addView(pagerButton(R.drawable.ic_chevron_left, "Previous controls", page > 0) { go(page - 1) })
                addView(text("${page + 1} / ${pages.size}", sp(13f), bold = true).apply { setPadding(s(12), 0, s(12), 0) })
                addView(pagerButton(R.drawable.ic_chevron_right, "More controls", page < pages.size - 1) { go(page + 1) })
            }, lp(MATCH, WRAP))
        }
        if (service.isWritingMode) box.addView(writingBar(), lp(MATCH, WRAP).apply { setMargins(s(6), s(4), s(6), s(8)) })
        if (lightMenu && controls.any { it.id == QuickControls.FRONT_LIGHT }) box.addView(frontLightMenu(), lp(MATCH, WRAP))
        box
    }

    /** Shown while Writing mode is on: what it switched off, and the way back. Solid black, so it reads on any screen. */
    private fun writingBar(): View = with(ctx) {
        hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = outline(2, 10, Ink.INK)
            setPadding(s(12), s(8), s(8), s(8))
            addView(icon(getDrawable(R.drawable.ic_qc_writing), iconPx(22)).apply { imageTintList = ColorStateList.valueOf(Ink.PAPER) })
            addView(vbox().apply {
                setPadding(s(10), 0, s(8), 0)
                addView(text("Writing mode is on", sp(15f), bold = true, color = Ink.PAPER))
                addView(text(WritingMode.summary(prefs), sp(12.5f), color = Ink.PAPER))
            }, lp(0, WRAP, 1f))
            addView(text("Turn off", sp(14f), bold = true).apply {
                background = outline(2, 8)
                setPadding(s(10), s(6), s(10), s(6))
                contentDescription = "Turn Writing mode off"
                setOnClickListener { service.writingMode(false) }
            })
        }
    }

    /** Page of the Front Light box: 0 is BOOX's presets, then yours. */
    private var lightPage = 0

    /** Levels being edited under the presets: "custom" (BOOX's Custom) or one of your presets' ids. */
    private var lightEdit: String? = null
    private var editLevels: QuickControls.Levels? = null

    /** Your preset whose Remove is waiting to be confirmed. */
    private var confirmRemove: String? = null

    /** Your preset being renamed in place, and its name field (kept across redraws so typing isn't lost). */
    private var renaming: String? = null
    private var renameField: android.widget.EditText? = null
    private val commitLevels = Runnable { applyEditLevels() }

    /**
     * Bright · Soft · Custom · Off like BOOX's Front Light card (the one in use
     * is black), then pages of your own presets and + New. Holding Custom or
     * one of yours drops down its brightness and warmth.
     */
    private fun frontLightMenu(): View = with(ctx) {
        val current = service.frontLightPreset()
        val mine = QuickControls.myPresets(prefs)
        val direct = AdvancedAccess.independentPresets(prefs)
        val active = QuickControls.activeMine(prefs)?.takeIf { (direct || current == "Custom") && mine.any { p -> p.id == it } }
        // Page 0: BOOX's four. Then yours, four to a page, ending with + New.
        val extra: List<QuickControls.LightPreset?> = mine + listOf(null)
        val pages = 1 + (extra.size + LIGHT_PER_PAGE - 1) / LIGHT_PER_PAGE
        lightPage = lightPage.coerceIn(0, pages - 1)
        fun go(to: Int) {
            if (to in 0 until pages && to != lightPage) {
                lightPage = to
                show(mode)
            }
        }
        vbox().apply {
            background = outline(2, 10)
            setPadding(s(6), s(8), s(6), s(10))
            addView(hbox().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(s(8), 0, s(2), s(6))
                addView(text("Front Light", sp(16f), bold = true), lp(0, WRAP, 1f))
                addView(icon(getDrawable(R.drawable.ic_close), iconPx(22)).apply {
                    contentDescription = "Close Front Light presets"
                    setPadding(s(6), s(6), s(6), s(6))
                    pressable()
                    setOnClickListener { closeLightMenu() }
                })
            }, lp(MATCH, WRAP))
            val row = SwipeRow(ctx, s(40)) { go(lightPage + it) }
            if (lightPage == 0) {
                val icons = mapOf("Bright" to R.drawable.ic_qc_light, "Soft" to R.drawable.ic_qc_dnd,
                    "Custom" to R.drawable.ic_settings, "Off" to R.drawable.ic_qc_light_off)
                QuickControls.PRESETS.forEach { preset ->
                    val lit = preset == current && !(preset == "Custom" && active != null)
                    val tile = roundTile(preset, icons.getValue(preset), lit, description = "Front Light $preset")
                    tile.setOnClickListener { service.frontLight(preset) }
                    if (preset == "Custom") tile.setOnLongClickListener {
                        if (lightEdit == "custom") lightEdit = null else {
                            lightEdit = "custom"
                            if (current != "Custom" || active != null) service.frontLightReadCustom()
                        }
                        editLevels = null
                        show(mode)
                        true
                    }
                    row.addView(tile, lp(0, WRAP, 1f))
                }
            } else if (!direct) {
                // Your presets need Advanced access; say why it's missing and where to set it up.
                row.addView(advancedPrompt(), lp(MATCH, WRAP))
            } else {
                val items = extra.drop((lightPage - 1) * LIGHT_PER_PAGE).take(LIGHT_PER_PAGE)
                items.forEach { p ->
                    val tile = if (p == null) {
                        roundTile("New", R.drawable.ic_add, lit = false, description = "New Front Light preset", dashed = true).apply {
                            setOnClickListener { newLightPreset() }
                        }
                    } else {
                        roundTile(p.name, R.drawable.ic_qc_light, lit = p.id == active, description = "Front Light ${p.name}").apply {
                            setOnClickListener { service.frontLightLevels(p.levels, p.id) }
                            setOnLongClickListener {
                                if (lightEdit == p.id) lightEdit = null else {
                                    lightEdit = p.id
                                    service.frontLightLevels(p.levels, p.id)
                                }
                                editLevels = null
                                show(mode)
                                true
                            }
                        }
                    }
                    row.addView(tile, lp(0, WRAP, 1f))
                }
                repeat(LIGHT_PER_PAGE - items.size) { row.addView(View(ctx), lp(0, 1, 1f)) }
            }
            addView(row, lp(MATCH, WRAP))
            levelsEditor(mine)?.let { addView(it, lp(MATCH, WRAP)) }
            addView(hbox().apply {
                gravity = Gravity.CENTER
                addView(pagerButton(R.drawable.ic_chevron_left, "Previous presets", lightPage > 0) { go(lightPage - 1) })
                addView(text("${lightPage + 1} / $pages", sp(13f), bold = true).apply { setPadding(s(12), 0, s(12), 0) })
                addView(pagerButton(R.drawable.ic_chevron_right, "More presets", lightPage < pages - 1) { go(lightPage + 1) })
            }, lp(MATCH, WRAP))
        }.let { menu ->
            lightBoxView = menu
            FrameLayout(ctx).apply {
                setPadding(s(6), s(6), s(6), s(8))
                addView(menu, FrameLayout.LayoutParams(MATCH, WRAP))
            }
        }
    }

    /** In place of your presets while Advanced access isn't on (or dropped after a restart). */
    private fun advancedPrompt(): View = with(ctx) {
        val restarted = prefs.independentPresets && prefs.lightTestPassed && AdvancedAccess.lightMethod(prefs) == AdvancedAccess.METHOD_SHELL &&
            AdvancedAccess.restartedSinceUse(prefs) && !AdvancedAccess.running()
        vbox().apply {
            setPadding(s(10), s(6), s(10), s(8))
            addView(text(if (restarted) "Start Shizuku again" else "Your own presets need Advanced access", sp(15f), bold = true))
            addView(text(
                if (restarted) "The tablet has restarted since your presets were last used, so Advanced access is off until Shizuku is started again."
                else "Advanced access (on-device ADB, no computer) lets your presets set the light without changing BOOX's Custom. Set it up once in Settings.",
                sp(13f), color = Ink.MUTED,
            ).apply { setPadding(0, s(4), 0, s(8)) })
            addView(inkButton(if (restarted) "Open Advanced access" else "Set up in Settings", sp(14f)) {
                service.hidePanel()
                Launcher.start(service, Intent(service, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_PAGE, "ADVANCED_ACCESS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }, lp(MATCH, WRAP))
        }
    }

    /** The Front Light page holding the preset in use: 0 for BOOX's, else the page with yours. */
    private fun activeLightPage(): Int {
        val id = QuickControls.activeMine(prefs) ?: return 0
        val direct = AdvancedAccess.independentPresets(prefs)
        if (!direct && service.frontLightPreset() != "Custom") return 0
        val i = QuickControls.myPresets(prefs).indexOfFirst { it.id == id }
        return if (i < 0) 0 else 1 + i / LIGHT_PER_PAGE
    }

    private fun closeLightMenu() {
        endRename(save = false)
        commitNow()
        lightMenu = false
        lightEdit = null
        show(mode)
    }

    /** Brightness and warmth for [lightEdit], under a dashed rule. */
    private fun levelsEditor(mine: List<QuickControls.LightPreset>): View? = with(ctx) {
        val target = lightEdit ?: return@with null
        val preset = mine.firstOrNull { it.id == target }
        if (target != "custom" && preset == null) return@with null
        val known = editLevels ?: if (preset != null) preset.levels else service.customLevels()
        val levels = known ?: QuickControls.Levels(16, 0)
        vbox().apply {
            background = dashedTop()
            setPadding(s(4), s(10), s(4), s(2))
            addView(text(
                if (preset != null) "${preset.name.uppercase()} · YOUR PRESET" else "CUSTOM · SAME AS BOOX'S CUSTOM" + if (known == null) " (READING…)" else "",
                sp(12.5f), bold = true,
            ).apply { setPadding(s(6), 0, s(6), s(4)) })
            addView(levelRow("Light", levels.brightness) { editLevel(levels.copy(brightness = it)) })
            addView(levelRow("Warm", levels.warmth) { editLevel(levels.copy(warmth = it)) })
            if (preset != null) addView(presetActions(preset), lp(MATCH, WRAP))
        }
    }

    /** Rename and Remove for one of your presets, both in place in the panel. */
    private fun presetActions(preset: QuickControls.LightPreset): View = with(ctx) {
        if (confirmRemove == preset.id) {
            return@with vbox().apply {
                background = outline(2, 8)
                setPadding(s(10), s(8), s(10), s(8))
                addView(text("Remove “${preset.name}”?", sp(14f), bold = true).apply { setPadding(0, 0, 0, s(6)) })
                addView(hbox().apply {
                    addView(inkButton("Cancel", sp(14f)) { confirmRemove = null; show(mode) }, lp(0, WRAP, 1f).apply { marginEnd = s(8) })
                    addView(inkButton("Remove", sp(14f)) { removePreset(preset) }.apply {
                        setTextColor(Ink.PAPER)
                        background = android.graphics.drawable.GradientDrawable().apply { setColor(Ink.INK); cornerRadius = dp(6).toFloat() }
                    }, lp(0, WRAP, 1f))
                }, lp(MATCH, WRAP))
            }.let { box -> FrameLayout(ctx).apply { setPadding(s(4), s(8), s(4), s(2)); addView(box, FrameLayout.LayoutParams(MATCH, WRAP)) } }
        }
        if (renaming == preset.id) {
            val field = renameField ?: android.widget.EditText(ctx).apply {
                setText(preset.name)
                setSelection(text.length)
                textSize = 16f
                setTextColor(Ink.INK)
                isSingleLine = true
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                background = outline(2, 6)
                setPadding(s(10), s(8), s(10), s(8))
                setOnEditorActionListener { _, _, _ -> endRename(save = true); true }
                setOnFocusChangeListener { _, has -> if (has) post { setSelection(text.length) } }
                renameField = this
            }
            (field.parent as? ViewGroup)?.removeView(field)
            return@with vbox().apply {
                background = outline(2, 8)
                setPadding(s(10), s(8), s(10), s(8))
                addView(text("Rename “${preset.name}”", sp(14f), bold = true).apply { setPadding(0, 0, 0, s(6)) })
                addView(field, lp(MATCH, WRAP).apply { bottomMargin = s(8) })
                addView(hbox().apply {
                    addView(inkButton("Cancel", sp(14f)) { endRename(save = false) }, lp(0, WRAP, 1f).apply { marginEnd = s(8) })
                    addView(inkButton("Save", sp(14f)) { endRename(save = true) }.apply {
                        setTextColor(Ink.PAPER)
                        background = android.graphics.drawable.GradientDrawable().apply { setColor(Ink.INK); cornerRadius = dp(6).toFloat() }
                    }, lp(0, WRAP, 1f))
                }, lp(MATCH, WRAP))
                field.post {
                    field.requestFocus()
                    // Focus can put the cursor back at the start; typing usually adds to the end.
                    field.setSelection(field.text.length)
                    (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }.let { box -> FrameLayout(ctx).apply { setPadding(s(4), s(8), s(4), s(2)); addView(box, FrameLayout.LayoutParams(MATCH, WRAP)) } }
        }
        hbox().apply {
            setPadding(s(4), s(8), s(4), s(2))
            addView(inkButton("Rename", sp(14f)) {
                commitNow()
                confirmRemove = null
                renaming = preset.id
                renameField = null
                show(mode)
            }, lp(0, WRAP, 1f).apply { marginEnd = s(8) })
            addView(inkButton("Remove", sp(14f)) { confirmRemove = preset.id; show(mode) }, lp(0, WRAP, 1f))
        }
    }

    /** Closes the name field, keeping the new name when [save] (an empty name keeps the old one). */
    private fun endRename(save: Boolean) {
        val id = renaming ?: return
        val field = renameField
        val name = field?.text?.toString()?.trim().orEmpty()
        field?.let {
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(it.windowToken, 0)
        }
        renaming = null
        renameField = null
        if (save && name.isNotEmpty()) {
            val mine = QuickControls.myPresets(prefs)
            mine.firstOrNull { it.id == id }?.takeIf { it.name != name }?.let { old ->
                QuickControls.saveMyPresets(prefs, mine.map { if (it.id == id) it.copy(name = name) else it })
                DebugLog.i("controls", "front light preset renamed: ${old.name} -> $name")
            }
        }
        show(mode)
    }

    private fun removePreset(preset: QuickControls.LightPreset) {
        root.removeCallbacks(commitLevels)
        editLevels = null
        // In use: put back the BOOX preset that was on before it.
        if (QuickControls.activeMine(prefs) == preset.id) service.frontLight(QuickControls.savedPreset(prefs) ?: "Bright")
        QuickControls.saveMyPresets(prefs, QuickControls.myPresets(prefs).filterNot { it.id == preset.id })
        DebugLog.i("controls", "front light preset removed: ${preset.name}")
        confirmRemove = null
        lightEdit = null
        show(mode)
    }

    private fun levelRow(label: String, value: Int, onChange: (Int) -> Unit): View = with(ctx) {
        hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(s(4), s(4), s(4), s(4))
            addView(text(label, sp(13f), bold = true), lp(s(44), WRAP))
            fun step(sign: String, by: Int) = FrameLayout(ctx).apply {
                contentDescription = "$label ${if (by < 0) "down" else "up"}"
                addView(text(sign, sp(20f), bold = true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
                pressable()
                // After pressable(), which would otherwise replace the ring with a plain background.
                background = ovalButton()
                setOnClickListener { onChange((value + by).coerceIn(0, QuickControls.LEVEL_MAX)) }
            }
            addView(step("−", -1), lp(s(34), s(34)))
            addView(LevelBar(ctx, QuickControls.LEVEL_MAX, value, dp(2)) { onChange(it) }.apply {
                contentDescription = "$label $value of ${QuickControls.LEVEL_MAX}"
            }, lp(0, s(18), 1f).apply { marginStart = s(8); marginEnd = s(8) })
            addView(step("+", 1), lp(s(34), s(34)))
            addView(text("$value", sp(16f), bold = true).apply { gravity = Gravity.END }, lp(s(34), WRAP))
        }
    }

    private fun editLevel(levels: QuickControls.Levels) {
        editLevels = levels.clamp()
        show(mode)
        // Send it once the taps stop, not on every step.
        root.removeCallbacks(commitLevels)
        root.postDelayed(commitLevels, 700)
    }

    private fun commitNow() {
        if (editLevels == null) return
        root.removeCallbacks(commitLevels)
        applyEditLevels()
    }

    private fun applyEditLevels() {
        val levels = editLevels ?: return
        val target = lightEdit ?: return
        if (target == "custom") {
            service.frontLightLevels(levels)
        } else {
            val mine = QuickControls.myPresets(prefs)
            QuickControls.saveMyPresets(prefs, mine.map { if (it.id == target) it.copy(levels = levels) else it })
            service.frontLightLevels(levels, target)
        }
    }

    /** + New: a preset from the light's current Custom levels, opened for adjusting. */
    private fun newLightPreset() {
        val mine = QuickControls.myPresets(prefs)
        var n = mine.size + 1
        while (mine.any { it.name == "Preset $n" }) n++
        // Starts from the light as it is (your BOOX Custom); SuperBoox never overwrites that.
        val p = QuickControls.LightPreset(QuickControls.newPresetId(), "Preset $n", service.customLevels() ?: QuickControls.Levels(16, 8))
        QuickControls.saveMyPresets(prefs, mine + p)
        lightEdit = p.id
        editLevels = null
        lightPage = 1 + mine.size / LIGHT_PER_PAGE
        service.frontLightLevels(p.levels, p.id)
        show(mode)
    }

    private fun dashedTop(): android.graphics.drawable.Drawable = object : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint().apply {
            color = Ink.INK
            strokeWidth = ctx.dp(1.5f).toFloat()
            style = android.graphics.Paint.Style.STROKE
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(ctx.dp(5).toFloat(), ctx.dp(4).toFloat()), 0f)
        }
        override fun draw(canvas: android.graphics.Canvas) {
            val y = bounds.top + paint.strokeWidth
            canvas.drawLine(bounds.left.toFloat() + ctx.dp(6), y, bounds.right.toFloat() - ctx.dp(6), y, paint)
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        @Deprecated("Drawable API")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** Round tile with a label under it: black when [lit]. */
    private fun roundTile(label: String, iconRes: Int, lit: Boolean, description: String, dashed: Boolean = false): ViewGroup = with(ctx) {
        vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, s(6))
            contentDescription = description
            val size = s(52)
            addView(FrameLayout(ctx).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (lit) Ink.INK else Ink.PAPER)
                    if (dashed) setStroke(dp(2), Ink.INK, dp(6).toFloat(), dp(4).toFloat()) else setStroke(dp(2), Ink.INK)
                }
                addView(icon(getDrawable(iconRes), iconPx(26)).apply {
                    imageTintList = ColorStateList.valueOf(if (lit) Ink.PAPER else Ink.INK)
                }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            }, lp(size, size))
            addView(text(label, sp(12f)).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(s(2), s(5), s(2), 0)
            }, lp(MATCH, WRAP))
            pressable()
        }
    }

    /** Round tile like the BOOX Control Center: black when on, outlined when off. */
    private fun controlTile(control: QuickControls.Control, on: Boolean): View = with(ctx) {
        val lit = on && control.kind == QuickControls.Kind.TOGGLE && !control.momentary
        val description = "${control.label}${if (control.kind == QuickControls.Kind.TOGGLE) if (on) ", on" else ", off" else ""}"
        roundTile(control.label, control.icon, lit, description).apply {
            if (control.id == QuickControls.FRONT_LIGHT) lightTile(this)
            setOnClickListener {
                // One-shot controls (Full Refresh) give a quick spin of the icon instead of filling.
                if (control.momentary) (getChildAt(0) as? ViewGroup)?.getChildAt(0)?.animate()?.rotationBy(360f)?.setDuration(500)?.start()
                service.quickControl(control, hold = false)
            }
            setOnLongClickListener {
                if (control.id == QuickControls.FRONT_LIGHT) {
                    if (lightMenu) closeLightMenu() else {
                        lightMenu = true
                        lightPage = activeLightPage()
                        show(mode)
                        // Make sure it shows what BOOX actually has (it may have been changed there).
                        service.checkFrontLight()
                    }
                } else if (control.id == QuickControls.WRITING) {
                    openSettingsPage("CONTROLS")
                } else {
                    service.quickControl(control, hold = true)
                }
                true
            }
        }
    }

    /** Front Light's tile names the state (with Custom's level) and shows where it is in the Off, Soft, Bright, Custom steps. */
    private fun lightTile(tile: ViewGroup) = with(ctx) {
        lightTileView = tile
        val current = service.frontLightPreset()
        val mine = QuickControls.activeMine(prefs)?.takeIf { current == "Custom" }
            ?.let { id -> QuickControls.myPresets(prefs).firstOrNull { it.id == id } }
        val level = if (current == "Custom") (mine?.levels ?: service.customLevels())?.let { " ${it.brightness * 100 / QuickControls.LEVEL_MAX}%" } else null
        (tile.getChildAt(1) as? android.widget.TextView)?.text = (mine?.name ?: current ?: "Front Light") + (level ?: "")
        val at = QuickControls.CYCLE.indexOf(current)
        tile.addView(hbox().apply {
            gravity = Gravity.CENTER
            QuickControls.CYCLE.forEachIndexed { i, _ ->
                addView(View(ctx).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(if (i == at) Ink.INK else Ink.PAPER)
                        setStroke(dp(1.5f), Ink.INK)
                    }
                }, lp(s(8), s(8)).apply { if (i < 3) marginEnd = s(4) })
            }
        }, lp(WRAP, WRAP))
        tile.contentDescription = "Front Light ${current ?: ""}, tap for next"
    }

    // ---- Last Opened Note / Document ------------------------------------------------------

    private fun lastNoteRow(): View {
        val note = Recents.list(prefs).firstOrNull { it.kind == RecentItem.Kind.NOTE && !isCurrentNote(it) }
        return lastRow(R.drawable.ic_note, "Last Opened Note", note?.title, enabled = note != null, onHold = { service.openNotesFolder(note?.title) }) {
            note?.let { service.openBooxNote(it.title) }
        }
    }

    private fun lastDocumentRow(): View {
        val doc = Recents.list(prefs).firstOrNull { it.kind == RecentItem.Kind.DOCUMENT && !isCurrent(it) }
        val app = doc?.let { Launcher.appLabel(service, it.pkg) }
        val sub = doc?.let { if (it.title == app) app else "${it.title} · $app" }
        return lastRow(R.drawable.ic_book, "Last Opened Document", sub, enabled = doc != null, onHold = { service.openDocumentHome(doc?.pkg) }) {
            doc?.let { openRecentDocument(it) }
        }
    }

    /** Tap reopens the note or document; hold opens where it lives (works on a grey row too). */
    private fun lastRow(iconRes: Int, label: String, subtitle: String?, enabled: Boolean, onHold: () -> Unit, onClick: () -> Unit): View = with(ctx) {
        val color = if (enabled) Ink.INK else Ink.DISABLED
        hbox().apply {
            setPadding(s(16), s(12), s(16), s(12))
            addView(icon(getDrawable(iconRes), iconPx(30)).apply { imageTintList = ColorStateList.valueOf(color) })
            addView(vbox().apply {
                setPadding(s(14), 0, 0, 0)
                addView(text(label, sp(17f), color = color, medium = true))
                if (subtitle != null) addView(oneLine(text(subtitle, sp(13f), color = if (enabled) Ink.MUTED else Ink.DISABLED)))
            }, lp(0, WRAP, 1f))
            pressable()
            if (enabled) setOnClickListener { onClick() }
            setOnLongClickListener { onHold(); true }
        }
    }

    // ---- Quick Access ----------------------------------------------------------------------

    private fun quickAccessList(): View = with(ctx) {
        val box = vbox()
        box.addView(FrameLayout(ctx).apply {
            setPadding(if (prefs.clipShowButton) s(8) else s(16), s(12), s(8), s(12))
            addView(text("Quick Access", sp(17f), bold = true), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            // Recent Files moves here when the top bar has the Clipboard button.
            if (prefs.clipShowButton) addView(vbox().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(s(8), s(4), s(8), s(3))
                contentDescription = "Recent Files"
                minimumWidth = s(48)
                addView(icon(getDrawable(R.drawable.ic_clock), iconPx(24)))
                // Single line at its full width, so it's never cut off.
                addView(text("Recent", sp(11f)).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setHorizontallyScrolling(false)
                }, lp(WRAP, WRAP))
                pressable(outline(2, 8))
                setOnClickListener { show(Mode.RECENT) }
            }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL))
            addView(vbox().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(s(8), s(4), s(8), s(3))
                minimumWidth = s(48)
                contentDescription = "Add to Quick Access"
                addView(icon(getDrawable(R.drawable.ic_link_add), iconPx(24)))
                addView(text("Add", sp(11f)).apply { gravity = Gravity.CENTER; maxLines = 1 }, lp(WRAP, WRAP))
                pressable(outline(2, 8))
                setOnClickListener { addFromContext() }
            }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL))
        })
        box.addView(rule(1))

        val pins = prefs.pins
        if (pins.isEmpty()) {
            box.addView(hint("Nothing here yet. Open a note and tap Add, or tap Add in the Notes library to pick several."))
        }
        pins.forEachIndexed { i, pin ->
            if (i > 0) box.addView(insetRule())
            box.addView(itemRow(pin))
            if (pin.type == PinType.FOLDER && pin.id in expandedFolders) {
                val holder = vbox()
                box.addView(holder)
                loadFolder(holder, Uri.parse(pin.value))
            }
        }
        box
    }

    /**
     * What kind of item a pin is, at a glance: the same icon as everywhere else
     * in the panel. Apps use a uniform glyph unless Settings asks for their own icons.
     */
    private fun pinIcon(pin: Pin): View = with(ctx) {
        val size = iconPx(22)
        if (pin.type == PinType.APP && prefs.appOwnIcons) {
            val own = runCatching {
                val pm = packageManager
                ComponentName.unflattenFromString(pin.value)?.let { pm.getActivityIcon(it) }
                    ?: pm.getApplicationIcon(pin.value.substringBefore('/'))
            }.getOrNull()
            if (own != null) return@with icon(own, size, tint = false)
        }
        val res = when (pin.type) {
            PinType.BOOX_NOTE -> R.drawable.ic_note
            PinType.DOCUMENT -> R.drawable.ic_book
            PinType.FILE -> R.drawable.ic_file
            PinType.FOLDER -> R.drawable.ic_folder
            PinType.APP -> R.drawable.ic_apps
            PinType.INTENT -> R.drawable.ic_link
        }
        icon(getDrawable(res), size)
    }

    private fun itemRow(pin: Pin): View {
        val label = if (pin.type == PinType.FOLDER && pin.id in expandedFolders) "${pin.label}  ▾" else pin.label
        return ctx.hbox().apply {
            setPadding(s(18), s(13), s(20), s(13))
            addView(pinIcon(pin))
            addView(oneLine(ctx.text(label, sp(17f))).apply { setPadding(s(12), 0, 0, 0) }, lp(0, WRAP, 1f))
            pressable()
            setOnClickListener { activate(pin) }
            setOnLongClickListener {
                show(Mode.EDIT)
                true
            }
        }
    }

    private fun activate(pin: Pin) {
        DebugLog.i("pin", "${pin.type} \"${pin.label}\" -> ${pin.value}")
        when (pin.type) {
            PinType.BOOX_NOTE -> service.openBooxNote(pin)
            PinType.FOLDER -> {
                if (!expandedFolders.add(pin.id)) expandedFolders.remove(pin.id)
                show(Mode.MAIN)
            }
            PinType.FILE -> launchAndClose { Launcher.openFile(service, Uri.parse(pin.value)) }
            PinType.DOCUMENT -> launchAndClose { ReaderLibrary.open(service, pin.value) }
            PinType.APP -> launchAndClose { Launcher.launchApp(service, pin.value) }
            PinType.INTENT -> launchAndClose { Launcher.openIntentUri(service, pin.value) }
        }
    }

    private inline fun launchAndClose(block: () -> Boolean) {
        if (block()) service.hidePanel()
    }

    /**
     * "Add": inside a note, pin that note; in the Notes library, open the
     * multi-select picker; anywhere else, pin the current app.
     */
    private fun addFromContext() {
        when (val state = notesState) {
            is NotesScreen.State.Editor -> {
                if (isNotePinned(state.title)) return toast("\"${state.title}\" is already in Quick Access")
                prefs.addPin(Pin(PinType.BOOX_NOTE, state.title, state.title))
                DebugLog.i("pin", "added open note \"${state.title}\"")
                toast("Added \"${state.title}\"")
                show(Mode.MAIN)
                return
            }
            is NotesScreen.State.Library -> {
                if (state.cards.isEmpty()) return toast("No notes found on this screen")
                pickChoices = state.cards
                pickDocs = emptyMap()
                picked.clear()
                show(Mode.PICK)
                return
            }
            else -> Unit
        }
        val pkg = foregroundPackage
        if (pkg == ReaderLibrary.NEOREADER) return addOpenDocument()
        if (pkg == BooxNotes.BOOX_HOME_PACKAGE) return addFromBooxLibrary()
        if (pkg == prefs.notesPackage) {
            // An open note whose name couldn't be read (the screen layout is in the log).
            return toast("Couldn't read this note's name. Please send a debug report (Settings → Troubleshooting)")
        }
        if (pkg == null || pkg == service.packageName || service.packageManager.getLaunchIntentForPackage(pkg) == null) {
            return toast("Open a note, or the Notes library, then tap Add")
        }
        addApp(pkg)
    }

    /**
     * On BOOX's home screen: if its Library (books) is showing, pick books to pin, matched to
     * NeoReader's library so they open directly. Anywhere else on the home screen, pin the app.
     */
    private fun addFromBooxLibrary() {
        val cards = (service.notesState(log = false) as? NotesScreen.State.Library)?.cards.orEmpty()
        if (cards.isEmpty()) return addApp(BooxNotes.BOOX_HOME_PACKAGE)
        io.execute {
            val docs = ReaderLibrary.all(service)
            val matched = cards.mapNotNull { c -> ReaderLibrary.match(docs, c.label)?.let { c to it } }
            main.post {
                if (!root.isAttachedToWindow) return@post
                DebugLog.i("pin", "BOOX Library: ${cards.size} cards, ${matched.size} matched to NeoReader's library")
                if (matched.isEmpty()) return@post addApp(BooxNotes.BOOX_HOME_PACKAGE)
                pickChoices = matched.map { it.first }
                pickDocs = matched.associate { it.first.label to it.second }
                picked.clear()
                show(Mode.PICK)
            }
        }
    }

    private fun addApp(pkg: String) {
        val label = Launcher.appLabel(service, pkg)
        if (prefs.pins.any { it.type == PinType.APP && it.value.substringBefore('/') == pkg }) {
            return toast("$label is already in Quick Access")
        }
        prefs.addPin(Pin(PinType.APP, label, pkg))
        toast("Added $label")
        show(Mode.MAIN)
    }

    /** In NeoReader: pin the document that's open (the newest in its library). */
    private fun addOpenDocument() {
        io.execute {
            val doc = ReaderLibrary.lastOpened(service)
            main.post {
                if (doc == null) return@post toast("Couldn't tell which document is open")
                if (prefs.pins.any { it.type == PinType.DOCUMENT && it.value == doc.path }) {
                    return@post toast("\"${doc.title}\" is already in Quick Access")
                }
                prefs.addPin(Pin(PinType.DOCUMENT, doc.title, doc.path))
                DebugLog.i("pin", "added open document \"${doc.title}\"")
                toast("Added \"${doc.title}\"")
                if (root.isAttachedToWindow) show(Mode.MAIN)
            }
        }
    }

    /** A remembered document: straight into it when its file is known, else back to its app. */
    private fun openRecentDocument(item: RecentItem) {
        val ok = if (item.path.isNotEmpty()) ReaderLibrary.open(service, item.path) else Launcher.launchApp(service, item.pkg)
        if (ok) service.hidePanel()
    }

    private fun isNotePinned(title: String) =
        prefs.pins.any { it.type == PinType.BOOX_NOTE && NotesScreen.matches(title, BooxNotes.splitPath(it.value).lastOrNull() ?: it.value) }

    // ---- Clipboard hub ---------------------------------------------------------------------

    private enum class ClipFilter(val label: String) { ALL("All"), TEXT("Text"), LINKS("Links"), IMAGES("Images"), HANDWRITING("Handwriting"), PINNED("Pinned") }


    private fun toggleClipboard() {
        if (mode == Mode.CLIPBOARD) return show(Mode.MAIN)
        ClipHistory.checkInForeground(service, prefs, foregroundPackage)
        clipPage = 0
        clipOpen = null
        show(Mode.CLIPBOARD)
    }

    private fun clipboardHub(): View = with(ctx) {
        vbox().apply {
            addView(hbox().apply {
                setPadding(s(14), s(10), s(6), s(4))
                addView(icon(getDrawable(R.drawable.ic_clipboard), iconPx(24)))
                addView(text("Clipboard", sp(18f), bold = true).apply { setPadding(s(8), 0, 0, 0) })
                addView(oneLine(text("", sp(13f), color = Ink.MUTED)).apply { setPadding(s(6), 0, s(6), 0); clipCount = this }, lp(0, WRAP, 1f))
                // Clears the history (pinned clips stay), after a confirm that takes the top of the list.
                addView(text("Clear", sp(13.5f), bold = true).apply {
                    contentDescription = "Clear clipboard history"
                    gravity = Gravity.CENTER
                    setPadding(s(10), s(4), s(10), s(4))
                    pressable(outline(2, 16))
                    setOnClickListener { clipConfirmClear = !clipConfirmClear; clipOpen = null; fillClips() }
                    clipClear = this
                })
                addView(FrameLayout(ctx).apply {
                    contentDescription = "Close the clipboard"
                    addView(icon(getDrawable(R.drawable.ic_close), iconPx(18)), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                    pressable(outline(2, 8))
                    setOnClickListener { show(Mode.MAIN) }
                }, lp(s(30), s(30)).apply { marginStart = s(6); marginEnd = s(6) })
            }, lp(MATCH, WRAP))
            // The search field is kept across redraws so typing (and the keyboard) isn't interrupted.
            val search = clipSearch ?: android.widget.EditText(ctx).apply {
                hint = "Search clipboard"
                textSize = sp(15f)
                setTextColor(Ink.INK)
                setHintTextColor(Ink.MUTED)
                isSingleLine = true
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                background = outline(2, 8)
                setPadding(s(10), s(7), s(10), s(7))
                setCompoundDrawablesRelativeWithIntrinsicBounds(getDrawable(R.drawable.ic_search)?.mutate()?.apply {
                    setTint(Ink.MUTED)
                    setBounds(0, 0, s(20), s(20))
                }, null, null, null)
                compoundDrawablePadding = s(8)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(e: android.text.Editable?) {
                        clipQuery = e?.toString().orEmpty()
                        clipPage = 0
                        clipOpen = null
                        fillClips()
                    }
                })
                setOnEditorActionListener { v, _, _ ->
                    (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .hideSoftInputFromWindow(v.windowToken, 0)
                    true
                }
                clipSearch = this
            }
            (search.parent as? ViewGroup)?.removeView(search)
            addView(search, lp(MATCH, WRAP).apply { setMargins(s(12), s(4), s(12), s(8)) })
            addView(vbox().also { clipBody = it }, lp(MATCH, 0, 1f))
            fillClips()
        }
    }

    private fun clipMatches(c: ClipHistory.Clip): Boolean {
        val kindOk = when (clipFilter) {
            ClipFilter.ALL -> true
            ClipFilter.TEXT -> c.kind == ClipHistory.Kind.TEXT
            ClipFilter.LINKS -> c.kind == ClipHistory.Kind.LINK
            ClipFilter.IMAGES -> c.kind == ClipHistory.Kind.IMAGE
            ClipFilter.HANDWRITING -> c.kind == ClipHistory.Kind.HANDWRITING
            ClipFilter.PINNED -> c.pinned
        }
        val q = clipQuery.trim()
        return kindOk && (q.isEmpty() || c.text.contains(q, ignoreCase = true) ||
            (c.source != null && Launcher.appLabel(service, c.source).contains(q, ignoreCase = true)))
    }

    /** Filter chips, the clips on this page (pinned first) and the pager. */
    private fun fillClips() {
        val body = clipBody ?: return
        body.removeAllViews()
        // Handwriting kept before it could be drawn gets its picture: drawn in the background (finding
        // the strokes can take a while), then shown on the next redraw.
        val all = ClipHistory.list(service).map { ClipHistory.ensurePreview(service, it) }
        val undrawn = all.filter { ClipHistory.needsDrawing(service, it) }
        if (undrawn.isNotEmpty() && !drawing) {
            drawing = true
            io.execute {
                undrawn.forEach { c -> runCatching { ClipHistory.draw(service, c) }.onFailure { DebugLog.w("clipboard", "drawing handwriting failed: $it") } }
                root.post {
                    drawing = false
                    if (mode == Mode.CLIPBOARD && root.isAttachedToWindow) fillClips()
                }
            }
        }
        clipCount?.text = "· ${all.size} ${if (all.size == 1) "item" else "items"}"
        // Nothing to clear when every clip is pinned (or there are none).
        val unpinned = all.count { !it.pinned }
        if (unpinned == 0) clipConfirmClear = false
        clipClear?.visibility = if (unpinned == 0) View.GONE else View.VISIBLE
        if (clipConfirmClear) body.addView(confirmBox(
            "Clear clipboard history?",
            if (unpinned < all.size) "Removes $unpinned ${if (unpinned == 1) "clip" else "clips"}. Pinned clips are kept."
            else "Removes ${if (unpinned == 1) "the 1 clip" else "all $unpinned clips"} kept here.",
            "Clear",
            onCancel = { clipConfirmClear = false; fillClips() },
        ) {
            ClipHistory.clear(service)
            clipConfirmClear = false
            clipPage = 0
            clipOpen = null
            toast("Clipboard history cleared")
            fillClips()
        }, lp(MATCH, WRAP).apply { setMargins(s(10), 0, s(10), s(8)) })
        with(ctx) {
            // Filter chips; the row scrolls sideways if they don't all fit (narrow panel, large menu size).
            body.addView(android.widget.HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(hbox().apply {
                setPadding(s(12), 0, s(8), s(6))
                ClipFilter.entries.forEach { f ->
                    val on = f == clipFilter
                    addView(text(f.label, sp(13.5f), bold = true, color = if (on) Ink.PAPER else Ink.INK).apply {
                        gravity = Gravity.CENTER
                        setPadding(s(10), s(4), s(10), s(4))
                        pressable(outline(2, 16, if (on) Ink.INK else Ink.PAPER))
                        setOnClickListener {
                            clipFilter = f
                            clipPage = 0
                            clipOpen = null
                            fillClips()
                        }
                    }, lp(WRAP, WRAP).apply { marginEnd = s(6) })
                }
                })
            }, lp(MATCH, WRAP))
        }
        val shown = all.filter(::clipMatches).sortedByDescending { it.pinned }
        // Fewer clips per page for each part of the panel that stays showing.
        val per = (CLIPS_PER_PAGE_FULL - (if (prefs.showSystemActions && prefs.clipShowControls) 2 else 0) -
            (if (prefs.clipShowLast) 1 else 0) - (if (prefs.clipShowApps) 3 else 0)).coerceAtLeast(CLIPS_PER_PAGE_MIN)
        val pages = maxOf(1, (shown.size + per - 1) / per)
        clipPage = clipPage.coerceIn(0, pages - 1)
        val list = ctx.vbox()
        if (shown.isEmpty()) {
            list.addView(hint(when {
                all.isEmpty() -> if (prefs.clipCatchMode == Prefs.CLIP_EVERY && AdvancedAccess.allowed())
                    "Nothing copied yet. Copies show up here as you make them."
                else "Nothing copied yet. Copy something, then open the panel: what's on the clipboard is saved each time it opens."
                clipQuery.isNotBlank() -> "No clips match “${clipQuery.trim()}”."
                else -> "No ${clipFilter.label.lowercase()} clips."
            }))
        }
        val current = ClipHistory.currentId(service)
        var lastPinned: Boolean? = null
        shown.drop(clipPage * per).take(per).forEach { c ->
            if (clipFilter == ClipFilter.ALL && c.pinned != lastPinned && shown.any { it.pinned } ) {
                list.addView(ctx.text(if (c.pinned) "PINNED" else "RECENT", sp(12f), bold = true).apply {
                    letterSpacing = 0.06f
                    setPadding(s(16), s(6), s(16), s(2))
                })
            }
            lastPinned = c.pinned
            // One clip that can't be shown is left out rather than taking the panel (and the service) down.
            val row = runCatching { clipRow(c, c.id == current) }
                .onFailure { DebugLog.e("clipboard", "clip ${c.id} (${c.kind}) couldn't be shown", it) }.getOrNull() ?: return@forEach
            list.addView(row, lp(MATCH, WRAP).apply { setMargins(s(10), s(4), s(10), s(4)) })
        }
        // Pages, like the rest of the panel; scrolls only if an opened clip makes the page too tall.
        val swipe = SwipeRow(ctx, s(40), vertical = true) { dir ->
            val to = clipPage + dir
            if (to in 0 until pages) { clipPage = to; clipOpen = null; fillClips() }
        }
        swipe.addView(list, lp(MATCH, WRAP))
        body.addView(scrolling(swipe), lp(MATCH, 0, 1f))
        if (pages > 1) {
            body.addView(ctx.hbox().apply {
                gravity = Gravity.CENTER
                setPadding(0, s(2), 0, s(6))
                addView(pagerButton(R.drawable.ic_chevron_left, "Newer clips", clipPage > 0) { clipPage--; clipOpen = null; fillClips() })
                addView(ctx.text("${clipPage + 1} / $pages", sp(13f), bold = true).apply { setPadding(s(12), 0, s(12), 0) })
                addView(pagerButton(R.drawable.ic_chevron_right, "Older clips", clipPage < pages - 1) { clipPage++; clipOpen = null; fillClips() })
            }, lp(MATCH, WRAP))
        }
    }

    private fun clipRow(c: ClipHistory.Clip, current: Boolean): View = with(ctx) {
        val open = clipOpen == c.id
        hbox().apply {
            gravity = Gravity.TOP
            background = outline(if (current) 3 else 2, 8)
            setPadding(s(10), s(9), s(10), s(9))
            val kindIcon = when (c.kind) {
                ClipHistory.Kind.TEXT -> R.drawable.ic_text
                ClipHistory.Kind.LINK -> R.drawable.ic_link
                ClipHistory.Kind.IMAGE -> R.drawable.ic_image
                ClipHistory.Kind.HANDWRITING -> R.drawable.ic_edit
            }
            addView(FrameLayout(ctx).apply {
                background = outline(1, 6)
                addView(icon(getDrawable(kindIcon), iconPx(18)), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            }, lp(s(30), s(30)))
            addView(vbox().apply {
                setPadding(s(10), 0, s(6), 0)
                if (c.kind == ClipHistory.Kind.IMAGE) {
                    addView(clipThumb(c, if (open) 160 else 64))
                } else if (c.kind == ClipHistory.Kind.HANDWRITING) {
                    // A picture of the strokes when BOOX's copy had one; never the raw address.
                    if (c.image?.endsWith(".png") == true) addView(clipThumb(c, if (open) 160 else 64))
                    addView(text("Handwriting from BOOX Notes", sp(if (c.image?.endsWith(".png") == true) 12.5f else 15f), medium = true))
                    if (open) addView(text("Pastes back into BOOX Notes while BOOX still has this copy (usually until the tablet restarts).", sp(12.5f), color = Ink.MUTED))
                } else {
                    addView(text(c.text.trim(), sp(15f)).apply {
                        maxLines = if (open) 8 else 2
                        ellipsize = TextUtils.TruncateAt.END
                    })
                }
                addView(hbox().apply {
                    setPadding(0, s(3), 0, 0)
                    if (current) addView(text("ON CLIPBOARD", sp(10.5f), bold = true).apply {
                        background = outline(1, 4)
                        setPadding(s(4), 0, s(4), 0)
                    }, lp(WRAP, WRAP).apply { marginEnd = s(6) })
                    if (c.pinned) addView(icon(getDrawable(R.drawable.ic_pin), iconPx(13)), lp(s(14), s(14)).apply { marginEnd = s(3) })
                    val from = c.source?.let { Launcher.appLabel(service, it) }
                    addView(oneLine(text(listOfNotNull(if (c.pinned) "Pinned" else null, from, ago(c.time)).joinToString(" · "), sp(12f), color = Ink.MUTED)))
                })
                if (open) addView(clipActions(c), lp(MATCH, WRAP))
                pressable()
                // Tap or hold: show or hide this clip's actions.
                val toggle = { clipOpen = if (open) null else c.id; fillClips() }
                setOnClickListener { toggle() }
                setOnLongClickListener { toggle(); true }
            }, lp(0, WRAP, 1f))
            if (!open) addView(FrameLayout(ctx).apply {
                contentDescription = "Copy"
                addView(icon(getDrawable(R.drawable.ic_copy), iconPx(20)), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                pressable()
                background = ovalButton()
                setOnClickListener { copyClip(c) }
            }, lp(s(38), s(38)))
        }
    }

    private fun clipThumb(c: ClipHistory.Clip, heightDp: Int): View = with(ctx) {
        val bmp = ClipHistory.imageFile(service, c)?.takeIf { it.exists() }?.let { thumbnail(it, sheetWidth(ctx, prefs, service.screenWidth()), s(heightDp)) }
        if (bmp == null) return@with text("Image no longer available", sp(14f), color = Ink.MUTED)
        ImageView(ctx).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
            maxHeight = s(heightDp)
            background = outline(1, 4)
            setPadding(s(2), s(2), s(2), s(2))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        }
    }

    /**
     * A clip's picture, scaled down on both sides to what the row can show, and kept between redraws:
     * every change in Settings rebuilds the preview panel, and full-size pictures each time ran out
     * of memory or were too large to draw, which stopped the service.
     */
    private fun thumbnail(file: java.io.File, maxW: Int, maxH: Int): android.graphics.Bitmap? {
        val key = "${file.path}|${file.lastModified()}|$maxW|$maxH"
        synchronized(thumbs) { thumbs.get(key) }?.let { return it }
        return runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > maxW * 2 || bounds.outHeight / sample > maxH * 2) sample *= 2
            android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }.onFailure { DebugLog.w("clipboard", "picture not shown: ${it.javaClass.simpleName} ${it.message}") }
            .getOrNull()?.also { synchronized(thumbs) { thumbs.put(key, it) } }
    }

    /** Copy · Paste · Share… / Pin · Delete (· Open, for links), in place under the clip. */
    private fun clipActions(c: ClipHistory.Clip): View = with(ctx) {
        val actions = listOfNotNull(
            Triple(R.drawable.ic_copy, "Copy") { copyClip(c) },
            Triple(R.drawable.ic_paste, "Paste") { pasteClip(c) },
            if (c.kind == ClipHistory.Kind.LINK) Triple(R.drawable.ic_open, "Open") { openLink(c) } else null,
            if (c.kind == ClipHistory.Kind.HANDWRITING) null else Triple(R.drawable.ic_share, "Share…") { shareClip(c) },
            Triple(R.drawable.ic_pin, if (c.pinned) "Unpin" else "Pin") {
                ClipHistory.setPinned(service, c.id, !c.pinned)
                fillClips()
            },
            Triple(R.drawable.ic_delete, "Delete") {
                ClipHistory.delete(service, c.id)
                clipOpen = null
                fillClips()
            },
        )
        vbox().apply {
            setPadding(0, s(8), 0, 0)
            // Rows of buttons that fill the width: 4 as 2 + 2, otherwise 3 to a row.
            actions.chunked(if (actions.size == 4) 2 else 3).forEach { row ->
                addView(hbox().apply {
                    row.forEachIndexed { i, (res, label, action) ->
                        val dark = i == 0 && label == "Copy"
                        addView(hbox().apply {
                            gravity = Gravity.CENTER
                            minimumHeight = s(38)
                            setPadding(s(4), s(4), s(4), s(4))
                            addView(icon(getDrawable(res), iconPx(17)).apply { if (dark) imageTintList = ColorStateList.valueOf(Ink.PAPER) })
                            addView(oneLine(text(label, sp(13.5f), bold = true, color = if (dark) Ink.PAPER else Ink.INK)).apply { setPadding(s(5), 0, 0, 0) })
                            contentDescription = label
                            pressable(outline(2, 6, if (dark) Ink.INK else Ink.PAPER))
                            setOnClickListener { action() }
                        }, lp(0, WRAP, 1f).apply { if (i < row.size - 1) marginEnd = s(6) })
                    }
                }, lp(MATCH, WRAP).apply { bottomMargin = s(6) })
            }
        }
    }

    private fun copyClip(c: ClipHistory.Clip) {
        if (ClipHistory.putBack(service, c)) {
            toast(when (c.kind) { ClipHistory.Kind.IMAGE -> "Image copied"; ClipHistory.Kind.HANDWRITING -> "Handwriting copied"; else -> "Copied" })
            clipOpen = null
            fillClips()
        } else {
            toast(if (c.kind == ClipHistory.Kind.HANDWRITING) "BOOX no longer has this copy" else "Couldn't copy that")
        }
    }

    private fun pasteClip(c: ClipHistory.Clip) {
        if (!ClipHistory.putBack(service, c)) return toast(if (c.kind == ClipHistory.Kind.HANDWRITING) "BOOX no longer has this copy" else "Couldn't copy that")
        service.hidePanel()
        service.pasteIntoApp()
    }

    private fun openLink(c: ClipHistory.Clip) {
        val t = c.text.trim()
        val uri = Uri.parse(if (t.startsWith("www.")) "https://$t" else t)
        if (Launcher.tryStart(service, Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) service.hidePanel()
        else toast("No app can open this link")
    }

    private fun shareClip(c: ClipHistory.Clip) {
        val send = Intent(Intent.ACTION_SEND)
        if (c.kind == ClipHistory.Kind.IMAGE) {
            val uri = ClipHistory.contentUri(c) ?: return toast("Image no longer available")
            send.type = "image/png"
            send.putExtra(Intent.EXTRA_STREAM, uri)
            send.clipData = android.content.ClipData.newRawUri("Image", uri)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            send.type = "text/plain"
            send.putExtra(Intent.EXTRA_TEXT, c.text)
        }
        val chooser = Intent.createChooser(send, "Share clip").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Launcher.tryStart(service, chooser)) service.hidePanel()
    }

    // ---- Recent Files ----------------------------------------------------------------------

    /** [forget]: takes the row off the list for good (until it's opened, or the file changes, again). */
    private class RecentRow(val iconRes: Int, val title: String, val subtitle: String, val time: Long, val forget: () -> Unit, val open: () -> Unit)

    private fun recentList(): View = with(ctx) {
        val box = vbox()
        box.addView(FrameLayout(ctx).apply {
            setPadding(s(8), s(12), s(16), s(12))
            addView(hbox().apply {
                addView(icon(getDrawable(R.drawable.ic_chevron_left), iconPx(26)))
                addView(text("Back", sp(14f), bold = true))
                setPadding(s(2), s(2), s(12), s(2))
                pressable(outline(2, 16))
                setOnClickListener { show(Mode.MAIN) }
            }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL))
            addView(text("Recent Files", sp(17f), bold = true), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        })
        box.addView(rule(1))
        val list = vbox()
        box.addView(list)

        var remembered = Recents.list(prefs).filterNot(::isCurrent).map { item ->
            val ago = ago(item.time)
            val forget = { Recents.remove(prefs, item.key) }
            when (item.kind) {
                RecentItem.Kind.NOTE -> RecentRow(R.drawable.ic_note, item.title, "Note · $ago", item.time, forget) { service.openBooxNote(item.title) }
                RecentItem.Kind.DOCUMENT -> {
                    val app = Launcher.appLabel(service, item.pkg)
                    RecentRow(R.drawable.ic_book, item.title, if (item.title == app) "Document · $ago" else "$app · $ago", item.time, forget) {
                        openRecentDocument(item)
                    }
                }
            }
        }
        var fileRows = emptyList<RecentRow>()
        var confirmClear = false

        // Redrawn in place (not through show()), so the list stays where it was scrolled to.
        fun render() {
            val rows = if (fileRows.isEmpty()) remembered else (remembered + fileRows).sortedByDescending { it.time }
            fillRecent(list, rows) { row ->
                // The rows above the list ("Last Opened Note / Document") show the newest of these too.
                val last = Recents.lastNote(prefs)?.key to Recents.lastDocument(prefs)?.key
                row.forget()
                remembered = remembered - row
                fileRows = fileRows - row
                if (last != Recents.lastNote(prefs)?.key to Recents.lastDocument(prefs)?.key) show(mode) else render()
            }
            if (rows.isEmpty()) return
            if (!confirmClear) {
                list.addView(inkButton("Clear all", sp(14f)) { confirmClear = true; render() }.apply { minHeight = s(40) },
                    lp(MATCH, WRAP).apply { setMargins(s(16), s(12), s(16), s(12)) })
                return
            }
            val confirm = confirmBox(
                "Clear all recent files?",
                "This only empties the list. No note or file is deleted.",
                "Clear all",
                onCancel = { confirmClear = false; render() },
            ) {
                Recents.clear(prefs)
                fileRows.forEach { it.forget() }
                show(mode)
            }
            list.addView(confirm, lp(MATCH, WRAP).apply { setMargins(s(12), s(12), s(12), s(12)) })
            // Taller than the button it replaces: bring all of it into view.
            confirm.post { confirm.requestRectangleOnScreen(android.graphics.Rect(0, 0, confirm.width, confirm.height + s(12)), true) }
        }
        render()

        // Merge in the newest files from the folder chosen in Settings.
        prefs.recentFolderUri?.let { uri ->
            io.execute {
                val files = runCatching { Folders.newestFiles(service, Uri.parse(uri), prefs.recentCount.coerceIn(1, 20)) }.getOrNull().orEmpty()
                main.post {
                    if (!root.isAttachedToWindow || mode != Mode.RECENT || !list.isAttachedToWindow) return@post
                    val hidden = Recents.hiddenFiles(prefs)
                    fileRows = files.filterNot { Recents.fileKey(it.uri.toString(), it.modified) in hidden }.map { f ->
                        RecentRow(R.drawable.ic_file, f.name, "Document · ${ago(f.modified)}", f.modified,
                            { Recents.hideFiles(prefs, listOf(Recents.fileKey(f.uri.toString(), f.modified))) }) {
                            launchAndClose { Launcher.openFile(service, f.uri) }
                        }
                    }
                    if (fileRows.isNotEmpty()) render()
                }
            }
        }
        box
    }

    private fun fillRecent(list: LinearLayout, rows: List<RecentRow>, onRemove: (RecentRow) -> Unit) {
        list.removeAllViews()
        if (rows.isEmpty()) {
            list.addView(hint("Notes and documents you open will appear here."))
            return
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) list.addView(insetRule())
            list.addView(ctx.hbox().apply {
                setPadding(s(16), s(5), s(6), s(5))
                addView(ctx.icon(ctx.getDrawable(r.iconRes), iconPx(24)))
                addView(ctx.vbox().apply {
                    setPadding(s(12), s(6), s(4), s(6))
                    addView(oneLine(ctx.text(r.title, sp(17f))))
                    addView(oneLine(ctx.text(r.subtitle, sp(13f), color = Ink.MUTED)))
                }, lp(0, WRAP, 1f))
                addView(FrameLayout(ctx).apply {
                    contentDescription = "Remove ${r.title} from Recent Files"
                    addView(ctx.icon(ctx.getDrawable(R.drawable.ic_close), iconPx(16)), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                    pressable(ctx.outline(2, 8))
                    setOnClickListener { onRemove(r) }
                }, lp(s(30), s(30)).apply { marginStart = s(6); marginEnd = s(10) })
                pressable()
                setOnClickListener { r.open() }
            })
        }
    }

    /** A question with Cancel and a filled button to go ahead, drawn in place: the panel is an overlay, not an activity with dialogs. */
    private fun confirmBox(title: String, detail: String, action: String, onCancel: () -> Unit, onConfirm: () -> Unit): View = with(ctx) {
        vbox().apply {
            background = outline(2, 8)
            setPadding(s(10), s(8), s(10), s(8))
            addView(text(title, sp(14.5f), bold = true))
            addView(text(detail, sp(13f), color = Ink.MUTED).apply { setPadding(0, s(2), 0, s(8)) })
            addView(hbox().apply {
                addView(inkButton("Cancel", sp(14f)) { onCancel() }, lp(0, WRAP, 1f).apply { marginEnd = s(8) })
                addView(inkButton(action, sp(14f)) { onConfirm() }.apply {
                    setTextColor(Ink.PAPER)
                    background = android.graphics.drawable.GradientDrawable().apply { setColor(Ink.INK); cornerRadius = dp(6).toFloat() }
                }, lp(0, WRAP, 1f))
            }, lp(MATCH, WRAP))
        }
    }

    private fun ago(time: Long): String =
        DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    private fun loadFolder(holder: LinearLayout, tree: Uri) {
        holder.addView(hint("Loading…", 36))
        io.execute {
            val files = runCatching { Folders.newestFiles(service, tree, 15) }.getOrNull()
            main.post {
                if (!root.isAttachedToWindow) return@post
                holder.removeAllViews()
                when {
                    files == null -> holder.addView(hint("Folder not accessible. Re-add it in Settings.", 36))
                    files.isEmpty() -> holder.addView(hint("Empty", 36))
                }
                files?.forEach { f ->
                    holder.addView(oneLine(ctx.text(f.name, sp(15f))).apply {
                        setPadding(s(36), s(9), s(16), s(9))
                        pressable()
                        setOnClickListener { launchAndClose { Launcher.openFile(service, f.uri) } }
                    })
                }
            }
        }
    }

    // ---- Edit mode (long-press) ----------------------------------------------------------

    private fun editList(): View = with(ctx) {
        val box = vbox()
        box.addView(hbox().apply {
            setPadding(s(16), s(10), s(10), s(10))
            addView(text("Edit Quick Access", sp(17f), bold = true), lp(0, WRAP, 1f))
            addView(inkButton("Done", sp(15f)) { show(Mode.MAIN) })
        })
        box.addView(rule(1))
        val pins = prefs.pins
        if (pins.isEmpty()) box.addView(hint("Nothing pinned."))
        pins.forEachIndexed { i, pin ->
            if (i > 0) box.addView(insetRule())
            box.addView(hbox().apply {
                setPadding(s(6), s(7), s(8), s(7))
                setBackgroundColor(Ink.PAPER)
                addView(dragHandle(this, i, pins.size) { from, to -> movePin(from, to) })
                addView(pinIcon(pin))
                addView(oneLine(text(pin.label, sp(17f))).apply { setPadding(s(12), 0, s(6), 0) }, lp(0, WRAP, 1f))
                val b = iconPx(44)
                addView(inkIconButton(R.drawable.ic_arrow_up, "Move up", b, enabled = i > 0) { movePin(i, i - 1) })
                addView(inkIconButton(R.drawable.ic_arrow_down, "Move down", b, enabled = i < pins.size - 1) { movePin(i, i + 1) })
                addView(inkIconButton(R.drawable.ic_delete, "Remove", b) {
                    prefs.removePin(pin.id)
                    show(Mode.EDIT)
                })
            })
        }
        box
    }

    /**
     * ≡ for a row in the edit view: press and drag it up or down; the row follows
     * on a bordered card and moves when let go. Only redraws on drop (e-ink).
     */
    private fun dragHandle(row: View, index: Int, count: Int, onMove: (Int, Int) -> Unit): View = with(ctx) {
        icon(getDrawable(R.drawable.ic_drag), iconPx(26)).apply {
            contentDescription = "Drag to move"
            // The padding is around the glyph, not taken out of it.
            layoutParams = LinearLayout.LayoutParams(s(26 + 12), s(26 + 16))
            setPadding(s(4), s(8), s(8), s(8))
            var startY = 0f
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startY = e.rawY
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        row.background = outline(2, 8)
                    }
                    android.view.MotionEvent.ACTION_MOVE -> row.translationY = e.rawY - startY
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        val step = (row.height + dp(1)).coerceAtLeast(1)
                        val to = (index + Math.round(row.translationY / step)).coerceIn(0, count - 1)
                        row.translationY = 0f
                        val moved = e.actionMasked == android.view.MotionEvent.ACTION_UP && to != index
                        // Rebuild after this touch is done: removing the row while it's still being
                        // drawn and touched crashed the panel (build 56 report).
                        root.post { if (moved) onMove(index, to) else show(Mode.EDIT) }
                    }
                }
                true
            }
        }
    }

    private fun movePin(from: Int, to: Int) {
        val pins = prefs.pins.toMutableList()
        if (from !in pins.indices || to !in pins.indices) return
        pins.add(to, pins.removeAt(from))
        prefs.pins = pins
        show(Mode.EDIT)
    }

    // ---- Add picker (Notes library, multi-select) ----------------------------------------

    private fun pickerBar(): View = with(ctx) {
        hbox().apply {
            setBackgroundColor(Ink.INK)
            setPadding(s(16), s(12), s(16), s(12))
            addView(text("Cancel", sp(16f), medium = true, color = Ink.PAPER).apply {
                isClickable = true
                setOnClickListener { show(Mode.MAIN) }
            })
            addView(text("Add to Quick Access", sp(16f), bold = true, color = Ink.PAPER).apply { gravity = Gravity.CENTER }, lp(0, WRAP, 1f))
            val n = picked.size
            val c = if (n > 0) Ink.PAPER else Ink.DISABLED
            addView(hbox().apply {
                addView(icon(getDrawable(R.drawable.ic_link_add), iconPx(22)).apply { imageTintList = ColorStateList.valueOf(c) })
                addView(text(" Add ($n)", sp(16f), medium = true, color = c))
                if (n > 0) {
                    isClickable = true
                    setOnClickListener { addPicked() }
                }
            })
        }
    }

    private fun pickerList(): View = with(ctx) {
        val box = vbox()
        pickChoices.forEachIndexed { i, card ->
            if (i > 0) box.addView(insetRule())
            val on = card.label in picked
            box.addView(hbox().apply {
                setPadding(s(16), s(11), s(16), s(11))
                addView(checkbox(on))
                val iconRes = when {
                    card.label in pickDocs -> R.drawable.ic_book
                    card.isFolder -> R.drawable.ic_folder
                    else -> R.drawable.ic_note
                }
                addView(icon(getDrawable(iconRes), iconPx(24)).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginStart = s(12)
                })
                addView(vbox().apply {
                    setPadding(s(12), 0, 0, 0)
                    addView(oneLine(text(card.label, sp(17f), bold = on)))
                    card.detail?.let { addView(oneLine(text(it, sp(13f), color = Ink.MUTED))) }
                }, lp(0, WRAP, 1f))
                pressable()
                setOnClickListener {
                    if (!picked.add(card.label)) picked.remove(card.label)
                    show(Mode.PICK)
                }
            })
        }
        box
    }

    private fun checkbox(on: Boolean): View = with(ctx) {
        FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(if (on) Ink.INK else Ink.PAPER)
                setStroke(dp(2), Ink.INK)
                cornerRadius = dp(3).toFloat()
            }
            if (on) addView(ImageView(ctx).apply {
                setImageDrawable(getDrawable(R.drawable.ic_check))
                imageTintList = ColorStateList.valueOf(Ink.PAPER)
            }, FrameLayout.LayoutParams(MATCH, MATCH))
            layoutParams = LinearLayout.LayoutParams(s(24), s(24))
        }
    }

    private fun addPicked() {
        var added = 0
        picked.forEach { label ->
            val doc = pickDocs[label]
            if (doc != null) {
                if (prefs.pins.none { it.type == PinType.DOCUMENT && it.value == doc.path }) {
                    prefs.addPin(Pin(PinType.DOCUMENT, doc.title, doc.path))
                    added++
                }
            } else if (!isNotePinned(label)) {
                prefs.addPin(Pin(PinType.BOOX_NOTE, label, label))
                added++
            }
        }
        DebugLog.i("pin", "added $added from the library: $picked")
        toast(if (added == 1) "Added 1 item" else "Added $added items")
        picked.clear()
        show(Mode.MAIN)
    }

    // ---- This app: BOOX's Optimize / EinkWise for the app in front -----------------------

    private fun thisAppStrip(): View? = with(ctx) {
        if (!prefs.showThisApp) return null
        val pkg = EinkMenus.target(service, foregroundPackage) ?: return null
        hbox().apply {
            setPadding(s(12), s(8), s(10), s(8))
            addView(icon(Launcher.appIcon(service, pkg), iconPx(32), tint = false))
            addView(vbox().apply {
                setPadding(s(10), 0, s(6), 0)
                addView(oneLine(text(Launcher.appLabel(service, pkg), sp(15f), medium = true)))
                addView(oneLine(text("Open now", sp(12f), color = Ink.MUTED)))
            }, lp(0, WRAP, 1f))
            addView(menuButton(R.drawable.ic_optimize, "Optimize") { service.openEinkMenu(optimize = true, pkg = pkg) })
            addView(menuButton(R.drawable.ic_einkwise, "EinkWise") { service.openEinkMenu(optimize = false, pkg = pkg) })
        }
    }

    /** Outlined icon + label button that opens one of BOOX's menus. */
    private fun menuButton(iconRes: Int, label: String, onClick: () -> Unit): View = with(ctx) {
        hbox().apply {
            contentDescription = label
            setPadding(s(8), s(6), s(10), s(6))
            addView(icon(getDrawable(iconRes), iconPx(18)))
            addView(text(label, sp(14f), bold = true).apply { setPadding(s(5), 0, 0, 0) })
            pressable(outline(2, 8))
            layoutParams = lp(WRAP, WRAP).apply { marginStart = s(6) }
            setOnClickListener { onClick() }
        }
    }

    // ---- Apps: 2 columns, 4 per page ------------------------------------------------------

    private fun dock(): View = with(ctx) {
        val apps = prefs.dock
        // Swipe sideways to page, like Quick Controls.
        val box = SwipeRow(ctx, s(40), vertical = true) { dir ->
            val pageCount = (dockCellCount + DOCK_PER_PAGE - 1) / DOCK_PER_PAGE
            val to = dockPage + dir
            if (to in 0 until pageCount) {
                dockPage = to
                show(mode)
            }
        }
        // "Previous app" sits wherever it was placed; it's left out until there is one.
        val cells: List<View> = apps.mapNotNull { app ->
            if (app.value == Prefs.PREVIOUS_APP) service.previousApp?.let { previousCell(it) } else appCell(app)
        }
        if (cells.isEmpty()) {
            box.addView(hint("Add apps in Settings."))
            return@with box
        }
        dockCellCount = cells.size
        val pages = cells.chunked(DOCK_PER_PAGE)
        dockPage = dockPage.coerceIn(0, pages.size - 1)
        pages[dockPage].chunked(2).forEachIndexed { r, pair ->
            if (r > 0) box.addView(rule(1))
            box.addView(hbox().apply {
                gravity = Gravity.NO_GRAVITY
                addView(pair[0], lp(0, WRAP, 1f))
                addView(vRule(), lp(dp(1), MATCH).apply {
                    topMargin = s(10)
                    bottomMargin = s(10)
                })
                if (pair.size > 1) addView(pair[1], lp(0, WRAP, 1f)) else addView(View(ctx), lp(0, 1, 1f))
            }, lp(MATCH, WRAP))
        }
        if (pages.size > 1) {
            box.addView(rule(1))
            box.addView(hbox().apply {
                gravity = Gravity.CENTER
                setPadding(0, s(2), 0, s(2))
                addView(pagerButton(R.drawable.ic_chevron_left, "Previous apps", dockPage > 0) { dockPage--; show(mode) })
                addView(text("${dockPage + 1} / ${pages.size}", sp(14f), bold = true).apply { setPadding(s(14), 0, s(14), 0) })
                addView(pagerButton(R.drawable.ic_chevron_right, "More apps", dockPage < pages.size - 1) { dockPage++; show(mode) })
            }, lp(MATCH, WRAP))
        }
        box
    }

    /** "Previous app": that app's icon with a small ← badge; tap to switch straight back, hold for recent apps. */
    private fun previousCell(pkg: String): View = with(ctx) {
        vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(s(4), s(9), s(4), s(9))
            contentDescription = "Previous app: ${Launcher.appLabel(service, pkg)}"
            val iconSize = iconPx(40)
            addView(FrameLayout(ctx).apply {
                addView(icon(Launcher.appIcon(service, pkg), iconSize, tint = false), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END))
                addView(FrameLayout(ctx).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Ink.PAPER)
                        setStroke(dp(2), Ink.INK)
                    }
                    addView(icon(getDrawable(R.drawable.ic_back), iconPx(14)), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                }, FrameLayout.LayoutParams(s(22), s(22), Gravity.TOP or Gravity.START))
            }, lp(dp(iconSize) + s(8), dp(iconSize) + s(8)))
            addView(oneLine(text("Previous app", sp(14f))).apply {
                gravity = Gravity.CENTER
                setPadding(0, s(3), 0, 0)
            }, lp(MATCH, WRAP))
            pressable()
            setOnClickListener { service.openPreviousApp() }
            // Hold: Android's recent apps, to pick any other app.
            setOnLongClickListener {
                service.systemAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                true
            }
        }
    }

    private fun appCell(app: Pin): View = with(ctx) {
        vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(s(4), s(9), s(4), s(9))
            contentDescription = app.label
            addView(icon(Launcher.appIcon(service, app.value), iconPx(40), tint = false))
            addView(oneLine(text(app.label, sp(14f))).apply {
                gravity = Gravity.CENTER
                setPadding(0, s(3), 0, 0)
            }, lp(MATCH, WRAP))
            pressable()
            setOnClickListener { launchAndClose { Launcher.launchApp(service, app.value) } }
        }
    }

    private fun pagerButton(res: Int, label: String, enabled: Boolean, onClick: () -> Unit): View = with(ctx) {
        FrameLayout(ctx).apply {
            contentDescription = label
            setPadding(s(10), s(8), s(10), s(8))
            addView(icon(getDrawable(res), iconPx(26)).apply { alpha = if (enabled) 1f else 0.3f })
            if (enabled) {
                pressable()
                setOnClickListener { onClick() }
            }
        }
    }

    // ---- Building blocks -----------------------------------------------------------------

    private fun scrolling(child: View): View = ScrollView(ctx).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalFadingEdgeEnabled = false
        addView(child, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    private fun rule(thicknessDp: Int): View = ctx.divider(thicknessDp)

    private fun vRule(): View = View(ctx).apply { setBackgroundColor(Ink.INK) }

    /** Thin rule between list rows, inset like Supernote's list separators. */
    private fun insetRule(): View = View(ctx).apply {
        setBackgroundColor(Ink.INK)
        layoutParams = LinearLayout.LayoutParams(MATCH, ctx.dp(1)).apply {
            marginStart = s(16)
            marginEnd = s(16)
        }
    }

    private fun hint(message: String, indentDp: Int = 16): View =
        ctx.text(message, sp(14f), color = Ink.MUTED).apply { setPadding(s(indentDp), s(10), s(16), s(12)) }

    private fun oneLine(t: TextView): TextView = t.apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun toast(msg: String) = Toast.makeText(service, msg, Toast.LENGTH_SHORT).show()

    companion object {
        // Two rows of two, like the Supernote photo; more pages with ‹ ›.
        const val DOCK_PER_PAGE = 4
        const val QC_PER_PAGE = 5
        const val LIGHT_PER_PAGE = 4
        const val CLIPS_PER_PAGE_MIN = 2

        /** The panel's width in pixels on a screen [screenW] wide (also used for Settings' live preview). */
        fun sheetWidth(context: Context, prefs: Prefs, screenW: Int): Int =
            maxOf(context.dp(300 * prefs.menuSize.scale), screenW * prefs.panelWidthPct.coerceIn(25, 100) / 100).coerceAtMost(screenW)
        const val CLIPS_PER_PAGE_FULL = 7
        private val io = Executors.newSingleThreadExecutor()

        /** Clip pictures already scaled for the panel, by file and size (about 16 MB at most). */
        private val thumbs = object : android.util.LruCache<String, android.graphics.Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.allocationByteCount
        }
    }
}

/**
 * A row that turns a sideways swipe into a page change, while taps and long
 * presses still reach its children.
 */
private class SwipeRow(
    context: android.content.Context,
    private val threshold: Int,
    vertical: Boolean = false,
    private val onSwipe: (Int) -> Unit,
) : android.widget.LinearLayout(context) {
    private var downX = 0f
    private var downY = 0f

    init {
        orientation = if (vertical) VERTICAL else HORIZONTAL
    }

    override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                if (kotlin.math.abs(dx) > threshold / 2 && kotlin.math.abs(dx) > kotlin.math.abs(e.y - downY)) return true
            }
        }
        return false
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_UP) {
            val dx = e.x - downX
            if (kotlin.math.abs(dx) > threshold) onSwipe(if (dx < 0) 1 else -1)
        }
        return true
    }
}

/** Front Light level bar: outlined, filled to [value]; tap or drag along it to pick. */
private class LevelBar(
    context: android.content.Context,
    private val max: Int,
    private var value: Int,
    private val stroke: Int,
    private val onPick: (Int) -> Unit,
) : View(context) {
    private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = stroke.toFloat()
        color = Ink.INK
    }
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Ink.INK }
    private val rect = android.graphics.RectF()

    override fun onDraw(canvas: android.graphics.Canvas) {
        val r = height / 2f
        rect.set(stroke / 2f, stroke / 2f, width - stroke / 2f, height - stroke / 2f)
        if (value >= max) {
            // Full: one solid pill, so no sliver is left at the rounded end.
            canvas.drawRoundRect(rect, r, r, fill)
            canvas.drawRoundRect(rect, r, r, line)
            return
        }
        canvas.save()
        val clip = android.graphics.Path().apply { addRoundRect(rect, r, r, android.graphics.Path.Direction.CW) }
        canvas.clipPath(clip)
        canvas.drawRect(0f, 0f, width * value / max.toFloat(), height.toFloat(), fill)
        canvas.restore()
        canvas.drawRoundRect(rect, r, r, line)
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            android.view.MotionEvent.ACTION_UP -> {
                // The ends snap, so the first and last steps are easy to hit.
                val f = (e.x / width).coerceIn(0f, 1f)
                value = when {
                    f > 0.96f -> max
                    f < 0.04f -> 0
                    else -> (f * max).roundToInt().coerceIn(0, max)
                }
                invalidate()
                onPick(value)
            }
        }
        return true
    }
}
