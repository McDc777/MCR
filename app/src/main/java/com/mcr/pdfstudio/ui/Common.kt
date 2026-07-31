package com.mcr.pdfstudio.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import com.mcr.pdfstudio.ui.vanta.VantaSurface
import com.mcr.pdfstudio.ui.vanta.VantaTokens
import com.mcr.pdfstudio.ui.vanta.VantaVariant
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Colour swatches offered for ink, highlight and text. */
val SWATCHES = listOf(
    0xFF000000.toInt(), 0xFFB3261E.toInt(), 0xFF1565C0.toInt(), 0xFF2E7D32.toInt(),
    0xFFF9A825.toInt(), 0xFF6A1B9A.toInt(), 0xFFEF6C00.toInt(), 0xFFFFFFFF.toInt(),
)

val HIGHLIGHTS = listOf(
    0xFFFFF176.toInt(), 0xFF80DEEA.toInt(), 0xFFA5D6A7.toInt(),
    0xFFF48FB1.toInt(), 0xFFFFCC80.toInt(),
)

/**
 * Renders one page.
 *
 * Bitmaps are produced off the main thread and re-produced whenever the
 * document changes ([EditorViewModel.docRevision]) or the layout width changes.
 */
@Composable
fun PageImage(
    vm: EditorViewModel,
    index: Int,
    modifier: Modifier = Modifier,
    scaleFactor: Float = 2f,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }.toInt().coerceAtLeast(64)
        val requestWidth = (widthPx * scaleFactor).toInt().coerceAtMost(3000)
        val aspect = remember(index, vm.docRevision) { vm.pageAspect(index) }

        val bitmap by produceState<Bitmap?>(
            initialValue = null,
            index,
            vm.docRevision,
            requestWidth,
            vm.invertPages,
        ) {
            value = withContext(Dispatchers.IO) { vm.renderPage(index, requestWidth) }
        }

        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .background(Color.White)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

/** A page the user can pinch to zoom and drag to pan. */
@Composable
fun ZoomablePage(
    vm: EditorViewModel,
    index: Int,
    modifier: Modifier = Modifier,
) {
    var scale by remember(index) { mutableStateOf(1f) }
    var offset by remember(index) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(index) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    // At 1x there is nothing to pan, so keep the page centred.
                    offset = if (scale <= 1.01f) {
                        Offset.Zero
                    } else {
                        offset + pan
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        PageImage(
            vm = vm,
            index = index,
            scaleFactor = if (scale > 2f) 3f else 2f,
            modifier = Modifier.graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y
            )
        )
    }
}

/**
 * Gives a surface a stable identity so neighbours never share a stroke.
 *
 * The golden-angle engine separates hues by index; deriving that index from the
 * title keeps a given section the same colour across recompositions and screen
 * visits, which matters more here than perfect uniqueness.
 */
fun vantaIndexFor(key: String): Int = kotlin.math.abs(key.hashCode()) % 12

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    VantaSurface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
        variant = VantaVariant.Panel,
        seed = "mcr-section",
        index = vantaIndexFor(title),
        contentPadding = PaddingValues(18.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = VantaTokens.InkOnDark,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        content()
    }
}

/** A glass action button. Colour rides the stroke; the fill stays colourless. */
@Composable
fun VantaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    prominent: Boolean = false,
) {
    VantaSurface(
        modifier = modifier,
        variant = VantaVariant.Button,
        seed = "mcr-button",
        index = vantaIndexFor(text),
        onClick = onClick,
        enabled = enabled,
        active = prominent,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 11.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

@Composable
fun SwatchRow(
    colors: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        colors.forEach { value ->
            val isSelected = value == selected
            Box(
                Modifier
                    .size(if (isSelected) 34.dp else 28.dp)
                    .background(Color(value), CircleShape)
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = CircleShape
                    )
                    .clickable { onSelect(value) }
            )
        }
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    steps: Int = 0,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps
        )
    }
}

/** Blocks input and explains what is happening during a long operation. */
@Composable
fun BusyOverlay(label: String?) {
    if (label == null) return
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTransformGestures { _, _, _, _ -> } },
        contentAlignment = Alignment.Center
    ) {
        VantaSurface(
            modifier = Modifier.padding(32.dp),
            variant = VantaVariant.Panel,
            seed = "mcr-busy",
            active = true,
            contentPadding = PaddingValues(28.dp)
        ) {
            Column(
                Modifier.align(Alignment.CenterHorizontally),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = VantaTokens.InkOnDark)
                Text(
                    label,
                    modifier = Modifier.padding(top = 14.dp),
                    textAlign = TextAlign.Center,
                    color = VantaTokens.InkOnDark,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
