package com.nbs.print.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nbs.print.doc.DocumentLoader
import com.nbs.print.doc.PrintableDoc
import com.nbs.print.layout.Margins
import com.nbs.print.layout.Paper
import com.nbs.print.layout.PrintOptions
import com.nbs.print.layout.ScaleSettings
import com.nbs.print.layout.SheetSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class UiState(
    val doc: PrintableDoc? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val options: PrintOptions = PrintOptions(),
    val page: Int = 0,
    val pageCount: Int = 0,
    val preview: Bitmap? = null,
)

class PrintViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", 0)

    private val _state = MutableStateFlow(
        UiState(options = PrintOptions(paper = savedPaper()))
    )
    val state = _state

    init {
        DocumentLoader.clearCache(app)
        viewModelScope.launch { renderPreviews() }
    }

    fun load(uris: List<Uri>, sharedText: String? = null) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { DocumentLoader.load(getApplication(), uris, sharedText) }
            }
            result.onSuccess { doc ->
                val old = _state.value.doc
                // Every new document starts at 100% / no scaling, whatever the previous one used.
                _state.update {
                    it.copy(
                        doc = doc, loading = false, error = null, page = 0, preview = null, pageCount = 0,
                        options = it.options.copy(scale = ScaleSettings()),
                    )
                }
                old?.close()
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't open that file.") }
            }
        }
    }

    fun update(change: (PrintOptions) -> PrintOptions) {
        _state.update { s ->
            val next = change(s.options)
            if (next.paper != s.options.paper) prefs.edit().putString(KEY_PAPER, next.paper.name).apply()
            s.copy(options = next)
        }
    }

    fun setPage(page: Int) = _state.update { it.copy(page = page.coerceIn(0, (it.pageCount - 1).coerceAtLeast(0))) }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** The sheet the preview is drawn on: the chosen paper with the margins the options imply. */
    private fun previewSheet(o: PrintOptions): SheetSpec {
        val w = (if (o.landscape) o.paper.heightMils else o.paper.widthMils) * PrintOptions.PT_PER_MIL
        val h = (if (o.landscape) o.paper.widthMils else o.paper.heightMils) * PrintOptions.PT_PER_MIL
        return SheetSpec(w, h, o.resolveMargins(PrintOptions.ASSUMED_PRINTER_MARGIN))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun renderPreviews() {
        _state
            .map { Triple(it.doc, it.options, it.page) }
            .distinctUntilChanged()
            .collectLatest { (doc, options, page) ->
                if (doc == null) return@collectLatest
                val sheet = previewSheet(options)
                val result = withContext(Dispatchers.Default) {
                    runCatching {
                        val count = doc.pageCount(sheet, options)
                        val index = page.coerceIn(0, count - 1)
                        Triple(count, index, doc.render(index, sheet, options, PREVIEW_PX_PER_PT))
                    }
                }
                result.onSuccess { (count, index, bitmap) ->
                    _state.update {
                        if (it.doc !== doc) it else it.copy(pageCount = count, page = index, preview = bitmap)
                    }
                }.onFailure { e ->
                    _state.update { if (it.doc !== doc) it else it.copy(error = e.message ?: "Couldn't render the preview.") }
                }
            }
    }

    private fun savedPaper(): Paper =
        prefs.getString(KEY_PAPER, null)?.let { name -> Paper.entries.firstOrNull { it.name == name } }
            ?: Paper.defaultFor(Locale.getDefault())

    override fun onCleared() {
        _state.value.doc?.close()
    }

    private companion object {
        const val KEY_PAPER = "paper"
        const val PREVIEW_PX_PER_PT = 1.6f
    }
}
