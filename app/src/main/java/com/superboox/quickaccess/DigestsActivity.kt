package com.superboox.quickaccess

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The Digests screen (its own app icon): every highlight, by book. Wide screens show the books
 * on the left and the highlights on the right; narrow ones (split screen beside a book) show one
 * column with a book chooser. Pages instead of scrolling where it fits, black on white, no
 * animation, like the rest of SuperBoox.
 */
class DigestsActivity : Activity() {

    private enum class Sort(val label: String) { BY_PAGE("By page"), NEWEST("Newest"), NOTES("With notes"), PINNED("Pinned") }

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private val io = Executors.newSingleThreadExecutor()

    private var all: List<Digests.Highlight> = emptyList()
    private var book: String? = null // null = All highlights
    private var sort = Sort.BY_PAGE
    private var source: Digests.Source? = null
    private var query = ""
    private var searching = false
    private var page = 0
    private var expanded: String? = null
    private var editing: String? = null
    private var scanning = false
    private var seenVersion = -1

    private val renderNow = Runnable { rebuild() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        root = vbox().apply { setBackgroundColor(Ink.PAPER) }
        setContentView(root)
        book = intent?.getStringExtra(EXTRA_BOOK)
        all = Digests.all(this)
        if (book == null) book = followOpenBook()
        if (book != null) sort = Sort.BY_PAGE else sort = Sort.NEWEST
        rebuild()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.getStringExtra(EXTRA_BOOK)?.let { book = it; page = 0; sort = Sort.BY_PAGE; render() }
    }

    override fun onResume() {
        super.onResume()
        reload()
        refreshKoreader()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render() // wide ↔ narrow (rotation, split screen)
    }

    /** The book open in a reading app (the last document SuperBoox saw), if it has highlights here. */
    private fun followOpenBook(): String? {
        val last = Recents.list(prefs).firstOrNull { it.kind == RecentItem.Kind.DOCUMENT } ?: return null
        return Digests.books(all).firstOrNull { NotesScreen.matches(it.title, Recents.noteName(last.title)) || NotesScreen.matches(it.title, last.title) }?.key
    }

    private fun reload() {
        all = Digests.all(this)
        seenVersion = Digests.version
        render()
    }

    private fun refreshKoreader() {
        if (scanning) return
        scanning = true
        render()
        io.execute {
            runCatching { Digests.refreshKoreader(applicationContext) }
            runOnUiThread {
                scanning = false
                if (Digests.version != seenVersion) reload() else render()
            }
        }
    }

    /** Rebuilds on the next turn of the main loop (never inside a tap: see SettingsActivity.render). */
    private fun render() {
        root.removeCallbacks(renderNow)
        root.post(renderNow)
    }

    private val wide: Boolean get() = resources.configuration.screenWidthDp >= 600

    // ---- Layout ------------------------------------------------------------------------------

    private fun rebuild() {
        root.removeCallbacks(renderNow)
        root.removeAllViews()
        val books = Digests.books(all)
        if (book != null && books.none { it.key == book }) book = null
        val current = books.firstOrNull { it.key == book }
        root.addView(appBar(if (!wide && current != null) current.title else "Digests"))
        if (searching) root.addView(searchRow())
        if (wide) {
            root.addView(hbox().apply {
                gravity = Gravity.TOP
                addView(rail(books), lp(dp(270), MATCH))
                addView(View(context).apply { setBackgroundColor(Ink.INK) }, lp(dp(2), MATCH))
                addView(main(current, books), lp(0, MATCH, 1f))
            }, lp(MATCH, 0, 1f))
        } else {
            root.addView(main(current, books), lp(MATCH, 0, 1f))
        }
    }

    private fun appBar(title: String): View = vbox().apply {
        addView(hbox().apply {
            setPadding(dp(16), dp(10), dp(12), dp(10))
            addView(icon(getDrawable(R.drawable.ic_digest), 28))
            addView(text(title, 24f, bold = true).apply {
                setPadding(dp(12), 0, dp(8), 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, lp(0, WRAP, 1f))
            addView(inkIconButton(R.drawable.ic_search, "Search") { searching = !searching; if (!searching) query = ""; page = 0; render() })
            addView(inkIconButton(R.drawable.ic_export, "Export") { export(null) })
            addView(inkIconButton(R.drawable.ic_settings, "Digests settings") {
                startActivity(Intent(this@DigestsActivity, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, "DIGESTS"))
            })
        })
        addView(divider(3))
    }

    private fun searchRow(): View = EditText(this).apply {
        hint = "Search highlights and notes"
        setText(query)
        setSelection(query.length)
        textSize = 16f
        setSingleLine()
        background = outline(2, 8)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        layoutParams = lp(MATCH, WRAP).apply { setMargins(dp(12), dp(8), dp(12), dp(4)) }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                page = 0
                // Redraw only the results: rebuilding would take the keyboard away.
                removeCallbacks(searchAgain)
                postDelayed(searchAgain, 500)
            }
        })
        post { requestFocus() }
    }

    private val searchAgain = Runnable {
        val books = Digests.books(all)
        val current = books.firstOrNull { it.key == book }
        // Replace the main area in place; the search box keeps focus.
        val body = root.getChildAt(root.childCount - 1)
        if (wide && body is LinearLayout) {
            body.removeViewAt(2)
            body.addView(main(current, books), 2, lp(0, MATCH, 1f))
        } else {
            root.removeViewAt(root.childCount - 1)
            root.addView(main(current, books), lp(MATCH, 0, 1f))
        }
    }

    private fun rail(books: List<Digests.Book>): View = ScrollView(this).apply {
        addView(vbox().apply {
            addView(label("BOOKS"))
            addView(bookRow("All highlights", "${books.size} books · ${all.size}", null, book == null))
            books.forEach { b ->
                addView(divider(1))
                addView(bookRow(b.title, listOfNotNull(b.source.label, ago(b.latest)).joinToString(" · "), b, book == b.key))
            }
            addView(divider(1))
        })
    }

    private fun bookRow(title: String, sub: String, b: Digests.Book?, on: Boolean): View = hbox().apply {
        setPadding(dp(12), dp(10), dp(12), dp(10))
        if (on) setBackgroundColor(Ink.INK)
        val fg = if (on) Ink.PAPER else Ink.INK
        addView(icon(getDrawable(R.drawable.ic_book), 22).apply { imageTintList = android.content.res.ColorStateList.valueOf(fg) })
        addView(vbox().apply {
            setPadding(dp(10), 0, dp(6), 0)
            addView(text(title, 15f, bold = true, color = fg).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(text(sub, 12f, color = if (on) Ink.PAPER else Ink.MUTED).apply { maxLines = 1 })
        }, lp(0, WRAP, 1f))
        b?.let { addView(text("${it.count}", 13f, bold = true, color = fg)) }
        if (!on) pressable()
        isClickable = true
        setOnClickListener {
            book = b?.key
            sort = if (b == null) Sort.NEWEST else Sort.BY_PAGE
            page = 0
            expanded = null
            render()
        }
    }

    private fun main(current: Digests.Book?, books: List<Digests.Book>): View = vbox().apply {
        // Narrow: a book chooser replaces the list on the left.
        if (!wide) addView(hbox().apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(text("Book", 15f, medium = true), lp(0, WRAP, 1f))
            addView(text((current?.title ?: "All highlights") + "  ▾", 15f, bold = true))
            pressable()
            setOnClickListener { chooseBook(books) }
        })
        if (!wide) addView(divider(1))
        val shown = filtered(current)
        if (wide) addView(vbox().apply {
            setPadding(dp(16), dp(12), dp(16), dp(4))
            addView(text(current?.title ?: "All highlights", 21f, bold = true))
            val sub = if (current == null) "${all.size} highlights" else listOfNotNull(current.author, current.source.label, "${current.count} highlights").joinToString(" · ")
            addView(text(sub + if (scanning) " · looking for new KOReader highlights…" else "", 13f, color = Ink.MUTED))
        })
        addView(chips(current))
        if (all.isEmpty()) {
            addView(emptyHelp())
            return@apply
        }
        val pages = ((shown.size + PER_PAGE - 1) / PER_PAGE).coerceAtLeast(1)
        page = page.coerceIn(0, pages - 1)
        val list = vbox()
        if (shown.isEmpty()) list.addView(text(if (query.isNotEmpty()) "Nothing matches “$query”." else "Nothing here yet.", 15f, color = Ink.MUTED).apply { setPadding(dp(20), dp(16), dp(20), dp(16)) })
        shown.drop(page * PER_PAGE).take(PER_PAGE).forEach { list.addView(card(it, current == null)) }
        addView(ScrollView(context).apply { addView(list) }, lp(MATCH, 0, 1f))
        addView(pager(pages))
    }

    private fun filtered(current: Digests.Book?): List<Digests.Highlight> {
        var l = all
        if (current != null) l = l.filter { it.bookKey == current.key }
        source?.let { s -> if (current == null) l = l.filter { it.source == s } }
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            l = l.filter { it.text.lowercase().contains(q) || it.note.lowercase().contains(q) || it.appNote.lowercase().contains(q) || it.book.lowercase().contains(q) }
        }
        l = when (sort) {
            Sort.BY_PAGE -> Digests.sortForBook(l)
            Sort.NEWEST -> l.sortedByDescending { it.time }
            Sort.NOTES -> l.filter { it.note.isNotBlank() || it.appNote.isNotBlank() }.sortedByDescending { it.time }
            Sort.PINNED -> l.filter { it.pinned }.sortedByDescending { it.time }
        }
        // Pinned first everywhere except their own filter.
        return if (sort == Sort.PINNED) l else l.sortedByDescending { it.pinned }
    }

    private fun chips(current: Digests.Book?): View = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(hbox().apply {
            setPadding(dp(12), dp(6), dp(12), dp(8))
            val sorts = if (current == null) listOf(Sort.NEWEST, Sort.NOTES, Sort.PINNED) else Sort.entries
            sorts.forEach { s -> addView(chip(s.label, sort == s) { sort = s; page = 0; render() }) }
            if (current == null) {
                addView(View(context), lp(dp(10), 1))
                addView(chip("All apps", source == null) { source = null; page = 0; render() })
                Digests.Source.entries.filter { s -> all.any { it.source == s } }.forEach { s ->
                    addView(chip(s.label, source == s) { source = s; page = 0; render() })
                }
            }
        })
    }

    private fun chip(label: String, on: Boolean, onClick: () -> Unit): View =
        text(label, 14f, bold = true, color = if (on) Ink.PAPER else Ink.INK).apply {
            background = outline(2, 16, if (on) Ink.INK else Ink.PAPER)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            layoutParams = lp(WRAP, WRAP).apply { marginEnd = dp(6) }
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun pager(pages: Int): View = hbox().apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(6), 0, dp(10))
        addView(inkIconButton(R.drawable.ic_chevron_left, "Previous page", 40, enabled = page > 0) { page--; expanded = null; render() })
        addView(text("${page + 1} / $pages", 15f, bold = true).apply { setPadding(dp(14), 0, dp(8), 0) })
        addView(inkIconButton(R.drawable.ic_chevron_right, "Next page", 40, enabled = page < pages - 1) { page++; expanded = null; render() })
    }

    // ---- A highlight -------------------------------------------------------------------------

    private fun card(h: Digests.Highlight, showBook: Boolean): View = hbox().apply {
        gravity = Gravity.TOP
        background = outline(2, 8)
        setPadding(dp(10), dp(9), dp(10), dp(9))
        layoutParams = lp(MATCH, WRAP).apply { setMargins(dp(12), dp(4), dp(12), dp(4)) }
        addView(View(context).apply { setBackgroundColor(Ink.INK) }, lp(dp(4), MATCH).apply { marginEnd = dp(10) })
        val open = expanded == h.id
        addView(vbox().apply {
            addView(text(h.text, 16f).apply {
                typeface = Typeface.SERIF
                setLineSpacing(0f, 1.15f)
                if (!open) { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }
            })
            addView(hbox().apply {
                setPadding(0, dp(4), 0, 0)
                if (h.pinned) addView(icon(getDrawable(R.drawable.ic_pin), 14).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(4) })
                if (showBook) addView(tag(h.source.label))
                val meta = listOfNotNull(
                    if (showBook) h.book else null,
                    h.page?.let { "p. $it" },
                    h.chapter?.takeIf { h.source != Digests.Source.SAVED || showBook },
                    ago(h.time),
                ).joinToString(" · ")
                addView(text(meta, 12f, color = Ink.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            })
            if (h.appNote.isNotBlank()) addView(noteLine(h.appNote, "${h.source.label} note"))
            if (editing == h.id) addView(noteEditor(h))
            else if (h.note.isNotBlank()) addView(noteLine(h.note, null))
            if (open && editing != h.id) addView(actions(h))
        }, lp(0, WRAP, 1f))
        isClickable = true
        setOnClickListener { expanded = if (open) null else h.id; editing = null; render() }
        setOnLongClickListener { expanded = h.id; render(); true }
    }

    private fun tag(s: String): View = text(s, 11f, bold = true).apply {
        background = outline(1, 4)
        setPadding(dp(5), 0, dp(5), 0)
        layoutParams = lp(WRAP, WRAP).apply { marginEnd = dp(6) }
    }

    private fun noteLine(note: String, who: String?): View = vbox().apply {
        setPadding(0, dp(6), 0, 0)
        addView(divider(1))
        addView(hbox().apply {
            gravity = Gravity.TOP
            setPadding(0, dp(5), 0, 0)
            addView(icon(getDrawable(R.drawable.ic_edit), 15))
            addView(text((who?.let { "$it: " } ?: "") + note, 14f).apply { setPadding(dp(6), 0, 0, 0) }, lp(0, WRAP, 1f))
        })
    }

    private fun noteEditor(h: Digests.Highlight): View = vbox().apply {
        setPadding(0, dp(8), 0, 0)
        val field = EditText(context).apply {
            hint = "Your note"
            setText(h.note)
            setSelection(h.note.length)
            textSize = 15f
            background = outline(2, 8)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            post { requestFocus() }
        }
        addView(field, lp(MATCH, WRAP))
        addView(hbox().apply {
            setPadding(0, dp(6), 0, 0)
            addView(inkButton("Save note") { Digests.setNote(this@DigestsActivity, h.id, field.text.toString()); editing = null; reload() }, lp(0, WRAP, 1f))
            addView(View(context), lp(dp(8), 1))
            addView(inkButton("Cancel") { editing = null; render() }, lp(0, WRAP, 1f))
        })
    }

    private fun actions(h: Digests.Highlight): View = vbox().apply {
        setPadding(0, dp(8), 0, 0)
        fun row(vararg b: View) = hbox().apply {
            b.forEachIndexed { i, v ->
                if (i > 0) addView(View(context), lp(dp(6), 1))
                addView(v, lp(0, WRAP, 1f))
            }
            layoutParams = lp(MATCH, WRAP).apply { topMargin = dp(6) }
        }
        val openLabel = if (h.source == Digests.Source.SAVED) "Open app" else "Open book"
        addView(row(
            inkButton(openLabel) { if (!Digests.open(this@DigestsActivity, h)) toast("Couldn't open it") else if (h.page != null) toast("Page ${h.page}") }.apply {
                background = outline(2, 6, Ink.INK); setTextColor(Ink.PAPER)
            },
            inkButton("Copy") { copy(h) },
            inkButton(if (h.note.isBlank()) "Add note" else "Edit note") { editing = h.id; render() },
        ))
        addView(row(
            inkButton("Share…") { share(h) },
            inkButton(if (h.pinned) "Unpin" else "Pin") { Digests.setPinned(this@DigestsActivity, h.id, !h.pinned); reload() },
            inkButton(if (h.source == Digests.Source.SAVED) "Delete" else "Hide") { hide(h) },
        ))
    }

    private fun quoted(h: Digests.Highlight): String =
        h.text + "\n— " + listOfNotNull(h.book, h.page?.let { "p. $it" }).joinToString(", ")

    private fun copy(h: Digests.Highlight) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Highlight", quoted(h)))
        toast("Copied")
    }

    private fun share(h: Digests.Highlight) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, quoted(h)).putExtra(Intent.EXTRA_SUBJECT, h.book)
        runCatching { startActivity(Intent.createChooser(send, "Share highlight")) }
    }

    private fun hide(h: Digests.Highlight) {
        val saved = h.source == Digests.Source.SAVED
        AlertDialog.Builder(this)
            .setTitle(if (saved) "Delete this passage?" else "Hide this highlight?")
            .setMessage(if (saved) "It's removed from Digests." else "It's removed from Digests only; your highlight in ${h.source.label} stays. Settings → Digests can show hidden highlights again.")
            .setPositiveButton(if (saved) "Delete" else "Hide") { _, _ -> Digests.hide(this, h); expanded = null; reload() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseBook(books: List<Digests.Book>) {
        val names = listOf("All highlights · ${all.size}") + books.map { "${it.title} · ${it.count}" }
        val selected = books.indexOfFirst { it.key == book } + 1
        AlertDialog.Builder(this)
            .setTitle("Book")
            .setSingleChoiceItems(names.toTypedArray(), selected) { d, which ->
                book = if (which == 0) null else books[which - 1].key
                sort = if (book == null) Sort.NEWEST else Sort.BY_PAGE
                page = 0
                expanded = null
                d.dismiss()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Nothing yet -------------------------------------------------------------------------

    private fun emptyHelp(): View = vbox().apply {
        setPadding(dp(16), dp(12), dp(16), dp(12))
        addView(text("No highlights yet", 18f, bold = true))
        val ko = KoreaderHighlights.installed(this@DigestsActivity) != null
        val lines = mutableListOf<String>()
        if (ko) lines += if (ClipHistory.hasFileAccess()) "KOReader: highlight as usual; they appear here." else "KOReader: allow All files access so SuperBoox can read KOReader's highlights."
        lines += "Any other app: select text, then Save to Digest in the menu that pops up (sometimes under ⋮), or Share → Save to Digest."
        lines.forEach { addView(text("• $it", 15f).apply { setPadding(0, dp(8), 0, 0) }) }
        if (ko && !ClipHistory.hasFileAccess()) addView(inkButton("Allow All files access") {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:$packageName")))
            }
        }.apply { layoutParams = lp(MATCH, WRAP).apply { topMargin = dp(14) } })
        if (scanning) addView(text("Looking for KOReader highlights…", 14f, color = Ink.MUTED).apply { setPadding(0, dp(12), 0, 0) })
    }

    // ---- Export ------------------------------------------------------------------------------

    private fun export(only: String?) {
        toast("Exporting…")
        io.execute {
            val result = runCatching { Digests.export(applicationContext, only) }
            runOnUiThread {
                result.onSuccess { n -> toast(if (n == 1) "Exported 1 book" else "Exported $n books") }
                    .onFailure { toast(it.message ?: "Couldn't export") }
            }
        }
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private fun label(s: String): View = text(s, 12f, bold = true).apply {
        letterSpacing = 0.06f
        setPadding(dp(14), dp(12), dp(14), dp(6))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun ago(t: Long): String? {
        if (t <= 0) return null
        val diff = System.currentTimeMillis() - t
        val min = diff / 60_000
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 24 * 60 -> "${min / 60} h ago"
            else -> {
                val then = Calendar.getInstance().apply { timeInMillis = t }
                val now = Calendar.getInstance()
                if (diff < 6L * 24 * 3600_000) SimpleDateFormat("EEE", Locale.getDefault()).format(Date(t))
                else if (then.get(Calendar.YEAR) == now.get(Calendar.YEAR)) SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(t))
                else SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(t))
            }
        }
    }

    companion object {
        const val EXTRA_BOOK = "book"
        private const val PER_PAGE = 6
    }
}
