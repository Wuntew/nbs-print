package com.nbs.print.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbs.print.ipp.Destination
import com.nbs.print.ipp.PrinterRef
import com.nbs.print.layout.PageParse
import com.nbs.print.layout.PageRanges
import com.nbs.print.layout.PageSelection
import com.nbs.print.layout.PrintOptions
import com.nbs.print.layout.ScaleMode
import com.nbs.print.print.DirectParams
import com.nbs.print.print.TwoSided
import kotlin.math.roundToInt

/** Everything the screen can ask the app to do. */
class PrintActions(
    val onPick: () -> Unit,
    val onPrint: () -> Unit,
    val onPrinterSettings: () -> Unit,
    val onChange: OptionsChange,
    val onPage: (Int) -> Unit,
    val onDismissError: () -> Unit,
    val onPages: ((PageSelection) -> PageSelection) -> Unit,
    val onTogglePage: (Int) -> Unit,
    val loadThumb: suspend (Int) -> Bitmap?,
    val onOpenPicker: () -> Unit,
    val onClosePicker: () -> Unit,
    val onSelectDestination: (Destination) -> Unit,
    val onRemoveSaved: (PrinterRef) -> Unit,
    val onAddPrinter: (String) -> Unit,
    val onDirect: ((DirectParams) -> DirectParams) -> Unit,
    val onRetryCaps: () -> Unit,
    val onCancelJob: () -> Unit,
    val onDismissJob: () -> Unit,
)

private enum class HomeSheet { Scale, Pages, Paper, Options }

private fun doneFooter(onDone: () -> Unit): @Composable () -> Unit = { InkPill("Done", onDone, Modifier.fillMaxWidth()) }

@Composable
fun PrintScreen(state: UiState, a: PrintActions) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            a.onDismissError()
        }
    }
    var sheet by rememberSaveable { mutableStateOf<HomeSheet?>(null) }
    val network = state.destination is Destination.Network

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = palette.ground,
            contentColor = palette.ink,
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                if (state.doc == null) EmptyState(state.loading, a)
                else Home(state, a, onOpenSheet = { sheet = it })
            }
        }

        val close = { sheet = null }
        when (sheet) {
            HomeSheet.Scale -> SheetFrame(close, footer = doneFooter(close)) { ScaleSheet(state, a.onChange) }
            HomeSheet.Pages -> SheetFrame(close, footer = doneFooter(close)) { PagesSheet(state, a.onPages) }
            HomeSheet.Paper -> SheetFrame(close, footer = doneFooter(close)) { PaperSheet(state, a.onChange) }
            HomeSheet.Options -> if (network && state.printerCaps != null) {
                SheetFrame(close, footer = doneFooter(close)) { OptionsSheet(state, a.onDirect) }
            }
            null -> {}
        }
        if (state.pickerOpen) {
            PrinterSheet(
                state,
                onDismiss = a.onClosePicker,
                onSelect = a.onSelectDestination,
                onRemove = a.onRemoveSaved,
                onAdd = a.onAddPrinter,
                onRetry = a.onRetryCaps,
            )
        }
        state.job?.let { JobOverlay(it, onCancel = a.onCancelJob, onDismiss = a.onDismissJob) }
    }
}

@Composable
private fun Home(state: UiState, a: PrintActions, onOpenSheet: (HomeSheet) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            // Landscape: paper on the left; settings on the right with Print pinned below them, so
            // the button is always reachable and only the tiles scroll on a short screen.
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Header(state, a)
                    PaperHero(state, a, Modifier.weight(1f).fillMaxWidth())
                    if (state.pageCount > 1) Filmstrip(state, a.loadThumb, a.onTogglePage, Modifier.padding(bottom = 12.dp))
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(end = 16.dp, top = 8.dp, bottom = 12.dp)) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Tiles(state, a, onOpenSheet)
                    }
                    Spacer(Modifier.height(12.dp))
                    PrintButton(state, a)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Header(state, a)
                PaperHero(state, a, Modifier.weight(1f).fillMaxWidth())
                if (state.pageCount > 1) Filmstrip(state, a.loadThumb, a.onTogglePage, Modifier.padding(top = 4.dp, bottom = 14.dp))
                Tiles(state, a, onOpenSheet, Modifier.padding(horizontal = 16.dp))
                PrintButton(state, a, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp))
            }
        }
    }
}

@Composable
private fun Header(state: UiState, a: PrintActions) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                state.doc?.title.orEmpty(),
                style = Type.bodyMedium.copy(fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                color = palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (state.pageCount > 0) {
                Text(
                    if (state.pageCount == 1) "1 page" else "${state.pageCount} pages",
                    style = Type.caption, color = palette.muted,
                )
            }
        }
        CircleIconButton(Icons.Default.FolderOpen, "Choose another file", a.onPick)
        Spacer(Modifier.width(8.dp))
        CircleIconButton(Icons.Default.Tune, "Android printer settings", a.onPrinterSettings)
    }
}

/** The page as it will print: the hero of the screen. Swipe sideways to browse pages. */
@Composable
private fun PaperHero(state: UiState, a: PrintActions, modifier: Modifier) {
    val bitmap = state.preview
    val sel = state.selection
    val skipped = sel is PageParse.Valid && state.page !in sel.pages

    BoxWithConstraints(
        modifier
            .padding(horizontal = 40.dp, vertical = 14.dp)
            .pointerInput(state.page, state.pageCount) {
                var drag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = {
                        if (drag < -90f) a.onPage(state.page + 1) else if (drag > 90f) a.onPage(state.page - 1)
                    },
                ) { _, delta -> drag += delta }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            CircularProgressIndicator(color = palette.ink, strokeWidth = 2.dp)
        } else {
            val aspect = bitmap.width.toFloat() / bitmap.height
            var h = maxHeight
            var w = h * aspect
            if (w > maxWidth) {
                w = maxWidth
                h = w / aspect
            }
            Box(Modifier.size(w, h)) {
                val shape = RoundedCornerShape(4.dp)
                Box(
                    Modifier
                        .fillMaxSize()
                        .shadow(28.dp, shape, ambientColor = Color(0x26281E16), spotColor = Color(0x59281E16))
                        .background(Color.White, shape)
                        .clip(shape)
                        .alpha(if (skipped) 0.4f else 1f),
                ) {
                    Image(
                        bitmap.asImageBitmap(), contentDescription = "Print preview, page ${state.page + 1}",
                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                    )
                }
                ScaleBadge(state.options, Modifier.align(Alignment.BottomStart).offset(x = 14.dp, y = 13.dp))
                if (skipped) {
                    Text(
                        "Won't print",
                        style = Type.micro, color = palette.problem,
                        modifier = Modifier
                            .align(Alignment.TopStart).padding(10.dp)
                            .background(Color.White, RoundedCornerShape(10.dp)).padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScaleBadge(o: PrintOptions, modifier: Modifier) {
    val label = when (o.scale.mode) {
        ScaleMode.ACTUAL -> "True size"
        ScaleMode.FIT -> "Fit to page"
        ScaleMode.FILL -> "Fill page"
        ScaleMode.CUSTOM -> "${o.scale.customPercent.roundToInt()}%"
    }
    Row(
        modifier
            .background(palette.ink, RoundedCornerShape(13.dp))
            .padding(start = 9.dp, end = 11.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (o.scale.mode == ScaleMode.ACTUAL) {
            Dot(palette.accent)
            Spacer(Modifier.width(6.dp))
        }
        Text(label.uppercase(), style = Type.micro, color = palette.onInk)
    }
}

@Composable
private fun Tiles(state: UiState, a: PrintActions, open: (HomeSheet) -> Unit, modifier: Modifier = Modifier) {
    val o = state.options
    val dest = destSummary(state)
    val multiPage = state.pageCount > 1
    val sel = state.selection
    val showOptions = state.destination is Destination.Network && state.printerCaps != null

    val scaleTile: @Composable (Modifier) -> Unit = { m ->
        OptionTile(
            label = "Scale",
            value = when (o.scale.mode) {
                ScaleMode.ACTUAL -> "100%"
                ScaleMode.FIT -> "Fit"
                ScaleMode.FILL -> "Fill"
                ScaleMode.CUSTOM -> "${o.scale.customPercent.roundToInt()}%"
            },
            sub = when (o.scale.mode) {
                ScaleMode.ACTUAL -> "No scaling"
                ScaleMode.FIT -> "Fit to page"
                ScaleMode.FILL -> "Fill page"
                ScaleMode.CUSTOM -> "Custom"
            },
            onClick = { open(HomeSheet.Scale) },
            modifier = m,
            accentDot = o.scale.mode == ScaleMode.ACTUAL,
        )
    }
    val pagesTile: @Composable (Modifier) -> Unit = { m ->
        val pages = (sel as? PageParse.Valid)?.pages
        OptionTile(
            label = "Pages",
            value = when {
                pages == null -> "—"
                pages.size == state.pageCount -> "All"
                else -> "${pages.size} of ${state.pageCount}"
            },
            sub = when {
                pages == null -> "Check the range"
                pages.size == state.pageCount -> "${state.pageCount} pages"
                else -> PageRanges.describe(pages)
            },
            subColor = if (pages == null) palette.problem else palette.muted,
            onClick = { open(HomeSheet.Pages) },
            modifier = m,
        )
    }
    val paperTile: @Composable (Modifier) -> Unit = { m ->
        OptionTile("Paper", o.paper.label, if (o.landscape) "Landscape" else "Portrait", { open(HomeSheet.Paper) }, m)
    }
    val printerTile: @Composable (Modifier) -> Unit = { m ->
        OptionTile(
            label = "Printer",
            value = dest.shortName,
            sub = dest.status,
            onClick = a.onOpenPicker,
            modifier = m,
            compactValue = true,
            subDot = dest.dot,
            subColor = if (dest.isProblem) palette.problem else palette.muted,
        )
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // IntrinsicSize.Min + fillMaxHeight keeps the two tiles in a row the same height.
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            scaleTile(Modifier.weight(1f).fillMaxHeight())
            if (multiPage) pagesTile(Modifier.weight(1f).fillMaxHeight()) else paperTile(Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (multiPage) {
                paperTile(Modifier.weight(1f).fillMaxHeight())
                printerTile(Modifier.weight(1f).fillMaxHeight())
            } else {
                printerTile(Modifier.weight(1f).fillMaxHeight())
            }
        }
        if (showOptions) {
            OptionTile(
                label = "Options",
                value = optionsSummary(state),
                sub = null,
                onClick = { open(HomeSheet.Options) },
                modifier = Modifier.fillMaxWidth(),
                compactValue = true,
            )
        }
    }
}

private fun optionsSummary(state: UiState): String {
    val caps = state.printerCaps ?: return ""
    val d = state.direct
    return buildList {
        add(if (d.copies == 1) "1 copy" else "${d.copies} copies")
        if (caps.colorSupported && caps.monochromeSupported) add(if (d.color) "Color" else "Black & white")
        if (caps.canDuplex) add(if (d.twoSided == TwoSided.OFF) "One-sided" else "Two-sided")
    }.joinToString(" · ")
}

@Composable
private fun PrintButton(state: UiState, a: PrintActions, modifier: Modifier = Modifier) {
    val count = (state.selection as? PageParse.Valid)?.pages?.size
    val ready = state.doc != null && state.preview != null && count != null &&
        (state.destination is Destination.SystemDialog || state.printerCaps?.canPrint == true)
    InkPill(
        text = when {
            state.pageCount <= 1 || count == null -> "Print"
            count == 1 -> "Print 1 page"
            else -> "Print $count pages"
        },
        onClick = a.onPrint,
        enabled = ready,
        icon = Icons.Default.Print,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun EmptyState(loading: Boolean, a: PrintActions) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) {
            CircularProgressIndicator(color = palette.ink, strokeWidth = 2.dp)
            Spacer(Modifier.height(16.dp))
            Text("Opening…", style = Type.body, color = palette.muted)
        } else {
            Box(
                Modifier.size(88.dp).background(palette.tile, RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Print, contentDescription = null, tint = palette.ink, modifier = Modifier.size(36.dp)) }
            Spacer(Modifier.height(24.dp))
            Text("Print exactly what\nyou mean to", style = Type.title.copy(fontSize = 30.sp, lineHeight = 34.sp), color = palette.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(
                "Pick a PDF, photo or text file. Everything prints at 100% unless you say otherwise.",
                style = Type.body, color = palette.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            InkPill("Choose a file", a.onPick, Modifier.fillMaxWidth(), icon = Icons.Default.FolderOpen)
            Spacer(Modifier.height(16.dp))
            Text(
                "You can also share a file to NBS Print from any app.",
                style = Type.caption, color = palette.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
