package com.superboox.quickaccess

import org.json.JSONObject
import java.util.UUID

enum class PinType {
    /** A note inside BOOX Notes, addressed by its name ("Folder / Note"). */
    BOOX_NOTE,

    /** A single document opened through the Storage Access Framework. */
    FILE,

    /** A SAF folder; expands inline in the panel to show its newest files. */
    FOLDER,

    /** A launchable app, value is "package" or "package/activity". */
    APP,

    /** Any intent in Intent.toUri(URI_INTENT_SCHEME) form. Escape hatch for firmware-specific deep links. */
    INTENT,
}

data class Pin(
    val type: PinType,
    val label: String,
    val value: String,
    val id: String = UUID.randomUUID().toString(),
    /** BOOX_NOTE only: the note's id in the Notes database, saved when pinned ("" if not found yet). */
    val noteId: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type.name)
        .put("label", label)
        .put("value", value)
        .apply { if (noteId.isNotEmpty()) put("noteId", noteId) }

    companion object {
        fun fromJson(o: JSONObject): Pin? {
            val type = runCatching { PinType.valueOf(o.getString("type")) }.getOrNull() ?: return null
            return Pin(
                type = type,
                label = o.optString("label"),
                value = o.optString("value"),
                id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                noteId = o.optString("noteId"),
            )
        }
    }
}
