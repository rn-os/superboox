package com.superboox.quickaccess

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads what BOOX Notes is showing, from its accessibility tree.
 *
 * Two screens matter:
 *  - the **library**: a scrolling list/grid of note and folder cards;
 *  - the **editor**: an open note, whose name sits in the top bar next to the
 *    back arrow, surrounded by tool labels ("Layer", "Lasso", "1/2", "0.50"…).
 *
 * These are heuristics on layout (where text is, what is scrollable, how big
 * the tappable card is) rather than on BOOX's internal view ids, so they keep
 * working across firmware versions and languages as long as the layout holds.
 */
object NotesScreen {

    class Card(
        val label: String,
        val node: AccessibilityNodeInfo,
        val bounds: Rect = Rect().also(node::getBoundsInScreen),
        /** Secondary text on the card: a date, or "6 notes" for a folder. */
        val detail: String? = null,
    ) {
        /** Folders show an item count instead of a date. */
        val isFolder: Boolean get() = detail?.let { FOLDER_HINT.containsMatchIn(it) } == true
    }

    sealed class State {
        /** An open note. [title] is the note's name as shown in the top bar. */
        class Editor(val title: String) : State()

        /** The library (or a folder in it). */
        class Library(val cards: List<Card>, val list: AccessibilityNodeInfo?) : State()

        object Unknown : State()
    }

    fun read(root: AccessibilityNodeInfo): State {
        val screen = Rect().also(root::getBoundsInScreen)
        // The editor names its title view note_name (seen on firmware 4.3); trust it first.
        // Its tool column can look like a grid of cards ("Layer", "Margin", … seen in build 17).
        noteNameView(root)?.let { title ->
            // Blank while a note is still loading: not the library, just not ready yet.
            return if (title.isEmpty()) State.Unknown else State.Editor(title)
        }
        val list = mainList(root, screen)
        val cards = cards(root, screen)
        if (cards.isNotEmpty()) return State.Library(cards, list)
        // No cards: an open note (its tall tool column may scroll, so a list alone
        // doesn't mean library). An empty folder also lands here, and backing out
        // of it is the right move anyway.
        editorTitle(root, screen)
            // Screen headings, not note names (seen: the library read as a note called "Library").
            ?.takeUnless { norm(it) in NOT_NOTE_TITLES }
            ?.let { return State.Editor(it) }
        return if (list != null) State.Library(emptyList(), list) else State.Unknown
    }

    /** Identifies what's on screen, to tell whether a tap or page flip changed anything. */
    fun signature(state: State): String = when (state) {
        is State.Editor -> "editor:" + norm(state.title)
        is State.Library -> "library:" + state.cards.joinToString("|") { norm(it.label) }
        State.Unknown -> "unknown"
    }

    /**
     * True if a label shown on screen refers to [wanted]. Handles names that
     * BOOX ellipsizes ("SuperB…x Notes") on either side.
     */
    fun matches(shown: String, wanted: String): Boolean {
        val a = norm(shown)
        val b = norm(wanted)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        return ellipsisMatch(a, b) || ellipsisMatch(b, a)
    }

    // ---- Library -------------------------------------------------------------------------

    /** The biggest vertically scrolling container: the library list/grid. */
    private fun mainList(root: AccessibilityNodeInfo, screen: Rect): AccessibilityNodeInfo? =
        all(root)
            .filter { it.isScrollable && it.isVisibleToUser }
            .map { it to Rect().also(it::getBoundsInScreen) }
            .filter { (_, r) -> r.height() > screen.height() / 3 }
            .maxByOrNull { (_, r) -> r.width() * r.height() }
            ?.first

    /**
     * Note/folder cards: a tappable block that is big (grid tile or full-width
     * row) and below the top bar. Its name is the first text in it that isn't a
     * date, page count or size. Tabs, toolbars and sort buttons are far too
     * small, so they drop out.
     *
     * Deliberately no "must be inside a scrolling list" rule: BOOX's own apps
     * flip pages instead of scrolling, so their lists often aren't scrollable.
     */
    private fun cards(root: AccessibilityNodeInfo, screen: Rect): List<Card> {
        val minTall = screen.height() * 9 / 100
        val minWide = screen.width() * 40 / 100
        val maxTall = screen.height() * 60 / 100
        val topBar = screen.top + screen.height() * 12 / 100
        // Per card (keyed by its bounds): its name node and first secondary text.
        val names = LinkedHashMap<String, Pair<String, AccessibilityNodeInfo>>()
        val details = HashMap<String, String>()
        val rects = HashMap<String, Rect>()
        for (n in all(root)) {
            val text = textOf(n)
            if (text.isEmpty() || !n.isVisibleToUser) continue
            val card = clickableAncestor(n) ?: continue
            val r = Rect().also(card::getBoundsInScreen)
            if (r.height() < minTall && r.width() < minWide) continue
            if (r.height() > maxTall) continue // the whole page, not a card
            if (r.centerY() < topBar) continue
            val key = r.flattenToString()
            rects[key] = r
            if (isMeta(text)) details.putIfAbsent(key, text) else names.putIfAbsent(key, text to n)
        }
        val out = LinkedHashMap<String, Card>()
        for ((key, nameAndNode) in names) {
            val (text, n) = nameAndNode
            out.putIfAbsent(norm(text), Card(text, n, rects.getValue(key), details[key]))
        }
        return out.values.toList()
    }

    // ---- Editor --------------------------------------------------------------------------

    /** The open note's name from BOOX's own title view only (no guessing), for Recent Files. */
    fun trustedTitle(root: AccessibilityNodeInfo): String? = noteNameView(root)?.takeIf { it.isNotEmpty() }

    private fun noteNameView(root: AccessibilityNodeInfo): String? =
        runCatching { root.findAccessibilityNodeInfosByViewId("com.onyx.android.note:id/note_name") }.getOrNull()
            ?.firstOrNull { it.isVisibleToUser }
            ?.let { textOf(it) }

    /** Left-most real text in the top bar: the note's name (next to the back arrow). */
    private fun editorTitle(root: AccessibilityNodeInfo, screen: Rect): String? =
        all(root)
            .filter { it.isVisibleToUser }
            .mapNotNull { n ->
                val t = textOf(n)
                val r = Rect().also(n::getBoundsInScreen)
                // Centre inside the top bar (~top 7%): excludes the tool column's labels
                // ("Tem…", "Layer"), which start just below it.
                val inTopBar = r.centerY() < screen.top + screen.height() * 7 / 100 && r.left < screen.centerX()
                if (inTopBar && t.isNotEmpty() && !isMeta(t)) t to r.left else null
            }
            .minByOrNull { it.second }
            ?.first

    // ---- Helpers ---------------------------------------------------------------------------

    fun textOf(n: AccessibilityNodeInfo): String =
        (n.text?.toString() ?: n.contentDescription?.toString())?.trim()?.replace('\n', ' ').orEmpty()

    /** Dates, times, counts, sizes, page numbers, pen widths: never a note name. */
    private fun isMeta(t: String): Boolean {
        val s = t.trim()
        if (s.length > 80) return true
        if (META_NUMERIC.matches(s)) return true
        if (META_DATE.containsMatchIn(s) && s.count { it.isDigit() } >= 2) return true
        if (META_COUNT.matches(s)) return true
        return false
    }

    private val FOLDER_HINT = Regex("""\b\d+\s*(notes?|items?|files?)\b|folder""", RegexOption.IGNORE_CASE)
    private val META_NUMERIC = Regex("""^[\d\s.,:/%()+-]+$""")
    private val META_DATE = Regex(
        """\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+\d|\d{4}[-/.]\d{1,2}|\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}|\d{1,2}:\d{2}""",
        RegexOption.IGNORE_CASE,
    )
    private val META_COUNT = Regex(
        """^.*\b\d+\s*(notes?|pages?|items?|files?|kb|mb|gb)\b.*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun clickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        repeat(6) {
            val c = cur ?: return null
            if (c.isClickable || c.isLongClickable) return c
            cur = c.parent
        }
        return null
    }

    private fun all(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && out.size < MAX_NODES) {
            val n = queue.removeFirst()
            out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return out
    }

    private fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")

    /** "superb…x notes" matches "superboox notes". */
    private fun ellipsisMatch(shown: String, full: String): Boolean {
        val parts = shown.split("…", "...").map { it.trim() }
        if (parts.size != 2) return false
        val (head, tail) = parts
        if (head.isEmpty() && tail.isEmpty()) return false
        return full.startsWith(head) && full.endsWith(tail) && full.length >= head.length + tail.length
    }

    private val NOT_NOTE_TITLES = setOf("library", "notes", "note", "home", "recent", "all")

    private const val MAX_NODES = 3_000
}
