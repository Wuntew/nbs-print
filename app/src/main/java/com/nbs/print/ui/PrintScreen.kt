package com.nbs.print.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nbs.print.layout.Anchor
import com.nbs.print.layout.MarginMode
import com.nbs.print.layout.Paper
import com.nbs.print.layout.PrintOptions
import com.nbs.print.layout.ScaleMode
import com.nbs.print.layout.ScalingMath
import kotlin.math.roundToInt

private typealias OptionsChange = ((PrintOptions) -> PrintOptions) -> Unit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintScreen(
    state: UiState,
    onPick: () -> Unit,
    onPrint: () -> Unit,
    onPrinterSettings: () -> Unit,
    onChange: OptionsChange,
    onPage: (Int) -> Unit,
    onDismissError: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            onDismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NBS Print") },
                actions = {
                    IconButton(onClick = onPick) { Icon(Icons.Default.FolderOpen, contentDescription = "Choose file") }
                    IconButton(onClick = onPrinterSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Printer setup")
                    }
                },
            )
        },
        bottomBar = {
            // Keeps the Print button clear of the 3-button bar / gesture pill and display cutouts.
            Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                Box(
                    Modifier
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Button(
                        onClick = onPrint,
                        enabled = state.doc != null && state.preview != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        Icon(Icons.Default.Print, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Print")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.doc == null -> EmptyState(state.loading, onPick)
                else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    if (maxWidth > maxHeight) {
                        Row(Modifier.fillMaxSize()) {
                            PreviewPane(state, onPage, Modifier.weight(1f).fillMaxHeight())
                            OptionsPane(state, onChange, Modifier.weight(1f).fillMaxHeight())
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            PreviewPane(state, onPage, Modifier.weight(0.46f).fillMaxWidth())
                            OptionsPane(state, onChange, Modifier.weight(0.54f).fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(loading: Boolean, onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Opening…")
        } else {
            Icon(
                Icons.Default.Print, contentDescription = null,
                modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text("Print exactly what you mean to", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Pick a PDF, photo or text file. Printing defaults to 100% with no scaling.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onPick) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Choose a file")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "You can also share a file to NBS Print from any app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PreviewPane(state: UiState, onPage: (Int) -> Unit, modifier: Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.doc?.title.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (state.pageCount > 1) {
                IconButton(onClick = { onPage(state.page - 1) }, enabled = state.page > 0) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous page")
                }
                Text("${state.page + 1} / ${state.pageCount}", style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { onPage(state.page + 1) }, enabled = state.page < state.pageCount - 1) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next page")
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            val bitmap = state.preview
            if (bitmap == null) {
                CircularProgressIndicator()
            } else {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Print preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.shadow(4.dp).background(Color.White),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionsPane(state: UiState, onChange: OptionsChange, modifier: Modifier) {
    val o = state.options
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section("Scale") {
            Chips(
                listOf(
                    ScaleMode.ACTUAL to "100% · No scaling",
                    ScaleMode.FIT to "Fit to page",
                    ScaleMode.FILL to "Fill page",
                    ScaleMode.CUSTOM to "Custom",
                ),
                selected = o.scale.mode,
            ) { mode -> onChange { it.copy(scale = it.scale.copy(mode = mode)) } }

            if (o.scale.mode == ScaleMode.CUSTOM) {
                CustomPercent(o.scale.customPercent) { p -> onChange { it.copy(scale = it.scale.copy(customPercent = p)) } }
            }
            val hint = when {
                state.doc?.isText == true -> "For text, Custom % changes the font size; Fit and Fill print at 100%."
                o.scale.mode == ScaleMode.ACTUAL -> "Printed at true size. Content larger than the paper is cropped."
                o.scale.mode == ScaleMode.FIT -> "Whole page fits inside the printable area."
                o.scale.mode == ScaleMode.FILL -> "Page covers the printable area; the overflow is cropped."
                else -> null
            }
            hint?.let { Hint(it) }
        }

        Section("Paper") {
            Chips(Paper.entries.map { it to it.label }, selected = o.paper) { p -> onChange { it.copy(paper = p) } }
            Chips(listOf(false to "Portrait", true to "Landscape"), selected = o.landscape) { l ->
                onChange { it.copy(landscape = l) }
            }
            Hint("Copies, color, duplex and page range are chosen in the print dialog.")
        }

        Section("Position") {
            Chips(listOf(Anchor.CENTER to "Centered", Anchor.TOP_LEFT to "Top-left"), selected = o.scale.anchor) { a ->
                onChange { it.copy(scale = it.scale.copy(anchor = a)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-rotate", style = MaterialTheme.typography.bodyLarge)
                    Hint("Turn landscape pages to fit portrait paper, and the reverse.")
                }
                Switch(
                    checked = o.scale.autoRotate,
                    onCheckedChange = { v -> onChange { it.copy(scale = it.scale.copy(autoRotate = v)) } },
                )
            }
        }

        Section("Margins") {
            Chips(
                listOf(
                    MarginMode.PRINTER_MINIMUM to "Printer minimum",
                    MarginMode.NONE to "None",
                    MarginMode.CUSTOM to "Custom",
                ),
                selected = o.marginMode,
            ) { m -> onChange { it.copy(marginMode = m) } }
            if (o.marginMode == MarginMode.CUSTOM) {
                NumberField(o.customMarginMm, 0f, 50f, "mm", "Margin") { v -> onChange { it.copy(customMarginMm = v) } }
            }
        }

        if (state.doc?.containsImages == true) {
            Section("Image size") {
                Chips(
                    PrintOptions.IMAGE_DPI_CHOICES.map { it to "$it dpi" },
                    selected = o.imageDpi,
                ) { d -> onChange { it.copy(imageDpi = d) } }
                Hint("Photos have no real physical size, so 100% assumes this resolution. Higher dpi prints smaller.")
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(items: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun CustomPercent(percent: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Slider(
            value = percent,
            onValueChange = { onChange(it.roundToInt().toFloat()) },
            valueRange = ScalingMath.MIN_PERCENT..ScalingMath.MAX_PERCENT,
            modifier = Modifier.weight(1f),
        )
        NumberField(
            percent, ScalingMath.MIN_PERCENT, ScalingMath.MAX_PERCENT, "%", "Scale",
            modifier = Modifier.width(112.dp), onValue = onChange,
        )
    }
}

/** Numeric text field that only forwards values inside [min]..[max]. */
@Composable
private fun NumberField(
    value: Float,
    min: Float,
    max: Float,
    suffix: String,
    label: String,
    modifier: Modifier = Modifier,
    onValue: (Float) -> Unit,
) {
    var text by remember(value) { mutableStateOf(if (value % 1f == 0f) value.roundToInt().toString() else value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val cleaned = raw.filter { it.isDigit() || it == '.' }.take(5)
            text = cleaned
            cleaned.toFloatOrNull()?.takeIf { it in min..max }?.let(onValue)
        },
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = text.toFloatOrNull()?.let { it !in min..max } ?: true,
        modifier = modifier,
    )
}
