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
 *   [ Recent Files | New Note | ⚙ ]
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
) {
    private enum class Mode { MAIN, RECENT, EDIT, PICK }

    private val ctx: Context = ContextThemeWrapper(service, R.style.Theme_SuperBoox)
    private val main = Handler(Looper.getMainLooper())
    private val scale = prefs.menuSize.scale
    private val expandedFolders = HashSet<String>()
    private var mode = Mode.MAIN
    private var dockPage = 0
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
    private val picked = LinkedHashSet<String>()

    val root: FrameLayout = object : FrameLayout(ctx) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (mode != Mode.MAIN) show(Mode.MAIN) else service.hidePanel()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private val sheet = ctx.hbox()
    private val content = ctx.vbox()

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
        show(Mode.MAIN)
    }

    /** dp scaled by the "Menu size" setting. */
    private fun s(v: Int): Int = ctx.dp(v * scale)
    private fun sp(v: Float): Float = v * scale
    private fun iconPx(v: Int): Int = (v * scale).roundToInt()

    private fun show(newMode: Mode) {
        mode = newMode
        val screenW = service.screenWidth()
        val pct = if (mode == Mode.PICK) maxOf(62, prefs.panelWidthPct) else prefs.panelWidthPct.coerceIn(25, 100)
        val width = maxOf(s(300), screenW * pct / 100).coerceAtMost(screenW)
        sheet.layoutParams = FrameLayout.LayoutParams(width, MATCH, if (prefs.edge == Edge.RIGHT) Gravity.END else Gravity.START)

        content.removeAllViews()
        if (mode == Mode.PICK) {
            content.addView(pickerBar(), lp(MATCH, WRAP))
            content.addView(scrolling(pickerList()), lp(MATCH, 0, 1f))
            return
        }
        content.addView(topRow(), lp(MATCH, WRAP))
        content.addView(rule(2))
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
        content.addView(dock(), lp(MATCH, WRAP))
    }

    // ---- Top: Recent Files | New Note | ⚙ --------------------------------------------------

    private fun topRow(): View = with(ctx) {
        hbox().apply {
            gravity = Gravity.NO_GRAVITY
            addView(topCell(R.drawable.ic_clock, "Recent Files") { show(if (mode == Mode.RECENT) Mode.MAIN else Mode.RECENT) }, lp(0, WRAP, 1f))
            addView(vRule(), lp(dp(2), MATCH))
            addView(topCell(R.drawable.ic_note_add, "New Note") { newNote() }, lp(0, WRAP, 1f))
            addView(vRule(), lp(dp(2), MATCH))
            addView(FrameLayout(ctx).apply {
                contentDescription = "SuperBoox settings"
                addView(icon(getDrawable(R.drawable.ic_settings), iconPx(26)), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                pressable()
                setOnClickListener { openSettings() }
            }, lp(s(52), MATCH))
        }
    }

    private fun topCell(iconRes: Int, label: String, onClick: () -> Unit): View = with(ctx) {
        vbox().apply {
            gravity = Gravity.CENTER
            setPadding(0, s(12), 0, s(10))
            contentDescription = label
            addView(icon(getDrawable(iconRes), iconPx(32)))
            // Text views in a vertical LinearLayout default to full width: centre the text itself.
            addView(text(label, sp(15f), medium = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, s(4), 0, 0)
            })
            pressable()
            setOnClickListener { onClick() }
        }
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

    /** Redraws in place (Quick Controls after a tap). */
    fun refresh() = show(mode)

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
        box
    }

    /** Round tile like the BOOX Control Center: black when on, outlined when off. */
    private fun controlTile(control: QuickControls.Control, on: Boolean): View = with(ctx) {
        val lit = on && control.kind == QuickControls.Kind.TOGGLE
        vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, s(6))
            contentDescription = "${control.label}${if (control.kind == QuickControls.Kind.TOGGLE) if (on) ", on" else ", off" else ""}"
            val size = s(52)
            addView(FrameLayout(ctx).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (lit) Ink.INK else Ink.PAPER)
                    setStroke(dp(2), Ink.INK)
                }
                addView(icon(getDrawable(control.icon), iconPx(26)).apply {
                    imageTintList = ColorStateList.valueOf(if (lit) Ink.PAPER else Ink.INK)
                }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            }, lp(size, size))
            addView(text(control.label, sp(12f)).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(s(2), s(5), s(2), 0)
            }, lp(MATCH, WRAP))
            pressable()
            setOnClickListener { service.quickControl(control, hold = false) }
            setOnLongClickListener {
                service.quickControl(control, hold = true)
                true
            }
        }
    }

    // ---- Last Opened Note / Document ------------------------------------------------------

    private fun lastNoteRow(): View {
        val note = Recents.list(prefs).firstOrNull { it.kind == RecentItem.Kind.NOTE && !isCurrentNote(it) }
        return lastRow(R.drawable.ic_note, "Last Opened Note", note?.title, enabled = note != null) {
            note?.let { service.openBooxNote(it.title) }
        }
    }

    private fun lastDocumentRow(): View {
        val doc = Recents.list(prefs).firstOrNull { it.kind == RecentItem.Kind.DOCUMENT && !isCurrent(it) }
        val app = doc?.let { Launcher.appLabel(service, it.pkg) }
        val sub = doc?.let { if (it.title == app) app else "${it.title} · $app" }
        return lastRow(R.drawable.ic_book, "Last Opened Document", sub, enabled = doc != null) {
            doc?.let { openRecentDocument(it) }
        }
    }

    private fun lastRow(iconRes: Int, label: String, subtitle: String?, enabled: Boolean, onClick: () -> Unit): View = with(ctx) {
        val color = if (enabled) Ink.INK else Ink.DISABLED
        hbox().apply {
            setPadding(s(16), s(12), s(16), s(12))
            addView(icon(getDrawable(iconRes), iconPx(30)).apply { imageTintList = ColorStateList.valueOf(color) })
            addView(vbox().apply {
                setPadding(s(14), 0, 0, 0)
                addView(text(label, sp(17f), color = color, medium = true))
                if (subtitle != null) addView(oneLine(text(subtitle, sp(13f), color = if (enabled) Ink.MUTED else Ink.DISABLED)))
            }, lp(0, WRAP, 1f))
            if (enabled) {
                pressable()
                setOnClickListener { onClick() }
            }
        }
    }

    // ---- Quick Access ----------------------------------------------------------------------

    private fun quickAccessList(): View = with(ctx) {
        val box = vbox()
        box.addView(FrameLayout(ctx).apply {
            setPadding(s(16), s(12), s(8), s(12))
            addView(text("Quick Access", sp(17f), bold = true), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            addView(vbox().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(s(6), 0, s(6), 0)
                contentDescription = "Add to Quick Access"
                addView(icon(getDrawable(R.drawable.ic_link_add), iconPx(24)))
                addView(text("Add", sp(11f)).apply { gravity = Gravity.CENTER })
                pressable()
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
                picked.clear()
                show(Mode.PICK)
                return
            }
            else -> Unit
        }
        val pkg = foregroundPackage
        if (pkg == ReaderLibrary.NEOREADER) return addOpenDocument()
        if (pkg == null || pkg == service.packageName || service.packageManager.getLaunchIntentForPackage(pkg) == null) {
            return toast("Open a note, or the Notes library, then tap Add")
        }
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

    // ---- Recent Files ----------------------------------------------------------------------

    private class RecentRow(val iconRes: Int, val title: String, val subtitle: String, val time: Long, val open: () -> Unit)

    private fun recentList(): View = with(ctx) {
        val box = vbox()
        box.addView(FrameLayout(ctx).apply {
            setPadding(s(8), s(12), s(16), s(12))
            addView(hbox().apply {
                addView(icon(getDrawable(R.drawable.ic_chevron_left), iconPx(26)))
                addView(text("Back", sp(14f)))
                pressable()
                setOnClickListener { show(Mode.MAIN) }
            }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL))
            addView(text("Recent Files", sp(17f), bold = true), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        })
        box.addView(rule(1))
        val list = vbox()
        box.addView(list)

        val remembered = Recents.list(prefs).filterNot(::isCurrent).map { item ->
            val ago = ago(item.time)
            when (item.kind) {
                RecentItem.Kind.NOTE -> RecentRow(R.drawable.ic_note, item.title, "Note · $ago", item.time) { service.openBooxNote(item.title) }
                RecentItem.Kind.DOCUMENT -> {
                    val app = Launcher.appLabel(service, item.pkg)
                    RecentRow(R.drawable.ic_book, item.title, if (item.title == app) "Document · $ago" else "$app · $ago", item.time) {
                        openRecentDocument(item)
                    }
                }
            }
        }
        fillRecent(list, remembered)

        // Merge in the newest files from the folder chosen in Settings.
        prefs.recentFolderUri?.let { uri ->
            io.execute {
                val files = runCatching { Folders.newestFiles(service, Uri.parse(uri), prefs.recentCount.coerceIn(1, 20)) }.getOrNull().orEmpty()
                main.post {
                    if (!root.isAttachedToWindow || mode != Mode.RECENT) return@post
                    val fileRows = files.map { f ->
                        RecentRow(R.drawable.ic_file, f.name, "Document · ${ago(f.modified)}", f.modified) {
                            launchAndClose { Launcher.openFile(service, f.uri) }
                        }
                    }
                    fillRecent(list, (remembered + fileRows).sortedByDescending { it.time })
                }
            }
        }
        box
    }

    private fun fillRecent(list: LinearLayout, rows: List<RecentRow>) {
        list.removeAllViews()
        if (rows.isEmpty()) {
            list.addView(hint("Notes and documents you open will appear here."))
            return
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) list.addView(insetRule())
            list.addView(ctx.hbox().apply {
                setPadding(s(16), s(11), s(16), s(11))
                addView(ctx.icon(ctx.getDrawable(r.iconRes), iconPx(24)))
                addView(ctx.vbox().apply {
                    setPadding(s(12), 0, 0, 0)
                    addView(oneLine(ctx.text(r.title, sp(17f))))
                    addView(oneLine(ctx.text(r.subtitle, sp(13f), color = Ink.MUTED)))
                }, lp(0, WRAP, 1f))
                pressable()
                setOnClickListener { r.open() }
            })
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
                setPadding(s(20), s(7), s(8), s(7))
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
                addView(icon(getDrawable(if (card.isFolder) R.drawable.ic_folder else R.drawable.ic_note), iconPx(24)).apply {
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
            if (!isNotePinned(label)) {
                prefs.addPin(Pin(PinType.BOOX_NOTE, label, label))
                added++
            }
        }
        DebugLog.i("pin", "added $added from the library: $picked")
        toast(if (added == 1) "Added 1 item" else "Added $added items")
        picked.clear()
        show(Mode.MAIN)
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

    /** "Previous app": that app's icon with a small ← badge; tap to switch straight back. */
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

    private companion object {
        // Two rows of two, like the Supernote photo; more pages with ‹ ›.
        const val DOCK_PER_PAGE = 4
        const val QC_PER_PAGE = 5
        val io = Executors.newSingleThreadExecutor()
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
