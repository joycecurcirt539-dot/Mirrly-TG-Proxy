package com.mirrly.tgproxy.ui.theme

import android.app.Activity
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay

// Deep True Pure Black AMOLED Neutral Android Palette (0% Blue Tint)
val AmoledBackground = Color(0xFF000000)
val AmoledSurfaceLow = Color(0xFF08080A)
val AmoledSurface = Color(0xFF101012)
val AmoledSurfaceHigh = Color(0xFF18181C)
val AmoledBorder = Color(0xFF222226)

val ActiveGreenLed: Color
    @Composable
    get() = LocalProtocolColors.current.primary

val ActiveGreenGlow: Color
    @Composable
    get() = LocalProtocolColors.current.glow

val InactiveGrayLed = Color(0xFF383840)
val TextWhite = Color(0xFFFFFFFF)
val TextMuted = Color(0xFF888894)
val MinimalAccent = Color(0xFF00F5D4)

// MTProto Protocol Theme (Signature Neon Cyan / Emerald)
val MtprotoAccent = Color(0xFF00F5D4)
val MtprotoGlow = Color(0x3D00F5D4)
val MtprotoSecondary = Color(0xFF00B4D8)
val MtprotoLight = Color(0xFF00FF87)
val MtprotoEmerald = Color(0xFF00E676)

// SOCKS5 Protocol Theme (Neon Violet / Purple)
val Socks5Accent = Color(0xFFB388FF)
val Socks5Glow = Color(0x3DB388FF)
val Socks5Secondary = Color(0xFF7C4DFF)
val Socks5Light = Color(0xFFC084FC)
val Socks5Indigo = Color(0xFF818CF8)

data class ProtocolColors(
    val primary: Color,
    val glow: Color,
    val secondary: Color,
    val light: Color,
    val orb1: Color,
    val orb2: Color,
    val orb3: Color,
    val orb4: Color
)

val MtprotoPalette = ProtocolColors(
    primary = MtprotoAccent,
    glow = MtprotoGlow,
    secondary = MtprotoSecondary,
    light = MtprotoLight,
    orb1 = Color(0xFF00F5D4),
    orb2 = Color(0xFF00FF87),
    orb3 = Color(0xFF00E676),
    orb4 = Color(0xFF00B4D8)
)

val Socks5Palette = ProtocolColors(
    primary = Socks5Accent,
    glow = Socks5Glow,
    secondary = Socks5Secondary,
    light = Socks5Light,
    orb1 = Color(0xFFB388FF),
    orb2 = Color(0xFFC084FC),
    orb3 = Color(0xFF7C4DFF),
    orb4 = Color(0xFF818CF8)
)

@Composable
fun rememberAnimatedProtocolColors(isSocks5: Boolean): ProtocolColors {
    val target = if (isSocks5) Socks5Palette else MtprotoPalette
    val spec = tween<Color>(durationMillis = 750, easing = FastOutSlowInEasing)

    val primary by androidx.compose.animation.animateColorAsState(target.primary, spec, label = "protoPrimary")
    val glow by androidx.compose.animation.animateColorAsState(target.glow, spec, label = "protoGlow")
    val secondary by androidx.compose.animation.animateColorAsState(target.secondary, spec, label = "protoSecondary")
    val light by androidx.compose.animation.animateColorAsState(target.light, spec, label = "protoLight")
    val orb1 by androidx.compose.animation.animateColorAsState(target.orb1, spec, label = "protoOrb1")
    val orb2 by androidx.compose.animation.animateColorAsState(target.orb2, spec, label = "protoOrb2")
    val orb3 by androidx.compose.animation.animateColorAsState(target.orb3, spec, label = "protoOrb3")
    val orb4 by androidx.compose.animation.animateColorAsState(target.orb4, spec, label = "protoOrb4")

    return remember(primary, glow, secondary, light, orb1, orb2, orb3, orb4) {
        ProtocolColors(
            primary = primary,
            glow = glow,
            secondary = secondary,
            light = light,
            orb1 = orb1,
            orb2 = orb2,
            orb3 = orb3,
            orb4 = orb4
        )
    }
}

val LocalProtocolColors = androidx.compose.runtime.compositionLocalOf { MtprotoPalette }

private val AmoledDarkColorScheme = darkColorScheme(
    primary = TextWhite,
    secondary = TextMuted,
    tertiary = MtprotoAccent,
    background = AmoledBackground,
    surface = AmoledBackground,
    surfaceContainerLow = AmoledSurfaceLow,
    surfaceContainer = AmoledSurface,
    surfaceContainerHigh = AmoledSurfaceHigh,
    outline = AmoledBorder,
    outlineVariant = AmoledBorder,
    onPrimary = Color.Black,
    onBackground = TextWhite,
    onSurface = TextWhite,
    onSurfaceVariant = TextMuted
)

@Composable
fun MirrlyTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
        }
    }

    val isSocks5 by com.mirrly.tgproxy.MirrlyApplication.instance.prefsManager.isSocks5Flow.collectAsState(initial = false)
    val protoColors = rememberAnimatedProtocolColors(isSocks5 = isSocks5)

    androidx.compose.runtime.CompositionLocalProvider(LocalProtocolColors provides protoColors) {
        MaterialTheme(
            colorScheme = AmoledDarkColorScheme.copy(tertiary = protoColors.primary),
            content = content
        )
    }
}

private val TopFadeColors = listOf(Color.Transparent, Color.Black)
private val BottomFadeColors = listOf(Color.Black, Color.Transparent)

/**
 * Universal Smooth Fading Edges extension modifier.
 * Dissolves top & bottom content edges when scrolling instead of hard clipping.
 * Optimized with zero-allocation draw passes for butter-smooth 120 FPS performance.
 */
fun Modifier.fadingEdges(
    topFadeHeight: Dp = 32.dp,
    bottomFadeHeight: Dp = 32.dp
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = size.height
        val w = size.width
        if (h <= 0f || w <= 0f) return@drawWithContent

        val topPx = topFadeHeight.toPx().coerceAtMost(h / 2f)
        val bottomPx = bottomFadeHeight.toPx().coerceAtMost(h / 2f)

        if (topPx > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = TopFadeColors,
                    startY = 0f,
                    endY = topPx
                ),
                blendMode = BlendMode.DstIn
            )
        }
        if (bottomPx > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = BottomFadeColors,
                    startY = h - bottomPx,
                    endY = h
                ),
                blendMode = BlendMode.DstIn
            )
        }
    }

/**
 * Universal Staggered Entrance Animation Modifier.
 * Sequentially animates cards and menu items floating upwards with smooth deceleration (Fade + Slide).
 * Preserves entered state via rememberSaveable so it never re-animates on back navigation.
 */
@Composable
fun Modifier.staggeredEntrance(
    index: Int,
    baseDelayMs: Int = 45,
    durationMs: Int = 380
): Modifier {
    var visible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!visible) {
            if (index > 0) {
                delay((index * baseDelayMs).toLong())
            }
            visible = true
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = durationMs, easing = FastOutSlowInEasing),
        label = "staggeredAlpha_$index"
    )
    val offsetY by animateDpAsState(
        targetValue = if (visible) 0.dp else 24.dp,
        animationSpec = tween(durationMillis = durationMs, easing = FastOutSlowInEasing),
        label = "staggeredOffsetY_$index"
    )

    return this.graphicsLayer {
        this.alpha = alpha
        translationY = offsetY.toPx()
    }
}

/**
 * Universal Tactile 3D Spring Press Modifier.
 * Compresses scale by 3% on touch press, with physical spring bounce on release.
 * Reliably handles clicks without dropping events or interfering with parent scroll gestures.
 */
fun Modifier.springPress(
    onClick: (() -> Unit)? = null,
    pressScale: Float = 0.97f,
    interactionSource: MutableInteractionSource? = null
): Modifier = composed {
    val internalInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    var pointerPressed by remember { mutableStateOf(false) }
    val sourcePressed by internalInteractionSource.collectIsPressedAsState()
    val isPressed = pointerPressed || sourcePressed

    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressScale else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "springPressScale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = internalInteractionSource,
                    indication = null,
                    onClick = onClick
                )
            } else {
                Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull()
                            if (change != null) {
                                pointerPressed = change.pressed
                            }
                        }
                    }
                }
            }
        )
}

/**
 * Universal Running Light Sweep (Shimmer Beam) Modifier.
 * Periodically sweeps a subtle running light shimmer beam across active card borders.
 */
fun Modifier.lightSweep(
    isEnabled: Boolean = true,
    shape: Shape = RoundedCornerShape(20.dp),
    borderWidth: Dp = 1.dp,
    sweepColor: Color? = null
): Modifier = composed {
    if (!isEnabled) return@composed this

    val effectiveSweepColor = sweepColor ?: LocalProtocolColors.current.primary
    val infiniteTransition = rememberInfiniteTransition(label = "lightSweepTransition")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0.0f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "lightSweepProgress"
    )

    this.drawWithContent {
        drawContent()
        val strokeWidthPx = borderWidth.toPx()
        val beamWidthPx = size.width * 0.45f + 100.dp.toPx()
        val totalTravel = size.width + beamWidthPx * 2
        val currentCenterX = -beamWidthPx + totalTravel * progress

        val brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                effectiveSweepColor.copy(alpha = 0.85f),
                Color.Transparent
            ),
            start = Offset(currentCenterX - beamWidthPx * 0.5f, 0f),
            end = Offset(currentCenterX + beamWidthPx * 0.5f, size.height)
        )

        when (val outline = shape.createOutline(size, layoutDirection, this)) {
            is Outline.Rectangle -> {
                drawRect(brush = brush, style = Stroke(width = strokeWidthPx))
            }
            is Outline.Rounded -> {
                val rect = outline.roundRect
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                    cornerRadius = CornerRadius(rect.topLeftCornerRadius.x, rect.topLeftCornerRadius.y),
                    style = Stroke(width = strokeWidthPx)
                )
            }
            is Outline.Generic -> {
                drawPath(path = outline.path, brush = brush, style = Stroke(width = strokeWidthPx))
            }
        }
    }
}
