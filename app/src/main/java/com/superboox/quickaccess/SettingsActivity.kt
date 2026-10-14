package com.superboox.quickaccess

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Setup and customisation. Built in code, re-rendered after every change. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        list = vbox().apply { setPadding(0, 0, 0, dp(48)) }
        scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list)
        }
        setContentView(scroll)
        // First launch: the tour (it also asks for the permissions SuperBoox uses).
        if (savedInstanceState == null && !prefs.tourDone) tourStep = 0
        openRequestedPage(intent)
        runCatching { rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuResult) }
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        runCatching { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuResult) }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRequestedPage(intent)
    }

    /** The panel can send the user straight to a page (Advanced access). */
    private fun openRequestedPage(intent: Intent?) {
        intent?.getStringExtra(EXTRA_PAGE)?.let { name -> runCatching { Page.valueOf(name) }.getOrNull() }?.let { page = it }
        // From the panel's Rename: open that preset's edit dialog.
        intent?.getStringExtra(EXTRA_EDIT_PRESET)?.let { id ->
            intent.removeExtra(EXTRA_EDIT_PRESET)
            QuickControls.myPresets(prefs).firstOrNull { it.id == id }?.let { p -> list.post { editLightPreset(p, backToPanel = true) } }
        }
    }

    private val shizukuResult = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code != AdvancedAccess.REQUEST_CODE) return@OnRequestPermissionResultListener
        val ok = result == android.content.pm.PackageManager.PERMISSION_GRANTED
        DebugLog.i("adb", "Shizuku permission ${if (ok) "granted" else "denied"}")
        if (ok) AdvancedAccess.markUsed(prefs)
        runOnUiThread { render() }
    }

    override fun onResume() {
        super.onResume()
        current = java.lang.ref.WeakReference(this)
        rebuild()
    }


    override fun onPause() {
        // The live preview and the black strip only live while their page is on screen.
        QuickAccessService.instance?.apply { hidePreview(); stripPreview = false }
        super.onPause()
    }

    // ---- Live preview ---------------------------------------------------------------------

    /** The real panel is shown beside Look & size or Clipboard (after a change there, or on request). */
    private var previewOn = false

    private fun previewable() = page == Page.LOOK || page == Page.CLIPBOARD

    /** Shows (or hides) the real panel and the black strip for this page, and keeps this page clear of the panel. */
    private fun updatePreview() {
        val svc = QuickAccessService.instance
        val touring = tourStep != null && page == Page.HOME
        svc?.stripPreview = page == Page.GESTURE || (touring && tourStep == TOUR_TRY)
        if (touring && tourStep in listOf(TOUR_PANEL, TOUR_CLIPBOARD, TOUR_ADVANCED) && svc != null) {
            // The real panel beside the page: as it is, open on the clipboard, or on Front Light's presets.
            svc.showPreview(clipboard = tourStep == TOUR_CLIPBOARD, lightPresets = tourStep == TOUR_ADVANCED)
            scroll.post { insetForPreview(svc) }
            return
        }
        if (svc == null || !previewOn || !previewable() || touring) {
            svc?.hidePreview()
            scroll.setPadding(0, 0, 0, 0)
            return
        }
        svc.showPreview(clipboard = page == Page.CLIPBOARD)
        scroll.post { insetForPreview(svc) }
    }

    /**
     * Moves this page's content off the side the panel covers, measured on the
     * screen as it is now (portrait or landscape, full screen or split). Keeps at
     * least 280dp for the settings themselves; past that the panel overlaps.
     */
    private fun insetForPreview(svc: QuickAccessService) {
        if (!svc.isPreviewShowing) return scroll.setPadding(0, 0, 0, 0)
        val loc = IntArray(2).also(scroll::getLocationOnScreen)
        val left = loc[0]
        val right = left + scroll.width
        val panelW = svc.previewWidth()
        val screenW = svc.screenWidth()
        val room = maxOf(0, scroll.width - dp(280))
        if (prefs.edge == Edge.RIGHT) {
            val covered = (right - (screenW - panelW)).coerceIn(0, room)
            scroll.setPadding(0, 0, covered, 0)
        } else {
            val covered = (panelW - left).coerceIn(0, room)
            scroll.setPadding(covered, 0, 0, 0)
        }
    }

    /** Under a previewable page's settings: show or hide the real panel. */
    private fun previewButton(): View = buttonRow(
        (if (previewOn) "Hide panel preview" else "Preview the panel") to {
            previewOn = !previewOn
            render()
        },
    )

    /** The settings pages: Home lists the others. */
    private enum class Page(val title: String) {
        HOME("SuperBoox"), GESTURE("Gesture"), LOOK("Look & size"), ITEMS("Quick Access items"),
        CONTROLS("Quick Controls"), APPS("Apps at the bottom"), RECENT("Recent files"), CLIPBOARD("Clipboard"),
        TROUBLESHOOTING("Troubleshooting"), BACKUP("Backup & restore"), ADVANCED_ACCESS("Advanced access"), ADVANCED("Advanced"),
        DEVICE("Your device"),
    }

    private var page = Page.HOME

    /** [MainGuard] is replacing this screen after an error. */
    private var recovering = false

    private val renderNow = Runnable { rebuild() }

    /**
     * Rebuilds the page on the next turn of the main loop rather than at once. Most calls come from
     * a tap on a view inside [list]: emptying [list] while Android is still handling that tap (and
     * telling accessibility services about it) left a hole in it that crashed the next layout
     * (build 65 report, ViewGroup.dispatchCollectViewAttributes). Several calls in a row rebuild once.
     */
    private fun render() {
        list.removeCallbacks(renderNow)
        list.post(renderNow)
    }

    private fun rebuild() {
        list.removeCallbacks(renderNow)
        val y = scroll.scrollY
        list.removeAllViews()
        if (tourStep != null && page == Page.HOME) {
            renderTour()
            updatePreview()
            return
        }
        if (page == Page.HOME) {
            statusSection()
            homeMenu()
        } else {
            pageTitle(page.title)
            list.addView(View(this), lp(MATCH, dp(4)))
            when (page) {
                Page.GESTURE -> gestureSection()
                Page.LOOK -> lookSection()
                Page.ITEMS -> pinsSection()
                Page.CONTROLS -> quickControlsSection()
                Page.APPS -> dockSection()
                Page.RECENT -> recentSection()
                Page.CLIPBOARD -> clipboardSection()
                Page.TROUBLESHOOTING -> troubleshootingSection()
                Page.BACKUP -> backupSection()
                Page.ADVANCED_ACCESS -> advancedAccessSection()
                Page.ADVANCED -> advancedSection()
                Page.DEVICE -> deviceSection()
                Page.HOME -> Unit
            }
        }
        scroll.post { scroll.scrollTo(0, y) }
        updatePreview()
    }

    private fun open(to: Page) {
        if (to != page) previewOn = false
        if (to != page) DebugLog.i("settings", "page ${to.name}")
        page = to
        scroll.scrollTo(0, 0)
        render()
    }

    @Deprecated("Platform Activity API; fine without AndroidX.")
    override fun onBackPressed() {
        when {
            page != Page.HOME -> open(Page.HOME)
            tourStep != null && tourStep!! > 0 -> { tourStep = tourStep!! - 1; render() }
            else -> super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(K_PAGE, page.name)
        outState.putBoolean(K_PREVIEW, previewOn)
        tourStep?.let { outState.putInt(K_TOUR, it) }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        page = savedInstanceState.getString(K_PAGE)?.let { runCatching { Page.valueOf(it) }.getOrNull() } ?: Page.HOME
        // Rotating keeps the preview; it's redrawn at the new size.
        previewOn = savedInstanceState.getBoolean(K_PREVIEW)
        tourStep = savedInstanceState.getInt(K_TOUR, -1).takeIf { it >= 0 }
    }

    /** A section page's title bar: ‹ back to Home, then the name, over a heavy rule. */
    private fun pageTitle(title: String) {
        list.addView(hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(10))
            addView(inkIconButton(R.drawable.ic_chevron_left, "Back", 44) { open(Page.HOME) })
            addView(text(title, 28f, bold = true).apply { setPadding(dp(14), 0, 0, 0) }, lp(0, WRAP, 1f))
        })
        list.addView(divider(3))
    }

    private fun homeMenu() {
        val controls = QuickControls.selected(prefs)
        val dock = prefs.dock
        val apps = dock.count { it.id != Prefs.PREVIOUS_APP }
        val folder = prefs.recentFolderUri
        list.addView(heading("Opening the panel"))
        list.addView(card(listOf(
            menuRow(R.drawable.ic_qc_touch, Page.GESTURE,
                if (prefs.handleEnabled) "${prefs.edge.name.lowercase().replaceFirstChar { it.uppercase() }} edge · ${prefs.trigger.title}" else "Edge gesture off"),
            menuRow(R.drawable.ic_panel, Page.LOOK,
                "${prefs.panelWidthPct}% wide · ${prefs.menuSize.title} menu · ${if (prefs.rememberPages) "Remembers last page" else "Starts on page 1"}"),
        )))
        list.addView(heading("In the panel"))
        list.addView(card(listOf(
            menuRow(R.drawable.ic_clock, Page.RECENT,
                if (folder == null) "Off" else "Folder: ${Launcher.displayName(this, Uri.parse(folder))}"),
            menuRow(R.drawable.ic_toggle, Page.CONTROLS,
                if (prefs.showSystemActions) "On · ${controls.size} controls" else "Off"),
            menuRow(R.drawable.ic_star, Page.ITEMS, prefs.pins.size.let { if (it == 1) "1 item" else "$it items" }),
            menuRow(R.drawable.ic_apps, Page.APPS,
                (if (apps == 1) "1 app" else "$apps apps") + if (prefs.previousAppInDock) " · Previous app included" else ""),
            menuRow(R.drawable.ic_clipboard, Page.CLIPBOARD, if (!prefs.clipShowButton) "Off" else
                "On · ${ClipHistory.list(this).size} clips · " + if (prefs.clipCatchMode == Prefs.CLIP_EVERY) "every copy" else "saved when the panel opens"),
        )))
        list.addView(heading("Help"))
        list.addView(card(listOf(
            actionRow(R.drawable.ic_star, "Show the tour again", "A quick walk-through, and the permissions SuperBoox uses") {
                tourStep = 0
                render()
            },
            menuRow(R.drawable.ic_check, Page.DEVICE, Devices.summary()),
            menuRow(R.drawable.ic_bug, Page.TROUBLESHOOTING, "Copy or share a debug report"),
            menuRow(R.drawable.ic_shield, Page.ADVANCED_ACCESS, when {
                AdvancedAccess.independentPresets(prefs) -> "Independent Front Light presets on"
                AdvancedAccess.allowed() -> "Connected"
                AdvancedAccess.restartedSinceUse(prefs) -> "Off since the restart · start Shizuku again"
                else -> "Off · unlocks independent Front Light presets"
            }),
            menuRow(R.drawable.ic_save, Page.BACKUP, lastBackup()?.let { "Last backup: $it" } ?: "Save your settings to a file"),
            menuRow(R.drawable.ic_settings, Page.ADVANCED, "BOOX Notes package"),
        )))
        list.addView(View(this), lp(MATCH, dp(24)))
    }

    // ---- Drag to reorder ------------------------------------------------------------------

    /**
     * ≡ handle for a reorderable [row]: press and drag it up or down; the row
     * follows the finger, lifted on a bordered card, and is moved when let go.
     * The list only redraws on drop, to keep e-ink ghosting down.
     */
    private fun dragHandle(row: View, index: Int, count: Int, onMove: (Int, Int) -> Unit): View =
        icon(getDrawable(R.drawable.ic_drag), 26).apply {
            contentDescription = "Drag to move"
            setPadding(dp(8), dp(10), dp(8), dp(10))
            var startY = 0f
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startY = e.rawY
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        // Lifted on a bordered card. No translationZ: a raised child makes Android reorder
                        // the list's drawing, which crashed the panel's drag (build 55) and this one (build 65).
                        row.background = outline(2, 8)
                    }
                    android.view.MotionEvent.ACTION_MOVE -> row.translationY = e.rawY - startY
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        val step = (row.height + dp(1)).coerceAtLeast(1)
                        val to = (index + Math.round(row.translationY / step)).coerceIn(0, count - 1)
                        row.translationY = 0f
                        if (e.actionMasked == android.view.MotionEvent.ACTION_UP && to != index) onMove(index, to)
                        render()
                    }
                }
                true
            }
        }

    // ---- Front Light: your presets --------------------------------------------------------

    private fun myLightPresets() {
        list.addView(heading("Front Light · your presets"))
        if (!AdvancedAccess.independentPresets(prefs)) {
            list.addView(card(listOf(
                cardText(if (AdvancedAccess.restartedSinceUse(prefs) && prefs.independentPresets) "Advanced access is off since the tablet restarted: start Shizuku again to use these."
                    else "Your presets need Advanced access (on-device ADB, no computer), so they can set the light without changing BOOX's Custom."),
                buttonRow("Advanced access" to { open(Page.ADVANCED_ACCESS) }),
            )))
            list.addView(View(this), lp(MATCH, dp(10)))
        }
        val mine = QuickControls.myPresets(prefs)
        val rows = mutableListOf<View>()
        if (mine.isEmpty()) rows += cardText("None yet. Add one here, or with + New on page 2 of the Front Light presets in the panel.")
        mine.forEachIndexed { i, p ->
            rows += hbox().apply {
                setPadding(dp(6), dp(8), dp(12), dp(8))
                gravity = Gravity.CENTER_VERTICAL
                addView(dragHandle(this, i, mine.size) { from, to -> QuickControls.saveMyPresets(prefs, mine.move(from, to)) })
                addView(android.widget.FrameLayout(context).apply {
                    background = outline(2, 20)
                    addView(icon(getDrawable(R.drawable.ic_qc_light), 20), android.widget.FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                }, lp(dp(38), dp(38)))
                addView(vbox().apply {
                    setPadding(dp(12), 0, 0, 0)
                    addView(text(p.name, 17f, medium = true))
                    addView(text("Brightness ${p.levels.brightness} · Warmth ${p.levels.warmth}", 13f, color = Ink.MUTED))
                }, lp(0, WRAP, 1f))
                addView(inkIconButton(R.drawable.ic_edit, "Edit", 44) { editLightPreset(p) })
                addView(inkIconButton(R.drawable.ic_arrow_up, "Move up", 44, i > 0) { QuickControls.saveMyPresets(prefs, mine.move(i, i - 1)); render() })
                addView(inkIconButton(R.drawable.ic_arrow_down, "Move down", 44, i < mine.size - 1) { QuickControls.saveMyPresets(prefs, mine.move(i, i + 1)); render() })
                addView(inkIconButton(R.drawable.ic_delete, "Remove", 44) { QuickControls.saveMyPresets(prefs, mine.filterNot { it.id == p.id }); render() })
            }
        }
        rows += buttonRow("+ New preset" to { editLightPreset(null) })
        list.addView(card(rows))
        list.addView(note("Shown on page 2 of the Front Light presets in the panel. BOOX only has Bright, Soft and Custom, so yours borrow Custom while they're on; picking Custom again puts your own BOOX Custom back."))
    }

    /** Name and levels; the light follows the sliders while the dialog is open, and goes back on Cancel. */
    private fun editLightPreset(existing: QuickControls.LightPreset?, backToPanel: Boolean = false) {
        val service = QuickAccessService.instance
        // The preview borrows Custom as this preset, so your BOOX Custom is never overwritten.
        val id = existing?.id ?: QuickControls.newPresetId()
        val before = service?.frontLightPreset()
        val beforeMine = QuickControls.activeMine(prefs)
        var levels = existing?.levels ?: service?.customLevels() ?: QuickControls.Levels(16, 8)
        var previewed = false
        val name = field("Name", existing?.name ?: "Preset ${QuickControls.myPresets(prefs).size + 1}")
        // Only with Advanced access: without it, presets can't change the light.
        val preview = Runnable { if (AdvancedAccess.independentPresets(prefs)) { previewed = true; service?.frontLightLevels(levels, id) } }
        fun bar(label: String, value: Int, onChange: (Int) -> Unit): View = vbox().apply {
            val title = text("$label: $value", 15f, bold = true).apply { setPadding(dp(4), dp(12), 0, 0) }
            addView(title)
            lateinit var seek: SeekBar
            fun step(sign: String, by: Int) = android.widget.FrameLayout(context).apply {
                contentDescription = "$label ${if (by < 0) "down" else "up"}"
                addView(text(sign, 20f, bold = true).apply { gravity = Gravity.CENTER },
                    android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
                pressable()
                background = ovalButton()
                setOnClickListener { seek.progress = (seek.progress + by).coerceIn(0, QuickControls.LEVEL_MAX) }
            }
            val row = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(step("−", -1), lp(dp(40), dp(40)))
            seek = SeekBar(context).apply {
                max = QuickControls.LEVEL_MAX
                progress = value
                progressTintList = android.content.res.ColorStateList.valueOf(Ink.INK)
                thumbTintList = android.content.res.ColorStateList.valueOf(Ink.INK)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        title.text = "$label: $p"
                        onChange(p)
                        list.removeCallbacks(preview)
                        list.postDelayed(preview, 800)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar) = Unit
                })
            }
            row.addView(seek, lp(0, WRAP, 1f))
            row.addView(step("+", 1), lp(dp(40), dp(40)))
            addView(row, lp(MATCH, WRAP))
        }
        val body = vbox().apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(name)
            addView(bar("Brightness", levels.brightness) { levels = levels.copy(brightness = it) })
            addView(bar("Warmth", levels.warmth) { levels = levels.copy(warmth = it) })
            addView(text(if (service != null && AdvancedAccess.independentPresets(prefs)) "Try it: the light changes as you slide." else "Turn on Advanced access to try it out as you slide.", 13f, color = Ink.MUTED)
                .apply { setPadding(dp(4), dp(8), 0, 0) })
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New preset" else "Edit preset")
            .setView(body)
            .setNegativeButton("Cancel") { _, _ ->
                list.removeCallbacks(preview)
                // Put the light back as it was.
                if (previewed && service != null) {
                    val wasMine = QuickControls.myPresets(prefs).firstOrNull { it.id == beforeMine && before == "Custom" }
                    if (wasMine != null) service.frontLightLevels(wasMine.levels, wasMine.id) else service.frontLight(before ?: "Custom")
                }
            }
            .setPositiveButton("Save") { _, _ ->
                list.removeCallbacks(preview)
                val label = name.text.toString().trim().ifEmpty { existing?.name ?: "Preset" }
                val mine = QuickControls.myPresets(prefs)
                QuickControls.saveMyPresets(prefs,
                    if (existing == null) mine + QuickControls.LightPreset(id, label, levels)
                    else mine.map { if (it.id == existing.id) it.copy(name = label, levels = levels) else it })
                render()
            }
            // Opened from the panel's Rename: go back to the panel when done.
            .setOnDismissListener { if (backToPanel) { finish(); QuickAccessService.instance?.showPanel() } }
            .show()
    }

    // ---- Advanced access (on-device ADB through Shizuku) -----------------------------------

    private fun advancedAccessSection() {
        val installed = AdvancedAccess.installed(this)
        val running = AdvancedAccess.running()
        val allowed = AdvancedAccess.allowed()
        val restarted = !running && AdvancedAccess.restartedSinceUse(prefs)
        list.addView(View(this), lp(MATCH, dp(12)))
        list.addView(hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = if (allowed) android.graphics.drawable.GradientDrawable().apply { setColor(Ink.INK); cornerRadius = dp(8).toFloat() } else outline(2, 8)
            val fg = if (allowed) Ink.PAPER else Ink.INK
            addView(icon(getDrawable(R.drawable.ic_shield), 30).apply { imageTintList = android.content.res.ColorStateList.valueOf(fg) })
            addView(vbox().apply {
                setPadding(dp(12), 0, 0, 0)
                addView(text(when {
                    allowed -> "Connected through Shizuku"
                    restarted -> "Off since the tablet restarted"
                    else -> "Not connected"
                }, 17f, bold = true, color = fg))
                addView(text(when {
                    allowed -> "ADB-level options below are available."
                    restarted -> "Start Shizuku again (step 2) to turn it back on."
                    else -> "Optional. Unlocks features Android only allows over ADB. No computer needed."
                }, 13f, color = if (allowed) Ink.PAPER else Ink.MUTED))
            }, lp(0, WRAP, 1f))
            layoutParams = lp(MATCH, WRAP).apply { marginStart = dp(12); marginEnd = dp(12) }
        })
        if (!allowed) {
            list.addView(heading("Set up once · on this tablet"))
            list.addView(card(listOf(
                stepRow(1, installed, "Install Shizuku", "Free and open source. It runs ADB commands for apps you allow, right on the tablet.",
                    if (installed) null else "Get Shizuku" to {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AdvancedAccess.SHIZUKU_RELEASES))) }
                            .onFailure { toast("Open ${AdvancedAccess.SHIZUKU_RELEASES} in a browser") }
                    }),
                stepRow(2, running, "Start Shizuku with Wireless debugging",
                    "Turn on Developer options and Wireless debugging. In Shizuku, tap Pair and enter the code Wireless debugging shows, then Start. Needs Wi‑Fi, not a computer. Again after every restart.",
                    if (!installed || running) null else "Open Shizuku" to {
                        packageManager.getLaunchIntentForPackage(AdvancedAccess.SHIZUKU_PACKAGE)?.let { startActivity(it) } ?: toast("Shizuku isn't installed")
                    }),
                stepRow(3, allowed, "Allow SuperBoox", "Shizuku asks once.",
                    if (!running) null else "Connect" to { AdvancedAccess.requestPermission() }),
            )))
        }
        list.addView(heading("Front light"))
        val method = AdvancedAccess.lightMethod(prefs)
        val testText = when {
            !prefs.lightTestRun -> "Checks that the light can be set directly. Tries SuperBoox on its own first, then Shizuku."
            prefs.lightTestPassed && method == AdvancedAccess.METHOD_APP -> "Passed: SuperBoox sets the light by itself, so Shizuku isn't needed for this."
            prefs.lightTestPassed -> "Passed through Shizuku: it needs to be running (started again after a restart)."
            else -> "Didn't pass. Run it again after connecting Shizuku."
        }
        val usable = prefs.lightTestPassed && (method == AdvancedAccess.METHOD_APP || allowed)
        list.addView(card(listOf(
            hbox().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(12), dp(10))
                addView(vbox().apply {
                    addView(text("Front light test", 17f, medium = true))
                    addView(text(testText, 13f, color = Ink.MUTED))
                }, lp(0, WRAP, 1f))
                addView(inkButton(if (prefs.lightTestRun) "Run again" else "Run test", 14f) { lightTest() }.apply { setPadding(dp(14), 0, dp(14), 0) })
            },
            CheckBox(this).apply {
                buttonTintList = android.content.res.ColorStateList.valueOf(Ink.INK)
                text = "Independent Front Light presets\nYour presets set the light directly. BOOX's Bright, Soft, Custom and Off stay as you set them."
                textSize = 16f
                setPadding(dp(8), dp(10), dp(12), dp(10))
                isEnabled = usable
                isChecked = prefs.independentPresets && prefs.lightTestPassed
                setOnCheckedChangeListener { _, on -> prefs.independentPresets = on; if (on) AdvancedAccess.markUsed(prefs); render() }
            },
            hbox().apply {
                setPadding(dp(16), dp(12), dp(16), dp(12))
                addView(text("What changes?", 16f, bold = true), lp(0, WRAP, 1f))
                addView(text(if (independentGuideOpen) "▴" else "▾", 16f, bold = true))
                pressable()
                setOnClickListener { independentGuideOpen = !independentGuideOpen; render() }
            },
        ) + if (independentGuideOpen) listOf(independentGuide()) else emptyList()))
        list.addView(note(if (allowed) "To remove access, revoke SuperBoox in Shizuku. More options that need ADB will appear here as they're added."
            else "Without it, SuperBoox works as before; your own Front Light presets stay unavailable."))
    }

    private fun stepRow(n: Int, done: Boolean, title: String, detail: String, action: Pair<String, () -> Unit>?): View = hbox().apply {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(text(if (done) "✓" else "$n", 15f, bold = true, color = if (done) Ink.PAPER else Ink.INK).apply {
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (done) Ink.INK else Ink.PAPER)
                setStroke(dp(2), Ink.INK)
            }
        }, lp(dp(30), dp(30)))
        addView(vbox().apply {
            setPadding(dp(12), 0, 0, 0)
            addView(text(title, 17f, medium = true))
            addView(text(detail, 13f, color = Ink.MUTED))
            if (action != null) addView(inkButton(action.first, 14f) { action.second() }, lp(WRAP, WRAP).apply { topMargin = dp(8) })
        }, lp(0, WRAP, 1f))
    }

    /**
     * Dims the light directly for 3 s (SuperBoox itself first, else through
     * Shizuku), puts it back, and asks whether it changed. A yes records which
     * way worked; presets then use it.
     */
    private fun lightTest() {
        val before = AdvancedAccess.currentLight(this)
        val dim = QuickControls.Levels(if ((before?.brightness ?: 32) > 10) 4 else 24, before?.warmth ?: 0)
        AlertDialog.Builder(this)
            .setTitle("Front light test")
            .setMessage("SuperBoox will set the light to ${dim.brightness} for 3 seconds, then put it back. Watch the screen.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start") { _, _ ->
                AdvancedAccess.setLight(dim) { method ->
                    DebugLog.i("light-test", "direct write ${method ?: "failed both ways"} (light was $before)")
                    if (method == null) {
                        prefs.lightTestPassed = false
                        AdvancedAccess.setLightMethod(prefs, null)
                        toast(if (AdvancedAccess.allowed()) "Couldn't change the light either way" else "SuperBoox can't change the light by itself; connect Shizuku and try again")
                        render()
                        return@setLight
                    }
                    list.postDelayed({
                        before?.let { AdvancedAccess.setLight(it, method) }
                        AlertDialog.Builder(this)
                            .setTitle("Did the light change?")
                            .setNegativeButton("No") { _, _ ->
                                prefs.lightTestPassed = false; prefs.independentPresets = false; AdvancedAccess.setLightMethod(prefs, null)
                                DebugLog.i("light-test", "user: did NOT change ($method)"); render()
                            }
                            .setPositiveButton("Yes, it dimmed") { _, _ ->
                                prefs.lightTestPassed = true; AdvancedAccess.setLightMethod(prefs, method); AdvancedAccess.markUsed(prefs)
                                DebugLog.i("light-test", "user: DID change ($method)"); render()
                            }
                            .show()
                    }, 3_000)
                }
            }
            .show()
    }

    // ---- Backup & restore -----------------------------------------------------------------

    private fun lastBackup(): String? = prefs.sp.getString(Prefs.K_LAST_BACKUP, null)

    private fun backupSection() {
        list.addView(heading("Back up"))
        list.addView(card(listOf(
            cardText("Saves all your SuperBoox settings to one small file you choose where to keep: Downloads, a cloud folder or a USB drive."),
            buttonRow("Back up now" to {
                val name = "superboox-backup-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.json"
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, name), REQ_BACKUP)
            }),
        ) + listOfNotNull(lastBackup()?.let { cardText("Last backup: $it") })))
        list.addView(heading("Restore"))
        list.addView(card(listOf(
            cardText("Pick a backup file. It replaces your current settings, after asking first."),
            buttonRow("Restore from file…" to {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_RESTORE)
            }),
        )))
        list.addView(heading("What's included"))
        list.addView(card(listOf(cardText(
            "✓ Gesture, look & size\n✓ Quick Access items (notes, books, apps, links)\n✓ Quick Controls and Front Light presets\n✓ Apps at the bottom\n\n" +
                "Not included: the troubleshooting log, recents, and file or folder access. Files and folders pinned with the file " +
                "picker need picking again after a reinstall; notes and NeoReader books work straight away.",
        ))))
    }

    private fun writeBackup(uri: Uri) {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        runCatching {
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Backup.export(prefs, version).toByteArray()) }
        }.onSuccess {
            val stamp = "${Launcher.displayName(this, uri)} · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date())}"
            prefs.sp.edit().putString(Prefs.K_LAST_BACKUP, stamp).apply()
            DebugLog.i("backup", "saved")
            toast("Backup saved")
        }.onFailure {
            DebugLog.e("backup", "save failed", it)
            toast("Couldn't save the backup: ${it.message}")
        }
    }

    private fun readBackup(uri: Uri) {
        val snapshot = runCatching {
            val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            Backup.parse(text)
        }.getOrElse {
            toast(it.message ?: "Couldn't read that file")
            return
        }
        val made = if (snapshot.created > 0) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            .format(java.util.Date(snapshot.created)) else "an unknown date"
        AlertDialog.Builder(this)
            .setTitle("Restore this backup?")
            .setMessage("Made $made${if (snapshot.app.isNotEmpty()) " with ${snapshot.app}" else ""}.\n\n${snapshot.summary}.\n\nYour current settings will be replaced.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore") { _, _ ->
                Backup.restore(prefs, snapshot)
                toast("Settings restored")
                open(Page.HOME)
            }
            .show()
    }

    /** One choice of several: a round radio, a title and what it does. */
    private fun radioRow(title: String, detail: String, selected: Boolean, onPick: () -> Unit): View = hbox().apply {
        setPadding(dp(16), dp(12), dp(16), dp(12))
        addView(android.widget.RadioButton(context).apply {
            buttonTintList = android.content.res.ColorStateList.valueOf(Ink.INK)
            isChecked = selected
            isClickable = false
            isFocusable = false
        })
        addView(vbox().apply {
            setPadding(dp(8), dp(4), 0, 0)
            addView(text(title, 17f, medium = true))
            addView(text(detail, 13f, color = Ink.MUTED))
        }, lp(0, WRAP, 1f))
        pressable()
        setOnClickListener { if (!selected) onPick() }
    }

    /** Like [menuRow], for an action rather than a page. */
    private fun actionRow(iconRes: Int, title: String, summary: String, onClick: () -> Unit): View = hbox().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        contentDescription = "$title: $summary"
        addView(android.widget.FrameLayout(context).apply {
            background = outline(2, 20)
            addView(icon(getDrawable(iconRes), 22), android.widget.FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        }, lp(dp(40), dp(40)))
        addView(vbox().apply {
            setPadding(dp(14), 0, dp(8), 0)
            addView(text(title, 18f, medium = true))
            addView(text(summary, 13f, color = Ink.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        }, lp(0, WRAP, 1f))
        addView(text("›", 26f, bold = true))
        pressable()
        setOnClickListener { onClick() }
    }

    // ---- First-run tour -------------------------------------------------------------------

    /** The tour's step while it's showing (null: not touring). */
    private var tourStep: Int? = null
    private var tourPanelOpensAtStart = 0
    private val tourPoll = android.os.Handler(android.os.Looper.getMainLooper())

    private fun endTour() {
        tourStep = null
        prefs.tourDone = true
        tourPoll.removeCallbacksAndMessages(null)
        QuickAccessService.instance?.apply { hidePreview(); stripPreview = false }
        scroll.setPadding(0, 0, 0, 0)
        render()
    }

    private fun tourGo(to: Int) {
        tourStep = to
        if (to == TOUR_TRY) tourPanelOpensAtStart = QuickAccessService.instance?.panelOpens ?: 0
        scroll.scrollTo(0, 0)
        render()
    }

    /** A permission row: what it's for, ✓ when granted, else a button to Android's switch. */
    private fun permissionRow(title: String, detail: String, granted: Boolean, open: () -> Unit): View = vbox().apply {
        setPadding(dp(16), dp(12), dp(16), dp(12))
        addView(hbox().apply {
            addView(text(title, 17f, medium = true), lp(0, WRAP, 1f))
            addView(text(if (granted) "✓ On" else "Off", 14f, bold = true).apply {
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = outline(2, 12)
            })
        })
        addView(text(detail, 13.5f, color = Ink.MUTED).apply { setPadding(0, dp(4), 0, dp(6)) })
        if (!granted) addView(inkButton("Turn on") { open() }, lp(MATCH, WRAP))
    }

    private fun ignoringBattery(): Boolean =
        (getSystemService(android.os.PowerManager::class.java))?.isIgnoringBatteryOptimizations(packageName) == true

    private fun renderTour() {
        val step = tourStep ?: return
        val svc = QuickAccessService.instance
        list.addView(hbox().apply {
            setPadding(dp(20), dp(18), dp(16), dp(6))
            addView(text("STEP ${step + 1} OF $TOUR_STEPS", 14f, bold = true).apply { letterSpacing = 0.05f }, lp(0, WRAP, 1f))
            if (step < TOUR_STEPS - 1) addView(inkButton("Skip tour", 15f) { endTour() })
        })
        fun title(t: String) = list.addView(text(t, 28f, bold = true).apply { setPadding(dp(20), dp(8), dp(20), dp(8)) })
        fun para(t: String) = list.addView(text(t, 17f).apply { setPadding(dp(20), 0, dp(20), dp(12)); setLineSpacing(0f, 1.2f) })
        when (step) {
            0 -> {
                title("Welcome to SuperBoox")
                para("A sidebar for your BOOX, like Supernote's: your notes and books, quick switches, a clipboard and your apps, one swipe away from anything you're doing.")
                para("This short tour shows you around and asks for the permissions SuperBoox uses. You can come back to it any time from Settings → Help.")
                list.addView(card(listOf(
                    cardText("Your tablet: ${Devices.summary()}\n${Devices.verdict()} " +
                        if (Devices.known() == null) "Most of it should work; if something doesn't, a debug report (Settings → Troubleshooting) shows us what your BOOX does differently."
                        else "Settings → Your device lists what's confirmed."),
                )))
            }
            1 -> {
                title("Turn SuperBoox on")
                para("SuperBoox works through Android's accessibility settings: that's how it adds the edge strip and the panel over other apps, and opens your notes for you. It's the one thing it can't work without.")
                list.addView(card(listOf(
                    permissionRow("SuperBoox service", "Android settings → Accessibility → SuperBoox Quick Access → On.", isServiceEnabled()) {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )))
                list.addView(note("SuperBoox only reads what's on screen to find your notes and switches. Nothing leaves the tablet."))
            }
            2 -> {
                title("Permissions")
                para("These are optional; each one unlocks something. Turn on what you'd like, now or later in Android settings.")
                list.addView(card(listOf(
                    permissionRow("Keep running in the background", "So BOOX doesn't freeze SuperBoox and the edge strip stops working.", ignoringBattery()) {
                        if (!Launcher.tryStart(this, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))))
                            Launcher.tryStart(this, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    },
                    permissionRow("Modify system settings", "Lets the Auto-rotate Quick Control switch rotation directly.", Settings.System.canWrite(this)) {
                        Launcher.tryStart(this, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
                    },
                    permissionRow("All files access", "Keeps every handwriting copy you make in BOOX Notes, with a preview, and lets you paste older ones. SuperBoox only uses BOOX's copy folder.", ClipHistory.hasFileAccess()) {
                        openAllFilesAccess()
                    },
                )))
            }
            TOUR_TRY -> {
                title("Open the panel")
                val opened = (svc?.panelOpens ?: 0) > tourPanelOpensAtStart
                para(if (svc == null) "Turn the SuperBoox service on first (step 2), then come back here."
                    else "Your swipe strip is shown in black on the ${prefs.edge.name.lowercase()} edge. ${prefs.trigger.title} along it to open the panel. Try it now.")
                list.addView(card(listOf(cardText(
                    if (opened) "✓ You opened it. Tap outside the panel to close it again."
                    else "Waiting for you to open the panel… Not working? Change the edge, size and gesture in Settings → Gesture.",
                ))))
                if (opened) list.addView(card(listOf(cardText(
                    "Two holds worth knowing: hold New Note for BOOX's create-note screen (name, layout, template), and hold Clipboard for its settings.",
                ))).apply { (layoutParams as ViewGroup.MarginLayoutParams).topMargin = dp(12) })
                // Notice the swipe without the user having to come back to this page.
                if (!opened && svc != null) tourPoll.postDelayed({ if (tourStep == TOUR_TRY && (QuickAccessService.instance?.panelOpens ?: 0) > tourPanelOpensAtStart) render() }, 1000)
            }
            TOUR_PANEL -> {
                title("Around the panel")
                para(if (svc != null) "The real panel is shown beside this page. Here's what each part does:" else "Here's what each part of the panel does:")
                listOf(
                    "Top bar" to "Clipboard, New Note (hold it for BOOX's new-note screen) and Settings.",
                    "Quick Controls" to "Tap to switch, hold for more. Front Light: hold for its presets.",
                    "Last opened" to "Your last note and document, one tap away.",
                    "Quick Access" to "Pinned notes, books and folders. Add pins what's open; hold the list to edit it. Recent lists recent files.",
                    "Apps" to "At the bottom, swipe for more.",
                ).forEachIndexed { i, (t, d) ->
                    list.addView(hbox().apply {
                        gravity = Gravity.TOP
                        setPadding(dp(20), dp(6), dp(20), dp(6))
                        addView(text("${i + 1}", 14f, bold = true, color = Ink.PAPER).apply {
                            gravity = Gravity.CENTER
                            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Ink.INK) }
                        }, lp(dp(26), dp(26)))
                        addView(text("$t. $d", 15.5f).apply { setPadding(dp(10), dp(2), 0, 0) }, lp(0, WRAP, 1f))
                    })
                }
            }
            TOUR_CLIPBOARD -> {
                title("Your clipboard")
                if (svc != null) para("The panel beside this page is open on your clipboard.")
                para("Tap Clipboard at the top of the panel to see what you've copied: text, links, pictures and handwriting from BOOX Notes. Tap or hold a clip to copy it again, paste it, share, pin or delete it.")
                list.addView(card(listOf(cardText("Choose what's kept in Settings → Clipboard. Copies are saved when the panel opens; catching every copy as it happens needs Advanced access (next step)."))))
            }
            TOUR_ADVANCED -> {
                title("Optional: Advanced access")
                if (svc != null) para("Beside this page: the Front Light presets (hold Front Light in the panel), where your own presets live.")
                para("A few extras need on-device ADB through the free Shizuku app (no computer): your own Front Light presets, and catching every copy as it happens. Everything else works without it.")
                list.addView(card(listOf(buttonRow("Set it up now" to { open(Page.ADVANCED_ACCESS) }))))
                list.addView(note("Back from Advanced access returns you to the tour."))
            }
            else -> {
                title("You're set")
                para("Make it yours in Settings: pin your notes, choose Quick Controls and apps, the panel's size and edge.")
                list.addView(card(listOf(cardText("Lost? Settings → Help → Show the tour again, or Troubleshooting for a debug report."))))
            }
        }
        list.addView(View(this), lp(MATCH, dp(16)))
        list.addView(divider(2))
        list.addView(hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(20))
            addView(inkButton("‹ Previous") { if (step > 0) tourGo(step - 1) }.apply { isEnabled = step > 0; alpha = if (step > 0) 1f else 0.4f }, lp(dp(140), WRAP))
            addView(hbox().apply {
                gravity = Gravity.CENTER
                repeat(TOUR_STEPS) { k ->
                    addView(View(context).apply {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(if (k == step) Ink.INK else Ink.PAPER)
                            setStroke(dp(2), Ink.INK)
                        }
                    }, lp(dp(12), dp(12)).apply { marginStart = dp(4); marginEnd = dp(4) })
                }
            }, lp(0, WRAP, 1f))
            addView(inkButton(if (step == TOUR_STEPS - 1) "Done" else "Next ›") {
                if (step == TOUR_STEPS - 1) endTour() else tourGo(step + 1)
            }.apply {
                setTextColor(Ink.PAPER)
                background = android.graphics.drawable.GradientDrawable().apply { setColor(Ink.INK); cornerRadius = dp(6).toFloat() }
            }, lp(dp(140), WRAP))
        })
    }

    /** Home row: round icon, the page's name with what's set under it, and ›. */
    private fun menuRow(iconRes: Int, to: Page, summary: String): View = hbox().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        contentDescription = "${to.title}: $summary"
        addView(android.widget.FrameLayout(context).apply {
            background = outline(2, 20)
            addView(icon(getDrawable(iconRes), 22), android.widget.FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        }, lp(dp(40), dp(40)))
        addView(vbox().apply {
            setPadding(dp(14), 0, dp(8), 0)
            addView(text(to.title, 18f, medium = true))
            addView(text(summary, 13f, color = Ink.MUTED).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }, lp(0, WRAP, 1f))
        addView(text("›", 26f, bold = true))
        pressable()
        setOnClickListener { open(to) }
    }

    // ---- Sections -------------------------------------------------------------------------

    private fun statusSection() {
        // White title bar with a heavy rule instead of a black action bar (e-ink).
        list.addView(text("SuperBoox", 28f, bold = true).apply { setPadding(dp(16), dp(20), dp(16), dp(10)) })
        list.addView(divider(3))
        list.addView(View(this), lp(MATCH, dp(8)))
        list.addView(paragraph("A Supernote-style Quick Access panel for BOOX. Swipe from the screen edge on any page to open it."))

        val running = QuickAccessService.instance != null
        val on = isServiceEnabled()
        val rows = mutableListOf<View>(
            text(when {
                running -> "● Service is running"
                on -> "◐ Turned on, but stopped"
                else -> "○ Service is off"
            }, 17f, bold = true).apply { setPadding(dp(16), dp(14), dp(16), dp(10)) },
        )
        when {
            running -> rows += buttonRow(
                "Open panel" to { QuickAccessService.instance?.showPanel() },
                "Restart service" to {
                    QuickAccessService.instance?.restart()
                    toast("SuperBoox restarted")
                    render()
                },
            )
            on -> {
                // Android keeps a service that stopped on an error off until it's switched off and on again.
                val crashed = prefs.sp.getLong(Prefs.K_LAST_CRASH, 0L).takeIf { it > 0 }
                rows += paragraph(
                    (if (crashed != null) "SuperBoox stopped after an error (${android.text.format.DateUtils.getRelativeTimeSpanString(crashed)}). " else "SuperBoox isn't running. ") +
                        "Android keeps it stopped until it's switched off and on again. " +
                        if (AdvancedAccess.allowed() || BootRestore.canSwitch(this)) "Restart does that for you." else "Restart opens its switch: turn it off, then on.",
                ).apply { setPadding(dp(16), dp(4), dp(16), dp(6)) }
                rows += buttonRow("Restart service" to { restartStoppedService() })
                if (crashed != null) rows += paragraph("A debug report (Help → Troubleshooting) shows what went wrong; please send it.")
                    .apply { setPadding(dp(16), 0, dp(16), dp(10)) }
            }
            else -> {
                rows += paragraph(
                    "Turn on \"SuperBoox Quick Access\" under Accessibility. On BOOX also open App Management for SuperBoox " +
                        "and allow it to run in the background so the system doesn't freeze it.",
                ).apply { setPadding(dp(16), dp(10), dp(16), dp(10)) }
                rows += buttonRow("Accessibility settings" to { openServiceSwitch() })
            }
        }
        if (on) rows += afterRestartRows()
        list.addView(card(rows))
    }

    /**
     * After a restart ([BootRestore]): the switch, and what SuperBoox can do about a service that
     * didn't come back: switch it off and on itself (WRITE_SECURE_SETTINGS), or else notify.
     */
    private fun afterRestartRows(): List<View> {
        val rows = mutableListOf(check("Turn the edge strip back on after a restart", prefs.restoreAfterRestart) {
            prefs.restoreAfterRestart = it
            DebugLog.i("boot", "restore after a restart ${if (it) "on" else "off"}")
            render()
        })
        if (!prefs.restoreAfterRestart) return rows
        val canSwitch = BootRestore.canSwitch(this)
        val canNotify = BootRestore.canNotify(this)
        rows += paragraph(when {
            canSwitch -> "If BOOX doesn't start SuperBoox after a restart, SuperBoox switches it off and on by itself."
            canNotify -> "If BOOX doesn't start SuperBoox after a restart, a notification tells you. " +
                "To have SuperBoox turn it back on by itself, allow it once" +
                if (AdvancedAccess.allowed()) " below." else " through Advanced access (Help → Advanced access), or over ADB."
            else -> "If BOOX doesn't start SuperBoox after a restart, SuperBoox can tell you with a notification, " +
                "or turn it back on by itself once allowed through Advanced access."
        }).apply { setPadding(dp(16), dp(2), dp(16), dp(6)) }
        val buttons = mutableListOf<Pair<String, () -> Unit>>()
        if (!canSwitch && AdvancedAccess.allowed()) buttons += "Allow" to {
            BootRestore.grantWithAdvancedAccess(this) { ok ->
                DebugLog.i("boot", "secure settings permission ${if (ok) "granted" else "not granted"} through Advanced access")
                toast(if (ok) "SuperBoox can turn itself back on now" else "Couldn't allow it through Shizuku")
                render()
            }
        }
        if (!canSwitch && !canNotify) buttons += "Allow notifications" to { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY) }
        if (buttons.isNotEmpty()) rows += buttonRow(*buttons.toTypedArray())
        return rows
    }

    /**
     * Switches the stopped service off and on: by itself with the secure settings permission or Advanced access (Android's own setting,
     * as its switch writes it), otherwise by opening SuperBoox's switch in Android's settings.
     */
    private fun restartStoppedService() {
        val cn = ComponentName(this, QuickAccessService::class.java)
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        if (BootRestore.canSwitch(this)) {
            DebugLog.i("service", "restarting by switching it off and on")
            return BootRestore.switchOffAndOn(this) { ok ->
                if (!ok) return@switchOffAndOn openServiceSwitch()
                toast("SuperBoox restarted")
                scroll.postDelayed({ render() }, 1500)
            }
        }
        if (!AdvancedAccess.allowed()) return openServiceSwitch()
        val ours = setOf(cn.flattenToString().lowercase(), cn.flattenToShortString().lowercase())
        val without = current.split(':').filter { it.isNotBlank() && it.lowercase() !in ours }.joinToString(":")
        val withUs = (without.split(':').filter { it.isNotBlank() } + cn.flattenToString()).joinToString(":")
        fun q(v: String) = "'" + v.replace("'", "") + "'"
        val off = if (without.isEmpty()) "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services ${q(without)}"
        DebugLog.i("service", "restarting through Advanced access")
        AdvancedAccess.shell("sh", "-c", "$off; sleep 1; settings put secure enabled_accessibility_services ${q(withUs)}; settings put secure accessibility_enabled 1") { code, out ->
            if (code != 0) {
                DebugLog.w("service", "restart failed ($code): $out")
                openServiceSwitch()
            } else {
                toast("SuperBoox restarted")
                scroll.postDelayed({ render() }, 1500)
            }
        }
    }

    /** SuperBoox's own switch in Android's accessibility settings (or the list, where that page can't be opened directly). */
    private fun openServiceSwitch() {
        val cn = ComponentName(this, QuickAccessService::class.java)
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, cn.flattenToString())
        if (runCatching { startActivity(details) }.isFailure) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun gestureSection() {
        list.addView(heading("Edge gesture"))
        list.addView(card(listOf(
            choiceRow("Screen edge", prefs.edge.name.lowercase().replaceFirstChar { it.uppercase() }) {
                pick("Screen edge", Edge.entries.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, prefs.edge.ordinal) {
                    prefs.edge = Edge.entries[it]
                }
            },
            choiceRow("Trigger", prefs.trigger.title) {
                pick("Trigger", Trigger.entries.map { it.title }, prefs.trigger.ordinal) { prefs.trigger = Trigger.entries[it] }
            },
            check("Edge gesture enabled", prefs.handleEnabled) { prefs.handleEnabled = it },
            check("Show a small marker on the edge", prefs.showHandle) { prefs.showHandle = it },
            slider("Touch strip width", prefs.handleWidthDp, 6, 48, "dp") { prefs.handleWidthDp = it },
            slider("Touch strip height", prefs.handleHeightPct, 10, 100, "% of screen") { prefs.handleHeightPct = it },
            slider("Touch strip position from top", prefs.handleTopPct, 0, 90, "%") { prefs.handleTopPct = it },
        )))
        list.addView(note("While this page is open, the swipe strip is drawn in black on the screen edge, at its real place and size. Try the swipe right here. " +
            "Elsewhere only the small marker shows: it stays thin, but the whole strip still responds to your swipe."))
        list.addView(
            note(
                "Tip: the \"Quick Access\" app icon opens the panel too. Assign it to a BOOX gesture or the Navigation Ball " +
                    "if you'd rather not reserve a strip of the screen edge. There's also a Quick Settings tile.",
            ),
        )
    }

    private fun lookSection() {
        list.addView(heading("Size"))
        list.addView(card(listOf(
            slider("Panel width", prefs.panelWidthPct, 25, 100, "% of screen") { prefs.panelWidthPct = it; previewOn = true; render() },
            choiceRow("Menu size", prefs.menuSize.title) {
                pick("Menu size", MenuSize.entries.map { it.title }, prefs.menuSize.ordinal) { prefs.menuSize = MenuSize.entries[it]; previewOn = true }
            },
            previewButton(),
        )))
        list.addView(note("Changing the width or menu size shows the real panel beside this page, at that size, so you can see it before opening the panel. It can't be tapped; hide it with the button or by leaving this page."))
        list.addView(heading("Icons"))
        list.addView(card(listOf(
            check("Use apps' own icons for pinned apps", prefs.appOwnIcons) { prefs.appOwnIcons = it },
        )))
        list.addView(note("Off: every app gets the same black app icon, to match the rest of the panel."))
        list.addView(heading("Pages"))
        list.addView(card(listOf(
            check("Remember last page", prefs.rememberPages) { prefs.rememberPages = it },
        )))
        list.addView(note("Quick Controls and apps reopen on the page you left them on. Off: they start on page 1 each time the panel opens."))
    }

    private fun pinsSection() {
        list.addView(View(this), lp(MATCH, dp(12)))
        val pins = prefs.pins
        val rows = mutableListOf<View>()
        if (pins.isEmpty()) rows += cardText("No items yet.")
        pins.forEachIndexed { i, pin ->
            rows += itemRow(pin, i, pins.size, onMove = { from, to -> prefs.pins = pins.move(from, to) },
                onEdit = { editPin(pin) }, onRemove = { prefs.removePin(pin.id); render() })
        }
        rows += buttonRow(
            "+ BOOX note" to { editPin(null) },
            "+ Document" to { pickDocument(REQ_FILE) },
            "+ Folder" to { pickTree(REQ_FOLDER) },
        )
        rows += buttonRow(
            "+ App" to { pickApp { prefs.addPin(it); render() } },
            "+ Custom intent" to { editIntent() },
        )
        list.addView(card(rows))
        list.addView(note("Easiest way to add a note or book: open it, swipe the panel open and tap Add."))
    }

    private fun quickControlsSection() {
        val controls = QuickControls.selected(prefs)
        list.addView(View(this), lp(MATCH, dp(12)))
        list.addView(card(listOf(
            check("Show Quick Controls in the panel", prefs.showSystemActions) { prefs.showSystemActions = it },
        )))
        if (controls.any { it.id == QuickControls.FRONT_LIGHT }) myLightPresets()
        if (controls.any { it.id == QuickControls.WRITING } || WritingMode.isOn(prefs)) writingModeCards()
        list.addView(heading("Controls · ${QuickAccessPanel.QC_PER_PAGE} per page"))
        val rows = mutableListOf<View>()
        controls.forEachIndexed { i, c ->
            rows += controlRow(c, i, controls.size,
                onMove = { from, to -> QuickControls.save(prefs, controls.move(from, to)) },
                onRemove = { QuickControls.save(prefs, controls.filterNot { it.id == c.id }) })
            if (c.id in shownGuides) rows += controlGuide(c)
        }
        if (controls.isEmpty()) rows += cardText("No controls yet.")
        rows += buttonRow(
            "+ Add control" to { addControls() },
            "Reset to default" to { QuickControls.reset(prefs); render() },
        )
        list.addView(card(rows))
        list.addView(note("Tap a control to switch it; hold it to open its settings (Front Light: hold for its presets). Swipe sideways or use ‹ › for more. Wi‑Fi, Bluetooth, Front Light and the BOOX tiles work through the BOOX Control Center."))
    }

    /** What Writing mode switches. Shown once the Writing control is added. */
    private fun writingModeCards() {
        val off = WritingMode.turnsOff(prefs)
        fun item(id: String) = check(WritingMode.label(id), id in off) { on ->
            WritingMode.setTurnsOff(prefs, WritingMode.turnsOff(prefs).let { if (on) it + id else it - id })
        }
        list.addView(heading("Writing mode turns off"))
        val rows = mutableListOf(item("wifi"), item("bluetooth"))
        // A tablet where BOOX shows no Front Light presets has no light to turn off.
        if (WritingMode.hasLight(prefs)) rows += item(WritingMode.LIGHT)
        rows += item("location")
        list.addView(card(rows))
        list.addView(note("Leave Bluetooth unticked if you write with a Bluetooth keyboard or headphones."))
        list.addView(heading("And turns on"))
        list.addView(card(listOf(check("Do Not Disturb", WritingMode.turnsOnDnd(prefs)) { WritingMode.setTurnsOnDnd(prefs, it) })))
        list.addView(note(
            (if (WritingMode.isOn(prefs)) "Writing mode is on now: changes here apply the next time you turn it on. " else "") +
                "Turning Writing mode off puts back only what it switched: the same radios, and the light on the preset it was on. Anything you changed yourself in between is left alone. " +
                (if (AdvancedAccess.allowed()) "With Advanced access connected, Wi‑Fi, Bluetooth and Location switch directly; the light still goes through BOOX's Control Center."
                else "It switches through BOOX's Control Center, so the screen holds still for a moment. No extra permission is needed.")
        ))
    }

    private fun controlRow(c: QuickControls.Control, index: Int, count: Int, onMove: (Int, Int) -> Unit, onRemove: () -> Unit): View = hbox().apply {
        setPadding(dp(6), dp(8), dp(12), dp(8))
        gravity = Gravity.CENTER_VERTICAL
        addView(dragHandle(this, index, count, onMove))
        addView(android.widget.FrameLayout(context).apply {
            background = outline(2, 20)
            addView(icon(getDrawable(c.icon), 20), android.widget.FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        }, lp(dp(38), dp(38)))
        addView(vbox().apply {
            setPadding(dp(12), 0, 0, 0)
            addView(text(c.label, 17f, medium = true))
            addView(text(describe(c), 13f, color = Ink.MUTED))
            // A picture of what tap and hold do, opened on request.
            addView(text(if (c.id in shownGuides) "Hide picture ▴" else "Show me ▾", 13.5f, bold = true).apply {
                setPadding(0, dp(4), dp(8), dp(2))
                pressable()
                setOnClickListener { if (!shownGuides.remove(c.id)) shownGuides += c.id; render() }
            })
        }, lp(0, WRAP, 1f))
        fun small(iconRes: Int, label: String, enabled: Boolean = true, action: () -> Unit) {
            addView(inkIconButton(iconRes, label, 44, enabled) { action(); render() })
        }
        small(R.drawable.ic_arrow_up, "Move up", index > 0) { onMove(index, index - 1) }
        small(R.drawable.ic_arrow_down, "Move down", index < count - 1) { onMove(index, index + 1) }
        small(R.drawable.ic_delete, "Remove") { onRemove() }
    }

    /** Controls whose "Show me" picture is open. */
    private val shownGuides = HashSet<String>()

    /** Tap and hold for [c], drawn: the tile filled for a tap, ringed for a hold. */
    private fun controlGuide(c: QuickControls.Control): View = hbox().apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        setPadding(dp(12), dp(10), dp(12), dp(14))
        val (tap, hold) = when {
            c.id == QuickControls.FRONT_LIGHT -> "light turns off / back on" to "opens the presets under the controls"
            c.id == QuickControls.WRITING -> "radios and light off / back as they were" to "opens its settings here"
            c.id == "rotate" -> "rotation on / off" to "opens BOOX's rotation menu"
            c.momentary -> "a full e‑ink refresh" to "opens its settings"
            c.kind == QuickControls.Kind.TOGGLE -> "switches it on / off" to "opens its settings"
            c.kind == QuickControls.Kind.TILE -> "opens it" to "opens its settings"
            else -> c.label.lowercase().replaceFirstChar { it.uppercase() } to null
        }
        fun figure(filled: Boolean, ringed: Boolean, title: String, detail: String) = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
            addView(android.widget.FrameLayout(context).apply {
                if (ringed) background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setStroke(dp(2), Ink.INK, dp(5).toFloat(), dp(4).toFloat())
                }
                addView(android.widget.FrameLayout(context).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(if (filled) Ink.INK else Ink.PAPER)
                        setStroke(dp(2), Ink.INK)
                    }
                    addView(icon(getDrawable(c.icon), 24).apply { if (filled) imageTintList = android.content.res.ColorStateList.valueOf(Ink.PAPER) },
                        android.widget.FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                }, android.widget.FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER))
            }, lp(dp(64), dp(64)))
            addView(text(title, 14f, bold = true).apply { gravity = Gravity.CENTER })
            addView(text(detail, 12.5f, color = Ink.MUTED).apply { gravity = Gravity.CENTER; maxWidth = dp(130) })
        }
        addView(figure(filled = true, ringed = false, title = "Tap", detail = tap))
        if (hold != null) addView(figure(filled = false, ringed = true, title = "Hold", detail = hold))
        if (c.id == QuickControls.FRONT_LIGHT) addView(vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(14), dp(8), 0)
            addView(hbox().apply {
                background = outline(2, 8)
                setPadding(dp(6), dp(6), dp(6), dp(6))
                repeat(4) { i ->
                    addView(View(context).apply {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(if (i == 0) Ink.INK else Ink.PAPER)
                            setStroke(dp(2), Ink.INK)
                        }
                    }, lp(dp(20), dp(20)).apply { if (i < 3) marginEnd = dp(4) })
                }
            })
            addView(text("Bright · Soft ·\nCustom · Off", 12.5f, color = Ink.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
        })
    }

    private var independentGuideOpen = false

    /** Independent presets off and on, drawn: whether BOOX's Custom is borrowed or left alone. */
    private fun independentGuide(): View = vbox().apply {
        setPadding(dp(16), dp(4), dp(16), dp(16))
        fun chip(t: String, black: Boolean) = text(t, 12.5f, bold = true, color = if (black) Ink.PAPER else Ink.INK).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = if (black) android.graphics.drawable.GradientDrawable().apply { setColor(Ink.INK); cornerRadius = dp(6).toFloat() } else outline(2, 6)
        }
        fun arrow() = text("→", 15f, bold = true).apply { setPadding(dp(8), 0, dp(8), 0) }
        addView(text("OFF", 13f, bold = true).apply { setPadding(0, dp(4), 0, dp(6)) })
        addView(hbox().apply { addView(chip("Your preset", false)); addView(arrow()); addView(chip("BOOX Custom", true)); addView(arrow()); addView(chip("Light", false)) })
        addView(text("Your preset borrows BOOX's Custom, so Custom's levels change too.", 12.5f, color = Ink.MUTED).apply { setPadding(0, dp(6), 0, dp(10)) })
        addView(text("ON", 13f, bold = true).apply { setPadding(0, dp(4), 0, dp(6)) })
        addView(hbox().apply {
            addView(chip("Your preset", false)); addView(arrow()); addView(chip("Light", false))
            addView(chip("BOOX Custom", true), lp(WRAP, WRAP).apply { marginStart = dp(18) })
            addView(text("untouched", 12.5f).apply { setPadding(dp(6), 0, 0, 0) })
        })
        addView(text("Your preset sets the light directly. BOOX's Custom keeps its own levels.", 12.5f, color = Ink.MUTED).apply { setPadding(0, dp(6), 0, 0) })
    }

    private fun describe(c: QuickControls.Control): String = when {
        c.id == QuickControls.FRONT_LIGHT -> "Tap: on/off · hold: Bright, Soft, Custom, Off"
        c.id == QuickControls.WRITING -> "Tap: radios and Front Light off, tap again to put them back · hold: these settings"
        c.id == "rotate" -> "Tap: on/off · hold: rotation menu"
        c.momentary -> "Tap: ${c.label.lowercase()} · hold: its settings"
        else -> describeKind(c)
    }

    private fun describeKind(c: QuickControls.Control): String = when (c.kind) {
        QuickControls.Kind.TOGGLE -> "Tap: on/off · hold: its settings"
        QuickControls.Kind.TILE -> "Tap: open · hold: its settings"
        QuickControls.Kind.ACTION -> "Tap: ${c.label.lowercase()}"
    }

    /** Picker grouped like the BOOX Control Center; controls already added are marked. */
    private fun addControls() {
        val have = QuickControls.selected(prefs).map { it.id }.toSet()
        val ticked = LinkedHashSet<String>()
        val body = vbox().apply { setPadding(dp(8), dp(4), dp(8), dp(4)) }
        for (group in QuickControls.GROUPS) {
            body.addView(text(group.uppercase(), 13f, bold = true).apply {
                letterSpacing = 0.06f
                setPadding(dp(12), dp(14), dp(12), dp(4))
            })
            QuickControls.ALL.filter { it.group == group }.forEach { c ->
                val added = c.id in have
                body.addView(CheckBox(this).apply {
                    buttonTintList = android.content.res.ColorStateList.valueOf(Ink.INK)
                    text = if (added) "${c.label}  (added)" else c.label
                    textSize = 16f
                    isEnabled = !added
                    isChecked = added
                    setOnCheckedChangeListener { _, on -> if (on) ticked += c.id else ticked -= c.id }
                })
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Add control")
            .setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                if (ticked.isNotEmpty()) {
                    QuickControls.save(prefs, QuickControls.selected(prefs) + ticked.mapNotNull { QuickControls.byId(it) })
                    render()
                }
            }
            .show()
    }

    private fun dockSection() {
        list.addView(View(this), lp(MATCH, dp(12)))
        val dock = prefs.dock
        val rows = mutableListOf<View>(
            check("Show \"Previous app\": the app you were in before this one", prefs.previousAppInDock) { prefs.previousAppInDock = it; render() },
        )
        if (EinkMenus.available) rows += check("Show \"This app\": BOOX's Optimize and EinkWise for the app that's open", prefs.showThisApp) { prefs.showThisApp = it; render() }
        if (dock.isEmpty()) rows += cardText("No apps yet.")
        dock.forEachIndexed { i, app ->
            rows += itemRow(app, i, dock.size, onMove = { from, to -> prefs.dock = dock.move(from, to) },
                onEdit = if (app.value == Prefs.PREVIOUS_APP) null else ({ renameDockApp(app) }),
                onRemove = { prefs.dock = dock.filterNot { it.id == app.id }; render() })
        }
        rows += buttonRow("+ Add app" to { pickApp { prefs.dock = prefs.dock + it; render() } })
        list.addView(card(rows))
        if (prefs.previousAppInDock) list.addView(note("Move \"Previous app\" with ↑ ↓ to choose where it sits."))
    }

    /** Edit an app at the bottom of the panel: rename it (only the name SuperBoox shows; Reset puts its own back) or replace it in place. */
    private fun renameDockApp(app: Pin) {
        val own = Launcher.appLabel(this, app.value.substringBefore('/'))
        val f = field("Name", app.label)
        f.post { f.requestFocus(); f.setSelection(f.text.length) }
        fun save(name: String) {
            prefs.dock = prefs.dock.map { if (it.id == app.id) it.copy(label = name) else it }
            DebugLog.i("dock", "renamed ${app.label} -> $name")
            render()
        }
        // Replace: another app takes this one's place in the row (its own name, not the custom one).
        lateinit var dialog: AlertDialog
        val replace = inkButton("⇄ Replace app…", 18f) {
            dialog.dismiss()
            pickApp { picked ->
                prefs.dock = prefs.dock.map { if (it.id == app.id) picked.copy(id = app.id) else it }
                DebugLog.i("dock", "replaced ${app.label} with ${picked.label}")
                render()
            }
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("Edit app")
            .setView(vbox().apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(text("Name", 13f, color = Ink.MUTED))
                addView(f, lp(MATCH, WRAP))
                // A gap below, so it doesn't sit on BOOX's button bar.
                addView(replace, lp(MATCH, WRAP).apply { topMargin = dp(16); bottomMargin = dp(16) })
            })
            .setPositiveButton("Save") { _, _ -> f.text.toString().trim().takeIf { it.isNotEmpty() }?.let(::save) }
            .setNeutralButton("Reset") { _, _ -> save(own) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun recentSection() {
        list.addView(View(this), lp(MATCH, dp(12)))
        val uri = prefs.recentFolderUri
        val rows = mutableListOf<View>(
            cardText(
                if (uri == null) "Show the newest files from a folder, e.g. where BOOX Notes exports PDFs (often /note or /Documents)."
                else "Folder: ${Launcher.displayName(this, Uri.parse(uri))}",
            ),
        )
        if (uri != null) {
            rows += slider("Files shown", prefs.recentCount, 1, 20, "") { prefs.recentCount = it }
        }
        rows += buttonRow(
            *listOfNotNull(
                (if (uri == null) "Choose folder" else "Change folder") to { pickTree(REQ_RECENT) },
                if (uri != null) "Don't show" to { prefs.recentFolderUri = null; render() } else null,
            ).toTypedArray(),
        )
        list.addView(card(rows))
    }

    private fun clipboardSection() {
        list.addView(heading("In the panel"))
        list.addView(card(listOf(
            check("Show the Clipboard button", prefs.clipShowButton) { prefs.clipShowButton = it },
        )))
        list.addView(note("The Clipboard button sits at the top left of the panel (hold it to come here); Recent Files moves to the left end of the Quick Access bar. Off: Recent Files stays at the top."))
        list.addView(heading("What to keep"))
        val everyCopy = "Every copy with Advanced access"
        list.addView(card(listOf(
            keepRow("Text", "Words and passages you copy: from notes, books, web pages, messages.", everyCopy, prefs.clipKeepText) { prefs.clipKeepText = it },
            keepRow("Links", "Web addresses. Kept as links, so you can open them again from the clipboard.", everyCopy, prefs.clipKeepLinks) { prefs.clipKeepLinks = it },
            keepRow("Images", "Pictures an app copies, like “Copy image” in the browser or gallery. Large images are kept at a reduced size.",
                "Only when the panel opens", prefs.clipKeepImages, advanced = false) { prefs.clipKeepImages = it },
            keepRow("Handwriting", "Strokes copied with the lasso in BOOX Notes. They paste back into BOOX Notes while BOOX still has the copy, usually until a restart.",
                everyCopy, prefs.clipKeepHandwriting) { prefs.clipKeepHandwriting = it },
            keepRow("Codes", "Short codes on their own, like a 6-digit sign-in code. Off by default; they're usually only needed once.", everyCopy, prefs.clipKeepCodes) { prefs.clipKeepCodes = it },
            keepRow("Passwords and private copies", "Never kept: copies from password managers and anything an app marks as private.", null, true, locked = true) {},
        )))
        list.addView(heading("Handwriting previews"))
        val files = ClipHistory.hasFileAccess()
        list.addView(card(listOf(
            cardText(if (files) "On: SuperBoox reads BOOX Notes' copy folder directly to show what handwriting you copied. It reads nothing else."
                else "Handwriting copied in BOOX Notes is kept in a BOOX folder SuperBoox can't open on its own. Allow All files access to show a preview of it " +
                    "even when Advanced access isn't running (e.g. after a restart). SuperBoox only reads BOOX's copy folder."),
            buttonRow((if (files) "Change in Android settings" else "Allow file access") to { openAllFilesAccess() }),
        )))
        list.addView(note("Every copy with Advanced access: caught as you copy while Advanced access is on; otherwise saved when you open the panel (only the latest copy). " +
            "Only when the panel opens: Android doesn't let SuperBoox fetch images in the background, even with Advanced access. Unticking a kind doesn't remove clips already saved."))
        list.addView(heading("While the clipboard is open, still show"))
        list.addView(card(listOf(
            check("Quick Controls", prefs.clipShowControls) { prefs.clipShowControls = it; previewOn = true; render() },
            check("Last Opened Note & Document", prefs.clipShowLast) { prefs.clipShowLast = it; previewOn = true; render() },
            check("Apps at the bottom", prefs.clipShowApps) { prefs.clipShowApps = it; previewOn = true; render() },
        ) + (if (QuickAccessService.instance != null) listOf(previewButton()) else listOf(
            // Without the service there's no real panel to show: small drawings instead.
            hbox().apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(12), dp(12), dp(14))
                addView(miniPanelLabeled("Now", prefs.clipShowControls, prefs.clipShowLast, prefs.clipShowApps))
                addView(miniPanelLabeled("Controls + apps", true, false, true))
                addView(miniPanelLabeled("All on", true, true, true))
            },
        ))))
        list.addView(note("Quick Access is always replaced, since the clipboard takes its place. Fewer clips fit on a page when more is shown."))
        list.addView(heading("History"))
        val ignored = ignoredApps()
        list.addView(card(listOf(
            choiceRow("Keep the last", "${prefs.clipLimit} clips") {
                val options = listOf(25, 50, 100, 200, 500)
                pick("Keep the last", options.map { "$it clips" }, options.indexOf(prefs.clipLimit).coerceAtLeast(0)) { prefs.clipLimit = options[it] }
            },
            check("Keep history after a restart", prefs.clipKeepAfterRestart) { prefs.clipKeepAfterRestart = it },
            choiceRow("Ignore copies from", if (ignored.isEmpty()) "Password managers" else "${ignored.size} ${if (ignored.size == 1) "app" else "apps"}") { editIgnoredApps() },
        )))
        list.addView(note("Pinned clips are always kept. History stays on this tablet and isn't part of backups."))
        list.addView(heading("Catching copies"))
        val connected = AdvancedAccess.allowed()
        val every = prefs.clipCatchMode == Prefs.CLIP_EVERY
        list.addView(card(listOf(
            radioRow("When the panel opens", "No setup. Saves what's on the clipboard each time you open the panel, so a copy replaced before then is missed.", !every) {
                prefs.clipCatchMode = Prefs.CLIP_PANEL
                render()
            },
            radioRow("Every copy, as it happens",
                if (connected) "Uses Advanced access to read the clipboard in the background."
                else "Needs Advanced access (Shizuku), which isn't connected right now. Until it is, copies are saved when the panel opens.",
                every) {
                prefs.clipCatchMode = Prefs.CLIP_EVERY
                if (!AdvancedAccess.allowed()) toast("Set up Advanced access to catch every copy")
                render()
            },
        )))
        ClipHistory.backgroundProblem()?.takeIf { every }?.let { list.addView(note("Background reading failed last time: $it")) }
        list.addView(card(listOf(
            buttonRow("Clear history (keeps pinned)" to {
                AlertDialog.Builder(this)
                    .setTitle("Clear clipboard history?")
                    .setMessage("Every clip that isn't pinned is removed.")
                    .setPositiveButton("Clear") { _, _ -> ClipHistory.clear(this); toast("Clipboard history cleared"); render() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }),
        )).apply { (layoutParams as ViewGroup.MarginLayoutParams).topMargin = dp(20) })
        list.addView(View(this), lp(MATCH, dp(24)))
    }

    /** One kind of clip to keep: tick box, what it covers, and how it's caught (black label: needs Advanced access). */
    private fun keepRow(title: String, detail: String, how: String?, value: Boolean, advanced: Boolean = true, locked: Boolean = false, onChange: (Boolean) -> Unit): View =
        hbox().apply {
            gravity = Gravity.TOP
            setPadding(dp(8), dp(10), dp(16), dp(12))
            val box = CheckBox(context).apply {
                buttonTintList = android.content.res.ColorStateList.valueOf(if (locked) Ink.DISABLED else Ink.INK)
                isChecked = value
                isClickable = false
                isFocusable = false
            }
            addView(box)
            addView(vbox().apply {
                setPadding(dp(4), dp(6), 0, 0)
                addView(text(title, 17f, medium = true, color = if (locked) Ink.MUTED else Ink.INK))
                addView(text(detail, 13f, color = Ink.MUTED).apply { setPadding(0, dp(2), 0, 0) })
                if (how != null) addView(text(how, 11.5f, bold = true, color = if (advanced) Ink.PAPER else Ink.INK).apply {
                    setPadding(dp(6), dp(1), dp(6), dp(1))
                    background = if (advanced) android.graphics.drawable.GradientDrawable().apply { setColor(Ink.INK); cornerRadius = dp(4).toFloat() } else outline(1, 4)
                }, lp(WRAP, WRAP).apply { topMargin = dp(6) })
            }, lp(0, WRAP, 1f))
            contentDescription = "$title, ${if (value) "kept" else "not kept"}"
            if (!locked) {
                pressable()
                setOnClickListener {
                    box.isChecked = !box.isChecked
                    onChange(box.isChecked)
                }
            }
        }

    /** A small drawing of the panel with the clipboard open: which parts still show. */
    private fun miniPanelLabeled(label: String, controls: Boolean, last: Boolean, apps: Boolean): View = vbox().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(6), 0, dp(6), 0)
        addView(MiniPanel.view(context, listOfNotNull("Top bar", if (controls) "Quick Controls" else null, if (last) "Last Opened" else null),
            "Clipboard", listOfNotNull(if (apps) "Apps" else null)), lp(dp(78), dp(122)))
        addView(text(label, 12f).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
    }

    /** Android's "All files access" switch for SuperBoox (Special app access). */
    private fun openAllFilesAccess() {
        val direct = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        if (!Launcher.tryStart(this, direct)) Launcher.tryStart(this, Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }

    /** Ignored apps that are installed (the built-in password manager list stays ignored even when not shown). */
    private fun ignoredApps(): List<String> = prefs.clipIgnored.filter { pkg ->
        runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }.sortedBy { Launcher.appLabel(this, it).lowercase() }

    private fun editIgnoredApps() {
        val apps = ignoredApps()
        val keep = BooleanArray(apps.size) { true }
        AlertDialog.Builder(this)
            .setTitle("Ignore copies from")
            .apply {
                if (apps.isEmpty()) setMessage("Copies from password managers are never kept. Add other apps whose copies shouldn't be kept.")
                else setMultiChoiceItems(apps.map { Launcher.appLabel(this@SettingsActivity, it) }.toTypedArray(), keep) { _, i, on -> keep[i] = on }
            }
            .setPositiveButton("Done") { _, _ ->
                val dropped = apps.filterIndexed { i, _ -> !keep[i] }.toSet()
                if (dropped.isNotEmpty()) prefs.clipIgnored = prefs.clipIgnored - dropped
                render()
            }
            .setNeutralButton("Add app…") { _, _ ->
                pickApp { pin ->
                    val pkg = pin.value.substringBefore('/')
                    prefs.clipIgnored = prefs.clipIgnored + pkg
                    render()
                }
            }
            .show()
    }

    private fun advancedSection() {
        list.addView(View(this), lp(MATCH, dp(12)))
        val pkg = prefs.notesPackage
        val installed = BooxNotes.isInstalled(this, pkg)
        list.addView(card(listOf(
            choiceRow("BOOX Notes package", if (installed) pkg else "$pkg (not found)") {
                prompt("BOOX Notes package", pkg, "Package name") { prefs.notesPackage = it.ifBlank { BooxNotes.DEFAULT_PACKAGE } }
            },
        )))
    }

    /** What SuperBoox knows about this tablet: each device-dependent feature and whether it's been seen working here. */
    private fun deviceSection() {
        list.addView(View(this), lp(MATCH, dp(12)))
        list.addView(card(listOf(cardText(
            "${Devices.summary()}\n${Devices.verdict()}\n\nSuperBoox reads BOOX's own screens and files, which differ between " +
                "models and firmware. \"Not yet tested\" means nobody has confirmed it on a tablet like yours, not that it's broken.",
        ))))
        list.addView(heading("Checked features"))
        list.addView(card(Devices.Feature.values().map { f ->
            hbox().apply {
                setPadding(dp(16), dp(12), dp(16), dp(12))
                addView(text(f.title, 16f), lp(0, WRAP, 1f))
                addView(text(Devices.status(f).label, 13f, bold = true).apply {
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    background = outline(2, 12)
                })
            }
        }))
        Devices.known()?.settings?.takeIf { it.isNotEmpty() }?.let { settings ->
            list.addView(heading("Settings it was checked with"))
            list.addView(card(settings.map { cardText(it) }))
        }
        list.addView(heading("Something not working?"))
        list.addView(card(listOf(
            cardText("A debug report names your model and firmware and shows what BOOX's screens look like to SuperBoox. That's what lets a feature be fixed and confirmed for your tablet."),
            buttonRow("Copy debug report" to { copyReport() }, "Share…" to { shareReport() }),
        )))
        list.addView(View(this), lp(MATCH, dp(24)))
    }

    private fun troubleshootingSection() {
        list.addView(View(this), lp(MATCH, dp(12)))
        list.addView(card(listOf(
            cardText(
                "SuperBoox keeps a log of what it does on this device (${DebugLog.entries().size} entries): gestures, " +
                    "the app in front, launches, and each step of opening a note. It never leaves the device " +
                    "unless you copy or share the report.",
            ),
            buttonRow(
                "Copy debug report" to { copyReport() },
                "Share…" to { shareReport() },
            ),
            buttonRow("Clear log" to { DebugLog.clear(); toast("Log cleared"); render() }),
        )))
        list.addView(View(this), lp(MATCH, dp(24)))
    }

    // ---- Cards ---------------------------------------------------------------------------

    /** Bold section name above its card. */
    private fun heading(value: String): View =
        text(value.uppercase(), 14f, bold = true).apply {
            letterSpacing = 0.06f
            setPadding(dp(20), dp(20), dp(20), dp(8))
        }

    /**
     * A section's rows in a black-bordered card, with a thin rule between
     * rows, so each setting reads as its own block on e-ink.
     */
    private fun card(rows: List<View>): View = vbox().apply {
        background = outline(2, 8)
        // Keep row backgrounds (pressed gray) inside the border.
        setPadding(dp(2), dp(2), dp(2), dp(2))
        clipToOutline = true
        rows.forEachIndexed { i, row ->
            if (i > 0) addView(divider(1))
            addView(row)
        }
        layoutParams = lp(MATCH, WRAP).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
        }
    }

    /** Explanatory text inside a card. */
    private fun cardText(s: String): TextView =
        text(s, 14f, color = Ink.MUTED).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }

    /** Help text under a card. */
    private fun note(s: String): TextView =
        text(s, 14f, color = Ink.MUTED).apply { setPadding(dp(20), dp(8), dp(20), dp(4)) }

    /** Builds the report off the main thread (it scans the Notes app), then hands it over. */
    private fun withReport(then: (String) -> Unit) {
        toast("Building debug report…")
        io.execute {
            // Never leave the user waiting: any failure still produces a (smaller) report.
            val report = try {
                DebugReport.build(this)
            } catch (t: Throwable) {
                DebugLog.e("report", "build failed", t)
                "SuperBoox debug report (partial: ${t.javaClass.simpleName} ${t.message})\n\n" +
                    DebugLog.entries().takeLast(300).joinToString("\n")
            }
            runOnUiThread { then(report) }
        }
    }

    private fun copyReport() = withReport { report ->
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("SuperBoox debug report", report))
        DebugLog.i("report", "copied (${report.length} chars)")
        toast("Debug report copied (${report.length / 1024 + 1} KB)")
    }

    private fun shareReport() = withReport { report ->
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "SuperBoox debug report")
            .putExtra(Intent.EXTRA_TEXT, report)
        runCatching { startActivity(Intent.createChooser(send, "Share debug report")) }
            .onFailure { toast("No app to share with; use Copy instead") }
    }

    // ---- Pins: dialogs ------------------------------------------------------------------

    private fun editPin(existing: Pin?) {
        if (existing != null && existing.type == PinType.INTENT) return editIntent(existing)
        if (existing != null && existing.type != PinType.BOOX_NOTE) {
            prompt("Rename", existing.label, "Label") { label -> replacePin(existing.copy(label = label.ifBlank { existing.label })) }
            return
        }
        val path = field("Folder / Note", existing?.value.orEmpty())
        val label = field("Label (optional)", existing?.label?.takeIf { it != existing.value }.orEmpty())
        dialogWith(if (existing == null) "Add BOOX note" else "Edit BOOX note", path, label) {
            val p = path.text.toString().trim()
            if (p.isEmpty()) return@dialogWith
            val l = label.text.toString().trim().ifEmpty { BooxNotes.splitPath(p).lastOrNull() ?: p }
            if (existing == null) prefs.addPin(Pin(PinType.BOOX_NOTE, l, p)) else replacePin(existing.copy(label = l, value = p, noteId = if (p == existing.value) existing.noteId else ""))
        }
    }

    private fun editIntent(existing: Pin? = null) {
        val label = field("Label", existing?.label.orEmpty())
        val uri = field("intent:#Intent;component=…;end", existing?.value.orEmpty())
        dialogWith(if (existing == null) "Add custom intent" else "Edit custom intent", label, uri) {
            val u = uri.text.toString().trim()
            if (runCatching { Intent.parseUri(u, Intent.URI_INTENT_SCHEME) }.isFailure) {
                toast("That isn't a valid intent URI")
                return@dialogWith
            }
            val l = label.text.toString().trim().ifEmpty { "Shortcut" }
            if (existing == null) prefs.addPin(Pin(PinType.INTENT, l, u)) else replacePin(existing.copy(label = l, value = u))
        }
    }

    private fun replacePin(pin: Pin) {
        prefs.pins = prefs.pins.map { if (it.id == pin.id) pin else it }
        render()
    }

    // ---- Storage Access Framework ------------------------------------------------------

    private fun pickDocument(req: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(intent, req)
    }

    private fun pickTree(req: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(intent, req)
    }

    @Deprecated("Platform Activity API; fine without AndroidX.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        if (requestCode == REQ_BACKUP) return writeBackup(uri)
        if (requestCode == REQ_RESTORE) return readBackup(uri)
        val granted = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching { contentResolver.takePersistableUriPermission(uri, granted) }
            .recoverCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = Launcher.displayName(this, uri)
        when (requestCode) {
            REQ_FILE -> prefs.addPin(Pin(PinType.FILE, name, uri.toString()))
            REQ_FOLDER -> prefs.addPin(Pin(PinType.FOLDER, name, uri.toString()))
            REQ_RECENT -> prefs.recentFolderUri = uri.toString()
        }
        render()
    }

    // ---- App picker -----------------------------------------------------------------------

    private class AppEntry(val label: String, val component: ComponentName, val icon: Drawable?)

    private fun pickApp(onPicked: (Pin) -> Unit) {
        toast("Loading apps…")
        io.execute {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = packageManager.queryIntentActivities(launcher, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map { ri: ResolveInfo ->
                    AppEntry(
                        ri.loadLabel(packageManager).toString(),
                        ComponentName(ri.activityInfo.packageName, ri.activityInfo.name),
                        ri.loadIcon(packageManager),
                    )
                }
                .sortedBy { it.label.lowercase() }
            // Two apps can share a name (e.g. two "Files"): show those apps' package names too.
            val shared = apps.groupingBy { it.label.lowercase() }.eachCount().filterValues { it > 1 }.keys
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val adapter = object : ArrayAdapter<AppEntry>(this, 0, apps) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val app = getItem(position)!!
                        return hbox().apply {
                            setPadding(dp(20), dp(10), dp(20), dp(10))
                            addView(icon(app.icon, 36, tint = false))
                            addView(vbox().apply {
                                setPadding(dp(16), 0, 0, 0)
                                addView(text(app.label, 17f))
                                if (app.label.lowercase() in shared) addView(text(app.component.packageName, 13f, color = Ink.MUTED))
                            })
                        }
                    }
                }
                AlertDialog.Builder(this)
                    .setTitle("Choose an app")
                    .setAdapter(adapter) { _, which ->
                        val app = apps[which]
                        onPicked(Pin(PinType.APP, app.label, app.component.flattenToString()))
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    // ---- Row builders ------------------------------------------------------------------

    private fun itemRow(
        pin: Pin,
        index: Int,
        count: Int,
        onMove: (Int, Int) -> Unit,
        onEdit: (() -> Unit)?,
        onRemove: () -> Unit,
    ): View = hbox().apply {
        setPadding(dp(6), dp(8), dp(12), dp(8))
        gravity = Gravity.CENTER_VERTICAL
        addView(dragHandle(this, index, count, onMove))
        val texts = vbox().apply {
            addView(text(pin.label, 17f, medium = true))
            addView(text(describe(pin), 13f, color = Ink.MUTED).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        addView(texts, lp(0, WRAP, 1f))
        fun small(iconRes: Int, label: String, enabled: Boolean = true, action: () -> Unit) {
            addView(inkIconButton(iconRes, label, 44, enabled) { action(); render() })
        }
        small(R.drawable.ic_arrow_up, "Move up", index > 0) { onMove(index, index - 1) }
        small(R.drawable.ic_arrow_down, "Move down", index < count - 1) { onMove(index, index + 1) }
        if (onEdit != null) small(R.drawable.ic_edit, "Edit") { onEdit() }
        small(R.drawable.ic_delete, "Remove") { onRemove() }
    }

    private fun describe(pin: Pin): String = when (pin.type) {
        PinType.BOOX_NOTE -> "BOOX note · ${pin.value}"
        PinType.FILE -> "Document"
        PinType.DOCUMENT -> "Document · ${pin.value.substringAfterLast('/')}"
        PinType.FOLDER -> "Folder"
        PinType.APP -> if (pin.value == Prefs.PREVIOUS_APP) "Switches back to the app you were in before" else "App · ${pin.value.substringBefore('/')}"
        PinType.INTENT -> "Intent · ${pin.value}"
    }

    private fun paragraph(s: String): TextView =
        text(s, 14f, color = Ink.MUTED).apply { setPadding(dp(16), dp(4), dp(16), dp(8)) }

    private fun buttonRow(vararg buttons: Pair<String, () -> Unit>): View = hbox().apply {
        setPadding(dp(12), dp(10), dp(12), dp(10))
        buttons.forEach { (label, action) ->
            addView(inkButton(label) { action() }, lp(0, WRAP, 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }
    }

    private fun choiceRow(title: String, value: String, onClick: () -> Unit): View = hbox().apply {
        setPadding(dp(16), dp(12), dp(16), dp(12))
        addView(text(title, 17f, medium = true), lp(0, WRAP, 1f))
        addView(text("$value  ›", 15f, bold = true).apply { gravity = Gravity.END })
        pressable()
        setOnClickListener { onClick() }
    }

    private fun check(title: String, value: Boolean, onChange: (Boolean) -> Unit): View = CheckBox(this).apply {
        buttonTintList = android.content.res.ColorStateList.valueOf(Ink.INK)
        text = title
        textSize = 17f
        isChecked = value
        setPadding(dp(8), dp(10), dp(16), dp(10))
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
        layoutParams = lp(MATCH, WRAP).apply { marginStart = dp(10) }
    }

    private fun slider(title: String, value: Int, min: Int, max: Int, unit: String, onChange: (Int) -> Unit): View = vbox().apply {
        setPadding(dp(16), dp(8), dp(16), dp(8))
        val label = text("$title: $value $unit".trim(), 15f)
        addView(label)
        addView(SeekBar(context).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(Ink.INK)
            thumbTintList = android.content.res.ColorStateList.valueOf(Ink.INK)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ink.DISABLED)
            this.max = max - min
            progress = value - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    label.text = "$title: ${p + min} $unit".trim()
                }

                override fun onStartTrackingTouch(sb: SeekBar) = Unit

                // Save on release only: each save rebuilds the overlay window.
                override fun onStopTrackingTouch(sb: SeekBar) = onChange(sb.progress + min)
            })
        }, lp(MATCH, WRAP))
    }

    // ---- Dialog helpers ------------------------------------------------------------------

    private fun pick(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), selected) { d, which ->
                onPick(which)
                d.dismiss()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun field(hint: String, value: String): EditText = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    private fun prompt(title: String, value: String, hint: String, onOk: (String) -> Unit) {
        val f = field(hint, value)
        dialogWith(title, f) { onOk(f.text.toString().trim()) }
    }

    private fun dialogWith(title: String, vararg fields: View, onOk: () -> Unit) {
        val box = vbox().apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            fields.forEach { addView(it, lp(MATCH, WRAP)) }
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                onOk()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun isServiceEnabled(): Boolean {
        if (QuickAccessService.instance != null) return true
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val cn = ComponentName(this, QuickAccessService::class.java)
        return enabled.split(':').any {
            it.equals(cn.flattenToString(), ignoreCase = true) || it.equals(cn.flattenToShortString(), ignoreCase = true)
        }
    }

    private fun <T> List<T>.move(from: Int, to: Int): List<T> {
        if (from !in indices || to !in indices) return this
        return toMutableList().apply { add(to, removeAt(from)) }
    }

    companion object {
        /** The Settings screen on show, for [MainGuard] to redraw after an error. */
        private var current: java.lang.ref.WeakReference<SettingsActivity>? = null

        /** Rebuilds the Settings screen from scratch (same page) if it's open; its views may be broken. */
        fun recoverIfOpen() {
            val a = current?.get() ?: return
            if (a.recovering || a.isFinishing || a.isDestroyed) return
            a.recovering = true
            // The broken screen is closed (its views can't be trusted) and a fresh one opened on the same
            // page once it's gone: Settings is singleTask, so starting it first would reuse the broken one.
            val app = a.applicationContext
            val again = Intent(app, SettingsActivity::class.java).putExtra(EXTRA_PAGE, a.page.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { a.finish() }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { app.startActivity(again) } }, 500)
        }

        const val K_PAGE = "page"
        const val EXTRA_PAGE = "page"
        const val EXTRA_EDIT_PRESET = "edit_preset"
        private const val K_PREVIEW = "preview"
        private const val K_TOUR = "tour"
        private const val TOUR_STEPS = 8
        private const val TOUR_TRY = 3
        private const val TOUR_PANEL = 4
        private const val TOUR_CLIPBOARD = 5
        private const val TOUR_ADVANCED = 6
        const val REQ_FILE = 1
        const val REQ_FOLDER = 2
        const val REQ_RECENT = 3
        const val REQ_BACKUP = 4
        const val REQ_RESTORE = 5
        const val REQ_NOTIFY = 6
        val io = Executors.newSingleThreadExecutor()
    }
}
