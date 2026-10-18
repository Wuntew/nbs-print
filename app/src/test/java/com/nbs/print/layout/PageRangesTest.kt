package com.nbs.print.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRangesTest {
    private fun pages(text: String, count: Int = 12): List<Int> =
        (PageRanges.parse(text, count) as PageParse.Valid).pages

    private fun error(text: String, count: Int = 12): String =
        (PageRanges.parse(text, count) as PageParse.Invalid).message

    @Test
    fun singlePagesAndRanges() {
        assertEquals(listOf(0, 1, 2, 4), pages("1-3, 5"))
    }

    @Test
    fun openEndedRanges() {
        assertEquals(listOf(9, 10, 11), pages("10-"))
        assertEquals(listOf(0, 1, 2), pages("-3"))
    }

    @Test
    fun resultIsSortedAndDeduplicated() {
        assertEquals(listOf(1, 2, 3, 4, 7), pages("8, 2-4, 3-5, 2"))
    }

    @Test
    fun acceptsSemicolonsAndSpaces() {
        assertEquals(listOf(0, 2, 4), pages("1; 3 5"))
    }

    @Test
    fun rejectsEmptyAndGarbage() {
        assertTrue(error("").contains("Enter pages"))
        assertTrue(error("abc").contains("abc"))
        assertTrue(error("-").contains("-"))
    }

    @Test
    fun rejectsOutOfRangeAndBackwards() {
        assertTrue(error("13").contains("13"))
        assertTrue(error("10-20").contains("20"))
        assertTrue(error("0").contains("start at 1"))
        assertTrue(error("5-2").contains("backwards"))
    }

    @Test
    fun modesResolveAgainstPageCount() {
        assertEquals(
            PageParse.Valid(listOf(0, 1, 2)),
            PageRanges.resolve(PageSelection(), pageCount = 3, currentPage = 1),
        )
        assertEquals(
            PageParse.Valid(listOf(1)),
            PageRanges.resolve(PageSelection(PageMode.CURRENT), pageCount = 3, currentPage = 1),
        )
        assertEquals(
            PageParse.Valid(listOf(0, 2)),
            PageRanges.resolve(PageSelection(PageMode.RANGE, "1,3"), pageCount = 3, currentPage = 1),
        )
    }

    @Test
    fun togglingAPageOutOfAllLeavesTheRest() {
        val sel = PageRanges.toggle(PageSelection(), page = 1, pageCount = 5, currentPage = 0)
        assertEquals(PageSelection(PageMode.RANGE, "1, 3-5"), sel)
        assertEquals(listOf(0, 2, 3, 4), PageRanges.selected(sel, 5, 0))
    }

    @Test
    fun togglingTheLastMissingPageRestoresAll() {
        val partial = PageSelection(PageMode.RANGE, "1-4")
        assertEquals(PageSelection(), PageRanges.toggle(partial, page = 4, pageCount = 5, currentPage = 0))
    }

    @Test
    fun togglingFromCurrentPageModeKeepsThatPage() {
        val sel = PageRanges.toggle(PageSelection(PageMode.CURRENT), page = 3, pageCount = 5, currentPage = 1)
        assertEquals(listOf(1, 3), PageRanges.selected(sel, 5, 1))
    }

    @Test
    fun deselectingEverythingGivesAnEmptyInvalidSelection() {
        val one = PageSelection(PageMode.RANGE, "2")
        val none = PageRanges.toggle(one, page = 1, pageCount = 5, currentPage = 0)
        assertTrue(PageRanges.resolve(none, 5, 0) is PageParse.Invalid)
        assertEquals(emptyList<Int>(), PageRanges.selected(none, 5, 0))
    }

    @Test
    fun togglingOutOfRangeIsIgnored() {
        val sel = PageSelection(PageMode.RANGE, "1-2")
        assertEquals(sel, PageRanges.toggle(sel, page = 9, pageCount = 5, currentPage = 0))
    }

    @Test
    fun oddAndEvenPages() {
        assertEquals(listOf(0, 2, 4), PageRanges.selected(PageRanges.odd(5), 5, 0))
        assertEquals(listOf(1, 3), PageRanges.selected(PageRanges.even(5), 5, 0))
        assertEquals(PageSelection(), PageRanges.odd(1)) // the only page is odd, so that's everything
    }

    @Test
    fun describeCollapsesRuns() {
        assertEquals("1-3, 5", PageRanges.describe(listOf(0, 1, 2, 4)))
        assertEquals("2, 3", PageRanges.describe(listOf(1, 2)))
        assertEquals("7", PageRanges.describe(listOf(6)))
        assertEquals("1, 3, 5-8", PageRanges.describe(listOf(0, 2, 4, 5, 6, 7)))
        assertEquals("", PageRanges.describe(emptyList()))
    }

    @Test
    fun currentPageIsClampedWhenDocumentShrinks() {
        assertEquals(
            PageParse.Valid(listOf(2)),
            PageRanges.resolve(PageSelection(PageMode.CURRENT), pageCount = 3, currentPage = 9),
        )
    }
}
