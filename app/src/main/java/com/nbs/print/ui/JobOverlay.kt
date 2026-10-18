package com.nbs.print.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbs.print.print.DirectProgress
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Full-screen progress for a direct print job. Covers the home screen while it runs. */
@Composable
internal fun JobOverlay(job: JobState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    BackHandler { if (job !is JobState.Running) onDismiss() }
    Box(
        Modifier
            .fillMaxSize()
            .background(palette.ground)
            .pointerInput(Unit) {} // swallow touches meant for the screen underneath
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        when (job) {
            is JobState.Running -> RunningContent(job, onCancel)
            is JobState.Finished -> FinishedContent(job, onDismiss)
            is JobState.Failed -> FailedContent(job, onDismiss)
        }
    }
}

private enum class StepState { Pending, Active, Done }

@Composable
private fun RunningContent(job: JobState.Running, onCancel: () -> Unit) {
    val p = job.progress
    // The ring fills while pages are prepared and again if the printer reports page counts;
    // otherwise a highlight sweeps round while we wait on the printer.
    val fraction: Float? = when (p) {
        is DirectProgress.Rendering -> if (p.total > 0) p.done.toFloat() / p.total else 0f
        is DirectProgress.Printing -> if (p.completed != null && p.total != null && p.total > 0) p.completed.toFloat() / p.total else null
        else -> null
    }
    val centerMain: String? = when (p) {
        is DirectProgress.Rendering -> "${(p.done + 1).coerceAtMost(p.total)}/${p.total}"
        is DirectProgress.Printing -> if (p.completed != null && p.total != null) "${p.completed}/${p.total}" else null
        else -> null
    }
    val centerLabel = when (p) {
        is DirectProgress.Rendering -> "Preparing"
        DirectProgress.Sending -> "Sending"
        is DirectProgress.Printing -> if (p.completed != null) "Printed" else "Printing"
        else -> ""
    }
    val steps = listOf(
        "Prepare pages" to when (p) {
            is DirectProgress.Rendering -> StepState.Active
            else -> StepState.Done
        },
        "Send to printer" to when (p) {
            is DirectProgress.Rendering -> StepState.Pending
            DirectProgress.Sending -> StepState.Active
            else -> StepState.Done
        },
        ((p as? DirectProgress.Printing)?.status ?: "Print") to when (p) {
            is DirectProgress.Printing -> StepState.Active
            else -> StepState.Pending
        },
    )

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(36.dp))
        Box(
            Modifier
                .size(260.dp)
                .semantics { contentDescription = "Printing: $centerLabel" },
            contentAlignment = Alignment.Center,
        ) {
            TickRing(fraction, Modifier.fillMaxSize())
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (centerMain != null) {
                    Text(centerMain, style = Type.hero.copy(fontSize = 60.sp, lineHeight = 64.sp), color = palette.ink)
                } else {
                    Icon(Icons.Default.Print, contentDescription = null, tint = palette.ink, modifier = Modifier.size(44.dp))
                }
                Micro(centerLabel, Modifier.padding(top = 6.dp))
            }
        }
        Spacer(Modifier.height(34.dp))
        Text("Printing…", style = Type.title.copy(fontSize = 30.sp), color = palette.ink)
        Text(job.printerName, style = Type.body, color = palette.muted, modifier = Modifier.padding(top = 4.dp))

        Spacer(Modifier.height(30.dp))
        Column(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            steps.forEach { (text, state) -> StepRow(text, state) }
        }

        Spacer(Modifier.weight(1f))
        GhostPill("Cancel", onCancel, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StepRow(text: String, state: StepState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            when (state) {
                StepState.Done -> Icon(Icons.Default.Check, contentDescription = "Done", tint = palette.muted, modifier = Modifier.size(16.dp))
                StepState.Active -> Dot(palette.accent, size = 9.dp)
                StepState.Pending -> Box(Modifier.size(9.dp).border(1.5.dp, palette.muted.copy(alpha = 0.5f), CircleShape))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = Type.body.copy(fontSize = 15.sp),
            color = when (state) {
                StepState.Active -> palette.ink
                else -> palette.muted
            },
        )
    }
}

@Composable
private fun FinishedContent(job: JobState.Finished, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(36.dp))
        Box(Modifier.size(260.dp).semantics { contentDescription = "Sent" }, contentAlignment = Alignment.Center) {
            TickRing(1f, Modifier.fillMaxSize())
            Icon(Icons.Default.Check, contentDescription = null, tint = palette.ink, modifier = Modifier.size(72.dp))
        }
        Spacer(Modifier.height(34.dp))
        Text("Sent", style = Type.title.copy(fontSize = 30.sp), color = palette.ink)
        Text(job.printerName, style = Type.body, color = palette.muted, modifier = Modifier.padding(top = 4.dp))
        Text(
            job.note ?: "Your printer has finished the job.",
            style = Type.body, color = palette.muted, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 18.dp),
        )
        Spacer(Modifier.weight(1f))
        InkPill("Done", onDismiss, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FailedContent(job: JobState.Failed, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(1f))
        Dot(palette.accent, size = 12.dp)
        Spacer(Modifier.height(18.dp))
        Text("Couldn't print", style = Type.title.copy(fontSize = 34.sp), color = palette.ink)
        Text(job.printerName, style = Type.body, color = palette.muted, modifier = Modifier.padding(top = 4.dp))
        Text(job.message, style = Type.body.copy(fontSize = 16.sp, lineHeight = 24.sp), color = palette.ink, modifier = Modifier.padding(top = 22.dp))
        Spacer(Modifier.weight(1.4f))
        InkPill("Done", onDismiss, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 60 tick marks round a circle. With a [fraction] the first part is inked in; without one a
 * highlight chases round the ring to show that we're waiting on the printer.
 */
@Composable
private fun TickRing(fraction: Float?, modifier: Modifier = Modifier) {
    val sweep by rememberInfiniteTransition(label = "ring").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    val ink = palette.ink
    val track = palette.muted.copy(alpha = 0.28f)
    val density = LocalDensity.current
    Canvas(modifier) {
        val stroke = with(density) { 2.5.dp.toPx() }
        val len = with(density) { 15.dp.toPx() }
        val outer = size.minDimension / 2f - stroke
        val center = Offset(size.width / 2f, size.height / 2f)
        val n = 60
        val lit = fraction?.let { (it * n).roundToInt() }
        for (i in 0 until n) {
            val angle = i.toFloat() / n * 2f * PI.toFloat() - PI.toFloat() / 2f
            val dir = Offset(cos(angle), sin(angle))
            val color = if (lit != null) {
                if (i < lit) ink else track
            } else {
                val behind = ((sweep * n - i) % n + n) % n
                if (behind < 16f) ink.copy(alpha = 1f - behind / 16f) .let { if (it.alpha < 0.28f) track else it } else track
            }
            drawLine(color, center + dir * (outer - len), center + dir * outer, strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}
