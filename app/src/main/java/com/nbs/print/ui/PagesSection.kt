package com.nbs.print.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.nbs.print.layout.PageMode
import com.nbs.print.layout.PageParse
import com.nbs.print.layout.PageRanges
import com.nbs.print.layout.PageSelection

@Composable
internal fun PagesSection(state: UiState, onPages: ((PageSelection) -> PageSelection) -> Unit) {
    val selection = state.selection
    Section("Pages") {
        Chips(
            listOf(
                PageMode.ALL to "All pages",
                PageMode.CURRENT to "This page (${state.page + 1})",
                PageMode.RANGE to "Select",
            ),
            selected = state.pages.mode,
        ) { mode -> onPages { it.copy(mode = mode) } }

        if (state.pages.mode == PageMode.RANGE) {
            OutlinedTextField(
                value = state.pages.rangeText,
                onValueChange = { text -> onPages { it.copy(rangeText = text.take(200)) } },
                label = { Text("Pages") },
                placeholder = { Text("1-3, 5, 8-") },
                singleLine = true,
                isError = selection is PageParse.Invalid,
                supportingText = {
                    if (selection is PageParse.Invalid) Text(selection.message)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (selection is PageParse.Valid && state.pageCount > 0) {
            val n = selection.pages.size
            Hint(
                if (n == state.pageCount) "All $n ${pagesWord(n)} will print."
                else "Printing ${PageRanges.describe(selection.pages)} ($n of ${state.pageCount} ${pagesWord(state.pageCount)})."
            )
        }
    }
}

private fun pagesWord(n: Int) = if (n == 1) "page" else "pages"

/** Marks a preview page that the current selection leaves out. */
@Composable
internal fun NotSelectedBadge() {
    Text("Won't print", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
}
