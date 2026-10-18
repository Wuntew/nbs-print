package com.nbs.print.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbs.print.layout.PageRanges
import kotlin.math.max

private val PaperWhite = Color.White
private val ThumbNumber = Color(0xFF6F6E69)

/**
 * A strip of page thumbnails. Tap a page to include or skip it (the preview jumps to it too).
 * Selected pages are outlined and ticked; skipped ones fade back.
 */
@Composable
internal fun Filmstrip(
    state: UiState,
    loadThumb: suspend (Int) -> Bitmap?,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = remember(state.pages, state.pageCount, state.page) {
        PageRanges.selected(state.pages, state.pageCount, state.page).toSet()
    }
    val listState = rememberLazyListState()
    LaunchedEffect(state.page, state.pageCount) {
        if (state.pageCount > 0) listState.animateScrollToItem(max(state.page - 2, 0))
    }

    val paper = state.options.paper
    val w = if (state.options.landscape) paper.heightMils else paper.widthMils
    val h = if (state.options.landscape) paper.widthMils else paper.heightMils
    val thumbHeight = 72.dp
    val thumbWidth = thumbHeight * (w.toFloat() / h)
    // Thumbnails depend on everything that changes how a page looks.
    val thumbKey = Triple(state.doc, state.options, state.printerCaps?.marginPt)

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Micro("Pages", Modifier.weight(1f))
            Text("${selected.size} of ${state.pageCount}", style = Type.caption.copy(fontSize = 12.sp), color = palette.ink)
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.pageCount, key = { it }) { page ->
                val isSelected = page in selected
                val isCurrent = page == state.page
                val bitmap by produceState<Bitmap?>(null, page, thumbKey) { value = loadThumb(page) }
                val ink = palette.ink
                val hairline = palette.hairline
                val shape = RoundedCornerShape(8.dp)

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(thumbWidth, thumbHeight)
                            .alpha(if (isSelected) 1f else 0.45f)
                            .clip(shape)
                            .background(PaperWhite)
                            .border(if (isSelected) 1.5.dp else 1.dp, if (isSelected) ink else hairline, shape)
                            .toggleable(value = isSelected, role = Role.Checkbox, onValueChange = { onToggle(page) })
                            .semantics {
                                contentDescription = "Page ${page + 1}" + if (isCurrent) ", showing in preview" else ""
                            },
                    ) {
                        bitmap?.let {
                            Image(
                                it.asImageBitmap(), contentDescription = null,
                                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) ink else PaperWhite)
                                .border(1.dp, if (isSelected) ink else hairline, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = palette.onInk, modifier = Modifier.size(12.dp))
                            }
                        }
                        Text(
                            "${page + 1}",
                            style = Type.caption.copy(fontSize = 10.sp),
                            color = ThumbNumber,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(4.dp)
                                .background(PaperWhite.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Dot(if (isCurrent) palette.accent else Color.Transparent, size = 5.dp)
                }
            }
        }
    }
}
