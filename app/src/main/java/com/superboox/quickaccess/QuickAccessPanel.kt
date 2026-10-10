package com.superboox.quickaccess

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
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
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The Quick Access panel, modelled on the Supernote sidebar: a full-height
 * sheet on the trigger edge with
 *  - a header (title, settings, close),
 *  - an optional row of system actions (Back, Home, Recents, Alerts, Capture),
 *  - "Quick Access" pins (BOOX notes, documents, folders, apps, intents);
 *    long-press one to rearrange or remove them,
 *  - "Recent" files from a chosen folder,
 *  - and apps at the bottom, 8 per page (4 × 2) like Supernote.
 *
 * Everything is sized through [s]/[sp] so the "Menu size" setting scales it.
 */
class QuickAccessPanel(
    private val service: QuickAccessService,
    private val prefs: Prefs,
    private val foregroundPackage: String?,
) {
    private val ctx: Context = ContextThemeWrapper(service, R.style.Theme_SuperBoox)
    private val main = Handler(Looper.getMainLooper())
    private val expandedFolders = HashSet<String>()
    private val scale = prefs.menuSize.scale
    private var editing = false
    private var dockPage = 0

    /** What BOOX Notes is showing, if it's the app behind the panel. */
    private val notesState: NotesScreen.State? =
        if (service.isNotesInFront) service.notesState() else null

    val root: FrameLayout = object : FrameLayout(ctx) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (editing) setEditing(false) else service.hidePanel()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    /** Scrollable middle part; swapped between the main list and choosers. */
    private val body = ctx.vbox()
    private val dockHolder = FrameLayout(ctx)

    init {
        build()
        renderMain()
        renderDock()
    }

    /** dp scaled by the "Menu size" setting. */
    private fun s(v: Int): Int = ctx.dp(v * scale)
    private fun sp(v: Float): Float = v * scale

    private fun build() = with(ctx) {
        // Tapping outside the sheet closes it (no dimming: it only costs an e-ink refresh).
        root.setOnClickListener { service.hidePanel() }

        val edge = prefs.edge
        val width = maxOf(s(300), service.screenWidth() * prefs.panelWidthPct.coerceIn(25, 100) / 100)
            .coerceAtMost(service.screenWidth())

        val content = vbox().apply { setBackgroundColor(Ink.PAPER) }
        content.addView(header(), lp(MATCH, WRAP))
        content.addView(divider(3))
        if (prefs.showSystemActions) {
            content.addView(systemActions(), lp(MATCH, WRAP))
            content.addView(divider(3))
        }
        val scroll = ScrollView(ctx).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = false
            addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        content.addView(scroll, lp(MATCH, 0, 1f))
        content.addView(divider(3))
        content.addView(dockHolder, lp(MATCH, WRAP))

        val sheet = hbox().apply {
            setBackgroundColor(Ink.PAPER)
            isClickable = true // swallow taps so they don't close the panel
            val line = View(ctx).apply { setBackgroundColor(Ink.INK) }
            if (edge == Edge.RIGHT) {
                addView(line, lp(dp(4), MATCH))
                addView(content, lp(0, MATCH, 1f))
            } else {
                addView(content, lp(0, MATCH, 1f))
                addView(line, lp(dp(4), MATCH))
            }
        }
        root.addView(
            sheet,
            FrameLayout.LayoutParams(width, MATCH, if (edge == Edge.RIGHT) Gravity.END else Gravity.START),
        )
    }

    // ---- Header & system actions -----------------------------------------------------------

    private fun header(): View = with(ctx) {
        hbox().apply {
            setPadding(s(16), s(12), s(8), s(12))
            addView(text("Quick Access", sp(24f), bold = true), lp(0, WRAP, 1f))
            addView(iconButton(R.drawable.ic_settings, "Settings") {
                service.hidePanel()
                Launcher.start(
                    service,
                    Intent(service, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            })
            addView(iconButton(R.drawable.ic_close, "Close") { service.hidePanel() })
        }
    }

    private fun systemActions(): View = with(ctx) {
        hbox().apply {
            setPadding(s(4), s(6), s(4), s(6))
            fun action(iconRes: Int, label: String, global: Int) {
                val cell = vbox().apply {
                    gravity = Gravity.CENTER
                    setPadding(0, s(6), 0, s(6))
                    contentDescription = label
                    addView(icon(getDrawable(iconRes), (28 * scale).roundToInt()))
                    addView(text(label, sp(13f), medium = true).apply { gravity = Gravity.CENTER })
                    pressable()
                    setOnClickListener { service.systemAction(global) }
                }
                addView(cell, lp(0, WRAP, 1f))
            }
            action(R.drawable.ic_back, "Back", AccessibilityService.GLOBAL_ACTION_BACK)
            action(R.drawable.ic_home, "Home", AccessibilityService.GLOBAL_ACTION_HOME)
            action(R.drawable.ic_recents, "Recents", AccessibilityService.GLOBAL_ACTION_RECENTS)
            action(R.drawable.ic_notifications, "Alerts", AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            action(R.drawable.ic_screenshot, "Capture", AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
        }
    }

    // ---- Main list -----------------------------------------------------------------------

    private fun renderMain(): Unit = with(ctx) {
        body.removeAllViews()
        val pins = prefs.pins

        if (editing) {
            body.addView(editHeader())
            pins.forEachIndexed { i, pin -> body.addView(editRow(pin, i, pins.size)) }
            if (pins.isEmpty()) body.addView(hint("Nothing pinned."))
            return@with
        }

        body.addView(sectionTitle("Quick Access", scale))
        if (pins.isEmpty()) {
            body.addView(hint("Nothing pinned yet. Open a note in BOOX Notes and pin it from here, or add documents, folders and apps in Settings."))
        }
        pins.forEachIndexed { i, pin ->
            if (i > 0) body.addView(rowRule())
            body.addView(pinRow(pin))
            if (pin.type == PinType.FOLDER && pin.id in expandedFolders) {
                val holder = vbox()
                body.addView(holder)
                loadFiles(holder, Uri.parse(pin.value), limit = 15, indentDp = 44)
            }
        }
        contextualPinRow()?.let {
            body.addView(rowRule())
            body.addView(it)
        }
        if (pins.isNotEmpty()) body.addView(hint("Long-press an item to rearrange or remove."))

        prefs.recentFolderUri?.let { uri ->
            body.addView(sectionTitle("Recent", scale))
            val holder = vbox()
            body.addView(holder)
            loadFiles(holder, Uri.parse(uri), limit = prefs.recentCount.coerceIn(1, 20), indentDp = 16)
        }
    }

    private fun pinRow(pin: Pin): View = with(ctx) {
        val (drawable, tint) = pinIcon(pin)
        row(drawable, tint, pin.label, subtitle(pin)).apply {
            setOnClickListener { activate(pin) }
            setOnLongClickListener {
                setEditing(true)
                true
            }
        }
    }

    private fun activate(pin: Pin) {
        DebugLog.i("pin", "${pin.type} \"${pin.label}\" -> ${pin.value}")
        when (pin.type) {
            PinType.BOOX_NOTE -> service.openBooxNote(pin.value)
            PinType.FOLDER -> {
                if (!expandedFolders.add(pin.id)) expandedFolders.remove(pin.id)
                renderMain()
            }
            PinType.FILE -> launchAndClose { Launcher.openFile(service, Uri.parse(pin.value)) }
            PinType.APP -> launchAndClose { Launcher.launchApp(service, pin.value) }
            PinType.INTENT -> launchAndClose { Launcher.openIntentUri(service, pin.value) }
        }
    }

    private inline fun launchAndClose(block: () -> Boolean) {
        if (block()) service.hidePanel()
    }

    // ---- Edit mode (long-press) ----------------------------------------------------------

    private fun setEditing(on: Boolean) {
        editing = on
        DebugLog.i("panel", if (on) "edit mode" else "edit mode done")
        renderMain()
    }

    private fun editHeader(): View = with(ctx) {
        hbox().apply {
            setPadding(s(16), s(14), s(12), s(10))
            addView(text("Edit Quick Access", sp(18f), bold = true), lp(0, WRAP, 1f))
            addView(inkButton("Done", sp(15f)) { setEditing(false) })
        }
    }

    private fun editRow(pin: Pin, index: Int, count: Int): View = with(ctx) {
        val (drawable, tint) = pinIcon(pin)
        hbox().apply {
            setPadding(s(16), s(8), s(10), s(8))
            addView(icon(drawable, (28 * scale).roundToInt(), tint))
            addView(
                text(pin.label, sp(17f), medium = true).apply {
                    setPadding(s(12), 0, s(4), 0)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                },
                lp(0, WRAP, 1f),
            )
            val b = (44 * scale).roundToInt()
            addView(inkIconButton(R.drawable.ic_arrow_up, "Move up", b, enabled = index > 0) { movePin(index, index - 1) })
            addView(inkIconButton(R.drawable.ic_arrow_down, "Move down", b, enabled = index < count - 1) { movePin(index, index + 1) })
            addView(inkIconButton(R.drawable.ic_delete, "Remove", b) {
                prefs.removePin(pin.id)
                renderMain()
            })
        }
    }

    private fun movePin(from: Int, to: Int) {
        val pins = prefs.pins.toMutableList()
        if (from !in pins.indices || to !in pins.indices) return
        pins.add(to, pins.removeAt(from))
        prefs.pins = pins
        renderMain()
    }

    // ---- Pinning from BOOX Notes / the current app ---------------------------------------

    /**
     * In BOOX Notes: inside a note, a one-tap "Pin this note"; in the library, a
     * picker listing only the note cards. Elsewhere: "Pin <current app>".
     */
    private fun contextualPinRow(): View? = with(ctx) {
        val add = getDrawable(R.drawable.ic_add)
        when (val state = notesState) {
            is NotesScreen.State.Editor -> {
                val pinned = isNotePinned(state.title)
                return row(add, true, if (pinned) "This note is pinned" else "Pin this note", state.title).apply {
                    if (!pinned) setOnClickListener { pinNote(state.title) } else isEnabled = false
                }
            }
            is NotesScreen.State.Library -> {
                val choices = state.cards.filterNot { isNotePinned(it.label) }
                if (choices.isEmpty()) return null
                return row(add, true, "Pin a note from this screen", "${choices.size} shown in BOOX Notes")
                    .apply { setOnClickListener { renderNotePicker(choices.map { it.label }) } }
            }
            NotesScreen.State.Unknown -> return null
            null -> Unit
        }
        val pkg = foregroundPackage ?: return null
        if (pkg == prefs.notesPackage || pkg == service.packageName || service.isNotesInFront) return null
        if (service.packageManager.getLaunchIntentForPackage(pkg) == null) return null
        if (prefs.pins.any { it.type == PinType.APP && it.value.substringBefore('/') == pkg }) return null
        val label = Launcher.appLabel(service, pkg)
        return row(add, true, "Pin $label", "Add the current app to Quick Access").apply {
            setOnClickListener {
                prefs.addPin(Pin(PinType.APP, label, pkg))
                renderMain()
            }
        }
    }

    private fun isNotePinned(title: String) =
        prefs.pins.any { it.type == PinType.BOOX_NOTE && NotesScreen.matches(title, BooxNotes.splitPath(it.value).lastOrNull() ?: it.value) }

    private fun pinNote(title: String) {
        prefs.addPin(Pin(PinType.BOOX_NOTE, title, title))
        DebugLog.i("pin", "pinned note \"$title\"")
        renderMain()
    }

    private fun renderNotePicker(labels: List<String>): Unit = with(ctx) {
        body.removeAllViews()
        body.addView(sectionTitle("Tap a note to pin", scale))
        labels.forEachIndexed { i, label ->
            if (i > 0) body.addView(rowRule())
            body.addView(row(getDrawable(R.drawable.ic_note), true, label, null).apply { setOnClickListener { pinNote(label) } })
        }
        body.addView(rowRule())
        body.addView(row(getDrawable(R.drawable.ic_back), true, "Back", null).apply { setOnClickListener { renderMain() } })
    }

    // ---- Files ----------------------------------------------------------------------------

    private fun loadFiles(holder: LinearLayout, tree: Uri, limit: Int, indentDp: Int) = with(ctx) {
        holder.addView(hint("Loading…", indentDp))
        io.execute {
            val files = runCatching { Folders.newestFiles(service, tree, limit) }.getOrNull()
            main.post {
                if (!root.isAttachedToWindow) return@post
                holder.removeAllViews()
                when {
                    files == null -> holder.addView(hint("Folder not accessible. Re-add it in Settings.", indentDp))
                    files.isEmpty() -> holder.addView(hint("Empty", indentDp))
                }
                files?.forEach { f ->
                    val ago = DateUtils.getRelativeTimeSpanString(f.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                    holder.addView(
                        row(getDrawable(R.drawable.ic_file), true, f.name, ago.toString(), indentDp).apply {
                            setOnClickListener { launchAndClose { Launcher.openFile(service, f.uri) } }
                        },
                    )
                }
            }
        }
    }

    // ---- Apps (Supernote style: 4 × 2 per page) ------------------------------------------

    private fun renderDock(): Unit = with(ctx) {
        dockHolder.removeAllViews()
        val apps = prefs.dock
        val pages = apps.chunked(DOCK_PER_PAGE).ifEmpty { listOf(emptyList()) }
        dockPage = dockPage.coerceIn(0, pages.size - 1)

        val box = vbox().apply { setPadding(s(8), s(8), s(8), s(10)) }
        if (apps.isEmpty()) box.addView(hint("Add apps in Settings."))
        pages[dockPage].chunked(DOCK_COLUMNS).forEach { rowApps ->
            val line = hbox()
            rowApps.forEach { app -> line.addView(appCell(app), lp(0, WRAP, 1f)) }
            repeat(DOCK_COLUMNS - rowApps.size) { line.addView(View(ctx), lp(0, 1, 1f)) }
            box.addView(line, lp(MATCH, WRAP))
        }
        if (pages.size > 1) {
            box.addView(hbox().apply {
                gravity = Gravity.CENTER
                setPadding(0, s(4), 0, 0)
                addView(iconButton(R.drawable.ic_chevron_left, "Previous apps", enabled = dockPage > 0) {
                    dockPage--
                    renderDock()
                })
                addView(text("${dockPage + 1} / ${pages.size}", sp(14f), bold = true).apply {
                    setPadding(s(16), 0, s(16), 0)
                })
                addView(iconButton(R.drawable.ic_chevron_right, "More apps", enabled = dockPage < pages.size - 1) {
                    dockPage++
                    renderDock()
                })
            }, lp(MATCH, WRAP))
        }
        dockHolder.addView(box)
    }

    private fun appCell(app: Pin): View = with(ctx) {
        vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(s(2), s(8), s(2), s(8))
            contentDescription = app.label
            addView(icon(Launcher.appIcon(service, app.value), (52 * scale).roundToInt(), tint = false))
            addView(
                text(app.label, sp(13f), medium = true).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, s(4), 0, 0)
                },
                lp(MATCH, WRAP),
            )
            pressable()
            setOnClickListener { launchAndClose { Launcher.launchApp(service, app.value) } }
        }
    }

    // ---- Building blocks -----------------------------------------------------------------

    private fun row(iconDrawable: Drawable?, tint: Boolean, title: String, subtitle: String?, indentDp: Int = 16): LinearLayout = with(ctx) {
        hbox().apply {
            setPadding(s(indentDp), s(13), s(16), s(13))
            addView(icon(iconDrawable, (30 * scale).roundToInt(), tint))
            val texts = vbox().apply {
                setPadding(s(14), 0, 0, 0)
                addView(text(title, sp(18f), medium = true).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
                if (!subtitle.isNullOrEmpty()) addView(text(subtitle, sp(13f), color = Ink.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            }
            addView(texts, lp(0, WRAP, 1f))
            pressable()
        }
    }

    /** Thin rule between list rows, inset like Supernote's list separators. */
    private fun rowRule(): View = View(ctx).apply {
        setBackgroundColor(Ink.INK)
        layoutParams = LinearLayout.LayoutParams(MATCH, ctx.dp(1)).apply {
            marginStart = s(16)
            marginEnd = s(16)
        }
    }

    private fun hint(message: String, indentDp: Int = 16): View =
        ctx.text(message, sp(14f), color = Ink.MUTED).apply { setPadding(s(indentDp), s(8), s(16), s(10)) }

    private fun iconButton(res: Int, label: String, enabled: Boolean = true, onClick: () -> Unit): View = with(ctx) {
        FrameLayout(ctx).apply {
            contentDescription = label
            setPadding(s(10), s(10), s(10), s(10))
            addView(icon(getDrawable(res), (28 * scale).roundToInt()).apply { alpha = if (enabled) 1f else 0.3f })
            if (enabled) {
                pressable()
                setOnClickListener { onClick() }
            }
        }
    }

    private fun pinIcon(pin: Pin): Pair<Drawable?, Boolean> = when (pin.type) {
        PinType.BOOX_NOTE -> ctx.getDrawable(R.drawable.ic_note) to true
        PinType.FILE -> ctx.getDrawable(R.drawable.ic_file) to true
        PinType.FOLDER -> ctx.getDrawable(R.drawable.ic_folder) to true
        PinType.INTENT -> ctx.getDrawable(R.drawable.ic_link) to true
        PinType.APP -> (Launcher.appIcon(service, pin.value) ?: ctx.getDrawable(R.drawable.ic_panel)) to false
    }

    private fun subtitle(pin: Pin): String = when (pin.type) {
        PinType.BOOX_NOTE -> if (pin.value != pin.label) "BOOX Notes · ${pin.value}" else "BOOX Notes"
        PinType.FILE -> "Document"
        PinType.FOLDER -> if (pin.id in expandedFolders) "Folder · tap to collapse" else "Folder"
        PinType.APP -> "App"
        PinType.INTENT -> "Shortcut"
    }

    private companion object {
        // Supernote shows 8 apps per page.
        const val DOCK_COLUMNS = 4
        const val DOCK_PER_PAGE = 8
        val io = Executors.newSingleThreadExecutor()
    }
}
