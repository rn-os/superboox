package com.superboox.quickaccess

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * English names for Control Center tiles on a tablet set to another language.
 *
 * Quick Controls find their tile by its English name ("Bluetooth", "Full Refresh"). The labels
 * come from the system UI's own text, so each label shown in the tablet's language is looked up
 * there and swapped for the same text in English. The BOOX tiles live in the same place, so they
 * translate too. Built once per language and kept.
 */
object TileNames {
    private const val SYSTEM_UI = "com.android.systemui"

    @Volatile private var cache: Pair<String, List<Pair<String, String>>>? = null

    /** [label] in English when it's a known Control Center text in another language, else null. */
    fun english(context: Context, label: String): String? {
        val pairs = table(context) ?: return null
        val l = label.trim()
        // Labels can carry a state after the name ("Bluetooth, On"): longest known text the label starts with.
        return pairs.firstOrNull { (local, _) -> l.startsWith(local, ignoreCase = true) }?.second
    }

    /** [text] in English only when it's exactly a known Control Center text (button names). */
    fun exact(context: Context, text: String): String? {
        val t = text.trim()
        return table(context)?.firstOrNull { (local, _) -> local.equals(t, ignoreCase = true) }?.second
    }

    /** Local text → English, longest first; null on an English tablet or if the system UI can't be read. */
    private fun table(context: Context): List<Pair<String, String>>? {
        val locale = context.resources.configuration.locales[0]
        if (locale.language == "en") return null
        cache?.let { (tag, pairs) -> if (tag == locale.toLanguageTag()) return pairs }
        val pairs = runCatching { build(context) }
            .onFailure { DebugLog.w("controls", "Control Center names not readable: ${it.javaClass.simpleName} ${it.message}") }
            .getOrNull() ?: emptyList()
        cache = locale.toLanguageTag() to pairs
        DebugLog.i("controls", "Control Center names: ${pairs.size} translated from ${locale.toLanguageTag()}")
        return pairs
    }

    private fun build(context: Context): List<Pair<String, String>> {
        val ui = context.createPackageContext(SYSTEM_UI, 0)
        val local = ui.resources
        val english = ui.createConfigurationContext(Configuration(local.configuration).apply { setLocale(Locale.ENGLISH) }).resources
        // Any known label gives the id range of the system UI's text; walk it until the ids run out.
        val anchor = listOf("quick_settings_bluetooth_label", "quick_settings_wifi_label", "airplane_mode", "quick_settings_dnd_label", "ok")
            .firstNotNullOfOrNull { name -> local.getIdentifier(name, "string", SYSTEM_UI).takeIf { it != 0 } }
            ?: return emptyList()
        val base = anchor and 0xFFFF0000.toInt()
        val out = HashMap<String, String>()
        var misses = 0
        var entry = 0
        while (misses < 400 && entry < 0x8000) {
            val id = base or entry
            entry++
            val l = try { local.getString(id) } catch (_: Resources.NotFoundException) { misses++; continue }
            misses = 0
            if (l.length !in 2..40) continue
            val e = runCatching { english.getString(id) }.getOrNull() ?: continue
            if (e != l && e.length in 2..40) out.putIfAbsent(l.trim(), e.trim())
        }
        return out.entries.map { it.key to it.value }.sortedByDescending { it.first.length }
    }
}
