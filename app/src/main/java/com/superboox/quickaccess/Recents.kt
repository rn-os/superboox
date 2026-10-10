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
) {
    enum class Kind { NOTE, DOCUMENT }

    val key: String get() = "$kind|$pkg|${title.trim().lowercase()}"

    fun toJson(): JSONObject = JSONObject().put("kind", kind.name).put("title", title).put("pkg", pkg).put("time", time)

    companion object {
        fun fromJson(o: JSONObject): RecentItem? = runCatching {
            RecentItem(Kind.valueOf(o.getString("kind")), o.getString("title"), o.getString("pkg"), o.getLong("time"))
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
        }.getOrDefault(emptyList())
    }

    fun lastNote(prefs: Prefs): RecentItem? = list(prefs).firstOrNull { it.kind == RecentItem.Kind.NOTE }

    fun lastDocument(prefs: Prefs): RecentItem? = list(prefs).firstOrNull { it.kind == RecentItem.Kind.DOCUMENT }

    /** Moves (or adds) [item] to the front. Skips the write when it's already the newest entry. */
    fun record(prefs: Prefs, item: RecentItem) {
        if (item.title.isBlank()) return
        val current = list(prefs)
        val sameAsNewest = current.firstOrNull()?.key == item.key
        val updated = (listOf(item) + current.filterNot { it.key == item.key }).take(MAX)
        val arr = JSONArray()
        updated.forEach { arr.put(it.toJson()) }
        prefs.sp.edit().putString(Prefs.K_RECENTS, arr.toString()).apply()
        if (!sameAsNewest) DebugLog.i("recent", "${item.kind} \"${item.title}\" (${item.pkg})")
    }

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
