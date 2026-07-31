package com.mcr.pdfstudio.ui.vanta

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.floor

/**
 * Vanta-Mc-UI for Compose.
 *
 * The governing law: **colour lives only on the stroke.** The fill is always
 * colourless — white frost at low alpha, so whatever tone appears inside a
 * surface is nothing but the backdrop showing through. Saturated colour exists
 * at the perimeter only, as a rotating gradient stroke plus an outward glow
 * ring drawn *behind* the pane so it can never flood the interior.
 */

// ---------------------------------------------------------------- tokens

object VantaTokens {
    // geometry.radius / geometry.stroke
    val radiusPanel = 22.dp
    val radiusButton = 13.dp
    val radiusInput = 12.dp
    val radiusContainer = 26.dp

    val strokePanel = 2.dp
    val strokeButton = 1.6.dp
    val strokeInput = 1.4.dp
    val strokeContainer = 1.5.dp

    // glow.blur / glow.ringFatPx
    val glowBlurPanel = 14.dp
    val glowBlurButton = 10.dp
    val glowBlurInput = 8.dp
    val glowBlurContainer = 18.dp
    val glowRingFat = 3.dp

    const val GLOW_REST = 0.85f
    const val GLOW_ACTIVE = 1.0f
    const val GLOW_CONTAINER = 0.6f
    const val GLOW_INPUT_RESTING = 0.0f
    const val GLOW_INPUT_FOCUSED = 0.95f

    /**
     * fill.frostAlpha is 0.08 when a real backdrop blur is sampling the page.
     * Compose has no backdrop filter, so degradation.noBackdropFilter applies
     * and the frost is raised so the surface still reads.
     */
    const val FROST_ALPHA = 0.16f
    const val BEVEL_RIM = 0.14f
    const val BEVEL_TOP = 0.55f
    const val BEVEL_SEAT = 0.10f

    // interaction.press
    const val PRESS_SCALE = 0.972f
    const val PRESS_DAMPING = 0.5f
    const val PRESS_STIFFNESS = 380f

    // stroke.rotation.spinPeriodRangeS
    const val SPIN_MIN_MS = 9_000
    const val SPIN_MAX_MS = 18_000

    // ink.onDark — the app rides on a dark backdrop
    val InkOnDark = Color(244, 246, 250)
    val InkOnLight = Color(24, 27, 34)
}

enum class VantaVariant { Panel, Button, Input, Container, Bare }

private val VantaVariant.radius: Dp
    get() = when (this) {
        VantaVariant.Panel -> VantaTokens.radiusPanel
        VantaVariant.Button -> VantaTokens.radiusButton
        VantaVariant.Input -> VantaTokens.radiusInput
        VantaVariant.Container -> VantaTokens.radiusContainer
        VantaVariant.Bare -> VantaTokens.radiusPanel
    }

private val VantaVariant.strokeWidth: Dp
    get() = when (this) {
        VantaVariant.Panel -> VantaTokens.strokePanel
        VantaVariant.Button -> VantaTokens.strokeButton
        VantaVariant.Input -> VantaTokens.strokeInput
        VantaVariant.Container -> VantaTokens.strokeContainer
        VantaVariant.Bare -> VantaTokens.strokePanel
    }

private val VantaVariant.glowBlur: Dp
    get() = when (this) {
        VantaVariant.Panel -> VantaTokens.glowBlurPanel
        VantaVariant.Button -> VantaTokens.glowBlurButton
        VantaVariant.Input -> VantaTokens.glowBlurInput
        VantaVariant.Container -> VantaTokens.glowBlurContainer
        VantaVariant.Bare -> VantaTokens.glowBlurPanel
    }

// --------------------------------------------------------------- palette

/**
 * Stroke identity engine.
 *
 * Sibling surfaces must never share a gradient, so hues are walked by the
 * golden angle: any number of neighbours stay maximally separated. Every stop
 * stays saturated — the stroke has to read as a neon edge, not as paint.
 */
object VantaPalette {

    private const val GOLDEN_ANGLE = 137.508f

    /** FNV-1a, so a given seed yields the same identity every run. */
    fun hash(input: String): Int {
        var h = -0x7ee3623b // 0x811c9dc5
        for (c in input) {
            h = h xor c.code
            h *= 0x01000193
        }
        return h
    }

    fun mulberry32(seed: Int): () -> Float {
        var a = seed
        return {
            a += 0x6d2b79f5
            var t = (a xor (a ushr 15)) * (1 or a)
            t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
            ((t xor (t ushr 14)).toUInt().toFloat() / 4294967296f)
        }
    }

    /** Three saturated stops for surface [index] within a group. */
    fun strokeFor(seed: String, index: Int): List<Color> {
        val rnd = mulberry32(hash(seed))
        val offset = rnd() * 360f
        val base = (offset + index * GOLDEN_ANGLE) % 360f

        // palette.harmonies weights: analogous .5, bridge .3, coolToHot .2
        val pick = rnd()
        return when {
            pick < 0.5f -> {
                val s = 88f + rnd() * 12f
                val l = 56f + rnd() * 14f
                listOf(base, base + 22f, base + 44f).map { hsl(it, s, l) }
            }
            pick < 0.8f -> {
                val separation = 120f + rnd() * 55f
                val s = 78f + rnd() * 22f
                val l = 58f + rnd() * 16f
                listOf(base, base + separation / 2f, base + separation).map { hsl(it, s, l) }
            }
            else -> {
                val s = 86f + rnd() * 14f
                val l = 54f + rnd() * 14f
                listOf(base, base + 45f, base + 95f).map { hsl(it, s, l) }
            }
        }
    }

    fun hsl(hDeg: Float, sPct: Float, lPct: Float): Color {
        val h = ((hDeg % 360f) + 360f) % 360f / 360f
        val s = (sPct / 100f).coerceIn(0f, 1f)
        val l = (lPct / 100f).coerceIn(0f, 1f)
        if (s == 0f) return Color(l, l, l)

        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun channel(t0: Float): Float {
            var t = t0
            if (t < 0) t += 1f
            if (t > 1) t -= 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 1f / 2f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }
        return Color(channel(h + 1f / 3f), channel(h), channel(h - 1f / 3f))
    }
}

/** Samples a cyclic colour ramp, so the gradient can be rotated by offsetting t. */
private fun colorAtCyclic(colors: List<Color>, t: Float): Color {
    val n = colors.size
    val x = (((t % 1f) + 1f) % 1f) * n
    val i = x.toInt() % n
    return lerp(colors[i], colors[(i + 1) % n], x - floor(x))
}

/**
 * A sweep gradient rotated by [angleDeg].
 *
 * The canvas cannot simply be rotated: that would turn the rounded rectangle
 * itself. Instead the colour stops are resampled around the cycle, which spins
 * the gradient while the geometry stays put.
 */
private fun rotatedSweep(colors: List<Color>, angleDeg: Float, steps: Int = 16): Brush {
    val shift = angleDeg / 360f
    val stops = Array(steps + 1) { k ->
        val p = k.toFloat() / steps
        p to colorAtCyclic(colors, p + shift)
    }
    return Brush.sweepGradient(*stops)
}

// --------------------------------------------------------------- surface

@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) == 0f
        }.getOrDefault(false)
    }
}

/**
 * The one surface treatment: colourless frost, neon gradient stroke, outward
 * edge-lit glow.
 *
 * [seed] and [index] decide the stroke identity — pass a stable seed and the
 * child's position so neighbours never collide.
 */
@Composable
fun VantaSurface(
    modifier: Modifier = Modifier,
    variant: VantaVariant = VantaVariant.Panel,
    seed: String = "vanta",
    index: Int = 0,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    active: Boolean = false,
    still: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = remember(seed, index) { VantaPalette.strokeFor(seed, index) }
    val reducedMotion = rememberReducedMotion()

    // Spin period and direction are part of the surface's identity, so two
    // neighbours never drift in lockstep.
    val identity = remember(seed, index) {
        val rnd = VantaPalette.mulberry32(VantaPalette.hash("$seed#$index"))
        val period = VantaTokens.SPIN_MIN_MS +
            (rnd() * (VantaTokens.SPIN_MAX_MS - VantaTokens.SPIN_MIN_MS)).toInt()
        val start = rnd() * 360f
        val clockwise = rnd() < 0.5f
        Triple(period, start, clockwise)
    }
    val (periodMs, startAngle, clockwise) = identity

    val angle = if (reducedMotion || still) {
        startAngle
    } else {
        val transition = rememberInfiniteTransition(label = "vanta-stroke")
        val spin by transition.animateFloat(
            initialValue = 0f,
            targetValue = if (clockwise) 360f else -360f,
            animationSpec = infiniteRepeatable(
                animation = tween(periodMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "angle"
        )
        startAngle + spin
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) VantaTokens.PRESS_SCALE else 1f,
        animationSpec = if (reducedMotion) {
            tween(90, easing = LinearEasing)
        } else {
            spring(
                dampingRatio = VantaTokens.PRESS_DAMPING,
                stiffness = VantaTokens.PRESS_STIFFNESS
            )
        },
        label = "gel"
    )

    val glowAlpha by animateFloatAsState(
        targetValue = when {
            !enabled -> 0.25f
            active || pressed -> VantaTokens.GLOW_ACTIVE
            variant == VantaVariant.Input -> VantaTokens.GLOW_INPUT_RESTING
            variant == VantaVariant.Container -> VantaTokens.GLOW_CONTAINER
            else -> VantaTokens.GLOW_REST
        },
        animationSpec = tween(220),
        label = "glow"
    )

    val shape = RoundedCornerShape(variant.radius)
    val strokeW = variant.strokeWidth
    val glowBlur = variant.glowBlur
    // Room for the glow to bleed outward without being clipped.
    val bleed = glowBlur + VantaTokens.glowRingFat

    Box(
        modifier
            .graphicsLayer {
                scaleX = press
                scaleY = press
            }
            .padding(bleed / 2)
    ) {
        // Glow ring — behind the pane, blurred, never a filled rectangle.
        if (glowAlpha > 0.01f) {
            Box(
                Modifier
                    .matchParentSize()
                    .blur(glowBlur, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                    .drawBehind {
                        val w = (strokeW.toPx() + VantaTokens.glowRingFat.toPx())
                        drawRoundRect(
                            brush = rotatedSweep(colors, angle),
                            topLeft = Offset(w / 2f, w / 2f),
                            size = Size(size.width - w, size.height - w),
                            cornerRadius = CornerRadius(variant.radius.toPx()),
                            style = Stroke(width = w),
                            alpha = glowAlpha
                        )
                    }
            )
        }

        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                // Colourless frost. No hue, ever.
                .background(Color.White.copy(alpha = VantaTokens.FROST_ALPHA))
                .drawWithContent {
                    drawContent()
                    // Bevel: a hairline rim plus a brighter top specular edge.
                    val inset = 1f
                    drawRoundRect(
                        color = Color.White.copy(alpha = VantaTokens.BEVEL_RIM),
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - inset * 2, size.height - inset * 2),
                        cornerRadius = CornerRadius(variant.radius.toPx()),
                        style = Stroke(width = 1f)
                    )
                    drawLine(
                        color = Color.White.copy(alpha = VantaTokens.BEVEL_TOP * 0.35f),
                        start = Offset(variant.radius.toPx(), 1f),
                        end = Offset(size.width - variant.radius.toPx(), 1f),
                        strokeWidth = 1f
                    )
                }
        )

        // Stroke — the only place colour is allowed to live.
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val w = strokeW.toPx()
                    drawRoundRect(
                        brush = rotatedSweep(colors, angle),
                        topLeft = Offset(w / 2f, w / 2f),
                        size = Size(size.width - w, size.height - w),
                        cornerRadius = CornerRadius(variant.radius.toPx()),
                        style = Stroke(width = w),
                        alpha = if (enabled) 1f else 0.35f
                    )
                }
        )

        Column(
            Modifier
                .then(
                    if (onClick != null && enabled) {
                        Modifier.clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = onClick
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(contentPadding)
        ) {
            CompositionLocalProvider(
                LocalContentColor provides VantaTokens.InkOnDark.copy(
                    alpha = if (enabled) 1f else 0.45f
                )
            ) {
                content()
            }
        }
    }
}

/**
 * The backdrop the glass sits on.
 *
 * Frosted glass needs something behind it or it reads as faint frost on
 * nothing. This stays deliberately colourless — near-black with a soft neutral
 * falloff — so the neon strokes are the only chroma on screen.
 */
fun Modifier.vantaBackdrop(): Modifier = this.drawBehind {
    drawRect(
        Brush.linearGradient(
            0f to Color(0xFF0B0C10),
            0.55f to Color(0xFF141519),
            1f to Color(0xFF090A0D),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height)
        )
    )
    // A faint off-centre lift keeps large empty areas from looking flat.
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.045f), Color.Transparent),
            center = Offset(size.width * 0.22f, size.height * 0.12f),
            radius = size.maxDimension * 0.6f
        ),
        radius = size.maxDimension * 0.6f,
        center = Offset(size.width * 0.22f, size.height * 0.12f)
    )
}

/** True when the surface should carry light ink (our backdrop always is). */
val vantaInk: Color get() = VantaTokens.InkOnDark
