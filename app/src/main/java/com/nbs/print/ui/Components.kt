package com.nbs.print.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Small uppercase label above a value. */
@Composable
internal fun Micro(text: String, modifier: Modifier = Modifier, color: Color = palette.muted) {
    Text(text.uppercase(), style = Type.micro, color = color, modifier = modifier)
}

@Composable
internal fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 7.dp) {
    Box(modifier.size(size).background(color, CircleShape))
}

@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier, color: Color = palette.muted) {
    Text(text, style = Type.body.copy(fontSize = 13.sp, lineHeight = 19.sp), color = color, modifier = modifier)
}

/** Round white button holding one icon. */
@Composable
internal fun CircleIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(palette.tile)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = palette.ink, modifier = Modifier.size(20.dp))
    }
}

/** One of the home screen's setting tiles: tiny label, big value, small caption. */
@Composable
internal fun OptionTile(
    label: String,
    value: String,
    sub: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentDot: Boolean = false,
    compactValue: Boolean = false,
    subDot: Color? = null,
    subColor: Color = palette.muted,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(palette.tile)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 88.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Micro(label, Modifier.weight(1f))
            if (accentDot) Dot(palette.accent)
        }
        Column(Modifier.padding(top = 10.dp)) {
            Text(
                value,
                style = if (compactValue) Type.tileValueSmall else Type.tileValue,
                color = palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    if (subDot != null) {
                        Dot(subDot, size = 6.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(sub, style = Type.caption, color = subColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The big dark pill (Print, Done). */
@Composable
internal fun InkPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Row(
        modifier
            .height(58.dp)
            .clip(CircleShape)
            .background(palette.ink.copy(alpha = if (enabled) 1f else 0.28f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = palette.onInk, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = Type.button, color = palette.onInk)
    }
}

/** Outlined secondary pill (Cancel). */
@Composable
internal fun GhostPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(56.dp)
            .clip(CircleShape)
            .border(BorderStroke(1.dp, palette.muted.copy(alpha = 0.5f)), CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.button.copy(fontWeight = FontWeight.Medium), color = palette.ink)
    }
}

/** Pill-shaped choice between a few options; the selected one is filled with ink. */
@Composable
internal fun <T> Segmented(items: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(palette.ground)
            .padding(4.dp)
            .selectableGroup(),
    ) {
        items.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (on) palette.ink else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(value) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = Type.bodyMedium.copy(fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (on) palette.onInk else palette.ink,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Wrapping row of pill chips, for choices too numerous for [Segmented]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChipRow(items: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.selectableGroup(),
    ) {
        items.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (on) palette.ink else palette.ground)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(value) })
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = Type.bodyMedium.copy(fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (on) palette.onInk else palette.ink,
                )
            }
        }
    }
}

/** Numeric text field that only forwards values inside [min]..[max]. */
@Composable
internal fun NumberField(
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
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = text.toFloatOrNull()?.let { it !in min..max } ?: true,
        modifier = modifier,
    )
}

/** A modal bottom sheet in the app's style: rounded top, soft handle, scrolls if tall. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SheetFrame(onDismiss: () -> Unit, footer: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.tile,
        contentColor = palette.ink,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .background(palette.hairline, CircleShape)
            )
        },
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        // The footer (e.g. Done) stays pinned below the scrolling content, clear of the system
        // navigation bar, so it can never end up underneath 3-button navigation on a short screen.
        Column(Modifier.fillMaxWidth().imePadding()) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp)
                    .then(if (footer == null) Modifier.navigationBarsPadding() else Modifier),
            ) {
                content()
            }
            if (footer != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 16.dp),
                ) { footer() }
            }
        }
    }
}

@Composable
internal fun SheetTitle(text: String) {
    Text(text, style = Type.title, color = palette.ink, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Micro(text, modifier.padding(top = 24.dp, bottom = 10.dp))
}
