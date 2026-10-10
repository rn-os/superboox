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
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val y = scroll.scrollY
        list.removeAllViews()
        statusSection()
        gestureSection()
        pinsSection()
        dockSection()
        recentSection()
        advancedSection()
        troubleshootingSection()
        scroll.post { scroll.scrollTo(0, y) }
    }

    // ---- Sections -------------------------------------------------------------------------

    private fun statusSection() {
        // White title bar with a heavy rule instead of a black action bar (e-ink).
        list.addView(text("SuperBoox", 28f, bold = true).apply { setPadding(dp(16), dp(20), dp(16), dp(10)) })
        list.addView(divider(3))
        list.addView(View(this), lp(MATCH, dp(8)))
        list.addView(paragraph("A Supernote-style Quick Access panel for BOOX. Swipe from the screen edge on any page to open it."))

        val on = isServiceEnabled()
        val rows = mutableListOf<View>(
            text(if (on) "● Service is running" else "○ Service is off", 17f, bold = true)
                .apply { setPadding(dp(16), dp(14), dp(16), dp(10)) },
        )
        if (!on) {
            rows += paragraph(
                "Turn on \"SuperBoox Quick Access\" under Accessibility. On BOOX also open App Management for SuperBoox " +
                    "and allow it to run in the background so the system doesn't freeze it.",
            ).apply { setPadding(dp(16), dp(10), dp(16), dp(10)) }
        }
        rows += buttonRow(
            "Accessibility settings" to { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            "Open panel" to {
                val s = QuickAccessService.instance
                if (s == null) toast("Turn the service on first") else { s.showPanel() }
            },
        )
        list.addView(card(rows))
    }

    private fun gestureSection() {
        list.addView(heading("Gesture"))
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
            slider("Panel width", prefs.panelWidthPct, 25, 100, "% of screen") { prefs.panelWidthPct = it },
            check("Show Back / Home / Recents / Capture row", prefs.showSystemActions) { prefs.showSystemActions = it },
            choiceRow("Menu size", prefs.menuSize.title) {
                pick("Menu size", MenuSize.entries.map { it.title }, prefs.menuSize.ordinal) { prefs.menuSize = MenuSize.entries[it] }
            },
        )))
        list.addView(
            note(
                "Tip: the \"Quick Access\" app icon opens the panel too. Assign it to a BOOX gesture or the Navigation Ball " +
                    "if you'd rather not reserve a strip of the screen edge. There's also a Quick Settings tile.",
            ),
        )
    }

    private fun pinsSection() {
        list.addView(heading("Quick Access items"))
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

    private fun dockSection() {
        list.addView(heading("Apps at the bottom"))
        val dock = prefs.dock
        val rows = mutableListOf<View>()
        if (dock.isEmpty()) rows += cardText("No apps yet.")
        dock.forEachIndexed { i, app ->
            rows += itemRow(app, i, dock.size, onMove = { from, to -> prefs.dock = dock.move(from, to) },
                onEdit = null, onRemove = { prefs.dock = dock.filterNot { it.id == app.id }; render() })
        }
        rows += buttonRow("+ Add app" to { pickApp { prefs.dock = prefs.dock + it; render() } })
        list.addView(card(rows))
    }

    private fun recentSection() {
        list.addView(heading("Recent files"))
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

    private fun advancedSection() {
        list.addView(heading("Advanced"))
        val pkg = prefs.notesPackage
        val installed = BooxNotes.isInstalled(this, pkg)
        list.addView(card(listOf(
            choiceRow("BOOX Notes package", if (installed) pkg else "$pkg (not found)") {
                prompt("BOOX Notes package", pkg, "Package name") { prefs.notesPackage = it.ifBlank { BooxNotes.DEFAULT_PACKAGE } }
            },
        )))
    }

    private fun troubleshootingSection() {
        list.addView(heading("Troubleshooting"))
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
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val adapter = object : ArrayAdapter<AppEntry>(this, 0, apps) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val app = getItem(position)!!
                        return hbox().apply {
                            setPadding(dp(20), dp(10), dp(20), dp(10))
                            addView(icon(app.icon, 36, tint = false))
                            addView(text(app.label, 17f).apply { setPadding(dp(16), 0, 0, 0) })
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
        setPadding(dp(16), dp(8), dp(12), dp(8))
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
        PinType.APP -> "App · ${pin.value.substringBefore('/')}"
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

    private companion object {
        const val REQ_FILE = 1
        const val REQ_FOLDER = 2
        const val REQ_RECENT = 3
        val io = Executors.newSingleThreadExecutor()
    }
}
