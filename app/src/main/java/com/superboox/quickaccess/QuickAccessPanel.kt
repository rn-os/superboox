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

/**
 * The Quick Access panel: a full-height sheet on the trigger edge with
 *  - a header (title, settings, close),
 *  - an optional row of system actions (Back, Home, Recents, Notifications, Screenshot),
 *  - "Quick Access" pins (BOOX notes, documents, folders, apps, intents),
 *  - "Recent" files from a chosen folder,
 *  - and a dock of apps at the bottom, like the Supernote sidebar.
 */
class QuickAccessPanel(
    private val service: QuickAccessService,
    private val prefs: Prefs,
    private val foregroundPackage: String?,
) {
    private val ctx: Context = ContextThemeWrapper(service, R.style.Theme_SuperBoox)
    private val main = Handler(Looper.getMainLooper())
    private val expandedFolders = HashSet<String>()

    val root: FrameLayout = object : FrameLayout(ctx) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) service.hidePanel()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    /** Scrollable middle part; swapped between the main list and choosers. */
    private val body = ctx.vbox()

    init {
        build()
        renderMain()
    }

    private fun build() = with(ctx) {
        // Tapping outside the sheet closes it (no dimming: it only costs an e-ink refresh).
        root.setOnClickListener { service.hidePanel() }

        val edge = prefs.edge
        val width = maxOf(dp(300), service.screenWidth() * prefs.panelWidthPct.coerceIn(25, 100) / 100)
            .coerceAtMost(service.screenWidth())

        val content = vbox().apply { setBackgroundColor(Ink.PAPER) }
        content.addView(header(), lp(MATCH, WRAP))
        if (prefs.showSystemActions) {
            content.addView(divider())
            content.addView(systemActions(), lp(MATCH, WRAP))
        }
        content.addView(divider(2))
        val scroll = ScrollView(ctx).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = false
            addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        content.addView(scroll, lp(MATCH, 0, 1f))
        content.addView(divider(2))
        content.addView(dock(), lp(MATCH, WRAP))

        val sheet = hbox().apply {
            setBackgroundColor(Ink.PAPER)
            isClickable = true // swallow taps so they don't close the panel
            val line = View(ctx).apply { setBackgroundColor(Ink.INK) }
            if (edge == Edge.RIGHT) {
                addView(line, lp(dp(3), MATCH))
                addView(content, lp(0, MATCH, 1f))
            } else {
                addView(content, lp(0, MATCH, 1f))
                addView(line, lp(dp(3), MATCH))
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
            setPadding(dp(16), dp(10), dp(8), dp(10))
            addView(text("Quick Access", 22f, bold = true), lp(0, WRAP, 1f))
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
            setPadding(dp(4), dp(6), dp(4), dp(6))
            fun action(iconRes: Int, label: String, global: Int) {
                val cell = vbox().apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, dp(6))
                    contentDescription = label
                    addView(icon(getDrawable(iconRes), 26))
                    addView(text(label, 12f).apply { gravity = Gravity.CENTER })
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

    private fun renderMain() = with(ctx) {
        body.removeAllViews()
        body.addView(sectionTitle("Quick Access"))

        val pins = prefs.pins
        if (pins.isEmpty()) {
            body.addView(
                text("Nothing pinned yet. Pin a note from BOOX Notes below, or add documents, folders and apps in Settings.", 14f, color = Ink.MUTED)
                    .apply { setPadding(dp(16), dp(4), dp(16), dp(12)) },
            )
        }
        pins.forEach { pin ->
            body.addView(pinRow(pin))
            if (pin.type == PinType.FOLDER && pin.id in expandedFolders) {
                val holder = vbox()
                body.addView(holder)
                loadFiles(holder, Uri.parse(pin.value), limit = 15, indentDp = 40)
            }
        }
        contextualPinRow()?.let(body::addView)

        prefs.recentFolderUri?.let { uri ->
            body.addView(sectionTitle("Recent"))
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
                renderConfirmRemove(pin)
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

    /** "Pin a note from this screen" inside BOOX Notes, otherwise "Pin <current app>". */
    private fun contextualPinRow(): View? = with(ctx) {
        val pkg = foregroundPackage ?: return null
        if (pkg == prefs.notesPackage) {
            return row(getDrawable(R.drawable.ic_add), true, "Pin a note from this screen", "Pick it from what BOOX Notes is showing")
                .apply { setOnClickListener { renderNotePicker() } }
        }
        if (pkg == service.packageName || service.packageManager.getLaunchIntentForPackage(pkg) == null) return null
        if (prefs.pins.any { it.type == PinType.APP && it.value.substringBefore('/') == pkg }) return null
        val label = Launcher.appLabel(service, pkg)
        return row(getDrawable(R.drawable.ic_add), true, "Pin $label", "Add the current app to Quick Access")
            .apply {
                setOnClickListener {
                    prefs.addPin(Pin(PinType.APP, label, pkg))
                    renderMain()
                }
            }
    }

    private fun renderNotePicker() = with(ctx) {
        val labels = service.notesScreenLabels()
        body.removeAllViews()
        body.addView(sectionTitle("Tap the note to pin"))
        body.addView(
            text(
                "Showing the text BOOX Notes has on screen. Notes inside a folder can be renamed to \"Folder / Note\" in Settings so they open from the library.",
                13f,
                color = Ink.MUTED,
            ).apply { setPadding(dp(16), 0, dp(16), dp(8)) },
        )
        if (labels.isEmpty()) {
            body.addView(text("Couldn't read the BOOX Notes screen.", 15f).apply { setPadding(dp(16), dp(8), dp(16), dp(8)) })
        }
        labels.forEach { label ->
            body.addView(
                row(getDrawable(R.drawable.ic_note), true, label, null).apply {
                    setOnClickListener {
                        prefs.addPin(Pin(PinType.BOOX_NOTE, label, label))
                        renderMain()
                    }
                },
            )
        }
        body.addView(cancelRow())
    }

    private fun renderConfirmRemove(pin: Pin) = with(ctx) {
        body.removeAllViews()
        body.addView(sectionTitle("Remove from Quick Access?"))
        body.addView(text(pin.label, 18f, bold = true).apply { setPadding(dp(16), dp(4), dp(16), dp(12)) })
        body.addView(
            row(getDrawable(R.drawable.ic_close), true, "Remove", null).apply {
                setOnClickListener {
                    prefs.removePin(pin.id)
                    renderMain()
                }
            },
        )
        body.addView(cancelRow())
    }

    private fun cancelRow(): View = with(ctx) {
        row(getDrawable(R.drawable.ic_back), true, "Back", null).apply { setOnClickListener { renderMain() } }
    }

    private fun loadFiles(holder: LinearLayout, tree: Uri, limit: Int, indentDp: Int) = with(ctx) {
        holder.addView(text("Loading…", 14f, color = Ink.MUTED).apply { setPadding(dp(indentDp), dp(6), dp(16), dp(6)) })
        io.execute {
            val files = runCatching { Folders.newestFiles(service, tree, limit) }.getOrNull()
            main.post {
                if (!root.isAttachedToWindow) return@post
                holder.removeAllViews()
                when {
                    files == null -> holder.addView(
                        text("Folder not accessible. Re-add it in Settings.", 14f, color = Ink.MUTED)
                            .apply { setPadding(dp(indentDp), dp(6), dp(16), dp(6)) },
                    )
                    files.isEmpty() -> holder.addView(
                        text("Empty", 14f, color = Ink.MUTED).apply { setPadding(dp(indentDp), dp(6), dp(16), dp(6)) },
                    )
                }
                files?.forEach { f ->
                    val when_ = DateUtils.getRelativeTimeSpanString(f.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                    holder.addView(
                        row(getDrawable(R.drawable.ic_file), true, f.name, when_.toString(), indentDp).apply {
                            setOnClickListener { launchAndClose { Launcher.openFile(service, f.uri) } }
                        },
                    )
                }
            }
        }
    }

    // ---- Dock ------------------------------------------------------------------------------

    private fun dock(): View = with(ctx) {
        val apps = prefs.dock
        val grid = vbox().apply { setPadding(dp(8), dp(6), dp(8), dp(10)) }
        grid.addView(sectionTitle("Apps").apply { setPadding(dp(8), dp(4), dp(8), dp(4)) })
        if (apps.isEmpty()) {
            grid.addView(text("Add apps in Settings.", 14f, color = Ink.MUTED).apply { setPadding(dp(8), 0, dp(8), dp(6)) })
        }
        apps.chunked(DOCK_COLUMNS).forEach { rowApps ->
            val line = hbox()
            rowApps.forEach { app ->
                val cell = vbox().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(2), dp(6), dp(2), dp(6))
                    contentDescription = app.label
                    addView(icon(Launcher.appIcon(service, app.value), 44, tint = false))
                    addView(
                        text(app.label, 12f).apply {
                            gravity = Gravity.CENTER
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        },
                        lp(MATCH, WRAP),
                    )
                    pressable()
                    setOnClickListener { launchAndClose { Launcher.launchApp(service, app.value) } }
                }
                line.addView(cell, lp(0, WRAP, 1f))
            }
            // Keep columns aligned on the last, partially filled row.
            repeat(DOCK_COLUMNS - rowApps.size) { line.addView(View(ctx), lp(0, 1, 1f)) }
            grid.addView(line, lp(MATCH, WRAP))
        }
        grid
    }

    // ---- Building blocks -----------------------------------------------------------------

    private fun row(iconDrawable: Drawable?, tint: Boolean, title: String, subtitle: String?, indentDp: Int = 16): LinearLayout = with(ctx) {
        hbox().apply {
            setPadding(dp(indentDp), dp(12), dp(16), dp(12))
            addView(icon(iconDrawable, 26, tint))
            val texts = vbox().apply {
                setPadding(dp(14), 0, 0, 0)
                addView(text(title, 17f).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
                if (!subtitle.isNullOrEmpty()) addView(text(subtitle, 12f, color = Ink.MUTED))
            }
            addView(texts, lp(0, WRAP, 1f))
            pressable()
        }
    }

    private fun iconButton(res: Int, label: String, onClick: () -> Unit): View = with(ctx) {
        FrameLayout(ctx).apply {
            contentDescription = label
            setPadding(dp(10), dp(10), dp(10), dp(10))
            addView(icon(getDrawable(res), 26))
            pressable()
            setOnClickListener { onClick() }
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
        const val DOCK_COLUMNS = 5
        val io = Executors.newSingleThreadExecutor()
    }
}
