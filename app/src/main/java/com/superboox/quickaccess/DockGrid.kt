package com.superboox.quickaccess

/**
 * Which tile goes in which cell of the Apps grid, pure so it can be unit tested.
 * "Previous app" is always the last (bottom-right) cell of every page that shows it.
 */
object DockGrid {
    sealed interface Cell {
        /** The pinned app at [index] in the dock list (without Previous app). */
        data class App(val index: Int) : Cell
        object Blank : Cell
        object Previous : Cell
    }

    /** Pages of cells (a multiple of [cols] each). [layout] is [Prefs.DOCK_ONE], [Prefs.DOCK_TWO] or automatic. */
    fun pages(apps: Int, hasPrevious: Boolean, cols: Int, layout: Int): List<List<Cell>> {
        val tiles = apps + if (hasPrevious) 1 else 0
        val oneRow = when (layout) {
            Prefs.DOCK_ONE -> true
            Prefs.DOCK_TWO -> false
            else -> tiles <= minOf(3, cols)
        }
        val perPage = if (oneRow) cols else cols * 2
        // When apps need more than one page, Previous app takes the last slot of every page, so it stays
        // visible, bottom right, and never spills onto a page of its own.
        val reserve = hasPrevious && tiles > perPage
        val chunks = (0 until apps).toList().let { if (reserve) it.chunked(perPage - 1) else it.chunked(perPage) }
            .ifEmpty { listOf(emptyList()) }
        return chunks.map { chunk ->
            val used = chunk.size + if (hasPrevious) 1 else 0
            val slots = if (chunks.size > 1) perPage else ((used + cols - 1) / cols) * cols
            val blanks = List((slots - used).coerceAtLeast(0)) { Cell.Blank }
            chunk.map { Cell.App(it) } + blanks + if (hasPrevious) listOf(Cell.Previous) else emptyList()
        }
    }
}
