package com.superboox.quickaccess

import org.json.JSONArray
import org.json.JSONObject

/** Something opened recently: a BOOX note (by name) or a document in a reading app. */
data class RecentItem(
    val kind: Kind,
    /** Note name, or document title (may be the reading app's name when the title isn't known). */
    val title: String,
    /** Package that showed it: the Notes editor or the reading app. */
    val pkg: String,
    val time: Long,
    /** DOCUMENT in NeoReader: the file's path, so it can be reopened directly ("" if unknown). */
    val path: String = "",
) {
    enum class Kind { NOTE, DOCUMENT }

    val key: String get() = "$kind|$pkg|${title.trim().lowercase()}"

    fun toJson(): JSONObject = JSONObject().put("kind", kind.name).put("title", title).put("pkg", pkg).put("time", time)
        .apply { if (path.isNotEmpty()) put("path", path) }

    companion object {
        fun fromJson(o: JSONObject): RecentItem? = runCatching {
            RecentItem(Kind.valueOf(o.getString("kind")), o.getString("title"), o.getString("pkg"), o.getLong("time"), o.optString("path"))
        }.getOrNull()
    }
}

/**
 * Remembers recently opened notes and documents for "Last Opened Note",
 * "Last Opened Document" and "Recent Files". Stored on the device only.
 */
object Recents {
    private const val MAX = 20

    fun list(prefs: Prefs): List<RecentItem> {
        val raw = prefs.sp.getString(Prefs.K_RECENTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { RecentItem.fromJson(arr.getJSONObject(it)) }
                // Earlier builds could save a BOOX Notes tool pop-up ("Pen 0.30mm") as a note; leave those out.
                .filterNot { it.kind == RecentItem.Kind.NOTE && isToolName(it.title) }
        }.getOrDefault(emptyList())
    }

    private val TOOL_NAME = Regex(
        """^(pen|pencil|fountain pen|ballpoint|brush|marker|highlighter|eraser|lasso|calligraphy|charcoal|shape|line)\b.*""",
        RegexOption.IGNORE_CASE,
    )

    /** A BOOX Notes tool name with a size ("Pen 0.30mm"), not a note. */
    fun isToolName(title: String): Boolean = TOOL_NAME.matches(title.trim()) && title.any { it.isDigit() }

    fun lastNote(prefs: Prefs): RecentItem? = list(prefs).firstOrNull { it.kind == RecentItem.Kind.NOTE }

    fun lastDocument(prefs: Prefs): RecentItem? = list(prefs).firstOrNull { it.kind == RecentItem.Kind.DOCUMENT }

    /** Moves (or adds) [item] to the front. Skips the write when it's already the newest entry. */
    fun record(prefs: Prefs, item: RecentItem) {
        if (item.title.isBlank()) return
        if (item.kind == RecentItem.Kind.NOTE && isToolName(item.title)) return DebugLog.i("recent", "skipped tool \"${item.title}\"")
        val current = list(prefs)
        val sameAsNewest = current.firstOrNull()?.key == item.key
        val updated = (listOf(item) + current.filterNot { it.key == item.key }).take(MAX)
        save(prefs, updated)
        if (!sameAsNewest) DebugLog.i("recent", "${item.kind} \"${item.title}\" (${item.pkg})")
    }

    private fun save(prefs: Prefs, items: List<RecentItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs.sp.edit().putString(Prefs.K_RECENTS, arr.toString()).apply()
    }

    /** Takes one entry off the list (by [RecentItem.key]). It comes back the next time it's opened. */
    fun remove(prefs: Prefs, key: String) {
        save(prefs, list(prefs).filterNot { it.key == key })
        DebugLog.i("recent", "removed one entry")
    }

    /** Empties the list. Only what SuperBoox remembers: no note or file is touched. */
    fun clear(prefs: Prefs) {
        prefs.sp.edit().remove(Prefs.K_RECENTS).apply()
        DebugLog.i("recent", "list cleared")
    }

    // Files from the folder chosen in Settings aren't remembered, they're listed fresh each time,
    // so clearing one is remembered instead: as it was then, so it shows again once it changes.

    fun fileKey(uri: String, modified: Long): String = "$uri|$modified"

    fun hiddenFiles(prefs: Prefs): Set<String> = runCatching {
        val arr = JSONArray(prefs.sp.getString(Prefs.K_RECENT_HIDDEN, null) ?: return emptySet())
        (0 until arr.length()).mapTo(LinkedHashSet()) { arr.getString(it) }
    }.getOrDefault(emptySet())

    fun hideFiles(prefs: Prefs, keys: Collection<String>) {
        if (keys.isEmpty()) return
        val arr = JSONArray()
        (hiddenFiles(prefs) + keys).toList().takeLast(MAX_HIDDEN).forEach { arr.put(it) }
        prefs.sp.edit().putString(Prefs.K_RECENT_HIDDEN, arr.toString()).apply()
    }

    private const val MAX_HIDDEN = 200

    /**
     * Reading apps whose "last document" is worth remembering. Known readers
     * plus a name heuristic; BOOX Notes, launchers and sync helpers are excluded.
     */
    fun isReader(pkg: String, notesPackage: String): Boolean {
        if (pkg == notesPackage || pkg == BooxNotes.BOOX_HOME_PACKAGE) return false
        if (pkg in KNOWN_READERS) return true
        val p = pkg.lowercase()
        if ("sync" in p || "launcher" in p || "store" in p || "vending" in p) return false
        return listOf("reader", "kindle", "koreader", "books", "pdf", "epub", "comic").any { it in p }
    }

    private val KNOWN_READERS = setOf(
        "com.onyx.kreader", // NeoReader
        "org.koreader.launcher",
        "org.koreader.launcher.fdroid",
        "com.amazon.kindle",
        "com.google.android.apps.books",
        "com.kobobooks.android",
        "com.flyersoft.moonreader",
        "com.flyersoft.moonreaderp",
        "org.readera",
        "org.readera.premium",
        "com.adobe.reader",
        "com.xodo.pdf.reader",
        "org.sufficientlysecure.viewer",
    )
}
