package com.superboox.quickaccess

/**
 * The panel's middle sections, in the order the user set in Settings → Look & size → Panel
 * sections. The top bar always comes first and the apps last; Quick Access takes the height left.
 */
enum class PanelSection(val id: String, val title: String, val summary: String) {
    CONTROLS("controls", "Quick Controls", "Wi‑Fi, Front Light and the other switches"),
    NOTE("note", "Last Opened Note", "Tap reopens it"),
    DOCUMENT("document", "Last Opened Document", "Tap reopens it"),
    HIGHLIGHTS("highlights", "Highlights", "Latest from the open book (Digests)"),
    QUICK("quick", "Quick Access", "Your pinned items; takes the space left"),
}

object PanelSections {
    val DEFAULT = listOf(PanelSection.CONTROLS, PanelSection.NOTE, PanelSection.DOCUMENT, PanelSection.HIGHLIGHTS, PanelSection.QUICK)

    /** Every section, in the saved order (sections added in later versions go where they'd be by default). */
    fun order(prefs: Prefs): List<PanelSection> {
        val saved = prefs.sp.getString(Prefs.K_PANEL_ORDER, null)?.split(',')
            ?.mapNotNull { id -> PanelSection.entries.firstOrNull { it.id == id } }?.distinct().orEmpty()
        if (saved.isEmpty()) return DEFAULT
        val out = saved.toMutableList()
        DEFAULT.filter { it !in out }.forEach { missing ->
            val after = DEFAULT.getOrNull(DEFAULT.indexOf(missing) - 1)
            out.add(if (after == null) 0 else out.indexOf(after) + 1, missing)
        }
        return out
    }

    fun save(prefs: Prefs, order: List<PanelSection>) {
        prefs.sp.edit().putString(Prefs.K_PANEL_ORDER, order.joinToString(",") { it.id }).apply()
    }

    fun reset(prefs: Prefs) {
        prefs.sp.edit().remove(Prefs.K_PANEL_ORDER).remove(Prefs.K_PANEL_HIDDEN).apply()
        prefs.digestPanel = true
    }

    /** Ticked in Settings. Highlights keeps its own setting (also in Settings → Digests). */
    fun ticked(prefs: Prefs, s: PanelSection): Boolean = when (s) {
        PanelSection.HIGHLIGHTS -> prefs.digestPanel
        else -> s.id !in hidden(prefs)
    }

    /** Drawn in the panel: ticked, and Quick Controls only when it has controls. */
    fun shown(prefs: Prefs, s: PanelSection): Boolean = ticked(prefs, s) && (s != PanelSection.CONTROLS || prefs.showSystemActions)

    fun setShown(prefs: Prefs, s: PanelSection, on: Boolean) {
        when (s) {
            PanelSection.HIGHLIGHTS -> prefs.digestPanel = on
            else -> prefs.sp.edit().putStringSet(Prefs.K_PANEL_HIDDEN, if (on) hidden(prefs) - s.id else hidden(prefs) + s.id).apply()
        }
    }

    private fun hidden(prefs: Prefs): Set<String> = prefs.sp.getStringSet(Prefs.K_PANEL_HIDDEN, null).orEmpty()

    fun isDefault(prefs: Prefs): Boolean = order(prefs) == DEFAULT && PanelSection.entries.all { ticked(prefs, it) }
}
