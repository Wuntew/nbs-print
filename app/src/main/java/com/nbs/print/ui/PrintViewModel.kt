package com.nbs.print.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nbs.print.doc.DocumentLoader
import com.nbs.print.doc.PrintableDoc
import com.nbs.print.ipp.Destination
import com.nbs.print.ipp.IppClient
import com.nbs.print.ipp.PrinterCaps
import com.nbs.print.ipp.PrinterDiscovery
import com.nbs.print.ipp.PrinterRef
import com.nbs.print.ipp.PrinterStore
import com.nbs.print.ipp.PrinterUri
import com.nbs.print.layout.PageParse
import com.nbs.print.layout.PageRanges
import com.nbs.print.layout.PageSelection
import com.nbs.print.layout.Paper
import com.nbs.print.layout.PrintOptions
import com.nbs.print.layout.ScaleSettings
import com.nbs.print.print.DirectParams
import com.nbs.print.print.DirectPrintJob
import com.nbs.print.print.PrintJobService
import com.nbs.print.print.DirectProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

sealed interface CapsState {
    data object None : CapsState
    data object Loading : CapsState
    data class Ready(val caps: PrinterCaps) : CapsState
    data class Failed(val message: String) : CapsState
}

sealed interface JobState {
    data class Running(val progress: DirectProgress, val printerName: String) : JobState
    data class Finished(val printerName: String, val note: String?) : JobState
    data class Failed(val printerName: String, val message: String) : JobState
}

data class UiState(
    val doc: PrintableDoc? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val options: PrintOptions = PrintOptions(),
    val page: Int = 0,
    val pageCount: Int = 0,
    val preview: Bitmap? = null,
    val pages: PageSelection = PageSelection(),
    val destination: Destination = Destination.SystemDialog,
    val caps: CapsState = CapsState.None,
    val direct: DirectParams = DirectParams(),
    val pickerOpen: Boolean = false,
    val pickerError: String? = null,
    val addingPrinter: Boolean = false,
    val discovered: List<PrinterRef> = emptyList(),
    val saved: List<PrinterRef> = emptyList(),
    val job: JobState? = null,
    /** The settings the user saved as their starting point, if any. */
    val defaults: Defaults? = null,
) {
    /** The pages that will print, or why the selection is unusable. */
    val selection: PageParse get() = PageRanges.resolve(pages, pageCount, page)

    val printerCaps: PrinterCaps? get() = (caps as? CapsState.Ready)?.caps
}

class PrintViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", 0)
    private val store = PrinterStore(app)
    private val defaultsStore = DefaultsStore(prefs)
    private val savedDefaults = defaultsStore.load()

    private val _state = MutableStateFlow(
        UiState(
            options = savedDefaults?.options ?: PrintOptions(paper = savedPaper()),
            direct = savedDefaults?.direct ?: DirectParams(),
            destination = store.destination(),
            saved = store.saved(),
            defaults = savedDefaults,
        )
    )
    val state = _state

    private var discoveryJob: Job? = null
    private var capsJob: Job? = null
    private var printJob: Job? = null
    private var activeJob: DirectPrintJob? = null

    init {
        DocumentLoader.clearCache(app)
        viewModelScope.launch { renderPreviews() }
        (_state.value.destination as? Destination.Network)?.let { loadCaps(it.printer) }
    }

    // ---- documents -------------------------------------------------------------------------

    fun load(uris: List<Uri>, sharedText: String? = null) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { DocumentLoader.load(getApplication(), uris, sharedText) }
            }
            result.onSuccess { doc ->
                val old = _state.value.doc
                // Every new document starts from the saved default scale (100% / no scaling unless the user
                // saved something else) and with all pages selected.
                _state.update {
                    it.copy(
                        doc = doc, loading = false, error = null, page = 0, preview = null, pageCount = 0,
                        options = it.options.copy(scale = it.defaults?.options?.scale ?: ScaleSettings()),
                        pages = PageSelection(),
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

    fun updatePages(change: (PageSelection) -> PageSelection) = _state.update { it.copy(pages = change(it.pages)) }

    /** The filmstrip's tap: include or skip [page], and show it in the preview. */
    fun togglePage(page: Int) = _state.update { s ->
        s.copy(pages = PageRanges.toggle(s.pages, page, s.pageCount, s.page), page = page.coerceIn(0, (s.pageCount - 1).coerceAtLeast(0)))
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    // ---- destination / printers --------------------------------------------------------------

    fun openPicker() {
        _state.update { it.copy(pickerOpen = true, pickerError = null, saved = store.saved()) }
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            runCatching {
                PrinterDiscovery(getApplication()).discover().collectLatest { list ->
                    _state.update { it.copy(discovered = list) }
                }
            }
        }
    }

    fun closePicker() {
        discoveryJob?.cancel()
        _state.update { it.copy(pickerOpen = false, pickerError = null, discovered = emptyList()) }
    }

    fun selectDestination(dest: Destination) {
        if (dest is Destination.Network) store.save(dest.printer)
        store.setDestination(dest)
        capsJob?.cancel()
        _state.update {
            it.copy(
                destination = dest,
                caps = if (dest is Destination.Network) CapsState.Loading else CapsState.None,
                direct = it.defaults?.direct ?: DirectParams(),
                saved = store.saved(),
            )
        }
        closePicker()
        (dest as? Destination.Network)?.let { loadCaps(it.printer) }
    }

    fun removeSaved(printer: PrinterRef) {
        store.remove(printer)
        _state.update {
            val dest = store.destination()
            it.copy(
                saved = store.saved(),
                destination = dest,
                caps = if (dest is Destination.Network) it.caps else CapsState.None,
            )
        }
    }

    /** Validates an address by asking the printer for its capabilities before saving it. */
    fun addPrinterByAddress(input: String) {
        val uri = PrinterUri.normalize(input)
        if (uri == null) {
            _state.update { it.copy(pickerError = "That doesn't look like a printer address. Try something like 192.168.1.20") }
            return
        }
        _state.update { it.copy(addingPrinter = true, pickerError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { IppClient.forDevice(getApplication()).getPrinterCaps(uri) }
            }
            result.onSuccess { caps ->
                _state.update { it.copy(addingPrinter = false) }
                selectDestination(Destination.Network(PrinterRef(caps.name.ifBlank { uri }, uri)))
            }.onFailure { e ->
                _state.update { it.copy(addingPrinter = false, pickerError = e.message ?: "Couldn't reach that printer.") }
            }
        }
    }

    fun retryCaps() {
        (_state.value.destination as? Destination.Network)?.let {
            _state.update { s -> s.copy(caps = CapsState.Loading) }
            loadCaps(it.printer)
        }
    }

    private fun loadCaps(printer: PrinterRef) {
        capsJob?.cancel()
        _state.update { it.copy(caps = CapsState.Loading) }
        capsJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { IppClient.forDevice(getApplication()).getPrinterCaps(printer.uri) }
            }
            // Ignore the answer if the user has picked another destination meanwhile.
            if ((_state.value.destination as? Destination.Network)?.printer != printer) return@launch
            result.onSuccess { caps ->
                _state.update { s ->
                    val paper = if (caps.supportsPaper(s.options.paper)) s.options.paper
                    else Paper.entries.firstOrNull { caps.supportsPaper(it) } ?: s.options.paper
                    s.copy(
                        caps = CapsState.Ready(caps),
                        options = s.options.copy(paper = paper),
                        direct = s.direct.copy(
                            copies = s.direct.copies.coerceIn(1, caps.copiesMax),
                            color = s.direct.color && caps.colorSupported,
                            format = s.direct.format?.takeIf { it in caps.routes },
                        ),
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(caps = CapsState.Failed(e.message ?: "Couldn't reach the printer.")) }
            }
        }
    }

    fun updateDirect(change: (DirectParams) -> DirectParams) = _state.update { it.copy(direct = change(it.direct)) }

    // ---- defaults -----------------------------------------------------------------------------

    /** Saves what is on screen now (scale, position, paper, margins, copies, color...) as the starting point. */
    fun saveDefaults() {
        val s = _state.value
        val d = Defaults(s.options, s.direct)
        defaultsStore.save(d)
        _state.update { it.copy(defaults = d) }
    }

    /** Forgets the saved defaults and returns the current settings to the app's own. */
    fun resetDefaults() {
        defaultsStore.clear()
        val factory = Defaults.factory()
        _state.update { it.copy(defaults = null, options = factory.options, direct = factory.direct) }
    }

    // ---- direct printing -----------------------------------------------------------------------

    fun printDirect() {
        val s = _state.value
        val doc = s.doc ?: return
        val dest = s.destination as? Destination.Network ?: return
        val caps = s.printerCaps ?: return
        val pages = (s.selection as? PageParse.Valid)?.pages ?: return
        if (printJob?.isActive == true) return

        val name = dest.printer.name
        val job = DirectPrintJob(getApplication(), IppClient.forDevice(getApplication()))
        activeJob = job
        PrintJobService.start(getApplication(), name)
        doc.retain()
        _state.update { it.copy(job = JobState.Running(DirectProgress.Rendering(0, pages.size), name)) }
        printJob = viewModelScope.launch {
            val self = coroutineContext[Job]
            try {
                job.run(doc, s.options, pages, dest.printer, caps, s.direct) { p ->
                    // A cancelled job may still be finishing a socket call; don't let it reopen the dialog.
                    if (self?.isActive != true) return@run
                    _state.update { cur ->
                        when (p) {
                            is DirectProgress.Done -> cur.copy(job = JobState.Finished(name, p.note))
                            is DirectProgress.Failed -> cur.copy(job = JobState.Failed(name, p.message))
                            else -> cur.copy(job = JobState.Running(p, name))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (self?.isActive == true) {
                    _state.update { it.copy(job = JobState.Failed(name, e.message ?: "Printing failed.")) }
                }
            } finally {
                doc.close()
                PrintJobService.stop(getApplication())
            }
        }
    }

    /** Stops a running job and, if the printer already has it, asks the printer to cancel it. */
    fun cancelJob() {
        val running = activeJob
        printJob?.cancel()
        _state.update { it.copy(job = null) }
        val dest = _state.value.destination as? Destination.Network ?: return
        val id = running?.sentJobId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { IppClient.forDevice(getApplication()).cancelJob(dest.printer.uri, id) }
        }
    }

    fun dismissJob() = _state.update { it.copy(job = null) }

    // ---- preview -----------------------------------------------------------------------------

    private data class PreviewKey(val doc: PrintableDoc?, val options: PrintOptions, val page: Int, val marginPt: Float?)

    // Small renders of what each page will print, for the filmstrip. Cleared whenever anything that
    // changes how a page looks (document, scale, paper, margins...) changes.
    private val thumbs = LruCache<Int, Bitmap>(48)
    private var thumbsFor: PreviewKey? = null

    suspend fun thumbnail(page: Int): Bitmap? {
        val s = _state.value
        val doc = s.doc ?: return null
        val key = PreviewKey(doc, s.options, 0, s.printerCaps?.marginPt)
        synchronized(thumbs) {
            if (thumbsFor != key) {
                thumbs.evictAll()
                thumbsFor = key
            }
            thumbs.get(page)?.let { return it }
        }
        val bitmap = withContext(Dispatchers.Default) {
            runCatching { doc.render(page, s.options.sheetSpec(key.marginPt), s.options, THUMB_PX_PER_PT) }.getOrNull()
        } ?: return null
        synchronized(thumbs) { if (thumbsFor == key) thumbs.put(page, bitmap) }
        return bitmap
    }

    private suspend fun renderPreviews() {
        _state
            .map { PreviewKey(it.doc, it.options, it.page, it.printerCaps?.marginPt) }
            .distinctUntilChanged()
            .collectLatest { (doc, options, page, marginPt) ->
                if (doc == null) return@collectLatest
                // With a printer chosen the preview uses its real margins, so it matches the job.
                val sheet = options.sheetSpec(marginPt)
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
        printJob?.cancel()
        _state.value.doc?.close()
    }

    private companion object {
        const val KEY_PAPER = "paper"
        const val PREVIEW_PX_PER_PT = 1.6f
        const val THUMB_PX_PER_PT = 0.3f
    }
}
