package com.mcr.pdfstudio.ui

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mcr.pdfstudio.ops.ShapeKind
import com.mcr.pdfstudio.ops.Stroke
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class MarkupTool(val label: String) {
    PEN("Pen"),
    HIGHLIGHTER("Highlighter"),
    RECTANGLE("Rectangle"),
    OVAL("Oval"),
    LINE("Line"),
    ARROW("Arrow"),
    ERASE("White-out"),
    NOTE("Note"),
}

/**
 * Freehand and shape markup drawn directly on the page.
 *
 * Everything is captured in view pixels, previewed live, and converted to PDF
 * user-space points only when applied — so what lands in the file matches what
 * was drawn regardless of zoom or screen density.
 */
@Composable
fun MarkupScreen(
    vm: EditorViewModel,
    onPickStampImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tool by remember { mutableStateOf(MarkupTool.PEN) }
    var color by remember { mutableStateOf(SWATCHES[1]) }
    var width by remember { mutableStateOf(3f) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Freehand paths, in canvas pixels. The inner lists are snapshot-backed too,
    // otherwise appending a point mid-drag would not redraw the preview.
    val paths = remember(vm.docRevision) { mutableStateListOf<SnapshotStateList<Offset>>() }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var notePoint by remember { mutableStateOf<Offset?>(null) }

    val pageIndex = vm.currentPage
    val aspect = remember(pageIndex, vm.docRevision) { vm.pageAspect(pageIndex) }

    // The real page box, needed to map pixels to points. Fetched off the main
    // thread because it opens the document.
    val box by produceState<PDRectangle?>(null, pageIndex, vm.docRevision) {
        value = withContext(Dispatchers.IO) { vm.pageBox(pageIndex) }
    }

    fun toPdf(point: Offset): PointF {
        val rect = box
        val size = canvasSize
        if (rect == null || size.width == 0 || size.height == 0) return PointF(0f, 0f)
        val fx = (point.x / size.width).coerceIn(0f, 1f)
        val fy = (point.y / size.height).coerceIn(0f, 1f)
        return PointF(
            rect.lowerLeftX + fx * rect.width,
            // Screen y grows downward, PDF y grows upward.
            rect.upperRightY - fy * rect.height
        )
    }

    /** Converts a stroke width in pixels to points. */
    fun toPdfWidth(px: Float): Float {
        val rect = box ?: return px
        if (canvasSize.width == 0) return px
        return px * rect.width / canvasSize.width
    }

    fun hasPending(): Boolean =
        paths.any { it.size > 1 } || (dragStart != null && dragEnd != null)

    fun clearPending() {
        paths.clear()
        dragStart = null
        dragEnd = null
    }

    fun apply() {
        val rect = box ?: return
        when (tool) {
            MarkupTool.PEN, MarkupTool.HIGHLIGHTER -> {
                val strokes = paths.filter { it.size > 1 }.map { path ->
                    Stroke(
                        points = path.map { toPdf(it) },
                        color = color,
                        width = toPdfWidth(
                            if (tool == MarkupTool.HIGHLIGHTER) width * 4f else width
                        ),
                        opacity = if (tool == MarkupTool.HIGHLIGHTER) 0.35f else 1f
                    )
                }
                if (strokes.isNotEmpty()) vm.applyStrokes(strokes)
            }

            MarkupTool.RECTANGLE, MarkupTool.OVAL, MarkupTool.LINE, MarkupTool.ARROW -> {
                val start = dragStart ?: return
                val end = dragEnd ?: return
                val a = toPdf(start)
                val b = toPdf(end)
                vm.applyShape(
                    kind = when (tool) {
                        MarkupTool.RECTANGLE -> ShapeKind.RECTANGLE
                        MarkupTool.OVAL -> ShapeKind.OVAL
                        MarkupTool.LINE -> ShapeKind.LINE
                        else -> ShapeKind.ARROW
                    },
                    x0 = a.x, y0 = a.y, x1 = b.x, y1 = b.y,
                    strokeColor = color,
                    fillColor = null,
                    lineWidth = toPdfWidth(width)
                )
            }

            MarkupTool.ERASE -> {
                val start = dragStart ?: return
                val end = dragEnd ?: return
                val a = toPdf(start)
                val b = toPdf(end)
                vm.eraseArea(
                    minOf(a.x, b.x), minOf(a.y, b.y),
                    kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)
                )
            }

            MarkupTool.NOTE -> Unit
        }
        clearPending()
    }

    Column(modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.padding(10.dp)) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MarkupTool.entries.forEach { option ->
                        FilterChip(
                            selected = tool == option,
                            onClick = {
                                tool = option
                                clearPending()
                            },
                            label = { Text(option.label) }
                        )
                    }
                }
                if (tool != MarkupTool.ERASE && tool != MarkupTool.NOTE) {
                    SwatchRow(
                        colors = if (tool == MarkupTool.HIGHLIGHTER) HIGHLIGHTS else SWATCHES,
                        selected = color,
                        onSelect = { color = it },
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    LabeledSlider(
                        "Thickness ${"%.1f".format(width)}",
                        width, 1f..14f, { width = it }
                    )
                }
                Text(
                    when (tool) {
                        MarkupTool.PEN -> "Draw with your finger, then Apply."
                        MarkupTool.HIGHLIGHTER -> "Swipe across text to highlight."
                        MarkupTool.ERASE -> "Drag a box to white it out."
                        MarkupTool.NOTE -> "Tap the page to drop a sticky note."
                        else -> "Drag from one corner to the other."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .onSizeChanged { canvasSize = it }
            ) {
                PageImage(vm, pageIndex, Modifier.fillMaxSize())

                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(tool, pageIndex) {
                            if (tool == MarkupTool.NOTE) {
                                detectTapGestures { offset -> notePoint = offset }
                            } else if (tool == MarkupTool.PEN ||
                                tool == MarkupTool.HIGHLIGHTER
                            ) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        paths.add(mutableStateListOf(offset))
                                    }
                                ) { change, _ ->
                                    paths.lastOrNull()?.add(change.position)
                                }
                            } else {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        dragStart = offset
                                        dragEnd = offset
                                    }
                                ) { change, _ ->
                                    dragEnd = change.position
                                }
                            }
                        }
                ) {
                    val paint = Color(color)
                    val alpha = if (tool == MarkupTool.HIGHLIGHTER) 0.35f else 1f
                    val strokePx = if (tool == MarkupTool.HIGHLIGHTER) width * 4f else width

                    for (path in paths) {
                        for (i in 1 until path.size) {
                            drawLine(
                                color = paint.copy(alpha = alpha),
                                start = path[i - 1],
                                end = path[i],
                                strokeWidth = strokePx,
                                cap = StrokeCap.Round
                            )
                        }
                    }

                    val start = dragStart
                    val end = dragEnd
                    if (start != null && end != null) {
                        val topLeft = Offset(minOf(start.x, end.x), minOf(start.y, end.y))
                        val size = Size(
                            kotlin.math.abs(end.x - start.x),
                            kotlin.math.abs(end.y - start.y)
                        )
                        when (tool) {
                            MarkupTool.RECTANGLE -> drawRect(
                                paint, topLeft, size, style = DrawStroke(width)
                            )
                            MarkupTool.OVAL -> drawOval(
                                paint, topLeft, size, style = DrawStroke(width)
                            )
                            MarkupTool.LINE, MarkupTool.ARROW -> drawLine(
                                paint, start, end, strokeWidth = width, cap = StrokeCap.Round
                            )
                            MarkupTool.ERASE -> drawRect(
                                Color.White.copy(alpha = 0.75f), topLeft, size
                            )
                            else -> Unit
                        }
                    }
                }
            }
        }

        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { apply() },
                    enabled = hasPending() && box != null
                ) { Text("Apply") }
                OutlinedButton(
                    onClick = { clearPending() },
                    enabled = hasPending()
                ) { Text("Discard") }
                OutlinedButton(onClick = onPickStampImage) { Text("Place image") }
                OutlinedButton(onClick = { vm.clearMarkup() }) { Text("Strip notes") }
            }
        }
    }

    notePoint?.let { point ->
        var text by remember(point) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { notePoint = null },
            title = { Text("Sticky note") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val p = toPdf(point)
                        vm.addNote(p.x, p.y, text)
                        notePoint = null
                    },
                    enabled = text.isNotBlank()
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { notePoint = null }) { Text("Cancel") }
            }
        )
    }
}
