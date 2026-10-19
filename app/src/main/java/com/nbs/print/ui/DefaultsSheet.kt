package com.nbs.print.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Saves the settings on screen as the starting point for every file, and shows what is saved.
 * "Reset" forgets them and puts the app's own settings (100%, local paper size) back.
 */
@Composable
internal fun DefaultsSheet(state: UiState, onSave: () -> Unit, onReset: () -> Unit) {
    val current = Defaults(state.options, state.direct)
    val saved = state.defaults
    val upToDate = saved == current

    SheetTitle("Default settings")
    Hint(
        if (saved == null) "Nothing saved yet, so every file starts at 100% (no scaling) on your local paper size."
        else "Every file you open starts with these settings. Pages always start as all selected."
    )

    SectionLabel(if (saved == null) "App settings" else "Saved")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        (saved ?: Defaults.factory()).summary().forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(label, style = Type.bodyMedium, color = palette.muted, modifier = Modifier.width(88.dp))
                Text(value, style = Type.bodyMedium, color = palette.ink, modifier = Modifier.weight(1f))
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    InkPill(
        if (upToDate && saved != null) "Saved" else "Save current settings as default",
        onSave,
        Modifier.fillMaxWidth(),
        enabled = !upToDate,
    )
    if (!upToDate && saved != null) {
        Spacer(Modifier.height(8.dp))
        Hint("The settings on screen differ from the saved ones.")
    }
    if (saved != null) {
        Spacer(Modifier.height(12.dp))
        GhostPill("Reset to app settings", onReset, Modifier.fillMaxWidth().padding(bottom = 4.dp))
    }
}
