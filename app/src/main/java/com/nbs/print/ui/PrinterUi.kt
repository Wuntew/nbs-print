package com.nbs.print.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nbs.print.ipp.Destination
import com.nbs.print.ipp.PrinterRef
import com.nbs.print.print.DirectParams
import com.nbs.print.print.DirectProgress
import com.nbs.print.print.TwoSided
import kotlinx.coroutines.delay

/** Tappable "where will this print" row shown above the Print button. */
@Composable
internal fun DestinationBar(state: UiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (title, subtitle, problem) = when (val d = state.destination) {
        Destination.SystemDialog -> Triple("Android print dialog", "Choose a printer in the next screen", false)
        is Destination.Network -> when (val c = state.caps) {
            CapsState.Loading, CapsState.None -> Triple(d.printer.name, "Checking printer…", false)
            is CapsState.Failed -> Triple(d.printer.name, c.message, true)
            is CapsState.Ready -> {
                val problems = c.caps.problems()
                if (problems.isEmpty()) Triple(d.printer.name, "Ready · prints directly from this app", false)
                else Triple(d.printer.name, problems.joinToString(" · "), true)
            }
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (state.destination is Destination.Network) Icons.Default.Wifi else Icons.Default.Print,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Default.ArrowDropDown, contentDescription = "Change printer")
    }
}

/** Copies / color / two-sided: the choices Android's dialog would otherwise own. */
@Composable
internal fun PrinterOptionsSection(
    state: UiState,
    onDirect: ((DirectParams) -> DirectParams) -> Unit,
    onRetry: () -> Unit,
) {
    if (state.destination !is Destination.Network) return
    when (val c = state.caps) {
        CapsState.None, CapsState.Loading -> Section("Printer") { Hint("Checking what this printer supports…") }
        is CapsState.Failed -> Section("Printer") {
            Text(c.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onRetry) { Text("Try again") }
        }
        is CapsState.Ready -> {
            val caps = c.caps
            val d = state.direct
            Section("Printer options") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Copies", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onDirect { it.copy(copies = (it.copies - 1).coerceAtLeast(1)) } }, enabled = d.copies > 1) {
                        Icon(Icons.Default.Remove, contentDescription = "Fewer copies")
                    }
                    Text("${d.copies}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(32.dp))
                    IconButton(
                        onClick = { onDirect { it.copy(copies = (it.copies + 1).coerceAtMost(caps.copiesMax)) } },
                        enabled = d.copies < caps.copiesMax,
                    ) { Icon(Icons.Default.Add, contentDescription = "More copies") }
                }
                if (caps.colorSupported && caps.monochromeSupported) {
                    Chips(listOf(true to "Color", false to "Black & white"), selected = d.color) { v ->
                        onDirect { it.copy(color = v) }
                    }
                }
                if (caps.canDuplex) {
                    Chips(
                        listOf(TwoSided.OFF to "One-sided", TwoSided.LONG_EDGE to "Two-sided · long edge", TwoSided.SHORT_EDGE to "Two-sided · short edge"),
                        selected = d.twoSided,
                    ) { v -> onDirect { it.copy(twoSided = v) } }
                }
                if (!caps.canPdf) {
                    Hint("This printer takes one page at a time, so two-sided isn't available and copies come out uncollated.")
                }
                if (!caps.scalingNoneSupported) {
                    Hint("This printer can't be told to skip its own scaling, so output may be slightly resized. The Android print dialog may behave differently.")
                }
            }
        }
    }
}

/** Pick Android's dialog, a discovered or saved printer, or add one by address. */
@Composable
internal fun PrinterPickerDialog(
    state: UiState,
    onDismiss: () -> Unit,
    onSelect: (Destination) -> Unit,
    onRemove: (PrinterRef) -> Unit,
    onAdd: (String) -> Unit,
) {
    var address by remember { mutableStateOf("") }
    var gaveUpSearching by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(8_000)
        gaveUpSearching = true
    }
    val current = (state.destination as? Destination.Network)?.printer
    val discoveredUris = state.discovered.map { it.uri }.toSet()
    val savedOnly = state.saved.filter { it.uri !in discoveredUris }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Print to") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PickerRow(
                    "Android print dialog",
                    "USB, Bluetooth and printers with a maker's plugin",
                    selected = state.destination == Destination.SystemDialog,
                    onClick = { onSelect(Destination.SystemDialog) },
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                Text("Printers on your Wi-Fi", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (state.discovered.isEmpty()) {
                    if (gaveUpSearching) {
                        Text(
                            "No printers found. Check that the printer is on and on the same Wi-Fi as this phone, or add it by address below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Searching…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                state.discovered.forEach { p ->
                    PickerRow(p.name, p.uri, selected = current?.uri == p.uri, onClick = { onSelect(Destination.Network(p)) })
                }

                if (savedOnly.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Saved", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    savedOnly.forEach { p ->
                        PickerRow(
                            p.name, p.uri, selected = current?.uri == p.uri,
                            onClick = { onSelect(Destination.Network(p)) },
                            onRemove = { onRemove(p) },
                        )
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Add by address", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("IP address or name") },
                    placeholder = { Text("192.168.1.20") },
                    singleLine = true,
                    isError = state.pickerError != null,
                    supportingText = { state.pickerError?.let { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Button(
                    onClick = { onAdd(address) },
                    enabled = address.isNotBlank() && !state.addingPrinter,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    if (state.addingPrinter) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.addingPrinter) "Checking…" else "Add printer")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
        if (onRemove != null) {
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Remove ${title}") }
        }
    }
}

/** Progress, result or failure of a direct print job. */
@Composable
internal fun JobDialog(job: JobState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    when (job) {
        is JobState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Printing to ${job.printerName}") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(progressText(job.progress))
                }
            },
            confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )

        is JobState.Finished -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Sent to ${job.printerName}") },
            text = { Text(job.note ?: "Your printer has finished the job.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )

        is JobState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Couldn't print") },
            text = { Text(job.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
    }
}

private fun progressText(p: DirectProgress): String = when (p) {
    is DirectProgress.Rendering -> "Preparing page ${p.done + 1} of ${p.total}…"
    DirectProgress.Sending -> "Sending to the printer…"
    is DirectProgress.Printing -> p.status
    is DirectProgress.Done -> "Done"
    is DirectProgress.Failed -> p.message
}
