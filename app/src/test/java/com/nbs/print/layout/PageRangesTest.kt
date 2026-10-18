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
