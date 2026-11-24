package com.arponzxc.novaai.ui.camerapill

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Dynamic Island inspired Camera Pill UI built with Jetpack Compose.
 * Features dark obsidian glassmorphism, glowing neon cyan/blue border,
 * futuristic AI avatar, real-time user/AI audio visualizer bars, minimized chip mode,
 * and an optional expanded quick-action bar.
 */
@Composable
fun CameraPillOverlayContent(
    uiState: CameraPillUiState,
    onPillTap: () -> Unit,
    onToggleMinimize: () -> Unit = {},
    onOpenApp: () -> Unit = {},
    onToggleMic: () -> Unit = {},
    onStopSpeaking: () -> Unit = {},
    onResetPosition: () -> Unit = {},
    isMicPaused: Boolean = false,
    modifier: Modifier = Modifier
) {
    val cfg = uiState.config
    val avatarSizeDp: Dp = (30f * cfg.avatarSizeScale).dp

    val borderColors = when (uiState.aiState) {
        CameraPillAiState.ERROR -> listOf(Color(0xFFEF4444), Color(0xFFF97316), Color(0xFFEF4444))
        CameraPillAiState.OFFLINE -> listOf(Color(0xFF475569), Color(0xFF64748B))
        CameraPillAiState.INTERRUPTED -> listOf(Color(0xFFFBBF24), Color(0xFF22D3EE))
        CameraPillAiState.THINKING -> listOf(Color(0xFF6366F1), Color(0xFF22D3EE), Color(0xFF3B82F6))
        CameraPillAiState.SPEAKING -> listOf(Color(0xFF38BDF8), Color(0xFF60A5FA), Color(0xFF818CF8))
        CameraPillAiState.LISTENING -> listOf(Color(0xFF22D3EE), Color(0xFF06B6D4), Color(0xFF3B82F6))
        CameraPillAiState.IDLE -> listOf(Color(0xFF0EA5E9).copy(alpha = 0.78f), Color(0xFF2563EB).copy(alpha = 0.78f))
    }

    val statusDotColor by animateColorAsState(
        targetValue = when (uiState.aiState) {
            CameraPillAiState.LISTENING -> Color(0xFF22D3EE)
            CameraPillAiState.SPEAKING -> Color(0xFF60A5FA)
            CameraPillAiState.THINKING -> Color(0xFFA78BFA)
            CameraPillAiState.INTERRUPTED -> Color(0xFFFBBF24)
            CameraPillAiState.ERROR -> Color(0xFFEF4444)
            CameraPillAiState.OFFLINE -> Color(0xFF94A3B8)
            CameraPillAiState.IDLE -> Color(0xFF10B981)
        },
        animationSpec = tween(250),
        label = "status_dot"
    )

    val effectiveAmp = when (uiState.aiState) {
        CameraPillAiState.SPEAKING -> uiState.aiAmplitude
        CameraPillAiState.LISTENING -> uiState.userAmplitude
        else -> 0f
    }

    AnimatedVisibility(
        visible = uiState.shouldShowOverlay,
        enter = fadeIn(tween(220)) + scaleIn(
            initialScale = 0.82f,
            animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium)
        ),
        exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.82f),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (uiState.isMinimized) {
                // Compact floating avatar badge
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .shadow(
                            elevation = 12.dp,
                            shape = CircleShape,
                            ambientColor = borderColors.first(),
                            spotColor = borderColors.last()
                        )
                        .clip(CircleShape)
                        .background(Color(0xEB050B16))
                        .border(1.4.dp, Brush.horizontalGradient(borderColors), CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onPillTap
                        )
                        .testTag("camera_pill_minimized_chip")
                        .semantics { contentDescription = "Restore NOVA AI floating avatar pill" }
                        .padding(6.dp)
                ) {
                    CameraPillAvatar(
                        aiState = uiState.aiState,
                        amplitude = effectiveAmp,
                        isHappyResponse = uiState.isHappyResponse,
                        animationsEnabled = cfg.avatarAnimationEnabled,
                        intensity = cfg.animationIntensity,
                        baseSize = avatarSizeDp
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusDotColor)
                            .border(1.dp, Color(0xFF050B16), CircleShape)
                    )
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .shadow(
                            elevation = 14.dp,
                            shape = RoundedCornerShape(50),
                            ambientColor = borderColors.first(),
                            spotColor = borderColors.last()
                        )
                        .clip(RoundedCornerShape(50))
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xEB050B16),
                                    Color(0xF00B1528),
                                    Color(0xEB071020)
                                )
                            )
                        )
                        .border(
                            width = 1.4.dp,
                            brush = Brush.horizontalGradient(borderColors),
                            shape = RoundedCornerShape(50)
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onPillTap
                        )
                        .animateContentSize(
                            animationSpec = spring(
                                dampingRatio = 0.78f,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )
                        .testTag("camera_pill_main_row")
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    // 1. Live 3D-styled AI Female Avatar
                    CameraPillAvatar(
                        aiState = uiState.aiState,
                        amplitude = effectiveAmp,
                        isHappyResponse = uiState.isHappyResponse,
                        animationsEnabled = cfg.avatarAnimationEnabled,
                        intensity = cfg.animationIntensity,
                        baseSize = avatarSizeDp
                    )

                    // 2. Center status / cutout spacer + compact label when expanded or active
                    Column(
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.widthIn(min = 52.dp, max = 144.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(statusDotColor)
                            )
                            Text(
                                text = if (uiState.isPreviewActive) "NOVA • ${uiState.aiState.badgeLabel}" else "NOVA",
                                color = Color(0xFFE0F2FE),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        AnimatedVisibility(
                            visible = uiState.isExpanded ||
                                uiState.aiState == CameraPillAiState.ERROR ||
                                uiState.aiState == CameraPillAiState.OFFLINE ||
                                uiState.isPreviewActive,
                            enter = fadeIn() + expandHorizontally(),
                            exit = fadeOut() + shrinkHorizontally()
                        ) {
                            Text(
                                text = uiState.statusText,
                                color = Color(0xFF93C5FD),
                                fontSize = 9.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // 3. Right Side: Voice Visualizer / Thinking Pulse / Error / Offline Indicator
                    when {
                        uiState.aiState == CameraPillAiState.ERROR -> {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = "Error",
                                tint = Color(0xFFF87171),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        uiState.aiState == CameraPillAiState.OFFLINE -> {
                            Icon(
                                imageVector = Icons.Rounded.CloudOff,
                                contentDescription = "Offline",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        uiState.aiState == CameraPillAiState.THINKING && cfg.showThinkingAnimation -> {
                            ThinkingNeuralDots(intensity = cfg.animationIntensity)
                        }
                        cfg.audioVisualizerEnabled &&
                            (uiState.aiState == CameraPillAiState.LISTENING ||
                                uiState.aiState == CameraPillAiState.SPEAKING ||
                                uiState.aiState == CameraPillAiState.INTERRUPTED) -> {
                            val isAiVoice = uiState.aiState == CameraPillAiState.SPEAKING
                            RealTimeVoiceVisualizerBars(
                                amplitude = if (isAiVoice) uiState.aiAmplitude else uiState.userAmplitude,
                                bass = uiState.bass,
                                mid = uiState.mid,
                                high = uiState.high,
                                isAiSpeaking = isAiVoice,
                                isInterrupted = uiState.aiState == CameraPillAiState.INTERRUPTED,
                                intensity = cfg.animationIntensity
                            )
                        }
                        else -> {
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                    }
                }

                // 4. Expanded Interactive Quick Controls Strip
                AnimatedVisibility(
                    visible = uiState.isExpanded,
                    enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.9f),
                    exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.9f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .shadow(10.dp, RoundedCornerShape(18.dp))
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xEB071020))
                            .border(1.dp, Color(0xFF1E3A5F), RoundedCornerShape(18.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                            .testTag("camera_pill_expanded_controls")
                    ) {
                        PillQuickActionChip(
                            icon = Icons.Rounded.OpenInNew,
                            label = "Open",
                            tint = Color(0xFF38BDF8),
                            testTag = "pill_action_open_app",
                            onClick = onOpenApp
                        )
                        PillQuickActionChip(
                            icon = if (isMicPaused) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                            label = if (isMicPaused) "Resume" else "Pause",
                            tint = if (isMicPaused) Color(0xFFFBBF24) else Color(0xFF34D399),
                            testTag = "pill_action_toggle_mic",
                            onClick = onToggleMic
                        )
                        if (uiState.aiState == CameraPillAiState.SPEAKING ||
                            uiState.aiState == CameraPillAiState.THINKING ||
                            uiState.isTtsSpeaking
                        ) {
                            PillQuickActionChip(
                                icon = Icons.Rounded.Stop,
                                label = "Stop",
                                tint = Color(0xFFF87171),
                                testTag = "pill_action_stop",
                                onClick = onStopSpeaking
                            )
                        }
                        PillQuickActionChip(
                            icon = Icons.Rounded.CloseFullscreen,
                            label = "Mini",
                            tint = Color(0xFFCBD5E1),
                            testTag = "pill_action_minimize",
                            onClick = onToggleMinimize
                        )
                        if (uiState.hasCustomPosition) {
                            PillQuickActionChip(
                                icon = Icons.Rounded.CenterFocusStrong,
                                label = "Snap",
                                tint = Color(0xFFA78BFA),
                                testTag = "pill_action_snap_cutout",
                                onClick = onResetPosition
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PillQuickActionChip(
    icon: ImageVector,
    label: String,
    tint: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F1E36))
            .border(0.8.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
            .semantics { contentDescription = label }
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = label,
            color = Color(0xFFE2E8F0),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun RealTimeVoiceVisualizerBars(
    amplitude: Float,
    bass: Float,
    mid: Float,
    high: Float,
    isAiSpeaking: Boolean,
    isInterrupted: Boolean,
    intensity: Float
) {
    val infiniteTransition = rememberInfiniteTransition(label = "voice_bars_phase")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 720, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    val barWeights = remember { listOf(0.45f, 0.85f, 1.0f, 0.80f, 0.50f) }
    val barColor = when {
        isInterrupted -> Color(0xFFFBBF24)
        isAiSpeaking -> Color(0xFF60A5FA)
        else -> Color(0xFF22D3EE)
    }
    val secondaryColor = if (isAiSpeaking) Color(0xFFA78BFA) else Color(0xFF34D399)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        modifier = Modifier.height(22.dp)
    ) {
        barWeights.forEachIndexed { idx, weight ->
            val band = when (idx) {
                0, 4 -> high.coerceAtLeast(amplitude * 0.6f)
                1, 3 -> mid.coerceAtLeast(amplitude * 0.85f)
                else -> bass.coerceAtLeast(amplitude)
            }
            val harmonic = ((sin(wavePhase + idx * 1.15f) + 1f) * 0.5f)
            val normalizedHeight = (0.20f + (band * 0.65f + harmonic * 0.25f) * weight * intensity)
                .coerceIn(0.18f, 1.0f)

            val animatedBar by animateFloatAsState(
                targetValue = normalizedHeight,
                animationSpec = tween(durationMillis = 85, easing = FastOutSlowInEasing),
                label = "bar_$idx"
            )

            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((4f + 16f * animatedBar).dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.verticalGradient(listOf(barColor, secondaryColor))
                    )
            )
        }
    }
}

@Composable
private fun ThinkingNeuralDots(intensity: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking_dots")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dots_phase"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.padding(end = 2.dp)
    ) {
        repeat(3) { i ->
            val wave = ((sin(phase - i * 1.0f) + 1f) * 0.5f)
            val scale = 0.65f + wave * 0.5f * intensity.coerceIn(0.5f, 1.4f)
            Box(
                modifier = Modifier
                    .size((4.5f * scale).dp)
                    .clip(CircleShape)
                    .background(if (i == 1) Color(0xFFA78BFA) else Color(0xFF22D3EE))
            )
        }
    }
}
