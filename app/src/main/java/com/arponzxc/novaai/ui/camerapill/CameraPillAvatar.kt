package com.arponzxc.novaai.ui.camerapill

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lightweight, hardware-accelerated 3D-styled futuristic female AI assistant avatar
 * rendered purely with Jetpack Compose Canvas.
 *
 * Supports idle breathing, periodic natural eye blinking, listening focus,
 * thinking neural orbit, lip-synced speaking expression, happy response smile,
 * interruption recovery, error tint, and offline dim state.
 */
@Composable
fun CameraPillAvatar(
    aiState: CameraPillAiState,
    amplitude: Float,
    isHappyResponse: Boolean,
    animationsEnabled: Boolean,
    intensity: Float,
    baseSize: Dp = 32.dp,
    modifier: Modifier = Modifier
) {
    val clampedIntensity = intensity.coerceIn(0.4f, 1.5f)
    val effectiveMotion = if (animationsEnabled) clampedIntensity else 0f

    // Periodic natural eye blinking
    var isBlinking by remember { mutableStateOf(false) }
    LaunchedEffect(animationsEnabled, aiState) {
        if (!animationsEnabled) {
            isBlinking = false
            return@LaunchedEffect
        }
        while (true) {
            delay(3300L)
            if (aiState != CameraPillAiState.OFFLINE) {
                isBlinking = true
                delay(130L)
                isBlinking = false
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "nova_avatar_loop")
    val breathePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "breathe"
    )
    val orbitAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "thinking_orbit"
    )

    val mouthOpenTarget = when {
        aiState == CameraPillAiState.SPEAKING -> (0.25f + amplitude * 0.75f).coerceIn(0.18f, 1f)
        aiState == CameraPillAiState.INTERRUPTED -> 0.22f
        else -> 0f
    }
    val mouthOpen by animateFloatAsState(
        targetValue = mouthOpenTarget,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.7f),
        label = "mouth_open"
    )

    val eyeOpenScale by animateFloatAsState(
        targetValue = if (isBlinking || aiState == CameraPillAiState.OFFLINE) 0.12f else 1f,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "blink"
    )

    val haloAccent by animateColorAsState(
        targetValue = when {
            isHappyResponse -> Color(0xFF34D399)
            aiState == CameraPillAiState.ERROR -> Color(0xFFF87171)
            aiState == CameraPillAiState.OFFLINE -> Color(0xFF64748B)
            aiState == CameraPillAiState.INTERRUPTED -> Color(0xFFFBBF24)
            aiState == CameraPillAiState.THINKING -> Color(0xFF818CF8)
            aiState == CameraPillAiState.SPEAKING -> Color(0xFF38BDF8)
            aiState == CameraPillAiState.LISTENING -> Color(0xFF22D3EE)
            else -> Color(0xFF0EA5E9)
        },
        animationSpec = tween(durationMillis = 260),
        label = "halo_accent"
    )

    Canvas(modifier = modifier.size(baseSize)) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.52f + sin(breathePhase) * (h * 0.025f * effectiveMotion)
        val r = minOf(w, h) * 0.36f

        // 1. Holographic ambient glow aura
        val auraBoost = when (aiState) {
            CameraPillAiState.SPEAKING -> 1.15f + amplitude * 0.25f * effectiveMotion
            CameraPillAiState.LISTENING -> 1.08f + amplitude * 0.2f * effectiveMotion
            CameraPillAiState.THINKING -> 1.12f
            else -> 1.0f
        }
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    haloAccent.copy(alpha = 0.38f),
                    haloAccent.copy(alpha = 0.12f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = r * 1.48f * auraBoost
            ),
            radius = r * 1.48f * auraBoost,
            center = Offset(cx, cy)
        )

        // 2. Thinking orbiting neural ring
        if (aiState == CameraPillAiState.THINKING && animationsEnabled) {
            rotate(degrees = orbitAngle, pivot = Offset(cx, cy)) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(
                            Color.Transparent,
                            Color(0xFF22D3EE),
                            Color(0xFFA78BFA),
                            Color.Transparent
                        )
                    ),
                    startAngle = 0f,
                    sweepAngle = 240f,
                    useCenter = false,
                    topLeft = Offset(cx - r * 1.28f, cy - r * 1.28f),
                    size = Size(r * 2.56f, r * 2.56f),
                    style = Stroke(width = w * 0.045f, cap = StrokeCap.Round)
                )
            }
        }

        // 3. Futuristic sleek bob hairstyle (back layer + luminous rim)
        val hairPath = Path().apply {
            moveTo(cx - r * 1.02f, cy + r * 0.22f)
            cubicTo(
                cx - r * 1.18f, cy - r * 0.55f,
                cx - r * 0.72f, cy - r * 1.22f,
                cx, cy - r * 1.18f
            )
            cubicTo(
                cx + r * 0.72f, cy - r * 1.22f,
                cx + r * 1.18f, cy - r * 0.55f,
                cx + r * 1.02f, cy + r * 0.22f
            )
            cubicTo(
                cx + r * 0.92f, cy + r * 0.65f,
                cx + r * 0.62f, cy + r * 0.85f,
                cx + r * 0.45f, cy + r * 0.86f
            )
            lineTo(cx - r * 0.45f, cy + r * 0.86f)
            cubicTo(
                cx - r * 0.62f, cy + r * 0.85f,
                cx - r * 0.92f, cy + r * 0.65f,
                cx - r * 1.02f, cy + r * 0.22f
            )
            close()
        }
        drawPath(
            path = hairPath,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF172554), Color(0xFF312E81), Color(0xFF0E7490)),
                start = Offset(cx - r, cy - r),
                end = Offset(cx + r, cy + r)
            )
        )

        // 4. Twin glowing cyber headset fins
        val finColor = haloAccent.copy(alpha = 0.92f)
        drawRoundRect(
            color = finColor,
            topLeft = Offset(cx - r * 1.15f, cy - r * 0.22f),
            size = Size(r * 0.22f, r * 0.48f),
            cornerRadius = CornerRadius(r * 0.1f, r * 0.1f)
        )
        drawRoundRect(
            color = finColor,
            topLeft = Offset(cx + r * 0.93f, cy - r * 0.22f),
            size = Size(r * 0.22f, r * 0.48f),
            cornerRadius = CornerRadius(r * 0.1f, r * 0.1f)
        )

        // 5. 3D-shaded smooth face sphere
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFFFEFE6),
                    Color(0xFFFCD9C8),
                    Color(0xFFE6B4A2)
                ),
                center = Offset(cx - r * 0.18f, cy - r * 0.22f),
                radius = r * 1.05f
            ),
            radius = r * 0.78f,
            center = Offset(cx, cy + r * 0.04f)
        )

        // 6. Soft bangs framing forehead
        val bangsPath = Path().apply {
            moveTo(cx - r * 0.82f, cy - r * 0.22f)
            cubicTo(
                cx - r * 0.55f, cy - r * 0.82f,
                cx - r * 0.15f, cy - r * 0.92f,
                cx + r * 0.08f, cy - r * 0.58f
            )
            cubicTo(
                cx + r * 0.28f, cy - r * 0.92f,
                cx + r * 0.68f, cy - r * 0.78f,
                cx + r * 0.82f, cy - r * 0.22f
            )
            cubicTo(
                cx + r * 0.62f, cy - r * 1.02f,
                cx - r * 0.62f, cy - r * 1.02f,
                cx - r * 0.82f, cy - r * 0.22f
            )
            close()
        }
        drawPath(
            path = bangsPath,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF1E3A8A), Color(0xFF06B6D4))
            )
        )

        // 7. Cheek blush (stronger when happy or speaking)
        val blushAlpha = if (isHappyResponse) 0.42f else if (aiState == CameraPillAiState.SPEAKING) 0.28f else 0.16f
        drawCircle(
            color = Color(0xFFFB7185).copy(alpha = blushAlpha),
            radius = r * 0.16f,
            center = Offset(cx - r * 0.40f, cy + r * 0.22f)
        )
        drawCircle(
            color = Color(0xFFFB7185).copy(alpha = blushAlpha),
            radius = r * 0.16f,
            center = Offset(cx + r * 0.40f, cy + r * 0.22f)
        )

        // 8. Expressive Eyes (blink, happy crescent, thinking upward gaze, attentive listening)
        val eyeY = cy + r * 0.02f
        val leftEyeX = cx - r * 0.29f
        val rightEyeX = cx + r * 0.29f
        val gazeShiftY = if (aiState == CameraPillAiState.THINKING) -r * 0.04f else 0f
        val gazeShiftX = if (aiState == CameraPillAiState.THINKING && animationsEnabled) {
            cos(breathePhase) * r * 0.04f * effectiveMotion
        } else 0f

        if (isHappyResponse && !isBlinking) {
            // Cheerful happy smiling eye arcs
            val happyLeft = Path().apply {
                moveTo(leftEyeX - r * 0.14f, eyeY + r * 0.03f)
                quadraticTo(leftEyeX, eyeY - r * 0.12f, leftEyeX + r * 0.14f, eyeY + r * 0.03f)
            }
            val happyRight = Path().apply {
                moveTo(rightEyeX - r * 0.14f, eyeY + r * 0.03f)
                quadraticTo(rightEyeX, eyeY - r * 0.12f, rightEyeX + r * 0.14f, eyeY + r * 0.03f)
            }
            drawPath(happyLeft, Color(0xFF0F172A), style = Stroke(width = w * 0.045f, cap = StrokeCap.Round))
            drawPath(happyRight, Color(0xFF0F172A), style = Stroke(width = w * 0.045f, cap = StrokeCap.Round))
        } else {
            val eyeH = (r * 0.28f * eyeOpenScale).coerceAtLeast(w * 0.035f)
            val eyeW = r * 0.23f
            listOf(leftEyeX, rightEyeX).forEach { ex ->
                // Dark iris oval
                drawOval(
                    color = Color(0xFF0F172A),
                    topLeft = Offset(ex - eyeW / 2f, eyeY - eyeH / 2f),
                    size = Size(eyeW, eyeH)
                )
                if (eyeOpenScale > 0.35f) {
                    // Glowing cyan iris accent + glossy specular glint
                    drawCircle(
                        color = haloAccent,
                        radius = r * 0.068f,
                        center = Offset(ex + gazeShiftX, eyeY + gazeShiftY + r * 0.015f)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = r * 0.036f,
                        center = Offset(ex - r * 0.045f + gazeShiftX, eyeY - r * 0.045f + gazeShiftY)
                    )
                }
            }
        }

        // 9. Expressive Mouth (animated speaking lip-sync, friendly smile, or neutral)
        val mouthY = cy + r * 0.37f
        when {
            aiState == CameraPillAiState.ERROR -> {
                val sadPath = Path().apply {
                    moveTo(cx - r * 0.16f, mouthY + r * 0.05f)
                    quadraticTo(cx, mouthY - r * 0.06f, cx + r * 0.16f, mouthY + r * 0.05f)
                }
                drawPath(sadPath, Color(0xFF9F1239), style = Stroke(width = w * 0.04f, cap = StrokeCap.Round))
            }
            mouthOpen > 0.12f -> {
                val mh = (r * 0.24f * mouthOpen).coerceAtLeast(w * 0.04f)
                val mw = r * 0.28f
                drawOval(
                    brush = Brush.verticalGradient(listOf(Color(0xFF9D174D), Color(0xFFF43F5E))),
                    topLeft = Offset(cx - mw / 2f, mouthY - mh / 2f),
                    size = Size(mw, mh)
                )
            }
            else -> {
                val smileCurve = if (isHappyResponse) r * 0.16f else if (aiState == CameraPillAiState.LISTENING) r * 0.11f else r * 0.08f
                val smilePath = Path().apply {
                    moveTo(cx - r * 0.18f, mouthY)
                    quadraticTo(cx, mouthY + smileCurve, cx + r * 0.18f, mouthY)
                }
                drawPath(
                    path = smilePath,
                    color = Color(0xFFBE185D),
                    style = Stroke(width = w * 0.042f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
    }
}
