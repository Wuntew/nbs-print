package com.nbs.print.layout

enum class PageMode { ALL, CURRENT, RANGE }

/** Which pages of the document to print. Resets to ALL for every new document. */
data class PageSelection(val mode: PageMode = PageMode.ALL, val rangeText: String = "")

sealed interface PageParse {
    /** Zero-based page indexes, ascending, no duplicates. */
    data class Valid(val pages: List<Int>) : PageParse

    data class Invalid(val message: String) : PageParse
}

object PageRanges {
    fun resolve(selection: PageSelection, pageCount: Int, currentPage: Int): PageParse = when {
        pageCount <= 0 -> PageParse.Invalid("Nothing to print")
        selection.mode == PageMode.ALL -> PageParse.Valid((0 until pageCount).toList())
        selection.mode == PageMode.CURRENT -> PageParse.Valid(listOf(currentPage.coerceIn(0, pageCount - 1)))
        else -> parse(selection.rangeText, pageCount)
    }

    /**
     * Parses "1-3, 5, 8-" (1-based). `8-` means page 8 to the end, `-4` means pages 1 to 4.
     * Separators may be commas, semicolons or spaces.
     */
    fun parse(text: String, pageCount: Int): PageParse {
        val tokens = text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return PageParse.Invalid("Enter pages, e.g. 1-3, 5")

        val pages = sortedSetOf<Int>()
        for (token in tokens) {
            val dash = token.indexOf('-')
            val (from, to) = if (dash < 0) {
                val n = token.toIntOrNull() ?: return bad(token)
                n to n
            } else {
                val a = token.substring(0, dash).trim()
                val b = token.substring(dash + 1).trim()
                if (a.isEmpty() && b.isEmpty()) return bad(token)
                val start = if (a.isEmpty()) 1 else a.toIntOrNull() ?: return bad(token)
                val end = if (b.isEmpty()) pageCount else b.toIntOrNull() ?: return bad(token)
                start to end
            }
            if (from < 1 || to < 1) return PageParse.Invalid("Pages start at 1")
            if (from > to) return PageParse.Invalid("\"$token\" runs backwards")
            val missing = if (to > pageCount) to else if (from > pageCount) from else null
            if (missing != null) {
                val pagesWord = if (pageCount == 1) "page" else "pages"
                return PageParse.Invalid("Page $missing doesn't exist. This document has $pageCount $pagesWord.")
            }
            for (p in from..to) pages.add(p - 1)
        }
        return PageParse.Valid(pages.toList())
    }

    /** The pages currently selected, or none if the selection can't be read. */
    fun selected(selection: PageSelection, pageCount: Int, currentPage: Int): List<Int> =
        (resolve(selection, pageCount, currentPage) as? PageParse.Valid)?.pages.orEmpty()

    /** A selection of exactly [pages]; everything selected collapses back to "all pages". */
    fun fromPages(pages: List<Int>, pageCount: Int): PageSelection =
        if (pageCount > 0 && pages.size == pageCount) PageSelection()
        else PageSelection(PageMode.RANGE, describe(pages))

    /** Flips one page in or out of the selection (the filmstrip's tap). */
    fun toggle(selection: PageSelection, page: Int, pageCount: Int, currentPage: Int): PageSelection {
        if (page !in 0 until pageCount) return selection
        val set = selected(selection, pageCount, currentPage).toSortedSet()
        if (!set.add(page)) set.remove(page)
        return fromPages(set.toList(), pageCount)
    }

    fun odd(pageCount: Int) = fromPages((0 until pageCount step 2).toList(), pageCount)

    fun even(pageCount: Int) = fromPages((1 until pageCount step 2).toList(), pageCount)

    /** "1-3, 5" for zero-based pages [0, 1, 2, 4]. */
    fun describe(pages: List<Int>): String {
        val out = ArrayList<String>()
        var i = 0
        while (i < pages.size) {
            var j = i
            while (j + 1 < pages.size && pages[j + 1] == pages[j] + 1) j++
            out += if (j == i) "${pages[i] + 1}" else if (j == i + 1) "${pages[i] + 1}, ${pages[j] + 1}" else "${pages[i] + 1}-${pages[j] + 1}"
            i = j + 1
        }
        return out.joinToString(", ")
    }

    private fun bad(token: String) = PageParse.Invalid("Can't read \"$token\"")
}
