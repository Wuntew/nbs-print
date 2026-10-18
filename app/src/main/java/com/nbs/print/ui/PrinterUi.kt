package com.nbs.print.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.nbs.print.ipp.Destination
import com.nbs.print.ipp.PrinterRef
import com.nbs.print.ipp.PrinterUri
import kotlinx.coroutines.delay

/** How the chosen destination is described on the home tile and in the printer sheet. */
internal data class DestSummary(
    val name: String,
    val shortName: String,
    val status: String,
    val dot: Color?,
    val isProblem: Boolean,
)

@Composable
internal fun destSummary(state: UiState): DestSummary {
    val p = palette
    return when (val d = state.destination) {
        Destination.SystemDialog -> DestSummary("Android print dialog", "Android dialog", "Choose in next screen", null, false)
        is Destination.Network -> when (val c = state.caps) {
            CapsState.None, CapsState.Loading -> DestSummary(d.printer.name, d.printer.name, "Checking…", null, false)
            is CapsState.Failed -> DestSummary(d.printer.name, d.printer.name, "Can't reach it", p.problem, true)
            is CapsState.Ready -> {
                val problems = c.caps.problems()
                if (problems.isEmpty()) DestSummary(d.printer.name, d.printer.name, "Ready", p.ok, false)
                else DestSummary(d.printer.name, d.printer.name, problems.joinToString(" · "), p.accent, true)
            }
        }
    }
}

/** Pick a discovered or saved printer, Android's dialog, or add a printer by address. */
@Composable
internal fun PrinterSheet(
    state: UiState,
    onDismiss: () -> Unit,
    onSelect: (Destination) -> Unit,
    onRemove: (PrinterRef) -> Unit,
    onAdd: (String) -> Unit,
    onRetry: () -> Unit,
) {
    var address by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var gaveUpSearching by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(8_000)
        gaveUpSearching = true
    }
    val current = (state.destination as? Destination.Network)?.printer
    val summary = destSummary(state)
    val discoveredUris = state.discovered.map { it.uri }.toSet()
    val savedOnly = state.saved.filter { it.uri !in discoveredUris }

    SheetFrame(onDismiss) {
        SheetTitle("Print to")

        val caps = state.caps
        if (caps is CapsState.Failed) {
            Text(caps.message, style = Type.body, color = palette.problem)
            Spacer(Modifier.height(8.dp))
            GhostPill("Try again", onRetry, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Micro("On your Wi-Fi", Modifier.weight(1f))
            if (state.discovered.isEmpty() && !gaveUpSearching) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = palette.accent)
                Spacer(Modifier.width(8.dp))
                Text("Searching", style = Type.caption, color = palette.muted)
            }
        }
        Spacer(Modifier.height(4.dp))
        if (state.discovered.isEmpty() && gaveUpSearching) {
            Hint(
                "No printers found. Check that the printer is on and on the same Wi-Fi as this phone, or add it by address below.",
                Modifier.padding(vertical = 8.dp),
            )
        }
        state.discovered.forEach { p ->
            val isCurrent = current?.uri == p.uri
            PrinterRow(
                icon = Icons.Default.Print,
                title = p.name,
                sub = if (isCurrent) summary.status else addressLabel(p.uri),
                subDot = if (isCurrent) summary.dot else null,
                subColor = if (isCurrent && summary.isProblem) palette.problem else palette.muted,
                selected = isCurrent,
                onClick = { onSelect(Destination.Network(p)) },
            )
        }
        savedOnly.forEach { p ->
            val isCurrent = current?.uri == p.uri
            PrinterRow(
                icon = Icons.Default.Print,
                title = p.name,
                sub = if (isCurrent) summary.status else addressLabel(p.uri),
                subDot = if (isCurrent) summary.dot else null,
                subColor = if (isCurrent && summary.isProblem) palette.problem else palette.muted,
                selected = isCurrent,
                onClick = { onSelect(Destination.Network(p)) },
                onRemove = { onRemove(p) },
            )
        }

        SectionLabel("Other ways")
        if (adding) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("IP address or name") },
                placeholder = { Text("192.168.1.20") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                isError = state.pickerError != null,
                supportingText = { state.pickerError?.let { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            InkPill(
                if (state.addingPrinter) "Checking…" else "Add printer",
                onClick = { onAdd(address) },
                enabled = address.isNotBlank() && !state.addingPrinter,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
        } else {
            PrinterRow(
                icon = Icons.Default.Add, title = "Add by address", sub = "IP address or name",
                dashed = true, onClick = { adding = true },
            )
        }
        PrinterRow(
            icon = Icons.Default.Description,
            title = "Android print dialog",
            sub = "USB, Bluetooth and plugin printers",
            selected = state.destination == Destination.SystemDialog,
            onClick = { onSelect(Destination.SystemDialog) },
            last = true,
        )
    }
}

private fun addressLabel(uri: String): String {
    val host = PrinterUri.host(uri)
    val port = uri.substringAfter("://").substringBefore('/').substringAfterLast(':', "").toIntOrNull()
    return if (port == null || port == PrinterUri.DEFAULT_PORT) host else "$host:$port"
}

@Composable
private fun PrinterRow(
    icon: ImageVector,
    title: String,
    sub: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    subDot: Color? = null,
    subColor: Color = palette.muted,
    dashed: Boolean = false,
    last: Boolean = false,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .then(if (dashed) Modifier.border(1.5.dp, palette.muted.copy(alpha = 0.55f), CircleShape) else Modifier.background(palette.ground)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = palette.ink, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.bodyMedium, color = palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (subDot != null) {
                    Dot(subDot, size = 6.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(sub, style = Type.caption.copy(fontSize = 13.sp), color = subColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(palette.ink), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, contentDescription = "Selected", tint = palette.onInk, modifier = Modifier.size(14.dp))
            }
        }
        if (onRemove != null) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Close, contentDescription = "Remove $title", tint = palette.muted, modifier = Modifier.size(18.dp)) }
        }
    }
    if (!last) HorizontalDivider(color = palette.hairline)
}
