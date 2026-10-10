package com.superboox.quickaccess

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * Small line drawings of the panel for Settings, so a choice can be seen
 * before trying it: plain rows for the parts that show, and one black block
 * for the part being explained (the clipboard, say).
 */
object MiniPanel {
    fun view(context: Context, above: List<String>, highlight: String, below: List<String>): View = with(context) {
        vbox().apply {
            background = outline(2, 4)
            setPadding(dp(2), dp(2), dp(2), dp(2))
            clipToOutline = true
            fun row(label: String) = text(label, 9f, bold = true).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, dp(3), 0, dp(3))
            }
            above.forEachIndexed { i, l ->
                if (i > 0) addView(divider(1))
                addView(row(l), LinearLayout.LayoutParams(MATCH, WRAP))
            }
            addView(row(highlight).apply {
                setTextColor(Ink.PAPER)
                setBackgroundColor(Ink.INK)
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))
            below.forEach { l ->
                addView(row(l), LinearLayout.LayoutParams(MATCH, WRAP))
            }
        }
    }
}
