package com.superboox.quickaccess

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.LinearLayout
import com.superboox.R

/**
 * The first-use card: the panel's own look (black outline, rounded, bold title, solid black main
 * button, outlined second one), so it reads the same on colour and black-and-white BOOX screens.
 */
object CueCard {
    fun build(
        service: Context,
        scale: Float,
        title: String,
        message: String,
        primary: Pair<String, () -> Unit>,
        secondary: Pair<String, () -> Unit>,
    ): View = with(ContextThemeWrapper(service, R.style.Theme_SuperBoox)) {
        fun s(v: Int) = dp(v * scale)
        vbox().apply {
            background = outline(2, 10)
            setPadding(s(14), s(12), s(14), s(12))
            minimumWidth = s(340)
            addView(text(title, 17f * scale, bold = true))
            addView(text(message, 14f * scale).apply { setPadding(0, s(4), 0, s(10)) })
            addView(hbox().apply {
                addView(inkButton(primary.first, 14f * scale, primary.second).apply {
                    setTextColor(Ink.PAPER)
                    background = outline(2, 6, fill = Ink.INK)
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(inkButton(secondary.first, 14f * scale, secondary.second),
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = s(8) })
            })
        }
    }
}
