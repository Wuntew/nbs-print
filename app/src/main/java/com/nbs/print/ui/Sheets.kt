package com.nbs.print.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.RoundedCornerShape
import com.nbs.print.ipp.Destination
import com.nbs.print.ipp.PrintRoute
import com.nbs.print.layout.Anchor
import com.nbs.print.layout.MarginMode
import com.nbs.print.layout.PageMode
import com.nbs.print.layout.PageParse
import com.nbs.print.layout.PageRanges
import com.nbs.print.layout.PageSelection
import com.nbs.print.layout.Paper
import com.nbs.print.layout.PrintOptions
import com.nbs.print.layout.ScaleMode
import com.nbs.print.layout.ScalingMath
import com.nbs.print.print.DirectParams
import com.nbs.print.print.TwoSided
import kotlin.math.roundToInt

internal typealias OptionsChange = ((PrintOptions) -> PrintOptions) -> Unit

@Composable
internal fun ScaleSheet(state: UiState, onChange: OptionsChange) {
    val s = state.options.scale
    // Short screens (landscape phones) get a smaller hero so the controls fit without much scrolling.
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 560
    val heroStyle = if (compact) Type.hero.copy(fontSize = 84.sp, lineHeight = 88.sp) else Type.hero
    fun setMode(m: ScaleMode) = onChange { it.copy(scale = it.scale.copy(mode = m)) }
    fun setPercent(p: Float) = onChange { it.copy(scale = it.scale.copy(mode = ScaleMode.CUSTOM, customPercent = p)) }

    Micro("Scale")
    Box(Modifier.fillMaxWidth().padding(top = 2.dp), contentAlignment = Alignment.Center) {
        when (s.mode) {
            ScaleMode.ACTUAL, ScaleMode.CUSTOM -> {
                val shown = if (s.mode == ScaleMode.ACTUAL) 100 else s.customPercent.roundToInt()
                Row {
                    Text("$shown", style = heroStyle, color = palette.ink)
                    Text("%", style = Type.heroUnit, color = palette.ink, modifier = Modifier.padding(top = if (compact) 14.dp else 24.dp, start = 2.dp))
                }
            }
            ScaleMode.FIT -> Text("Fit", style = heroStyle, color = palette.ink)
            ScaleMode.FILL -> Text("Fill", style = heroStyle, color = palette.ink)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (s.mode == ScaleMode.ACTUAL) {
            Dot(palette.accent)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            when (s.mode) {
                ScaleMode.ACTUAL -> "No scaling · prints at true size"
                ScaleMode.FIT -> "Whole page fits the printable area"
                ScaleMode.FILL -> "Fills the printable area, overflow cropped"
                ScaleMode.CUSTOM -> "Your own percentage"
            },
            style = Type.body.copy(lineHeight = 20.sp),
            color = palette.muted,
        )
    }

    Spacer(Modifier.height(22.dp))
    Segmented(
        listOf(ScaleMode.ACTUAL to "100%", ScaleMode.FIT to "Fit", ScaleMode.FILL to "Fill", ScaleMode.CUSTOM to "Custom"),
        selected = s.mode,
        onSelect = ::setMode,
    )

    Spacer(Modifier.height(22.dp))
    Row(Modifier.fillMaxWidth()) {
        Micro("Custom", Modifier.weight(1f))
        Micro("${ScalingMath.MIN_PERCENT.roundToInt()} – ${ScalingMath.MAX_PERCENT.roundToInt()}%")
    }
    Slider(
        value = if (s.mode == ScaleMode.CUSTOM) s.customPercent else 100f,
        onValueChange = { setPercent(it.roundToInt().toFloat()) },
        valueRange = ScalingMath.MIN_PERCENT..ScalingMath.MAX_PERCENT,
        colors = SliderDefaults.colors(
            thumbColor = palette.ink,
            activeTrackColor = palette.ink,
            inactiveTrackColor = palette.hairline,
        ),
    )
    // Tick labels under the track; the 100 marks true size.
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(16.dp)) {
        listOf(10f, 100f, 200f, 300f, 400f).forEach { v ->
            val frac = (v - ScalingMath.MIN_PERCENT) / (ScalingMath.MAX_PERCENT - ScalingMath.MIN_PERCENT)
            Text(
                "${v.roundToInt()}",
                style = Type.caption.copy(fontSize = 11.sp),
                color = if (v == 100f) palette.accent else palette.muted,
                modifier = Modifier.align(BiasAlignment(frac * 2f - 1f, 0f)),
            )
        }
    }
    if (s.mode == ScaleMode.CUSTOM) {
        Spacer(Modifier.height(14.dp))
        NumberField(
            s.customPercent, ScalingMath.MIN_PERCENT, ScalingMath.MAX_PERCENT, "%", "Exact scale",
            modifier = Modifier.fillMaxWidth(), onValue = ::setPercent,
        )
    }

    Spacer(Modifier.height(16.dp))
    Hint(
        if (state.doc?.isText == true) "For text, Custom % changes the font size; Fit and Fill print at 100%."
        else "Content larger than the paper is cropped from the centre."
    )
}

private enum class PagesChip { ALL, CURRENT, ODD, EVEN, RANGE }

@Composable
internal fun PagesSheet(state: UiState, onPages: ((PageSelection) -> PageSelection) -> Unit) {
    val count = state.pageCount
    val selected = PageRanges.selected(state.pages, count, state.page)
    val derived = when {
        state.pages.mode == PageMode.ALL -> PagesChip.ALL
        state.pages.mode == PageMode.CURRENT -> PagesChip.CURRENT
        selected == PageRanges.selected(PageRanges.odd(count), count, 0) -> PagesChip.ODD
        selected == PageRanges.selected(PageRanges.even(count), count, 0) -> PagesChip.EVEN
        else -> PagesChip.RANGE
    }
    // "Range" stays highlighted while the field is open, even if the typed range equals odd or even.
    var rangeOpen by remember { mutableStateOf(derived == PagesChip.RANGE) }
    val chip = if (rangeOpen) PagesChip.RANGE else derived

    Micro("Pages")
    Text(
        when {
            count > 0 && selected.size == count -> "All $count pages"
            selected.isEmpty() -> "None selected"
            else -> "${selected.size} of $count · ${PageRanges.describe(selected)}"
        },
        style = Type.title, color = palette.ink, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
    )

    ChipRow(
        listOf(
            PagesChip.ALL to "All pages",
            PagesChip.CURRENT to "This page (${state.page + 1})",
            PagesChip.ODD to "Odd",
            PagesChip.EVEN to "Even",
            PagesChip.RANGE to "Range",
        ),
        selected = chip,
    ) { picked ->
        rangeOpen = picked == PagesChip.RANGE
        when (picked) {
            PagesChip.ALL -> onPages { PageSelection() }
            PagesChip.CURRENT -> onPages { PageSelection(PageMode.CURRENT) }
            PagesChip.ODD -> onPages { PageRanges.odd(count) }
            PagesChip.EVEN -> onPages { PageRanges.even(count) }
            PagesChip.RANGE -> onPages { PageSelection(PageMode.RANGE, PageRanges.describe(selected)) }
        }
    }

    if (chip == PagesChip.RANGE) {
        val parsed = state.selection
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.pages.rangeText,
            onValueChange = { text -> onPages { PageSelection(PageMode.RANGE, text.take(200)) } },
            label = { Text("Pages") },
            placeholder = { Text("1-3, 5, 8-") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            isError = parsed is PageParse.Invalid,
            supportingText = { if (parsed is PageParse.Invalid) Text(parsed.message) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(Modifier.height(14.dp))
    Hint("You can also tap pages in the strip on the main screen to include or skip them.")
}

@Composable
internal fun PaperSheet(state: UiState, onChange: OptionsChange) {
    val o = state.options
    val caps = state.printerCaps

    SheetTitle("Paper & layout")
    Micro("Paper size")
    Spacer(Modifier.height(10.dp))
    ChipRow(Paper.entries.filter { caps?.supportsPaper(it) ?: true }.map { it to it.label }, selected = o.paper) { p ->
        onChange { it.copy(paper = p) }
    }
    if (state.destination is Destination.Network) {
        Spacer(Modifier.height(8.dp))
        Hint("Only paper sizes this printer reports are offered.")
    }

    Spacer(Modifier.height(16.dp))
    Segmented(listOf(false to "Portrait", true to "Landscape"), selected = o.landscape) { l ->
        onChange { it.copy(landscape = l) }
    }

    SectionLabel("Position")
    Segmented(listOf(Anchor.CENTER to "Centered", Anchor.TOP_LEFT to "Top-left"), selected = o.scale.anchor) { a ->
        onChange { it.copy(scale = it.scale.copy(anchor = a)) }
    }
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Auto-rotate", style = Type.bodyMedium, color = palette.ink)
            Hint("Turn landscape pages to fit portrait paper, and the reverse.")
        }
        Switch(
            checked = o.scale.autoRotate,
            onCheckedChange = { v -> onChange { it.copy(scale = it.scale.copy(autoRotate = v)) } },
            colors = SwitchDefaults.colors(
                checkedTrackColor = palette.ink, checkedThumbColor = palette.onInk,
                uncheckedTrackColor = palette.ground, uncheckedThumbColor = palette.muted,
                uncheckedBorderColor = palette.hairline,
            ),
        )
    }

    SectionLabel("Margins")
    ChipRow(
        listOf(
            MarginMode.PRINTER_MINIMUM to "Printer minimum",
            MarginMode.NONE to "None",
            MarginMode.CUSTOM to "Custom",
        ),
        selected = o.marginMode,
    ) { m -> onChange { it.copy(marginMode = m) } }
    if (o.marginMode == MarginMode.CUSTOM) {
        Spacer(Modifier.height(12.dp))
        NumberField(o.customMarginMm, 0f, 50f, "mm", "Margin", Modifier.fillMaxWidth()) { v ->
            onChange { it.copy(customMarginMm = v) }
        }
    }

    if (state.doc?.containsImages == true) {
        SectionLabel("Image size")
        ChipRow(PrintOptions.IMAGE_DPI_CHOICES.map { it to "$it dpi" }, selected = o.imageDpi) { d ->
            onChange { it.copy(imageDpi = d) }
        }
        Spacer(Modifier.height(8.dp))
        Hint("Photos have no real physical size, so 100% assumes this resolution. Higher dpi prints smaller.")
    }

}

/** Copies, color and two-sided: the choices Android's dialog would otherwise own. */
@Composable
internal fun OptionsSheet(state: UiState, onDirect: ((DirectParams) -> DirectParams) -> Unit) {
    val caps = state.printerCaps ?: return
    val d = state.direct

    SheetTitle("Print options")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Copies", style = Type.bodyMedium, color = palette.ink, modifier = Modifier.weight(1f))
        StepButton(Icons.Default.Remove, "Fewer copies", enabled = d.copies > 1) {
            onDirect { it.copy(copies = (it.copies - 1).coerceAtLeast(1)) }
        }
        Text("${d.copies}", style = Type.tileValue, color = palette.ink, modifier = Modifier.width(52.dp), textAlign = TextAlign.Center)
        StepButton(Icons.Default.Add, "More copies", enabled = d.copies < caps.copiesMax) {
            onDirect { it.copy(copies = (it.copies + 1).coerceAtMost(caps.copiesMax)) }
        }
    }
    if (caps.colorSupported && caps.monochromeSupported) {
        SectionLabel("Color")
        Segmented(listOf(true to "Color", false to "Black & white"), selected = d.color) { v -> onDirect { it.copy(color = v) } }
    }
    val route = caps.routeFor(d.format)
    if (route == PrintRoute.PDF && caps.hasDuplex) {
        SectionLabel("Two-sided")
        Segmented(
            listOf(TwoSided.OFF to "Off", TwoSided.LONG_EDGE to "Long edge", TwoSided.SHORT_EDGE to "Short edge"),
            selected = d.twoSided,
        ) { v -> onDirect { it.copy(twoSided = v) } }
        Spacer(Modifier.height(8.dp))
        Hint("Long edge flips like a book; short edge flips like a notepad.")
    }

    if (caps.routes.size > 1) {
        SectionLabel("Send as")
        Segmented(
            listOf<Pair<PrintRoute?, String>>(null to "Auto") + caps.routes.map { it to it.label },
            selected = d.format,
        ) { v -> onDirect { it.copy(format = v) } }
        Spacer(Modifier.height(8.dp))
        Hint("Auto picks the most dependable format for this printer. If a printout comes out the wrong size, try another.")
    }

    when (route) {
        PrintRoute.RASTER, PrintRoute.URF -> if (caps.hasDuplex) {
            Spacer(Modifier.height(14.dp))
            Hint("Pages go to this printer as images, so two-sided isn't available. Choose PDF above if it offers it.")
        }
        PrintRoute.JPEG -> {
            Spacer(Modifier.height(14.dp))
            Hint(
                "Pages go as JPEG pictures, which can't state a physical size. They are sent one at a " +
                    "time, so keep NBS Print open until the last one is out. Copies come out uncollated and the size " +
                    "may be off by a few percent."
            )
        }
        else -> {}
    }
    if (route != PrintRoute.JPEG && !caps.scalingNoneSupported) {
        Spacer(Modifier.height(10.dp))
        Hint("This printer can't be told to skip its own scaling, so output may be slightly resized. The Android print dialog may behave differently.")
    }

    PrinterDetails(caps.diagnostics(route))
}

/** What the printer reports, tucked away; the Copy button is for sending it along when something misbehaves. */
@Composable
private fun PrinterDetails(text: String) {
    var open by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Spacer(Modifier.height(20.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button) { open = !open }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Micro(if (open) "Hide printer details" else "Printer details", Modifier.weight(1f))
    }
    if (open) {
        Text(
            text,
            style = Type.caption.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
            color = palette.ink,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(palette.ground)
                .padding(12.dp),
        )
        Spacer(Modifier.height(8.dp))
        GhostPill("Copy details", { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)) }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(palette.ground)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription = description, modifier = Modifier.size(20.dp),
            tint = if (enabled) palette.ink else palette.muted.copy(alpha = 0.4f),
        )
    }
}
