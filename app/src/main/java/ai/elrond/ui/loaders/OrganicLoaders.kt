package ai.elrond.ui.loaders

import ai.elrond.domain.AiColorMode
import ai.elrond.domain.AiLoaderStyle
import ai.elrond.ui.theme.LeapBlue
import ai.elrond.ui.theme.LeapGreen
import ai.elrond.ui.theme.LeapGrey
import ai.elrond.ui.theme.LeapNavy
import ai.elrond.ui.theme.LeapPink
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp

/**
 * The "organic loaders" from the `organic-loaders` Claude Design handoff (FA-17), rebuilt as Compose
 * animations. The handoff's loaders are CSS metaball ("gooey") animations: blurred circles passed
 * through an alpha-threshold so overlapping blobs visually merge. We recreate that with a
 * [android.graphics.RenderEffect] (blur → alpha-threshold colour matrix) on Android 12+; below that
 * the [goo] modifier is a no-op and the circles simply overlap (a graceful degrade — `minSdk = 29`).
 *
 * Seven loaders are offered ([AiLoaderStyle]); colours follow [AiColorMode] (the Leap palette for
 * COLOR — the design's `c` variants — or a single ink for BLACK). The fidelity of the goo merge is
 * device-tuned (it can't be unit-tested — `RenderEffect` needs a hardware canvas), so these are
 * verified on-device like the other Compose canvas visuals.
 */
@Composable
fun OrganicLoader(
    style: AiLoaderStyle,
    colorMode: AiColorMode,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when (style) {
            AiLoaderStyle.ORBIT -> OrbitLoader(size, colorMode)
            AiLoaderStyle.SPLIT -> SplitLoader(size, colorMode)
            AiLoaderStyle.COMET -> CometLoader(size, colorMode)
            AiLoaderStyle.LAVA -> LavaLoader(size, colorMode)
            AiLoaderStyle.PINCH -> PinchLoader(size, colorMode)
            AiLoaderStyle.RINGS -> RingsLoader(size, colorMode)
            AiLoaderStyle.CLUSTER -> ClusterLoader(size, colorMode)
        }
    }
}

// ---- the Leap palette used by the `c` (colour) variants, plus the BLACK ink ----
private val Ink = Color(0xFF111111)
private val Orange = Color(0xFFE8A13C) // 14m colour-cycle only

private fun tone(mode: AiColorMode, c: Color): Color = if (mode == AiColorMode.COLOR) c else Ink

// ---- shared animation helpers ----

/** Oscillates 0→1→0 (CSS ease-in-out reverse). [delayMs] fast-forwards to stagger (negative delay). */
@Composable
private fun InfiniteTransition.oscState(
    durMs: Int,
    delayMs: Int = 0,
    easing: Easing = FastOutSlowInEasing,
): State<Float> = animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
        animation = tween(durMs, easing = easing),
        repeatMode = RepeatMode.Reverse,
        initialStartOffset = StartOffset(delayMs, StartOffsetType.FastForward),
    ),
    label = "osc",
)

/** Continuous rotation 0→360° (or reversed) for orbiting/tumbling loaders. */
@Composable
private fun InfiniteTransition.spinState(
    durMs: Int,
    reverse: Boolean = false,
    delayMs: Int = 0,
    easing: Easing = LinearEasing,
): State<Float> = animateFloat(
    initialValue = 0f,
    targetValue = if (reverse) -360f else 360f,
    animationSpec = infiniteRepeatable(
        animation = tween(durMs, easing = easing),
        repeatMode = RepeatMode.Restart,
        initialStartOffset = StartOffset(delayMs, StartOffsetType.FastForward),
    ),
    label = "spin",
)

// ---- the gooey metaball layer ----

/** Applies the blur + alpha-threshold metaball effect to a layer's children (Android 12+). */
@Composable
private fun Modifier.goo(blur: Dp): Modifier {
    val effect = rememberGoo(blur)
    return this.graphicsLayer {
        renderEffect = effect
        clip = false
    }
}

@Composable
private fun rememberGoo(blur: Dp): RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val density = LocalDensity.current
    return remember(blur, density) {
        buildGoo(with(density) { blur.toPx() }.coerceAtLeast(0.1f))
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun buildGoo(radiusPx: Float): RenderEffect {
    // Alpha-only threshold: A' = 22·A − 10 (the handoff's feColorMatrix, scaled to 0..255). Blurred
    // edges below the threshold drop out and overlapping blobs fuse — RGB is untouched so the Leap
    // colours survive.
    val matrix = ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 22f, -2550f,
        ),
    )
    return AndroidRenderEffect.createChainEffect(
        AndroidRenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix)),
        AndroidRenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.DECAL),
    ).asComposeRenderEffect()
}

// ---- the seven loaders (geometry ported from the handoff CSS, expressed as fractions of size) ----

/** 17 · goo-cluster — 5 satellites pump in/out around a core (the default, 17c). */
@Composable
private fun ClusterLoader(sizeDp: Dp, mode: AiColorMode) {
    val t = rememberInfiniteTransition(label = "cluster")
    val px = with(LocalDensity.current) { sizeDp.toPx() }
    val dotD = sizeDp * 0.186f
    val coreD = sizeDp * 0.214f
    val sat = listOf(LeapBlue, LeapGreen, LeapPink, LeapBlue, LeapPink)
    Box(Modifier.size(sizeDp).goo(sizeDp * 0.07f)) {
        Box(Modifier.align(Alignment.Center).size(coreD).clip(CircleShape).background(tone(mode, LeapNavy)))
        for (i in 0 until 5) {
            val out = t.oscState(1600, delayMs = i * 320)
            Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = i * 72f }) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .size(dotD)
                        .graphicsLayer {
                            translationY = px * 0.057f + (1f - out.value) * px * 0.243f
                            val sc = lerp(0.6f, 1f, out.value)
                            scaleX = sc
                            scaleY = sc
                        }
                        .clip(CircleShape)
                        .background(tone(mode, sat[i])),
                )
            }
        }
    }
}

/** 2 · metaball-orbit — 3 rings sweep satellites out and back around a core. */
@Composable
private fun OrbitLoader(sizeDp: Dp, mode: AiColorMode) {
    val t = rememberInfiniteTransition(label = "orbit")
    val px = with(LocalDensity.current) { sizeDp.toPx() }
    val dotD = sizeDp * 0.227f
    val coreD = sizeDp * 0.267f
    val ring = listOf(LeapBlue, LeapPink, LeapGreen)
    val spins = listOf(t.spinState(2400), t.spinState(3400, reverse = true), t.spinState(2900))
    Box(Modifier.size(sizeDp).goo(sizeDp * 0.07f)) {
        Box(Modifier.align(Alignment.Center).size(coreD).clip(CircleShape).background(tone(mode, LeapNavy)))
        for (i in 0 until 3) {
            val out = t.oscState(1800)
            val spin = spins[i]
            Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = spin.value }) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(dotD)
                        .graphicsLayer {
                            translationY = -px * 0.307f * out.value
                            val sc = lerp(0.7f, 1f, out.value)
                            scaleX = sc
                            scaleY = sc
                        }
                        .clip(CircleShape)
                        .background(tone(mode, ring[i])),
                )
            }
        }
    }
}

/** 7 · goo-comet — 3 trailing arms orbit a core, fused into a comet by the goo. */
@Composable
private fun CometLoader(sizeDp: Dp, mode: AiColorMode) {
    val t = rememberInfiniteTransition(label = "comet")
    val px = with(LocalDensity.current) { sizeDp.toPx() }
    val dotD = sizeDp * 0.214f
    val coreD = sizeDp * 0.186f
    val arm = listOf(LeapBlue, LeapNavy, LeapPink)
    val alphas = listOf(1f, 0.9f, 0.8f)
    val ease = CubicBezierEasing(0.6f, 0f, 0.4f, 1f)
    Box(Modifier.size(sizeDp).goo(sizeDp * 0.07f)) {
        Box(Modifier.align(Alignment.Center).size(coreD).clip(CircleShape).background(tone(mode, LeapGrey)))
        for (i in 0 until 3) {
            val spin = t.spinState(1400, delayMs = i * 120, easing = ease)
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = spin.value
                        alpha = alphas[i]
                    },
            ) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .size(dotD)
                        .graphicsLayer { translationY = px * 0.057f }
                        .clip(CircleShape)
                        .background(tone(mode, arm[i])),
                )
            }
        }
    }
}

/** 11 · lava-rise — two blobs rise from a base puddle and fall back. */
@Composable
private fun LavaLoader(sizeDp: Dp, mode: AiColorMode) {
    val t = rememberInfiniteTransition(label = "lava")
    val px = with(LocalDensity.current) { sizeDp.toPx() }
    Box(Modifier.size(sizeDp).goo(sizeDp * 0.08f)) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .size(width = sizeDp * 0.43f, height = sizeDp * 0.2f)
                .clip(RoundedCornerShape(50))
                .background(tone(mode, LeapNavy)),
        )
        val r1 = t.oscState(3000)
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .size(sizeDp * 0.28f)
                .graphicsLayer { translationY = -lerp(0.04f, 0.667f, r1.value) * px }
                .clip(CircleShape)
                .background(tone(mode, LeapPink)),
        )
        val r2 = t.oscState(3000, delayMs = 1500)
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .size(sizeDp * 0.2f)
                .graphicsLayer {
                    translationX = px * 0.07f
                    translationY = -lerp(0.04f, 0.667f, r2.value) * px
                }
                .clip(CircleShape)
                .background(tone(mode, LeapBlue)),
        )
    }
}

/** 14 · goo-pinch — two blobs pinch into a peanut and merge, the whole thing rotating (14m cycles). */
@Composable
private fun PinchLoader(sizeDp: Dp, mode: AiColorMode) {
    val t = rememberInfiniteTransition(label = "pinch")
    val px = with(LocalDensity.current) { sizeDp.toPx() }
    val dotD = sizeDp * 0.373f
    val spin = t.spinState(3400)
    // 14m: one blob cycles the Leap palette on each merge; the other stays cyan. BLACK → ink.
    val cyc = t.animateFloat(
        0f, 5f,
        infiniteRepeatable(tween(7500, easing = LinearEasing), RepeatMode.Restart),
        label = "cycle",
    )
    val palette = listOf(LeapGreen, Orange, LeapNavy, LeapPink, LeapGrey)
    val colorA = if (mode == AiColorMode.COLOR) palette[cyc.value.toInt().coerceIn(0, 4)] else Ink
    val colorB = tone(mode, LeapBlue)
    Box(Modifier.size(sizeDp).graphicsLayer { rotationZ = spin.value }.goo(sizeDp * 0.08f)) {
        val p = t.oscState(1500)
        Box(
            Modifier
                .align(Alignment.Center)
                .size(dotD)
                .graphicsLayer {
                    translationX = -0.24f * px * p.value
                    val sc = lerp(1f, 0.82f, p.value)
                    scaleX = sc
                    scaleY = sc
                }
                .clip(CircleShape)
                .background(colorA),
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .size(dotD)
                .graphicsLayer {
                    translationX = 0.24f * px * p.value
                    val sc = lerp(1f, 0.82f, p.value)
                    scaleX = sc
                    scaleY = sc
                }
                .clip(CircleShape)
                .background(colorB),
        )
    }
}

/** 15 · pulse-rings — 3 blobby outline rings expand, rotate and fade (no goo — they're strokes). */
@Composable
private fun RingsLoader(sizeDp: Dp, mode: AiColorMode) {
    val t = rememberInfiniteTransition(label = "rings")
    val ring = listOf(LeapBlue, LeapPink, LeapGreen)
    val shape = RoundedCornerShape(48, 52, 56, 44)
    Box(Modifier.size(sizeDp), contentAlignment = Alignment.Center) {
        for (i in 0 until 3) {
            val p = t.animateFloat(
                0f, 1f,
                infiniteRepeatable(
                    tween(2400, easing = LinearEasing),
                    RepeatMode.Restart,
                    initialStartOffset = StartOffset(i * 800, StartOffsetType.FastForward),
                ),
                label = "ring",
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val sc = lerp(0.2f, 2.4f, p.value)
                        scaleX = sc
                        scaleY = sc
                        rotationZ = lerp(0f, 140f, p.value)
                        alpha = lerp(1f, 0f, p.value)
                    }
                    .border(sizeDp * 0.1f, tone(mode, ring[i]), shape),
            )
        }
    }
}

/** 5 · splitting-blob — two blobs part and rejoin horizontally. */
@Composable
private fun SplitLoader(sizeDp: Dp, mode: AiColorMode) {
    val t = rememberInfiniteTransition(label = "split")
    val px = with(LocalDensity.current) { sizeDp.toPx() }
    val dotD = sizeDp * 0.31f
    Box(Modifier.size(sizeDp).goo(sizeDp * 0.08f)) {
        val p = t.oscState(2000)
        Box(
            Modifier
                .align(Alignment.Center)
                .size(dotD)
                .graphicsLayer {
                    translationX = -0.26f * px * p.value
                    val sc = lerp(0.9f, 1.05f, p.value)
                    scaleX = sc
                    scaleY = sc
                }
                .clip(CircleShape)
                .background(tone(mode, LeapPink)),
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .size(dotD)
                .graphicsLayer {
                    translationX = 0.26f * px * p.value
                    val sc = lerp(0.9f, 1.05f, p.value)
                    scaleX = sc
                    scaleY = sc
                }
                .clip(CircleShape)
                .background(tone(mode, LeapBlue)),
        )
    }
}
