package com.superboox.quickaccess

import com.superboox.quickaccess.DockGrid.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DockGridTest {
    private val auto = Prefs.DOCK_AUTO

    private fun allPages() = sequence {
        for (cols in 2..4) for (apps in 0..25) for (prev in listOf(false, true))
            for (layout in listOf(Prefs.DOCK_ONE, Prefs.DOCK_TWO, auto))
                yield(Triple(cols, apps, prev) to DockGrid.pages(apps, prev, cols, layout))
    }

    @Test fun previousAppIsBottomRightOfEveryPage() {
        for ((case, pages) in allPages()) {
            val (cols, apps, prev) = case
            for (page in pages) {
                assertEquals("cells fill whole rows: $case", 0, page.size % cols)
                if (prev) assertEquals("Previous is last cell: $case", Cell.Previous, page.last())
                else assertTrue("no Previous when off: $case", Cell.Previous !in page)
                assertEquals("Previous at most once: $case", if (prev) 1 else 0, page.count { it == Cell.Previous })
            }
            if (apps == 0 && prev) assertEquals("only Previous: $case", 1, pages.size)
        }
    }

    @Test fun everyAppShownExactlyOnceInOrder() {
        for ((case, pages) in allPages()) {
            val shown = pages.flatten().filterIsInstance<Cell.App>().map { it.index }
            assertEquals("apps in order: $case", (0 until case.second).toList(), shown)
        }
    }

    @Test fun previousNeverGetsAPageOfItsOwn() {
        for ((case, pages) in allPages()) {
            if (case.second > 0) pages.forEach { p -> assertTrue("page has an app: $case", p.any { it is Cell.App }) }
        }
    }

    @Test fun multiplePagesAreAllTheSameSize() {
        for ((case, pages) in allPages()) assertEquals("equal pages: $case", 1, pages.map { it.size }.distinct().size)
    }

    @Test fun threeAppsPlusPreviousAutoIsTwoRowsOnTwoColumns() {
        // 2 columns, 3 apps + Previous = 4 tiles: two rows, Previous bottom right.
        val pages = DockGrid.pages(3, true, 2, auto)
        assertEquals(1, pages.size)
        assertEquals(listOf(Cell.App(0), Cell.App(1), Cell.App(2), Cell.Previous), pages[0])
    }

    @Test fun overflowReservesPreviousOnEachPage() {
        // 4 columns, two rows = 8 per page; 10 apps + Previous: 7 apps + Previous, then 3 apps + blanks + Previous.
        val pages = DockGrid.pages(10, true, 4, Prefs.DOCK_TWO)
        assertEquals(2, pages.size)
        assertEquals((0..6).map { Cell.App(it) } + Cell.Previous, pages[0])
        assertEquals((7..9).map { Cell.App(it) } + List(4) { Cell.Blank } + Cell.Previous, pages[1])
    }
}
