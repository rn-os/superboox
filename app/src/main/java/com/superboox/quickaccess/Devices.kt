package com.superboox.quickaccess

import android.os.Build

/**
 * Which BOOX this is, and what has been checked on it. SuperBoox works by reading BOOX's own
 * screens and files, which differ between models and firmware, so a feature confirmed on one
 * tablet is only "not yet tested" on another until someone has tried it there.
 *
 * Add a tablet to [TABLE] only for what was actually seen working on it (a device check or
 * a user's debug report), with the build it was checked on.
 */
object Devices {
    enum class Feature(val title: String) {
        PINNED_NOTES("Open pinned notes from any app"),
        ADD("Add from a note, the Notes library and BOOX's Library"),
        LAST_OPENED("Last opened note"),
        NEW_NOTE("Hold New Note for BOOX's create-note screen"),
        HANDWRITING_KEEP("Keep every handwriting copy"),
        HANDWRITING_PASTE("Paste an older handwriting clip"),
    }

    enum class Status(val label: String) {
        CONFIRMED("✓ Confirmed"), OTHER_FIRMWARE("Other firmware"), UNTESTED("Not yet tested")
    }

    /** One model on one firmware: what was seen working there, and the settings it was seen working with. */
    class Known(
        val model: String,
        val name: String,
        val firmware: String,
        val checkedOn: String,
        val confirmed: Set<Feature>,
        val settings: List<String>,
    )

    private val TABLE = listOf(
        Known(
            model = "NoteAir6C", name = "Note Air 6C", firmware = "4.3", checkedOn = "1.1.0-dev.79",
            confirmed = Feature.values().toSet(),
            settings = listOf(
                "All files access on: needed to keep every handwriting copy and to paste older ones.",
                "Advanced access (Shizuku) isn't needed for any of these.",
                "Allow SuperBoox's notifications, or its short messages (\"Added\", \"Handwriting copied\") don't show.",
            ),
        ),
    )

    val model: String get() = Build.MODEL.orEmpty()

    /** BOOX's firmware version ("4.3") from its build name ("2026-09-16_13-47_4.3-rel_0916_…"), if it's there. */
    val firmware: String? get() = firmwareOf(Build.DISPLAY.orEmpty())

    fun firmwareOf(display: String): String? = Regex("_(\\d+\\.\\d+(?:\\.\\d+)?)-").find(display)?.groupValues?.get(1)

    private fun sameModel(k: Known) = k.model.equals(model.replace(" ", ""), ignoreCase = true)

    /** This model's entry for this firmware, else its entry for another firmware. */
    fun known(): Known? = TABLE.firstOrNull { sameModel(it) && it.firmware == firmware } ?: TABLE.firstOrNull(::sameModel)

    fun status(feature: Feature): Status {
        val k = known() ?: return Status.UNTESTED
        return when {
            feature !in k.confirmed -> Status.UNTESTED
            k.firmware == firmware -> Status.CONFIRMED
            else -> Status.OTHER_FIRMWARE
        }
    }

    /** "Note Air 6C · firmware 4.3", or the model as Android names it. */
    fun summary(): String = (known()?.name ?: "${Build.MANUFACTURER.orEmpty()} $model".trim()) + " · firmware ${firmware ?: "unknown"}"

    /** One line on how far SuperBoox has been checked on this tablet. */
    fun verdict(): String {
        val k = known() ?: return "SuperBoox hasn't been checked on this model yet."
        return if (k.firmware == firmware) "Checked on this model and firmware (SuperBoox ${k.checkedOn})."
        else "Checked on this model with firmware ${k.firmware}; yours is ${firmware ?: "unknown"}."
    }

    /** For the debug report. */
    fun describe(): String = buildString {
        append("known to SuperBoox: ${verdict()}")
        Feature.values().forEach { f -> append("\n  ${status(f).name.lowercase()}: ${f.title}") }
    }
}
