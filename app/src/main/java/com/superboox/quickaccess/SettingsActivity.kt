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
import android.widget.Button
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
        list.addView(text("SuperBoox", 28f, bold = true).apply { setPadding(dp(16), dp(20), dp(16), dp(4)) })
        list.addView(paragraph("A Supernote-style Quick Access panel for BOOX. Swipe from the screen edge on any page to open it."))

        val on = isServiceEnabled()
        list.addView(
            text(if (on) "● Service is running" else "○ Service is off", 17f, bold = true)
                .apply { setPadding(dp(16), dp(12), dp(16), dp(4)) },
        )
        if (!on) {
            list.addView(
                paragraph(
                    "Turn on \"SuperBoox Quick Access\" under Accessibility. On BOOX also open App Management for SuperBoox " +
                        "and allow it to run in the background so the system doesn't freeze it.",
                ),
            )
        }
        list.addView(buttonRow(
            "Accessibility settings" to { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            "Open panel" to {
                val s = QuickAccessService.instance
                if (s == null) toast("Turn the service on first") else { s.showPanel() }
            },
        ))
    }

    private fun gestureSection() {
        list.addView(sectionTitle("Gesture"))
        list.addView(choiceRow("Screen edge", prefs.edge.name.lowercase().replaceFirstChar { it.uppercase() }) {
            pick("Screen edge", Edge.entries.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, prefs.edge.ordinal) {
                prefs.edge = Edge.entries[it]
            }
        })
        list.addView(choiceRow("Trigger", prefs.trigger.title) {
            pick("Trigger", Trigger.entries.map { it.title }, prefs.trigger.ordinal) { prefs.trigger = Trigger.entries[it] }
        })
        list.addView(check("Edge gesture enabled", prefs.handleEnabled) { prefs.handleEnabled = it })
        list.addView(check("Show a small marker on the edge", prefs.showHandle) { prefs.showHandle = it })
        list.addView(slider("Touch strip width", prefs.handleWidthDp, 6, 48, "dp") { prefs.handleWidthDp = it })
        list.addView(slider("Touch strip height", prefs.handleHeightPct, 10, 100, "% of screen") { prefs.handleHeightPct = it })
        list.addView(slider("Touch strip position from top", prefs.handleTopPct, 0, 90, "%") { prefs.handleTopPct = it })
        list.addView(slider("Panel width", prefs.panelWidthPct, 25, 100, "% of screen") { prefs.panelWidthPct = it })
        list.addView(check("Show Back / Home / Recents / Capture row", prefs.showSystemActions) { prefs.showSystemActions = it })
        list.addView(
            paragraph(
                "Tip: the \"Quick Access\" app icon opens the panel too. Assign it to a BOOX gesture or the Navigation Ball " +
                    "if you'd rather not reserve a strip of the screen edge. There's also a Quick Settings tile.",
            ),
        )
    }

    private fun pinsSection() {
        list.addView(sectionTitle("Quick Access items"))
        val pins = prefs.pins
        if (pins.isEmpty()) list.addView(paragraph("No items yet."))
        pins.forEachIndexed { i, pin ->
            list.addView(itemRow(pin, i, pins.size, onMove = { from, to -> prefs.pins = pins.move(from, to) },
                onEdit = { editPin(pin) }, onRemove = { prefs.removePin(pin.id); render() }))
        }
        list.addView(buttonRow(
            "+ BOOX note" to { editPin(null) },
            "+ Document" to { pickDocument(REQ_FILE) },
            "+ Folder" to { pickTree(REQ_FOLDER) },
        ))
        list.addView(buttonRow(
            "+ App" to { pickApp { prefs.addPin(it); render() } },
            "+ Custom intent" to { editIntent() },
        ))
        list.addView(
            paragraph(
                "BOOX notes open by name: SuperBoox starts BOOX Notes at its library and taps the note for you. " +
                    "Use \"Folder / Note\" for notes in folders. The easiest way to add one is to open BOOX Notes, " +
                    "swipe the panel open and tap \"Pin a note from this screen\".",
            ),
        )
    }

    private fun dockSection() {
        list.addView(sectionTitle("Apps at the bottom"))
        val dock = prefs.dock
        if (dock.isEmpty()) list.addView(paragraph("No apps yet."))
        dock.forEachIndexed { i, app ->
            list.addView(itemRow(app, i, dock.size, onMove = { from, to -> prefs.dock = dock.move(from, to) },
                onEdit = null, onRemove = { prefs.dock = dock.filterNot { it.id == app.id }; render() }))
        }
        list.addView(buttonRow("+ Add app" to { pickApp { prefs.dock = prefs.dock + it; render() } }))
    }

    private fun recentSection() {
        list.addView(sectionTitle("Recent files"))
        val uri = prefs.recentFolderUri
        list.addView(
            paragraph(
                if (uri == null) "Show the newest files from a folder, e.g. where BOOX Notes exports PDFs (often /note or /Documents)."
                else "Folder: ${Launcher.displayName(this, Uri.parse(uri))}",
            ),
        )
        if (uri != null) {
            list.addView(slider("Files shown", prefs.recentCount, 1, 20, "") { prefs.recentCount = it })
        }
        list.addView(buttonRow(
            *listOfNotNull(
                (if (uri == null) "Choose folder" else "Change folder") to { pickTree(REQ_RECENT) },
                if (uri != null) "Don't show" to { prefs.recentFolderUri = null; render() } else null,
            ).toTypedArray(),
        ))
    }

    private fun advancedSection() {
        list.addView(sectionTitle("Advanced"))
        val pkg = prefs.notesPackage
        val installed = BooxNotes.isInstalled(this, pkg)
        list.addView(choiceRow("BOOX Notes package", if (installed) pkg else "$pkg (not found)") {
            prompt("BOOX Notes package", pkg, "Package name") { prefs.notesPackage = it.ifBlank { BooxNotes.DEFAULT_PACKAGE } }
        })
        list.addView(
            paragraph(
                "Find a firmware-specific deep link with: adb shell dumpsys activity activities | grep -i onyx " +
                    "while the note is open, then add it as a Custom intent " +
                    "(e.g. intent:#Intent;component=com.onyx.android.note/.SomeActivity;S.extra=value;end).",
            ),
        )
    }

    private fun troubleshootingSection() {
        list.addView(sectionTitle("Troubleshooting"))
        list.addView(
            paragraph(
                "SuperBoox keeps a log of what it does on this device (${DebugLog.entries().size} entries): gestures, " +
                    "the app in front, launches, and each step of opening a BOOX note. It never leaves the device " +
                    "unless you copy or share the report.",
            ),
        )
        list.addView(buttonRow(
            "Copy debug report" to { copyReport() },
            "Share…" to { shareReport() },
        ))
        list.addView(buttonRow("Clear log" to { DebugLog.clear(); toast("Log cleared"); render() }))
    }

    private fun copyReport() {
        val report = DebugReport.build(this)
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("SuperBoox debug report", report))
        DebugLog.i("report", "copied (${report.length} chars)")
        toast("Debug report copied (${report.length / 1024 + 1} KB)")
    }

    private fun shareReport() {
        val report = DebugReport.build(this)
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
            if (existing == null) prefs.addPin(Pin(PinType.BOOX_NOTE, l, p)) else replacePin(existing.copy(label = l, value = p))
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
        setPadding(dp(16), dp(6), dp(8), dp(6))
        val texts = vbox().apply {
            addView(text(pin.label, 17f))
            addView(text(describe(pin), 12f, color = Ink.MUTED).apply { maxLines = 1 })
        }
        addView(texts, lp(0, WRAP, 1f))
        fun small(label: String, enabled: Boolean = true, action: () -> Unit) {
            addView(Button(context).apply {
                text = label
                isEnabled = enabled
                minWidth = 0
                minimumWidth = dp(44)
                setOnClickListener { action(); render() }
            }, lp(WRAP, WRAP))
        }
        small("↑", index > 0) { onMove(index, index - 1) }
        small("↓", index < count - 1) { onMove(index, index + 1) }
        if (onEdit != null) small("Edit") { onEdit() }
        small("✕") { onRemove() }
    }

    private fun describe(pin: Pin): String = when (pin.type) {
        PinType.BOOX_NOTE -> "BOOX note · ${pin.value}"
        PinType.FILE -> "Document"
        PinType.FOLDER -> "Folder"
        PinType.APP -> "App · ${pin.value.substringBefore('/')}"
        PinType.INTENT -> "Intent · ${pin.value}"
    }

    private fun paragraph(s: String): TextView =
        text(s, 14f, color = Ink.MUTED).apply { setPadding(dp(16), dp(4), dp(16), dp(8)) }

    private fun buttonRow(vararg buttons: Pair<String, () -> Unit>): View = hbox().apply {
        setPadding(dp(12), dp(4), dp(12), dp(4))
        buttons.forEach { (label, action) ->
            addView(Button(context).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            }, lp(0, WRAP, 1f))
        }
    }

    private fun choiceRow(title: String, value: String, onClick: () -> Unit): View = hbox().apply {
        setPadding(dp(16), dp(12), dp(16), dp(12))
        addView(text(title, 17f), lp(0, WRAP, 1f))
        addView(text(value, 15f, color = Ink.MUTED).apply { gravity = Gravity.END })
        pressable()
        setOnClickListener { onClick() }
    }

    private fun check(title: String, value: Boolean, onChange: (Boolean) -> Unit): View = CheckBox(this).apply {
        text = title
        textSize = 17f
        isChecked = value
        setPadding(dp(8), dp(8), dp(16), dp(8))
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
        layoutParams = lp(MATCH, WRAP).apply { marginStart = dp(8) }
    }

    private fun slider(title: String, value: Int, min: Int, max: Int, unit: String, onChange: (Int) -> Unit): View = vbox().apply {
        setPadding(dp(16), dp(8), dp(16), dp(8))
        val label = text("$title: $value $unit".trim(), 15f)
        addView(label)
        addView(SeekBar(context).apply {
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
