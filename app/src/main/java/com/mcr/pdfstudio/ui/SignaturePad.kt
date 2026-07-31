package com.mcr.pdfstudio.ui

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Sign-here pad.
 *
 * Strokes are captured in the pad's pixel space and handed back with the pad
 * size, so the caller can rasterise at exactly the resolution it wants.
 */
@Composable
fun SignaturePadDialog(
    onSave: (strokes: List<List<PointF>>, width: Int, height: Int, color: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val strokes = remember { mutableStateListOf<SnapshotStateList<Offset>>() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var color by remember { mutableStateOf(SWATCHES[0]) }
    var thickness by remember { mutableStateOf(6f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign here") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Draw your signature with a finger or stylus. It is saved on " +
                        "this device so you can place it on any page later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(8.dp)
                        )
                        .onSizeChanged { size = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    strokes.add(mutableStateListOf(offset))
                                }
                            ) { change, _ ->
                                strokes.lastOrNull()?.add(change.position)
                            }
                        }
                ) {
                    Canvas(Modifier.fillMaxWidth().height(190.dp)) {
                        for (stroke in strokes) {
                            for (i in 1 until stroke.size) {
                                drawLine(
                                    color = Color(color),
                                    start = stroke[i - 1],
                                    end = stroke[i],
                                    strokeWidth = thickness,
                                    cap = StrokeCap.Round
                                )
                            }
                        }
                    }
                }
                SwatchRow(SWATCHES, color, onSelect = { color = it })
                LabeledSlider(
                    "Pen ${"%.1f".format(thickness)}",
                    thickness, 2f..14f, { thickness = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        strokes.map { stroke -> stroke.map { PointF(it.x, it.y) } },
                        size.width,
                        size.height,
                        color
                    )
                },
                enabled = strokes.any { it.size > 1 } && size.width > 0
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { strokes.clear() }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun Row(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) { content() }
}
